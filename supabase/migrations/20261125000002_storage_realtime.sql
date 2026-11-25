-- =====================================================================
-- Mao - storage buckets and realtime authorization
-- =====================================================================

-- ---------------------------------------------------------------------
-- Storage
--   avatars    (public read)  users/<uid>/...   groups/<conversation id>/...
--   chat-media (private)      <conversation id>/<uploader uid>/<file>
-- ---------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit)
values ('avatars', 'avatars', true, 5 * 1024 * 1024),
       ('chat-media', 'chat-media', false, 50 * 1024 * 1024)
on conflict (id) do nothing;

-- needed for upsert (overwrite); files are public anyway
create policy "avatars: signed-in users can list"
on storage.objects for select to authenticated
using (bucket_id = 'avatars');

create policy "avatars: owner or group editor can write"
on storage.objects for insert to authenticated
with check (
  bucket_id = 'avatars' and (
    ((storage.foldername(name))[1] = 'users'  and (storage.foldername(name))[2] = auth.uid()::text)
 or ((storage.foldername(name))[1] = 'groups' and public.can_edit_group_info_text((storage.foldername(name))[2]))
  )
);

create policy "avatars: owner or group editor can update"
on storage.objects for update to authenticated
using (
  bucket_id = 'avatars' and (
    ((storage.foldername(name))[1] = 'users'  and (storage.foldername(name))[2] = auth.uid()::text)
 or ((storage.foldername(name))[1] = 'groups' and public.can_edit_group_info_text((storage.foldername(name))[2]))
  )
);

create policy "avatars: owner or group editor can delete"
on storage.objects for delete to authenticated
using (
  bucket_id = 'avatars' and (
    ((storage.foldername(name))[1] = 'users'  and (storage.foldername(name))[2] = auth.uid()::text)
 or ((storage.foldername(name))[1] = 'groups' and public.can_edit_group_info_text((storage.foldername(name))[2]))
  )
);

-- Upload only into <conversation>/<your uid>/ of a conversation you can send to.
create policy "chat-media: members upload to own folder"
on storage.objects for insert to authenticated
with check (
  bucket_id = 'chat-media'
  and (storage.foldername(name))[2] = auth.uid()::text
  and public.is_member_text((storage.foldername(name))[1])
);

-- Read if you belong to the conversation the folder is for, or to any
-- conversation that a (forwarded) message referencing this file lives in.
create policy "chat-media: members read"
on storage.objects for select to authenticated
using (
  bucket_id = 'chat-media' and (
    public.is_member_text((storage.foldername(name))[1])
    or exists (select 1 from public.messages m
               where m.media_path = storage.objects.name and public.is_member(m.conversation_id))
  )
);

create policy "chat-media: uploader deletes own files"
on storage.objects for delete to authenticated
using (bucket_id = 'chat-media' and owner_id = auth.uid()::text);

-- ---------------------------------------------------------------------
-- Realtime
-- Postgres changes are filtered per subscriber by the RLS policies above.
-- ---------------------------------------------------------------------
alter publication supabase_realtime add table
  public.messages,
  public.message_reactions,
  public.pinned_messages,
  public.conversations,
  public.conversation_members,
  public.group_join_requests;

-- Private broadcast/presence channels (typing indicators, online presence).
-- Topic format: conv:<conversation uuid>. Only members may join or send.
create policy "realtime: members receive conversation topics"
on realtime.messages for select to authenticated
using (
  realtime.topic() like 'conv:%'
  and public.is_member_text(substring(realtime.topic() from 6))
);

create policy "realtime: members send on conversation topics"
on realtime.messages for insert to authenticated
with check (
  realtime.topic() like 'conv:%'
  and public.is_member_text(substring(realtime.topic() from 6))
);
