-- Phase 2 call state machine. Runs after 10_rls_tests.sql in the same database
-- (reuses its users and the A<->B direct chat 'DM' and B<->F chat 'DMF').
\set ON_ERROR_STOP 1
\pset tuples_only on
\pset format unaligned
truncate t.results;
set role authenticated;

-- ---------- start / authorization ----------
select t.as_user('C');
select t.fails($q$select public.start_direct_call(t.id('DM'))$q$, 'outsider cannot start call in someone else''s chat');
select t.fails($q$select public.start_direct_call(t.id('G'))$q$, 'cannot start direct call in a group');

select t.as_user('A');
insert into t.ids select 'CALL1', (public.start_direct_call(t.id('DM'), 'voice')).id;
select t.ok((select status = 'ringing' and initiator_id = t.id('A') from public.calls where id = t.id('CALL1')), 'call starts ringing');
select t.ok((select count(*) = 2 from public.call_participants where call_id = t.id('CALL1')), 'both participants recorded');
select t.fails($q$select public.start_direct_call(t.id('DM'))$q$, 'caller cannot start a second call');

select t.as_user('F');
select t.fails($q$select public.start_direct_call(t.id('DMF'))$q$, 'busy callee rejected');

select t.as_user('B');
select t.ok((select count(*) = 1 from public.call_invitations where call_id = t.id('CALL1') and invitee_id = t.id('B')), 'callee sees invitation');
select t.as_user('C');
select t.ok((select count(*) from public.calls where id = t.id('CALL1')) = 0, 'outsider cannot see call');
select t.ok((select count(*) from public.call_invitations) = 0, 'outsider cannot see invitations');

-- ---------- signaling topic ----------
select set_config('realtime.topic', 'call:' || t.id('CALL1'), false);
select t.fails($q$insert into realtime.messages (topic, payload) values (current_setting('realtime.topic'), '{}')$q$, 'outsider blocked from call signaling');
select t.as_user('B');
select t.works($q$insert into realtime.messages (topic, payload) values (current_setting('realtime.topic'), '{}')$q$, 'participant may signal');

-- ---------- accept ----------
select t.as_user('A');
select t.fails($q$select public.accept_call(t.id('CALL1'))$q$, 'caller cannot accept own call');
select t.as_user('B');
select t.works($q$select public.accept_call(t.id('CALL1'), 'device-1')$q$, 'callee accepts');
select t.ok((select status = 'active' and started_at is not null from public.calls where id = t.id('CALL1')), 'call active after accept');
select t.fails($q$select public.accept_call(t.id('CALL1'), 'device-2')$q$, 'second device cannot accept again');
select t.ok((select device_id = 'device-1' from public.call_participants where call_id = t.id('CALL1') and user_id = t.id('B')), 'accepting device recorded');
select t.works($q$select public.log_call_event(t.id('CALL1'), 'reconnecting')$q$, 'participant logs event');
select t.fails($q$select public.log_call_event(t.id('CALL1'), 'evil')$q$, 'unknown event type rejected');
select t.fails($q$select public.log_call_event_internal(t.id('CALL1'), 'started')$q$, 'internal logger not callable');

-- ---------- end ----------
select t.works($q$select public.end_call(t.id('CALL1'))$q$, 'callee hangs up');
select t.ok((select status = 'ended' and ended_at is not null from public.calls where id = t.id('CALL1')), 'active call ends as ended');
select t.works($q$select public.end_call(t.id('CALL1'))$q$, 'end_call is idempotent');
select t.ok((select count(*) = 2 from public.call_participants where call_id = t.id('CALL1') and status = 'left'), 'participants marked left');

-- ---------- decline / cancel / missed ----------
select t.as_user('A');
insert into t.ids select 'CALL2', (public.start_direct_call(t.id('DM'))).id;
select t.as_user('B');
select public.decline_call(t.id('CALL2'));
select t.ok((select status = 'declined' from public.calls where id = t.id('CALL2')), 'decline marks declined');

select t.as_user('A');
insert into t.ids select 'CALL3', (public.start_direct_call(t.id('DM'))).id;
select public.end_call(t.id('CALL3'), 'cancelled');
select t.ok((select status = 'cancelled' from public.calls where id = t.id('CALL3')), 'caller cancel marks cancelled');

insert into t.ids select 'CALL4', (public.start_direct_call(t.id('DM'))).id;
select public.end_call(t.id('CALL4'), 'no_answer');
select t.ok((select status = 'missed' from public.calls where id = t.id('CALL4')), 'no answer marks missed');

-- stale ringing call expires server-side
insert into t.ids select 'CALL5', (public.start_direct_call(t.id('DM'))).id;
reset role;
update public.calls set created_at = now() - interval '2 minutes' where id = t.id('CALL5');
set role authenticated;
select t.as_user('B');
select t.fails($q$select public.accept_call(t.id('CALL5'))$q$, 'cannot accept an expired call');
select t.ok((select status = 'missed' from public.calls where id = t.id('CALL5')), 'stale ringing call becomes missed');

-- ---------- history ----------
select t.ok((select count(*) from public.my_call_history()) = 5, 'history lists my calls');
select t.ok((select bool_and(not outgoing) and bool_and(peer_display_name = 'User A') from public.my_call_history()), 'history shows direction and peer');
select t.as_user('C');
select t.ok((select count(*) from public.my_call_history()) = 0, 'history is private');
select t.fails($q$select public.end_call(t.id('CALL1'))$q$, 'outsider cannot end call');
reset role;

select case when ok then 'PASS' else 'FAIL' end || '  ' || label || coalesce('  -> ' || detail, '') from t.results order by n;
select count(*) filter (where ok) || ' passed, ' || count(*) filter (where not ok) || ' failed' from t.results;
