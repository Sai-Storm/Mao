-- =====================================================================
-- Mao - Phase 2: one-to-one calls (state machine + signaling authorization)
--
-- Call state lives in public.calls and changes only through the
-- SECURITY DEFINER functions below. WebRTC offers/answers/ICE candidates
-- travel over the private realtime topic call:<call id>, which only the
-- call's participants may join.
-- =====================================================================

create or replace function public.is_call_participant(p_call uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.call_participants p where p.call_id = p_call and p.user_id = auth.uid())
$$;

create or replace function public.is_call_participant_text(p_call text)
returns boolean language sql stable security definer set search_path = '' as $$
  select case
    when p_call ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      then public.is_call_participant(p_call::uuid)
    else false end
$$;

create policy "realtime: participants receive call signaling"
on realtime.messages for select to authenticated
using (realtime.topic() like 'call:%' and public.is_call_participant_text(substring(realtime.topic() from 6)));

create policy "realtime: participants send call signaling"
on realtime.messages for insert to authenticated
with check (realtime.topic() like 'call:%' and public.is_call_participant_text(substring(realtime.topic() from 6)));

-- Ringing calls that nobody answered become "missed"; abandoned active calls end.
create or replace function public.expire_stale_calls()
returns void language plpgsql security definer set search_path = '' as $$
begin
  with expired as (
    update public.calls set status = 'missed', ended_at = now(), end_reason = 'no_answer'
     where scope = 'direct' and status = 'ringing' and created_at < now() - interval '60 seconds'
    returning id
  )
  update public.call_participants p set status = 'missed'
    from expired e where p.call_id = e.id and p.status in ('invited', 'ringing');

  update public.call_invitations set status = 'missed', responded_at = now()
   where status in ('invited', 'ringing') and expires_at < now();

  with stale as (
    update public.calls set status = 'ended', ended_at = now(), end_reason = 'stale'
     where scope = 'direct' and status = 'active' and started_at < now() - interval '12 hours'
    returning id
  )
  update public.call_participants p set status = 'left', left_at = now()
    from stale s where p.call_id = s.id and p.status = 'joined';
end $$;

create or replace function public.log_call_event_internal(p_call uuid, p_type text, p_payload jsonb default '{}')
returns void language sql security definer set search_path = '' as $$
  insert into public.call_events (call_id, actor_id, event_type, payload) values (p_call, auth.uid(), p_type, coalesce(p_payload, '{}'))
$$;

-- Is this user tied up in a ringing or active call right now?
create or replace function public.user_in_call(p_user uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.call_participants p join public.calls c on c.id = p.call_id
    where p.user_id = p_user and c.status in ('ringing', 'active') and p.status in ('invited', 'ringing', 'joined')
  )
$$;

create or replace function public.start_direct_call(p_conversation uuid, p_media public.call_media default 'voice')
returns public.calls language plpgsql security definer set search_path = '' as $$
declare
  v_me   uuid := public.require_uid();
  v_peer uuid;
  v_call public.calls;
begin
  perform public.expire_stale_calls();
  if not exists (select 1 from public.conversations where id = p_conversation and kind = 'direct')
     or not public.is_member(p_conversation) then
    raise exception 'conversation not found' using errcode = '42501';
  end if;
  select user_id into v_peer from public.conversation_members
   where conversation_id = p_conversation and user_id <> v_me limit 1;
  if v_peer is null then raise exception 'nobody to call'; end if;
  if public.user_in_call(v_me) then raise exception 'you are already in a call'; end if;
  if public.user_in_call(v_peer) then raise exception 'busy' using hint = 'The other person is on another call'; end if;

  insert into public.calls (conversation_id, scope, media, initiator_id, room_name, status)
  values (p_conversation, 'direct', p_media, v_me, 'dc_' || replace(gen_random_uuid()::text, '-', ''), 'ringing')
  returning * into v_call;

  insert into public.call_participants (call_id, user_id, status, joined_at)
  values (v_call.id, v_me, 'joined', now()), (v_call.id, v_peer, 'ringing', null);
  insert into public.call_invitations (call_id, inviter_id, invitee_id, status, expires_at)
  values (v_call.id, v_me, v_peer, 'ringing', now() + interval '45 seconds');
  perform public.log_call_event_internal(v_call.id, 'started', jsonb_build_object('media', p_media));
  return v_call;
end $$;

-- First device to accept wins; other devices of the same user see status='active'.
create or replace function public.accept_call(p_call uuid, p_device text default null)
returns public.calls language plpgsql security definer set search_path = '' as $$
declare
  v_me   uuid := public.require_uid();
  v_call public.calls;
begin
  perform public.expire_stale_calls();
  select * into v_call from public.calls where id = p_call for update;
  if v_call.id is null or not public.is_call_participant(p_call) then raise exception 'call not found'; end if;
  if v_call.initiator_id = v_me then raise exception 'cannot accept your own call'; end if;
  -- Not raising here keeps expire_stale_calls()'s cleanup committed; the client
  -- sees status <> 'active' and shows "call no longer available".
  if v_call.status <> 'ringing' then return v_call; end if;

  update public.calls set status = 'active', started_at = now() where id = p_call returning * into v_call;
  update public.call_participants set status = 'joined', joined_at = now(), device_id = left(p_device, 64)
   where call_id = p_call and user_id = v_me;
  update public.call_invitations set status = 'joined', responded_at = now()
   where call_id = p_call and invitee_id = v_me;
  perform public.log_call_event_internal(p_call, 'accepted', jsonb_build_object('device', left(p_device, 64)));
  return v_call;
end $$;

create or replace function public.decline_call(p_call uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_me   uuid := public.require_uid();
  v_call public.calls;
begin
  select * into v_call from public.calls where id = p_call for update;
  if v_call.id is null or not public.is_call_participant(p_call) or v_call.initiator_id = v_me then
    raise exception 'call not found';
  end if;
  if v_call.status <> 'ringing' then return; end if;
  update public.calls set status = 'declined', ended_at = now(), end_reason = 'declined' where id = p_call;
  update public.call_participants set status = 'declined' where call_id = p_call and user_id = v_me;
  update public.call_invitations set status = 'declined', responded_at = now() where call_id = p_call and invitee_id = v_me;
  perform public.log_call_event_internal(p_call, 'declined');
end $$;

-- Hang up / cancel. Idempotent. Reasons: hangup | cancelled | no_answer | connection_lost | permission_denied | failed
create or replace function public.end_call(p_call uuid, p_reason text default 'hangup')
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_me     uuid := public.require_uid();
  v_call   public.calls;
  v_reason text := left(coalesce(p_reason, 'hangup'), 32);
  v_status public.call_status;
begin
  select * into v_call from public.calls where id = p_call for update;
  if v_call.id is null or not public.is_call_participant(p_call) then raise exception 'call not found'; end if;
  if v_call.status not in ('ringing', 'active') then return; end if;

  v_status := case
    when v_call.status = 'active' then 'ended'
    when v_reason in ('connection_lost', 'failed', 'permission_denied') then 'failed'
    when v_call.initiator_id = v_me and v_reason = 'no_answer' then 'missed'
    when v_call.initiator_id = v_me then 'cancelled'
    else 'declined' end::public.call_status;

  update public.calls set status = v_status, ended_at = now(), end_reason = v_reason where id = p_call;
  update public.call_participants set
    status = case when status = 'joined' then 'left'
                  when v_status = 'missed' then 'missed'
                  else status end::public.call_participant_status,
    left_at = case when status = 'joined' then now() else left_at end
  where call_id = p_call;
  update public.call_invitations set
    status = case when v_status = 'missed' then 'missed' else 'removed' end::public.call_participant_status,
    responded_at = now()
  where call_id = p_call and status in ('invited', 'ringing');
  perform public.log_call_event_internal(p_call, 'ended', jsonb_build_object('reason', v_reason, 'status', v_status));
end $$;

-- Diagnostics from clients (connection quality, reconnects). Allow-listed types only.
create or replace function public.log_call_event(p_call uuid, p_type text, p_payload jsonb default '{}')
returns void language plpgsql security definer set search_path = '' as $$
begin
  perform public.require_uid();
  if not public.is_call_participant(p_call) then raise exception 'call not found'; end if;
  if p_type not in ('connected', 'reconnecting', 'reconnected', 'ice_restart', 'muted', 'unmuted',
                    'route_changed', 'camera_on', 'camera_off', 'failed') then
    raise exception 'unsupported event type';
  end if;
  perform public.log_call_event_internal(p_call, p_type, p_payload);
end $$;

-- Call history for the signed-in user (RLS on calls/participants applies).
create or replace function public.my_call_history(p_limit integer default 100)
returns table (
  id uuid, conversation_id uuid, scope public.call_scope, media public.call_media, status public.call_status,
  outgoing boolean, created_at timestamptz, started_at timestamptz, ended_at timestamptz, end_reason text,
  peer_id uuid, peer_display_name text, peer_avatar_url text, group_name text)
language sql stable set search_path = '' as $$
  select c.id, c.conversation_id, c.scope, c.media, c.status, c.initiator_id = auth.uid(),
         c.created_at, c.started_at, c.ended_at, c.end_reason,
         peer.id, peer.display_name, peer.avatar_url, conv.name
  from public.call_participants me
  join public.calls c on c.id = me.call_id
  join public.conversations conv on conv.id = c.conversation_id
  left join lateral (
    select p.id, p.display_name, p.avatar_url
    from public.call_participants o join public.profiles p on p.id = o.user_id
    where c.scope = 'direct' and o.call_id = c.id and o.user_id <> auth.uid() limit 1
  ) peer on true
  where me.user_id = auth.uid()
  order by c.created_at desc
  limit least(greatest(coalesce(p_limit, 100), 1), 500)
$$;

revoke execute on all functions in schema public from public, anon;
grant  execute on all functions in schema public to authenticated, service_role;
revoke execute on function public.log_call_event_internal(uuid, text, jsonb) from authenticated;
revoke execute on function public.post_system_message(uuid, text) from authenticated;
revoke execute on function public.handle_new_user() from authenticated;
