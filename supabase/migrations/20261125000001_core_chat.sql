-- =====================================================================
-- Mao - core chat schema (profiles, private + group conversations)
-- Phase 1: group messaging. All privileged writes go through
-- SECURITY DEFINER functions that check auth.uid() and roles; RLS
-- blocks every other path.
-- =====================================================================

create extension if not exists pg_trgm with schema extensions;
create extension if not exists pgcrypto with schema extensions;

create type public.conversation_kind as enum ('direct', 'group');
create type public.member_role       as enum ('owner', 'admin', 'member');
create type public.message_kind      as enum ('text', 'image', 'video', 'document', 'voice', 'system');
create type public.join_request_status as enum ('pending', 'approved', 'rejected', 'cancelled');

-- ---------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------
create table public.profiles (
  id            uuid primary key references auth.users (id) on delete cascade,
  username      text not null unique check (username ~ '^[a-z0-9_]{3,24}$'),
  display_name  text not null check (char_length(display_name) between 1 and 60),
  avatar_url    text,
  bio           text check (char_length(bio) <= 200),
  last_seen_at  timestamptz,
  created_at    timestamptz not null default now()
);

create table public.conversations (
  id                        uuid primary key default gen_random_uuid(),
  kind                      public.conversation_kind not null,
  name                      text check (name is null or char_length(name) between 1 and 80),
  description               text check (description is null or char_length(description) <= 500),
  avatar_url                text,
  is_public                 boolean not null default false,
  join_requires_approval    boolean not null default true,
  only_admins_can_send      boolean not null default false,
  only_admins_can_edit_info boolean not null default true,
  direct_key                text unique,          -- '<smaller uuid>:<larger uuid>' for 1:1 chats
  created_by                uuid references public.profiles (id) on delete set null,
  created_at                timestamptz not null default now(),
  last_message_at           timestamptz not null default now(),
  last_message_preview      text,
  check ((kind = 'direct') = (direct_key is not null)),
  check (kind = 'group' or name is null)
);

-- Surrogate id: realtime DELETE events expose only the primary key.
create table public.conversation_members (
  id                  uuid primary key default gen_random_uuid(),
  conversation_id     uuid not null references public.conversations (id) on delete cascade,
  user_id             uuid not null references public.profiles (id) on delete cascade,
  role                public.member_role not null default 'member',
  restricted_until    timestamptz,               -- admin-imposed "cannot send" until
  joined_at           timestamptz not null default now(),
  added_by            uuid references public.profiles (id) on delete set null,
  last_delivered_at   timestamptz,
  last_read_at        timestamptz,
  notifications_muted boolean not null default false,
  unique (conversation_id, user_id)
);
create index conversation_members_user_idx on public.conversation_members (user_id);

create table public.messages (
  id                uuid primary key default gen_random_uuid(),
  conversation_id   uuid not null references public.conversations (id) on delete cascade,
  sender_id         uuid references public.profiles (id) on delete set null,
  client_id         uuid not null,                 -- idempotency key generated on the device
  kind              public.message_kind not null default 'text',
  body              text check (body is null or char_length(body) <= 4000),
  media_path        text,
  media_name        text,
  media_mime        text,
  media_size        bigint,
  media_duration_ms integer,
  reply_to_id       uuid references public.messages (id) on delete set null,
  forwarded         boolean not null default false,
  mentions          uuid[] not null default '{}',
  created_at        timestamptz not null default now(),
  edited_at         timestamptz,
  deleted_at        timestamptz,
  unique (sender_id, client_id)
);
create index messages_page_idx  on public.messages (conversation_id, created_at desc, id desc);
create index messages_media_idx on public.messages (media_path) where media_path is not null;
create index messages_body_trgm on public.messages using gin (body extensions.gin_trgm_ops);

create table public.message_reactions (
  id              uuid primary key default gen_random_uuid(),
  message_id      uuid not null references public.messages (id) on delete cascade,
  conversation_id uuid not null references public.conversations (id) on delete cascade,
  user_id         uuid not null references public.profiles (id) on delete cascade,
  emoji           text not null check (char_length(emoji) between 1 and 16),
  created_at      timestamptz not null default now(),
  unique (message_id, user_id, emoji)
);
create index message_reactions_conv_idx on public.message_reactions (conversation_id);

create table public.pinned_messages (
  id              uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations (id) on delete cascade,
  message_id      uuid not null references public.messages (id) on delete cascade,
  pinned_by       uuid references public.profiles (id) on delete set null,
  pinned_at       timestamptz not null default now(),
  unique (conversation_id, message_id)
);

create table public.group_invites (
  id                uuid primary key default gen_random_uuid(),
  conversation_id   uuid not null references public.conversations (id) on delete cascade,
  code              text not null unique
                      default translate(encode(extensions.gen_random_bytes(9), 'base64'), '+/', '-_'),
  requires_approval boolean not null default false,
  max_uses          integer check (max_uses is null or max_uses > 0),
  use_count         integer not null default 0,
  expires_at        timestamptz,
  revoked_at        timestamptz,
  created_by        uuid references public.profiles (id) on delete set null,
  created_at        timestamptz not null default now()
);

create table public.group_join_requests (
  id              uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations (id) on delete cascade,
  user_id         uuid not null references public.profiles (id) on delete cascade,
  invite_id       uuid references public.group_invites (id) on delete set null,
  status          public.join_request_status not null default 'pending',
  created_at      timestamptz not null default now(),
  decided_by      uuid references public.profiles (id) on delete set null,
  decided_at      timestamptz
);
create unique index group_join_requests_one_pending
  on public.group_join_requests (conversation_id, user_id) where status = 'pending';

-- Reserved for Phase 6 (FCM). Tokens are only readable by their owner.
create table public.device_tokens (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references public.profiles (id) on delete cascade,
  token      text not null unique,
  platform   text not null default 'android',
  updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Authorization helpers (SECURITY DEFINER so they can be used inside
-- RLS policies without recursive policy evaluation).
-- ---------------------------------------------------------------------
create or replace function public.member_role_of(p_conversation uuid, p_user uuid default auth.uid())
returns public.member_role
language sql stable security definer set search_path = '' as $$
  select m.role from public.conversation_members m
  where m.conversation_id = p_conversation and m.user_id = p_user
$$;

create or replace function public.is_member(p_conversation uuid)
returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.conversation_members m
    where m.conversation_id = p_conversation and m.user_id = auth.uid()
  )
$$;

create or replace function public.is_group_admin(p_conversation uuid)
returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.conversation_members m
    join public.conversations c on c.id = m.conversation_id
    where m.conversation_id = p_conversation and m.user_id = auth.uid()
      and c.kind = 'group' and m.role in ('owner', 'admin')
  )
$$;

-- Text-safe variant for storage paths / realtime topics (never throws on bad input).
create or replace function public.is_member_text(p_conversation text)
returns boolean
language sql stable security definer set search_path = '' as $$
  select case
    when p_conversation ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      then public.is_member(p_conversation::uuid)
    else false end
$$;

create or replace function public.can_edit_group_info(p_conversation uuid)
returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.conversation_members m
    join public.conversations c on c.id = m.conversation_id
    where m.conversation_id = p_conversation and m.user_id = auth.uid() and c.kind = 'group'
      and (m.role in ('owner', 'admin') or not c.only_admins_can_edit_info)
  )
$$;

create or replace function public.can_edit_group_info_text(p_conversation text)
returns boolean
language sql stable security definer set search_path = '' as $$
  select case
    when p_conversation ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      then public.can_edit_group_info(p_conversation::uuid)
    else false end
$$;

create or replace function public.can_send(p_conversation uuid)
returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.conversation_members m
    join public.conversations c on c.id = m.conversation_id
    where m.conversation_id = p_conversation and m.user_id = auth.uid()
      and (m.restricted_until is null or m.restricted_until <= now())
      and (c.kind = 'direct' or not c.only_admins_can_send or m.role in ('owner', 'admin'))
  )
$$;

-- ---------------------------------------------------------------------
-- Triggers
-- ---------------------------------------------------------------------

-- Create a profile for every new auth user (username comes from sign-up metadata).
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_username text := lower(coalesce(new.raw_user_meta_data ->> 'username', ''));
begin
  if v_username !~ '^[a-z0-9_]{3,24}$'
     or exists (select 1 from public.profiles where username = v_username) then
    v_username := 'user_' || substr(replace(new.id::text, '-', ''), 1, 10);
  end if;
  insert into public.profiles (id, username, display_name)
  values (new.id, v_username,
          left(coalesce(nullif(new.raw_user_meta_data ->> 'display_name', ''), v_username), 60));
  return new;
end $$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- Normalise client-supplied message rows. Server controls timestamps and flags.
create or replace function public.messages_before_insert()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  new.created_at := now();
  new.edited_at  := null;
  new.deleted_at := null;
  if coalesce(current_setting('mao.trusted_insert', true), '') <> 'on' then
    new.forwarded := false;
  end if;
  if new.reply_to_id is not null and not exists (
       select 1 from public.messages r
       where r.id = new.reply_to_id and r.conversation_id = new.conversation_id) then
    raise exception 'reply_to_id must reference a message in the same conversation';
  end if;
  -- keep only mentions of real members of this conversation
  new.mentions := coalesce(array(
    select distinct u from unnest(new.mentions) u
    where exists (select 1 from public.conversation_members m
                  where m.conversation_id = new.conversation_id and m.user_id = u)
  ), '{}');
  return new;
end $$;

create trigger messages_before_insert
  before insert on public.messages
  for each row execute function public.messages_before_insert();

create or replace function public.message_preview(p_kind public.message_kind, p_body text)
returns text language sql immutable set search_path = '' as $$
  select case p_kind
    when 'image'    then '📷 Photo'
    when 'video'    then '🎬 Video'
    when 'document' then '📄 Document'
    when 'voice'    then '🎤 Voice message'
    else left(coalesce(p_body, ''), 120) end
$$;

create or replace function public.messages_after_insert()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  update public.conversations
     set last_message_at = new.created_at,
         last_message_preview = public.message_preview(new.kind, new.body)
   where id = new.conversation_id;
  -- the sender has obviously read everything up to their own message
  if new.sender_id is not null then
    update public.conversation_members
       set last_read_at = greatest(coalesce(last_read_at, new.created_at), new.created_at),
           last_delivered_at = greatest(coalesce(last_delivered_at, new.created_at), new.created_at)
     where conversation_id = new.conversation_id and user_id = new.sender_id;
  end if;
  return new;
end $$;

create trigger messages_after_insert
  after insert on public.messages
  for each row execute function public.messages_after_insert();

create or replace function public.reactions_before_insert()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  select m.conversation_id into new.conversation_id from public.messages m where m.id = new.message_id;
  if new.conversation_id is null then raise exception 'message not found'; end if;
  new.created_at := now();
  return new;
end $$;

create trigger reactions_before_insert
  before insert on public.message_reactions
  for each row execute function public.reactions_before_insert();

-- ---------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------
alter table public.profiles             enable row level security;
alter table public.conversations        enable row level security;
alter table public.conversation_members enable row level security;
alter table public.messages             enable row level security;
alter table public.message_reactions    enable row level security;
alter table public.pinned_messages      enable row level security;
alter table public.group_invites        enable row level security;
alter table public.group_join_requests  enable row level security;
alter table public.device_tokens        enable row level security;

-- profiles: any signed-in user can look people up; only you edit yours.
create policy profiles_select on public.profiles for select to authenticated using (true);
create policy profiles_update on public.profiles for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());

-- conversations: members see theirs; everyone sees public groups (for discovery).
create policy conversations_select on public.conversations for select to authenticated
  using (public.is_member(id) or (kind = 'group' and is_public));

-- members: visible to other members of the same conversation.
create policy members_select on public.conversation_members for select to authenticated
  using (public.is_member(conversation_id));

-- messages: read if member; insert only as yourself, only if allowed to send,
-- only into your own upload folder for media. Edits/deletes go through RPCs.
create policy messages_select on public.messages for select to authenticated
  using (public.is_member(conversation_id));
create policy messages_insert on public.messages for insert to authenticated
  with check (
    sender_id = auth.uid()
    and kind <> 'system'
    and public.can_send(conversation_id)
    and (media_path is null
         or media_path like conversation_id::text || '/' || auth.uid()::text || '/%')
  );

create policy reactions_select on public.message_reactions for select to authenticated
  using (public.is_member(conversation_id));
create policy reactions_insert on public.message_reactions for insert to authenticated
  with check (user_id = auth.uid() and public.is_member(conversation_id));
create policy reactions_delete on public.message_reactions for delete to authenticated
  using (user_id = auth.uid());

create policy pins_select on public.pinned_messages for select to authenticated
  using (public.is_member(conversation_id));

create policy invites_select on public.group_invites for select to authenticated
  using (public.is_group_admin(conversation_id));

create policy join_requests_select on public.group_join_requests for select to authenticated
  using (user_id = auth.uid() or public.is_group_admin(conversation_id));

create policy device_tokens_all on public.device_tokens for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ---------------------------------------------------------------------
-- Trusted operations (RPC)
-- ---------------------------------------------------------------------
create or replace function public.require_uid()
returns uuid language plpgsql stable set search_path = '' as $$
declare v uuid := auth.uid();
begin
  if v is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  return v;
end $$;

create or replace function public.post_system_message(p_conversation uuid, p_text text)
returns void language plpgsql security definer set search_path = '' as $$
begin
  insert into public.messages (conversation_id, sender_id, client_id, kind, body)
  values (p_conversation, null, gen_random_uuid(), 'system', p_text);
end $$;

create or replace function public.display_name_of(p_user uuid)
returns text language sql stable security definer set search_path = '' as $$
  select display_name from public.profiles where id = p_user
$$;

-- 1:1 conversation, created on first use and reused afterwards.
create or replace function public.get_or_create_direct(p_other uuid)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  v_me  uuid := public.require_uid();
  v_key text;
  v_id  uuid;
begin
  if p_other = v_me then raise exception 'cannot start a chat with yourself'; end if;
  if not exists (select 1 from public.profiles where id = p_other) then
    raise exception 'user not found';
  end if;
  v_key := least(v_me::text, p_other::text) || ':' || greatest(v_me::text, p_other::text);
  select id into v_id from public.conversations where direct_key = v_key;
  if v_id is null then
    insert into public.conversations (kind, direct_key, created_by)
    values ('direct', v_key, v_me)
    on conflict (direct_key) do nothing
    returning id into v_id;
    if v_id is null then
      select id into v_id from public.conversations where direct_key = v_key;
    else
      insert into public.conversation_members (conversation_id, user_id, role, added_by)
      values (v_id, v_me, 'member', v_me), (v_id, p_other, 'member', v_me);
    end if;
  end if;
  return v_id;
end $$;

create or replace function public.create_group(
  p_name text, p_description text, p_is_public boolean,
  p_join_requires_approval boolean, p_member_ids uuid[])
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_id uuid;
begin
  if coalesce(trim(p_name), '') = '' then raise exception 'group name is required'; end if;
  insert into public.conversations (kind, name, description, is_public, join_requires_approval, created_by)
  values ('group', trim(p_name), nullif(trim(p_description), ''), p_is_public,
          coalesce(p_join_requires_approval, true), v_me)
  returning id into v_id;

  insert into public.conversation_members (conversation_id, user_id, role, added_by)
  values (v_id, v_me, 'owner', v_me);

  insert into public.conversation_members (conversation_id, user_id, role, added_by)
  select v_id, p.id, 'member', v_me
  from public.profiles p
  where p.id = any (coalesce(p_member_ids, '{}')) and p.id <> v_me
  on conflict (conversation_id, user_id) do nothing;

  perform public.post_system_message(v_id, public.display_name_of(v_me) || ' created the group');
  return v_id;
end $$;

-- Partial update: null arguments leave the field unchanged.
-- p_clear_avatar removes the group picture.
create or replace function public.update_group_info(
  p_conversation uuid,
  p_name text default null, p_description text default null, p_avatar_url text default null,
  p_is_public boolean default null, p_join_requires_approval boolean default null,
  p_only_admins_can_send boolean default null, p_only_admins_can_edit_info boolean default null,
  p_clear_avatar boolean default false)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_settings boolean := p_is_public is not null or p_join_requires_approval is not null
                        or p_only_admins_can_send is not null or p_only_admins_can_edit_info is not null;
begin
  if not public.can_edit_group_info(p_conversation) then
    raise exception 'not allowed to edit this group' using errcode = '42501';
  end if;
  if v_settings and not public.is_group_admin(p_conversation) then
    raise exception 'only administrators can change group settings' using errcode = '42501';
  end if;
  if p_name is not null and trim(p_name) = '' then raise exception 'group name is required'; end if;

  update public.conversations set
    name                      = coalesce(trim(p_name), name),
    description               = case when p_description is null then description
                                     else nullif(trim(p_description), '') end,
    avatar_url                = case when p_clear_avatar then null else coalesce(p_avatar_url, avatar_url) end,
    is_public                 = coalesce(p_is_public, is_public),
    join_requires_approval    = coalesce(p_join_requires_approval, join_requires_approval),
    only_admins_can_send      = coalesce(p_only_admins_can_send, only_admins_can_send),
    only_admins_can_edit_info = coalesce(p_only_admins_can_edit_info, only_admins_can_edit_info)
  where id = p_conversation and kind = 'group';

  if p_name is not null then
    perform public.post_system_message(p_conversation,
      public.display_name_of(v_me) || ' renamed the group to "' || trim(p_name) || '"');
  end if;
  if p_only_admins_can_send is not null then
    perform public.post_system_message(p_conversation, case when p_only_admins_can_send
      then 'Only administrators can send messages now' else 'All members can send messages now' end);
  end if;
end $$;

create or replace function public.add_group_members(p_conversation uuid, p_user_ids uuid[])
returns integer language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_count integer;
  v_names text;
begin
  if not public.is_group_admin(p_conversation) then
    raise exception 'only administrators can add members' using errcode = '42501';
  end if;
  with added as (
    insert into public.conversation_members (conversation_id, user_id, role, added_by)
    select p_conversation, p.id, 'member', v_me
    from public.profiles p where p.id = any (coalesce(p_user_ids, '{}'))
    on conflict (conversation_id, user_id) do nothing
    returning user_id
  )
  select count(*), string_agg(public.display_name_of(user_id), ', ') into v_count, v_names from added;

  -- anyone added directly no longer needs a pending request
  update public.group_join_requests set status = 'approved', decided_by = v_me, decided_at = now()
   where conversation_id = p_conversation and status = 'pending' and user_id = any (p_user_ids);

  if v_count > 0 then
    perform public.post_system_message(p_conversation, public.display_name_of(v_me) || ' added ' || v_names);
  end if;
  return v_count;
end $$;

create or replace function public.remove_group_member(p_conversation uuid, p_user uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_my_role public.member_role := public.member_role_of(p_conversation, v_me);
  v_target_role public.member_role := public.member_role_of(p_conversation, p_user);
begin
  if p_user = v_me then raise exception 'use leave_group to leave'; end if;
  if v_target_role is null then raise exception 'user is not a member'; end if;
  if not public.is_group_admin(p_conversation)
     or v_target_role = 'owner'
     or (v_target_role = 'admin' and v_my_role <> 'owner') then
    raise exception 'not allowed to remove this member' using errcode = '42501';
  end if;
  delete from public.conversation_members where conversation_id = p_conversation and user_id = p_user;
  perform public.post_system_message(p_conversation,
    public.display_name_of(v_me) || ' removed ' || public.display_name_of(p_user));
end $$;

-- Owner may set admin/member; admins may promote members to admin.
create or replace function public.set_member_role(p_conversation uuid, p_user uuid, p_role public.member_role)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_my_role public.member_role := public.member_role_of(p_conversation, v_me);
  v_target_role public.member_role := public.member_role_of(p_conversation, p_user);
begin
  if p_role = 'owner' then raise exception 'use transfer_group_ownership'; end if;
  if v_target_role is null then raise exception 'user is not a member'; end if;
  if not public.is_group_admin(p_conversation) or v_target_role = 'owner' then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  if v_my_role <> 'owner' and not (v_target_role = 'member' and p_role = 'admin') then
    raise exception 'only the owner can demote administrators' using errcode = '42501';
  end if;
  update public.conversation_members set role = p_role
   where conversation_id = p_conversation and user_id = p_user;
  perform public.post_system_message(p_conversation, public.display_name_of(p_user) ||
    case when p_role = 'admin' then ' is now an administrator' else ' is no longer an administrator' end);
end $$;

create or replace function public.transfer_group_ownership(p_conversation uuid, p_user uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_me uuid := public.require_uid();
begin
  if public.member_role_of(p_conversation, v_me) is distinct from 'owner' then
    raise exception 'only the owner can transfer ownership' using errcode = '42501';
  end if;
  if public.member_role_of(p_conversation, p_user) is null then raise exception 'user is not a member'; end if;
  update public.conversation_members set role = 'admin' where conversation_id = p_conversation and user_id = v_me;
  update public.conversation_members set role = 'owner' where conversation_id = p_conversation and user_id = p_user;
  perform public.post_system_message(p_conversation, public.display_name_of(p_user) || ' is now the group owner');
end $$;

-- Restrict (mute) a member from sending until a time; null lifts the restriction.
create or replace function public.restrict_group_member(p_conversation uuid, p_user uuid, p_until timestamptz)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_my_role public.member_role := public.member_role_of(p_conversation, v_me);
  v_target_role public.member_role := public.member_role_of(p_conversation, p_user);
begin
  if v_target_role is null then raise exception 'user is not a member'; end if;
  if not public.is_group_admin(p_conversation) or p_user = v_me or v_target_role = 'owner'
     or (v_target_role = 'admin' and v_my_role <> 'owner') then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  update public.conversation_members set restricted_until = p_until
   where conversation_id = p_conversation and user_id = p_user;
  perform public.post_system_message(p_conversation, public.display_name_of(p_user) ||
    case when p_until is null or p_until <= now() then ' can send messages again'
         else ' was restricted from sending messages' end);
end $$;

create or replace function public.leave_group(p_conversation uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_role public.member_role := public.member_role_of(p_conversation, v_me);
  v_heir uuid;
begin
  if v_role is null then return; end if;
  if (select kind from public.conversations where id = p_conversation) <> 'group' then
    raise exception 'cannot leave a private conversation';
  end if;
  delete from public.conversation_members where conversation_id = p_conversation and user_id = v_me;
  if v_role = 'owner' then
    select user_id into v_heir from public.conversation_members
     where conversation_id = p_conversation
     order by (role = 'admin') desc, joined_at asc limit 1;
    if v_heir is null then
      delete from public.conversations where id = p_conversation;   -- last member left
      return;
    end if;
    update public.conversation_members set role = 'owner'
     where conversation_id = p_conversation and user_id = v_heir;
  end if;
  perform public.post_system_message(p_conversation, public.display_name_of(v_me) || ' left the group');
end $$;

-- Pinning: group admins, or either participant in a private chat.
create or replace function public.set_message_pinned(p_message uuid, p_pinned boolean)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_conv uuid;
  v_kind public.conversation_kind;
begin
  select m.conversation_id, c.kind into v_conv, v_kind
    from public.messages m join public.conversations c on c.id = m.conversation_id
   where m.id = p_message and m.deleted_at is null;
  if v_conv is null or not public.is_member(v_conv) then raise exception 'message not found'; end if;
  if v_kind = 'group' and not public.is_group_admin(v_conv) then
    raise exception 'only administrators can pin messages' using errcode = '42501';
  end if;
  if p_pinned then
    insert into public.pinned_messages (conversation_id, message_id, pinned_by)
    values (v_conv, p_message, v_me) on conflict (conversation_id, message_id) do nothing;
  else
    delete from public.pinned_messages where conversation_id = v_conv and message_id = p_message;
  end if;
end $$;

create or replace function public.edit_message(p_message uuid, p_body text, p_mentions uuid[] default '{}')
returns void language plpgsql security definer set search_path = '' as $$
declare v_me uuid := public.require_uid();
begin
  if coalesce(trim(p_body), '') = '' then raise exception 'message cannot be empty'; end if;
  update public.messages m set
    body = p_body,
    edited_at = now(),
    mentions = coalesce(array(
      select distinct u from unnest(coalesce(p_mentions, '{}')) u
      where exists (select 1 from public.conversation_members cm
                    where cm.conversation_id = m.conversation_id and cm.user_id = u)), '{}')
  where m.id = p_message and m.sender_id = v_me and m.deleted_at is null
    and m.kind = 'text' and public.can_send(m.conversation_id);
  if not found then raise exception 'cannot edit this message' using errcode = '42501'; end if;
end $$;

-- Senders delete their own; group admins can delete anyone's (except the owner's, unless owner).
-- Returns the media path so the sender's client can remove the file from storage.
create or replace function public.delete_message(p_message uuid)
returns text language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_msg public.messages;
  v_path text;
begin
  select * into v_msg from public.messages where id = p_message;
  if v_msg.id is null or not public.is_member(v_msg.conversation_id) or v_msg.kind = 'system' then
    raise exception 'message not found';
  end if;
  if v_msg.sender_id is distinct from v_me and not (
       public.is_group_admin(v_msg.conversation_id)
       and (public.member_role_of(v_msg.conversation_id, v_msg.sender_id) is distinct from 'owner'
            or public.member_role_of(v_msg.conversation_id, v_me) = 'owner')) then
    raise exception 'not allowed to delete this message' using errcode = '42501';
  end if;
  v_path := v_msg.media_path;
  update public.messages set deleted_at = now(), body = null, media_path = null, media_name = null,
         media_mime = null, media_size = null, mentions = '{}'
   where id = p_message;
  delete from public.message_reactions where message_id = p_message;
  delete from public.pinned_messages where message_id = p_message;
  -- only hand the path back to its uploader; other files stay until a cleanup job runs
  return case when v_msg.sender_id = v_me then v_path else null end;
end $$;

-- Forward a message to other conversations the caller can send to.
-- The original media file is reused; storage RLS lets members of any
-- conversation that references a file read it.
create or replace function public.forward_message(p_message uuid, p_targets uuid[])
returns integer language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_src public.messages;
  v_target uuid;
  v_count integer := 0;
begin
  select * into v_src from public.messages where id = p_message and deleted_at is null and kind <> 'system';
  if v_src.id is null or not public.is_member(v_src.conversation_id) then raise exception 'message not found'; end if;
  perform set_config('mao.trusted_insert', 'on', true);
  foreach v_target in array coalesce(p_targets, '{}') loop
    if public.can_send(v_target) then
      insert into public.messages (conversation_id, sender_id, client_id, kind, body, media_path,
                                   media_name, media_mime, media_size, media_duration_ms, forwarded)
      values (v_target, v_me, gen_random_uuid(), v_src.kind, v_src.body, v_src.media_path,
              v_src.media_name, v_src.media_mime, v_src.media_size, v_src.media_duration_ms, true);
      v_count := v_count + 1;
    end if;
  end loop;
  perform set_config('mao.trusted_insert', 'off', true);
  return v_count;
end $$;

-- Read / delivery watermarks (cheap per-member receipts that scale to large groups).
create or replace function public.mark_conversation_read(p_conversation uuid, p_up_to timestamptz default now())
returns void language plpgsql security definer set search_path = '' as $$
declare v_ts timestamptz := least(coalesce(p_up_to, now()), now());
begin
  update public.conversation_members set
    last_read_at = greatest(coalesce(last_read_at, v_ts), v_ts),
    last_delivered_at = greatest(coalesce(last_delivered_at, v_ts), v_ts)
  where conversation_id = p_conversation and user_id = public.require_uid()
    and (last_read_at is null or last_read_at < v_ts);
end $$;

create or replace function public.mark_all_delivered()
returns void language plpgsql security definer set search_path = '' as $$
begin
  update public.conversation_members m set last_delivered_at = now()
  from public.conversations c
  where c.id = m.conversation_id and m.user_id = public.require_uid()
    and (m.last_delivered_at is null or m.last_delivered_at < c.last_message_at);
  update public.profiles set last_seen_at = now() where id = auth.uid();
end $$;

-- ---- invitations ----------------------------------------------------
create or replace function public.create_group_invite(
  p_conversation uuid, p_requires_approval boolean default false,
  p_expires_in_hours integer default null, p_max_uses integer default null)
returns public.group_invites language plpgsql security definer set search_path = '' as $$
declare v_row public.group_invites;
begin
  if not public.is_group_admin(p_conversation) then
    raise exception 'only administrators can create invite links' using errcode = '42501';
  end if;
  insert into public.group_invites (conversation_id, requires_approval, expires_at, max_uses, created_by)
  values (p_conversation, coalesce(p_requires_approval, false),
          case when p_expires_in_hours is null then null else now() + make_interval(hours => p_expires_in_hours) end,
          p_max_uses, public.require_uid())
  returning * into v_row;
  return v_row;
end $$;

create or replace function public.revoke_group_invite(p_invite uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
  update public.group_invites set revoked_at = now()
   where id = p_invite and revoked_at is null and public.is_group_admin(conversation_id);
  if not found then raise exception 'invite not found' using errcode = '42501'; end if;
end $$;

create or replace function public.preview_group_invite(p_code text)
returns table (conversation_id uuid, name text, description text, avatar_url text,
               member_count bigint, requires_approval boolean, is_member boolean, has_pending_request boolean)
language plpgsql stable security definer set search_path = '' as $$
begin
  perform public.require_uid();
  return query
  select c.id, c.name, c.description, c.avatar_url,
         (select count(*) from public.conversation_members m where m.conversation_id = c.id),
         i.requires_approval,
         public.is_member(c.id),
         exists (select 1 from public.group_join_requests r where r.conversation_id = c.id
                  and r.user_id = auth.uid() and r.status = 'pending')
  from public.group_invites i join public.conversations c on c.id = i.conversation_id
  where i.code = p_code and i.revoked_at is null
    and (i.expires_at is null or i.expires_at > now())
    and (i.max_uses is null or i.use_count < i.max_uses);
end $$;

-- Returns 'joined', 'requested', 'already_member' or 'already_requested'.
create or replace function public.join_group_with_invite(p_code text)
returns text language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_inv public.group_invites;
begin
  select * into v_inv from public.group_invites
   where code = p_code and revoked_at is null
     and (expires_at is null or expires_at > now())
     and (max_uses is null or use_count < max_uses)
   for update;
  if v_inv.id is null then raise exception 'invite link is invalid or expired'; end if;
  if public.is_member(v_inv.conversation_id) then return 'already_member'; end if;

  if v_inv.requires_approval then
    insert into public.group_join_requests (conversation_id, user_id, invite_id)
    values (v_inv.conversation_id, v_me, v_inv.id)
    on conflict (conversation_id, user_id) where status = 'pending' do nothing;
    return case when found then 'requested' else 'already_requested' end;
  end if;

  insert into public.conversation_members (conversation_id, user_id, role)
  values (v_inv.conversation_id, v_me, 'member');
  update public.group_invites set use_count = use_count + 1 where id = v_inv.id;
  perform public.post_system_message(v_inv.conversation_id, public.display_name_of(v_me) || ' joined via invite link');
  return 'joined';
end $$;

-- Join a public group directly, or request to join when approval is required.
create or replace function public.join_public_group(p_conversation uuid)
returns text language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_conv public.conversations;
begin
  select * into v_conv from public.conversations where id = p_conversation and kind = 'group' and is_public;
  if v_conv.id is null then raise exception 'group not found'; end if;
  if public.is_member(p_conversation) then return 'already_member'; end if;
  if v_conv.join_requires_approval then
    insert into public.group_join_requests (conversation_id, user_id)
    values (p_conversation, v_me)
    on conflict (conversation_id, user_id) where status = 'pending' do nothing;
    return case when found then 'requested' else 'already_requested' end;
  end if;
  insert into public.conversation_members (conversation_id, user_id, role) values (p_conversation, v_me, 'member');
  perform public.post_system_message(p_conversation, public.display_name_of(v_me) || ' joined the group');
  return 'joined';
end $$;

create or replace function public.decide_join_request(p_request uuid, p_approve boolean)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := public.require_uid();
  v_req public.group_join_requests;
begin
  select * into v_req from public.group_join_requests where id = p_request and status = 'pending' for update;
  if v_req.id is null then raise exception 'request not found'; end if;
  if not public.is_group_admin(v_req.conversation_id) then
    raise exception 'only administrators can manage join requests' using errcode = '42501';
  end if;
  update public.group_join_requests
     set status = case when p_approve then 'approved'::public.join_request_status else 'rejected' end,
         decided_by = v_me, decided_at = now()
   where id = p_request;
  if p_approve then
    insert into public.conversation_members (conversation_id, user_id, role, added_by)
    values (v_req.conversation_id, v_req.user_id, 'member', v_me)
    on conflict (conversation_id, user_id) do nothing;
    if v_req.invite_id is not null then
      update public.group_invites set use_count = use_count + 1 where id = v_req.invite_id;
    end if;
    perform public.post_system_message(v_req.conversation_id,
      public.display_name_of(v_me) || ' approved ' || public.display_name_of(v_req.user_id) || ' to join');
  end if;
end $$;

create or replace function public.cancel_join_request(p_conversation uuid)
returns void language sql security definer set search_path = '' as $$
  update public.group_join_requests set status = 'cancelled', decided_at = now()
   where conversation_id = p_conversation and user_id = auth.uid() and status = 'pending'
$$;

-- ---- conversation list ---------------------------------------------
-- SECURITY INVOKER: RLS on the underlying tables applies.
create or replace function public.my_conversations()
returns table (
  id uuid, kind public.conversation_kind, name text, description text, avatar_url text,
  is_public boolean, last_message_at timestamptz, last_message_preview text,
  my_role public.member_role, unread_count bigint, member_count bigint,
  peer_id uuid, peer_username text, peer_display_name text, peer_avatar_url text, peer_last_seen_at timestamptz)
language sql stable set search_path = '' as $$
  select c.id, c.kind, c.name, c.description, c.avatar_url, c.is_public,
         c.last_message_at, c.last_message_preview, me.role,
         (select count(*) from public.messages m
           where m.conversation_id = c.id and m.deleted_at is null
             and m.sender_id is distinct from auth.uid() and m.kind <> 'system'
             and m.created_at > coalesce(me.last_read_at, me.joined_at)),
         (select count(*) from public.conversation_members x where x.conversation_id = c.id),
         peer.id, peer.username, peer.display_name, peer.avatar_url, peer.last_seen_at
  from public.conversation_members me
  join public.conversations c on c.id = me.conversation_id
  left join lateral (
    select p.* from public.conversation_members o join public.profiles p on p.id = o.user_id
    where c.kind = 'direct' and o.conversation_id = c.id and o.user_id <> auth.uid() limit 1
  ) peer on true
  where me.user_id = auth.uid()
  order by c.last_message_at desc
$$;

-- Lock down: signed-in users may call the RPCs; anonymous clients may not.
-- (Postgres grants EXECUTE to PUBLIC by default, so revoke from PUBLIC too.)
revoke execute on all functions in schema public from public, anon;
grant  execute on all functions in schema public to authenticated, service_role;
revoke execute on function public.post_system_message(uuid, text) from authenticated;
revoke execute on function public.handle_new_user() from authenticated;
