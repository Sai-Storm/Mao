\set ON_ERROR_STOP 1
\pset tuples_only on
\pset format unaligned
-- ---------- harness ----------
create schema t; grant usage on schema t to authenticated, anon;
create table t.results (n serial, label text, ok boolean, detail text); grant all on t.results, t.results_n_seq to authenticated, anon;
create table t.ids (k text primary key, v uuid); grant all on t.ids to authenticated, anon;
create function t.id(k text) returns uuid language sql stable as $$ select v from t.ids where ids.k = id.k $$;
create function t.ok(cond boolean, label text) returns void language plpgsql as $$
begin insert into t.results(label, ok) values (label, coalesce(cond, false)); end $$;
create function t.fails(q text, label text) returns void language plpgsql as $$
begin
  begin execute q; insert into t.results(label, ok, detail) values (label, false, 'unexpectedly succeeded');
  exception when others then insert into t.results(label, ok, detail) values (label, true, sqlerrm); end;
end $$;
create function t.works(q text, label text) returns void language plpgsql as $$
begin
  begin execute q; insert into t.results(label, ok) values (label, true);
  exception when others then insert into t.results(label, ok, detail) values (label, false, sqlerrm); end;
end $$;
create function t.as_user(k text) returns text language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', t.id(k))::text, false) $$;
grant execute on all functions in schema t to authenticated, anon;

-- ---------- users ----------
insert into t.ids values ('A', gen_random_uuid()), ('B', gen_random_uuid()), ('C', gen_random_uuid()),
  ('D', gen_random_uuid()), ('E', gen_random_uuid()), ('F', gen_random_uuid());
insert into auth.users (id, email, raw_user_meta_data)
select v, k || '@x.test', json_build_object('username', lower(k) || '_user', 'display_name', 'User ' || k)::jsonb from t.ids;
insert into auth.users (id, email, raw_user_meta_data) values (gen_random_uuid(), 'bad@x.test', '{"username":"BAD NAME!"}');
select t.ok((select count(*) = 7 from public.profiles), 'profiles created by trigger');
select t.ok(exists (select 1 from public.profiles where username like 'user\_%'), 'invalid username falls back to user_xxx');

set role authenticated;
-- ---------- group creation ----------
select t.as_user('A');
insert into t.ids select 'G', public.create_group('Gold Lake', 'desc', false, true, array[t.id('B')]);
select t.ok(public.member_role_of(t.id('G'), t.id('A')) = 'owner', 'creator is owner');
select t.ok(public.member_role_of(t.id('G'), t.id('B')) = 'member', 'added user is member');
select t.ok(exists (select 1 from public.messages where conversation_id = t.id('G') and kind = 'system'), 'system message posted');

-- ---------- non-member isolation ----------
select t.as_user('C');
select t.ok((select count(*) from public.conversations where id = t.id('G')) = 0, 'non-member cannot see private group');
select t.ok((select count(*) from public.messages where conversation_id = t.id('G')) = 0, 'non-member cannot read messages');
select t.ok((select count(*) from public.conversation_members where conversation_id = t.id('G')) = 0, 'non-member cannot list members');
select t.fails(format($q$insert into public.messages (conversation_id, sender_id, client_id, body) values (%L, %L, gen_random_uuid(), 'hi')$q$, t.id('G'), t.id('C')), 'non-member cannot post');

-- ---------- member messaging ----------
select t.as_user('B');
insert into t.ids values ('cid1', gen_random_uuid());
select t.works(format($q$insert into public.messages (conversation_id, sender_id, client_id, body, created_at) values (%L, %L, %L, 'hello @a_user', '2000-01-01')$q$, t.id('G'), t.id('B'), t.id('cid1')), 'member can post');
select t.works(format($q$insert into public.messages (conversation_id, sender_id, client_id, body) values (%L, %L, %L, 'dup') on conflict (sender_id, client_id) do nothing$q$, t.id('G'), t.id('B'), t.id('cid1')), 'retry with same client_id is a no-op');
select t.ok((select count(*) from public.messages where client_id = t.id('cid1')) = 1, 'duplicate prevented by client_id');
select t.ok((select created_at > now() - interval '1 minute' from public.messages where client_id = t.id('cid1')), 'server overrides client created_at');
insert into t.ids select 'M1', id from public.messages where client_id = t.id('cid1');
select t.ok((select mentions = '{}' from public.messages where id = t.id('M1')), 'mentions default empty');
select t.fails(format($q$insert into public.messages (conversation_id, sender_id, client_id, body) values (%L, %L, gen_random_uuid(), 'spoof')$q$, t.id('G'), t.id('A')), 'cannot post as someone else');
select t.fails(format($q$insert into public.messages (conversation_id, sender_id, client_id, kind, body) values (%L, %L, gen_random_uuid(), 'system', 'fake')$q$, t.id('G'), t.id('B')), 'cannot post system messages');
select t.fails(format($q$insert into public.messages (conversation_id, sender_id, client_id, kind, media_path) values (%L, %L, gen_random_uuid(), 'image', %L)$q$, t.id('G'), t.id('B'), t.id('G') || '/' || t.id('A') || '/x.jpg'), 'cannot reference another user''s upload folder');
select t.works(format($q$insert into public.messages (conversation_id, sender_id, client_id, body, mentions) values (%L, %L, gen_random_uuid(), 'hey', array[%L, %L]::uuid[])$q$, t.id('G'), t.id('B'), t.id('A'), t.id('F')), 'post with mentions');
select t.ok((select mentions = array[t.id('A')] from public.messages where body = 'hey'), 'mentions of non-members are stripped');
update public.messages set body = 'hacked' where id = t.id('M1');
select t.ok((select body from public.messages where id = t.id('M1')) = 'hello @a_user', 'direct UPDATE has no effect (no policy)');

-- ---------- role permissions ----------
select t.fails(format($q$select public.add_group_members(%L, array[%L]::uuid[])$q$, t.id('G'), t.id('C')), 'member cannot add members');
select t.fails(format($q$select public.update_group_info(%L, p_name => 'x')$q$, t.id('G')), 'member cannot edit info by default');
select t.fails(format($q$select public.set_message_pinned(%L, true)$q$, t.id('M1')), 'member cannot pin in group');
select t.as_user('A');
select public.set_member_role(t.id('G'), t.id('B'), 'admin');
select t.ok(public.member_role_of(t.id('G'), t.id('B')) = 'admin', 'owner promotes member to admin');
select t.as_user('B');
select t.fails(format($q$select public.remove_group_member(%L, %L)$q$, t.id('G'), t.id('A')), 'admin cannot remove owner');
select t.works(format($q$select public.add_group_members(%L, array[%L]::uuid[])$q$, t.id('G'), t.id('C')), 'admin adds member');
select t.as_user('C');
select t.ok((select count(*) from public.messages where conversation_id = t.id('G')) >= 2, 'new member can read history');
select t.fails(format($q$select public.set_member_role(%L, %L, 'member')$q$, t.id('G'), t.id('B')), 'member cannot demote admin');

-- ---------- restricted messaging ----------
select t.as_user('A');
select public.update_group_info(t.id('G'), p_only_admins_can_send => true);
select t.as_user('C');
select t.fails(format($q$insert into public.messages (conversation_id, sender_id, client_id, body) values (%L, %L, gen_random_uuid(), 'x')$q$, t.id('G'), t.id('C')), 'member blocked when only admins can send');
select t.as_user('B');
select t.works(format($q$insert into public.messages (conversation_id, sender_id, client_id, body) values (%L, %L, gen_random_uuid(), 'admin talk')$q$, t.id('G'), t.id('B')), 'admin can send when restricted');
select t.as_user('A');
select public.update_group_info(t.id('G'), p_only_admins_can_send => false);
select public.restrict_group_member(t.id('G'), t.id('C'), now() + interval '1 hour');
select t.as_user('C');
select t.fails(format($q$insert into public.messages (conversation_id, sender_id, client_id, body) values (%L, %L, gen_random_uuid(), 'x')$q$, t.id('G'), t.id('C')), 'restricted member cannot send');
select t.as_user('B');
select t.fails(format($q$select public.restrict_group_member(%L, %L, now() + interval '1 hour')$q$, t.id('G'), t.id('A')), 'admin cannot restrict owner');
select t.works(format($q$select public.restrict_group_member(%L, %L, null)$q$, t.id('G'), t.id('C')), 'admin lifts restriction');
select t.as_user('C');
insert into t.ids values ('cidC', gen_random_uuid());
select t.works(format($q$insert into public.messages (conversation_id, sender_id, client_id, body) values (%L, %L, %L, 'back')$q$, t.id('G'), t.id('C'), t.id('cidC')), 'member can send after lift');
insert into t.ids select 'MC', id from public.messages where client_id = t.id('cidC');

-- ---------- edit / delete ----------
select t.fails(format($q$select public.edit_message(%L, 'changed')$q$, t.id('M1')), 'cannot edit someone else''s message');
select t.as_user('B');
select t.works(format($q$select public.edit_message(%L, 'edited')$q$, t.id('M1')), 'sender edits own message');
select t.ok((select edited_at is not null and body = 'edited' from public.messages where id = t.id('M1')), 'edit recorded');
select t.as_user('C');
select t.fails(format($q$select public.delete_message(%L)$q$, t.id('M1')), 'member cannot delete others'' messages');
select t.as_user('B');
select t.fails(format($q$select public.delete_message(%L)$q$, (select id from public.messages where conversation_id = t.id('G') and kind = 'system' limit 1)), 'system messages cannot be deleted');

-- ---------- reactions & pins ----------
select t.as_user('C');
select t.works(format($q$insert into public.message_reactions (message_id, user_id, emoji) values (%L, %L, '👍')$q$, t.id('M1'), t.id('C')), 'member reacts');
select t.ok((select conversation_id = t.id('G') from public.message_reactions where user_id = t.id('C')), 'reaction conversation_id set by trigger');
select t.as_user('D');
select t.fails(format($q$insert into public.message_reactions (message_id, user_id, emoji) values (%L, %L, '👍')$q$, t.id('M1'), t.id('D')), 'non-member cannot react');
select t.ok((select count(*) from public.message_reactions) = 0, 'non-member cannot see reactions');
select t.as_user('A');
select t.works(format($q$select public.set_message_pinned(%L, true)$q$, t.id('M1')), 'owner pins');
select t.as_user('C');
select t.ok((select count(*) from public.pinned_messages where conversation_id = t.id('G')) = 1, 'member sees pins');
select t.as_user('A');
select t.works(format($q$select public.delete_message(%L)$q$, t.id('MC')), 'owner deletes member message');
select t.ok((select deleted_at is not null and body is null from public.messages where id = t.id('MC')), 'deleted message is scrubbed');

-- ---------- invites ----------
select t.as_user('C');
select t.fails(format($q$select public.create_group_invite(%L)$q$, t.id('G')), 'member cannot create invite');
select t.ok((select count(*) from public.group_invites) = 0, 'member cannot list invites');
select t.as_user('A');
create temp table inv as select * from public.create_group_invite(t.id('G'));
create temp table inv2 as select * from public.create_group_invite(t.id('G'), true);
select t.ok((select length(code) >= 10 from inv), 'invite code generated');
select t.as_user('D');
select t.ok((select member_count from public.preview_group_invite((select code from inv))) = 3, 'invite preview shows group to outsider');
select t.ok(public.join_group_with_invite((select code from inv)) = 'joined', 'invite link joins instantly');
select t.ok(public.is_member(t.id('G')), 'joined user is member');
select t.as_user('E');
select t.ok(public.join_group_with_invite((select code from inv2)) = 'requested', 'approval invite creates request');
select t.ok(not public.is_member(t.id('G')), 'requester not yet member');
select t.ok(public.join_group_with_invite((select code from inv2)) = 'already_requested', 'second request deduplicated');
select t.as_user('C');
select t.ok((select count(*) from public.group_join_requests) = 0, 'member cannot see join requests');
select t.as_user('B');
select t.works(format($q$select public.decide_join_request(%L, true)$q$, (select id from public.group_join_requests where user_id = t.id('E'))), 'admin approves request');
select t.as_user('E');
select t.ok(public.is_member(t.id('G')), 'approved user is member');
select t.as_user('A');
select public.revoke_group_invite((select id from inv));
select t.as_user('F');
select t.ok((select count(*) from public.preview_group_invite((select code from inv))) = 0, 'revoked invite no longer previews');
select t.fails($q$select public.join_group_with_invite((select code from inv))$q$, 'revoked invite cannot be used');

-- ---------- public groups ----------
select t.as_user('A');
insert into t.ids select 'P', public.create_group('Open Lake', null, true, false, null);
select t.as_user('C');
select t.ok((select count(*) from public.conversations where id = t.id('P')) = 1, 'public group discoverable');
select t.ok((select count(*) from public.messages where conversation_id = t.id('P')) = 0, 'public group messages hidden before joining');
select t.ok(public.join_public_group(t.id('P')) = 'joined', 'join open public group');

-- ---------- direct chats, forwarding, storage ----------
select t.as_user('B');
insert into t.ids select 'DM', public.get_or_create_direct(t.id('A'));
select t.ok(public.get_or_create_direct(t.id('A')) = t.id('DM'), 'direct chat reused');
select t.as_user('A');
select t.ok(public.get_or_create_direct(t.id('B')) = t.id('DM'), 'direct chat same from other side');
select t.fails($q$select public.leave_group(t.id('DM'))$q$, 'cannot leave a direct chat');
select t.as_user('C');
select t.ok((select count(*) from public.conversations where id = t.id('DM')) = 0, 'outsider cannot see direct chat');
select t.ok(public.forward_message(t.id('M1'), array[t.id('DM')]) = 0, 'cannot forward into chat you are not in');
select t.as_user('B');
select t.works(format($q$insert into storage.objects (bucket_id, name, owner_id) values ('chat-media', %L, %L)$q$, t.id('G') || '/' || t.id('B') || '/p.jpg', t.id('B')), 'member uploads to own folder');
select t.fails(format($q$insert into storage.objects (bucket_id, name, owner_id) values ('chat-media', %L, %L)$q$, t.id('G') || '/' || t.id('A') || '/p.jpg', t.id('B')), 'cannot upload into another user''s folder');
insert into public.messages (conversation_id, sender_id, client_id, kind, media_path) values (t.id('G'), t.id('B'), gen_random_uuid(), 'image', t.id('G') || '/' || t.id('B') || '/p.jpg');
insert into t.ids select 'DMF', public.get_or_create_direct(t.id('F'));
select t.as_user('F');
select t.ok((select count(*) from storage.objects) = 0, 'outsider cannot read group file');
select t.as_user('B');
select t.ok(public.forward_message((select id from public.messages where media_path is not null limit 1), array[t.id('DMF')]) = 1, 'forward image into direct chat');
select t.as_user('F');
select t.ok((select forwarded from public.messages where conversation_id = t.id('DMF') limit 1), 'forwarded flag set');
select t.ok((select count(*) from storage.objects) = 1, 'forward recipient can read the forwarded file');
insert into public.messages (conversation_id, sender_id, client_id, body, forwarded) values (t.id('DMF'), t.id('F'), gen_random_uuid(), 'fake fwd', true);
select t.ok((select not forwarded from public.messages where body = 'fake fwd'), 'client cannot set forwarded=true');
select t.as_user('C');
select t.fails(format($q$select public.update_group_info(%L, p_is_public => true)$q$, t.id('G')), 'member cannot change group settings');

-- ---------- realtime topic authorization ----------
select t.as_user('C');
select set_config('realtime.topic', 'conv:' || t.id('G'), false);
select t.works($q$insert into realtime.messages (topic, payload) values (current_setting('realtime.topic'), '{}')$q$, 'member may broadcast on group topic');
select set_config('realtime.topic', 'conv:' || t.id('DM'), false);
select t.fails($q$insert into realtime.messages (topic, payload) values (current_setting('realtime.topic'), '{}')$q$, 'non-member blocked from DM topic');
select set_config('realtime.topic', 'conv:not-a-uuid', false);
select t.fails($q$insert into realtime.messages (topic, payload) values (current_setting('realtime.topic'), '{}')$q$, 'malformed topic rejected');

-- ---------- receipts & list ----------
select t.as_user('C');
select t.ok((select unread_count from public.my_conversations() where id = t.id('G')) >= 1, 'unread count reported');
select public.mark_conversation_read(t.id('G'));
select t.ok((select unread_count from public.my_conversations() where id = t.id('G')) = 0, 'mark read clears unread');
select t.ok((select count(*) from public.my_conversations() where id = t.id('DM')) = 0, 'my_conversations only lists own chats');
select t.as_user('B');
select t.ok((select peer_username from public.my_conversations() where id = t.id('DM')) = 'a_user', 'direct chat lists peer');

-- ---------- leaving ----------
select t.as_user('A');
select public.leave_group(t.id('G'));
select t.as_user('B');
select t.ok(public.member_role_of(t.id('G'), t.id('B')) = 'owner', 'ownership passes to admin when owner leaves');

-- ---------- calls: read-only for clients ----------
select t.fails(format($q$insert into public.calls (conversation_id, scope, media, initiator_id, room_name) values (%L, 'group', 'video', %L, 'r1')$q$, t.id('G'), t.id('B')), 'clients cannot create calls directly');

reset role;
set role anon;
select set_config('request.jwt.claims', '', false);
select t.fails($q$select public.create_group('x', null, false, true, null)$q$, 'anon cannot call RPCs');
reset role;

select case when ok then 'PASS' else 'FAIL' end || '  ' || label || coalesce('  -> ' || detail, '') from t.results order by n;
select count(*) filter (where ok) || ' passed, ' || count(*) filter (where not ok) || ' failed' from t.results;
