-- =====================================================================
-- Mao - call database (Phases 2-6)
--
-- Clients get READ access only. Every write (starting, answering, ending
-- calls, issuing LiveKit tokens) must go through trusted server-side code
-- (Supabase Edge Functions using the service_role key), which verifies
-- membership before acting. No calling UI or token function ships yet:
-- these tables exist so the schema and RLS are reviewed up front.
-- =====================================================================

create type public.call_scope  as enum ('direct', 'group');
create type public.call_media  as enum ('voice', 'video');
create type public.call_status as enum ('ringing', 'active', 'ended', 'missed', 'declined', 'cancelled', 'failed');
create type public.call_participant_status as enum ('invited', 'ringing', 'joined', 'declined', 'missed', 'left', 'removed');

create table public.calls (
  id              uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations (id) on delete cascade,
  scope           public.call_scope not null,
  media           public.call_media not null,
  initiator_id    uuid references public.profiles (id) on delete set null,
  room_name       text not null unique,          -- WebRTC session id or LiveKit room name
  status          public.call_status not null default 'ringing',
  created_at      timestamptz not null default now(),
  started_at      timestamptz,                   -- first media connection
  ended_at        timestamptz,
  end_reason      text
);
create index calls_conversation_idx on public.calls (conversation_id, created_at desc);

create table public.call_participants (
  id         uuid primary key default gen_random_uuid(),
  call_id    uuid not null references public.calls (id) on delete cascade,
  user_id    uuid not null references public.profiles (id) on delete cascade,
  status     public.call_participant_status not null default 'invited',
  invited_at timestamptz not null default now(),
  joined_at  timestamptz,
  left_at    timestamptz,
  device_id  text,
  unique (call_id, user_id)
);
create index call_participants_user_idx on public.call_participants (user_id);

create table public.call_invitations (
  id           uuid primary key default gen_random_uuid(),
  call_id      uuid not null references public.calls (id) on delete cascade,
  inviter_id   uuid references public.profiles (id) on delete set null,
  invitee_id   uuid not null references public.profiles (id) on delete cascade,
  status       public.call_participant_status not null default 'invited',
  created_at   timestamptz not null default now(),
  responded_at timestamptz,
  expires_at   timestamptz not null default now() + interval '60 seconds'
);
create index call_invitations_invitee_idx on public.call_invitations (invitee_id, created_at desc);

create table public.call_events (
  id         bigint generated always as identity primary key,
  call_id    uuid not null references public.calls (id) on delete cascade,
  actor_id   uuid references public.profiles (id) on delete set null,
  event_type text not null,                    -- e.g. ringing, accepted, ice_restart, reconnected, ended
  payload    jsonb not null default '{}',
  created_at timestamptz not null default now()
);
create index call_events_call_idx on public.call_events (call_id, created_at);

create table public.group_call_rooms (
  id              uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations (id) on delete cascade,
  call_id         uuid not null unique references public.calls (id) on delete cascade,
  livekit_room    text not null unique,
  is_active       boolean not null default true,
  created_by      uuid references public.profiles (id) on delete set null,
  created_at      timestamptz not null default now(),
  ended_at        timestamptz
);
-- at most one active group call per conversation
create unique index group_call_rooms_one_active
  on public.group_call_rooms (conversation_id) where is_active;

create table public.group_call_participants (
  id         uuid primary key default gen_random_uuid(),
  room_id    uuid not null references public.group_call_rooms (id) on delete cascade,
  user_id    uuid not null references public.profiles (id) on delete cascade,
  identity   text not null,                    -- LiveKit participant identity (= user id)
  joined_at  timestamptz not null default now(),
  left_at    timestamptz,
  revoked_at timestamptz,                      -- token revoked / kicked; token issuer must refuse
  unique (room_id, user_id)
);

-- ---------------------------------------------------------------------
-- RLS: read-only for members of the conversation; no client writes.
-- ---------------------------------------------------------------------
alter table public.calls                   enable row level security;
alter table public.call_participants       enable row level security;
alter table public.call_invitations        enable row level security;
alter table public.call_events             enable row level security;
alter table public.group_call_rooms        enable row level security;
alter table public.group_call_participants enable row level security;

create or replace function public.can_see_call(p_call uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.calls c where c.id = p_call and public.is_member(c.conversation_id))
$$;
grant execute on function public.can_see_call(uuid) to authenticated;
revoke execute on function public.can_see_call(uuid) from public, anon;

create policy calls_select on public.calls for select to authenticated
  using (public.is_member(conversation_id));
create policy call_participants_select on public.call_participants for select to authenticated
  using (public.can_see_call(call_id));
create policy call_invitations_select on public.call_invitations for select to authenticated
  using (invitee_id = auth.uid() or inviter_id = auth.uid());
create policy call_events_select on public.call_events for select to authenticated
  using (exists (select 1 from public.call_participants p
                 where p.call_id = call_events.call_id and p.user_id = auth.uid()));
create policy group_call_rooms_select on public.group_call_rooms for select to authenticated
  using (public.is_member(conversation_id));
create policy group_call_participants_select on public.group_call_participants for select to authenticated
  using (exists (select 1 from public.group_call_rooms r
                 where r.id = group_call_participants.room_id and public.is_member(r.conversation_id)));

alter publication supabase_realtime add table
  public.calls, public.call_participants, public.call_invitations, public.group_call_rooms,
  public.group_call_participants;
