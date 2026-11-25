# Mao

Android chat app (Kotlin, Jetpack Compose, Supabase) with a charcoal and gold glass design.

## Status

| Phase | Scope | State |
|---|---|---|
| Foundation | E-mail auth, profiles, private 1:1 chats | Built. Compiles. Not yet run against a real Supabase project. |
| 1. Group messaging | Groups, roles, admin tools, invites, rich messaging | Built. Database rules pass 87 automated tests. Not yet tested on devices. |
| 2–5. Calls | 1:1 WebRTC voice/video, LiveKit group voice/video | Database tables and RLS only. No calling code yet. |
| 6. Call notifications | FCM, foreground service | Not started (`device_tokens` table exists). |
| 7. Multi-device testing | | Not started. |

There is no calling interface in the app yet. That is deliberate: per the spec, no call
screens ship until they make real connections.

## Setup

1. **Supabase project.** Apply the migrations in order:
   ```
   supabase link --project-ref <ref>
   supabase db push
   ```
   or paste `supabase/migrations/*.sql` into the SQL editor one file at a time.
2. **Auth → URL configuration.** Add `mao://auth` to the allowed redirect URLs (used for e-mail confirmation).
3. **local.properties.** Set `supabase.url` and `supabase.anonKey` (Project Settings → API).
   Never put the `service_role` key in the app.
4. Open the project in Android Studio, or build from the command line:
   `./gradlew :app:assembleDebug`. The build needs SDK platform 37.1.
   `build.dir` in `local.properties` keeps build output out of OneDrive.

### Logo / icons

Save the logo as `branding/mao_logo_source.png`, then run `python branding/make_icons.py`
(needs Pillow). It generates the in-app logo and the adaptive launcher icon.

## How it is secured

* Every table has RLS. Clients can **read** only conversations they belong to,
  plus public groups for discovery.
* Clients can **insert** messages only as themselves, only where `can_send()` allows
  (membership, not restricted, the "only admins can send" setting), and only with media
  from their own upload folder.
* Everything privileged goes through `SECURITY DEFINER` RPCs that check `auth.uid()` and
  the caller's role: group creation, add/remove/promote, restrict, pin, invites, join
  approvals, edit, delete, forward.
* Typing indicators use private realtime channels (`conv:<id>`). Only members can join
  them, enforced by policies on `realtime.messages`.
* Files: `chat-media/<conversation>/<uploader>/…`, private, served through signed URLs.
  Anyone in a conversation that references a file (for example a forward) can read it.
* Call tables are read-only for clients. Calls will be created only by trusted Edge
  Functions using the service role, which will also mint LiveKit tokens.

### Database tests

`supabase/tests/run_local.sh` creates a throwaway local Postgres, applies the migrations
on top of minimal Supabase stubs, and runs `10_rls_tests.sql`. That file acts as several
users and checks what each one may and may not do.

## Group messaging features (Phase 1)

Public and private groups, with name, description and photo. Members are added at
creation or later. Invite links (`mao://join/<code>`) can be instant or approval-based,
with optional expiry, and can be revoked. Public groups can be browsed and joined, or
joined by request.

Roles are owner, admin and member. Admins can add, remove, promote, restrict for
1 h / 24 h / indefinitely, edit info, pin, set "only admins can send", and approve
requests. The owner can transfer ownership. If the owner leaves, the longest-serving
admin (or member) takes over.

Messages support text, photos, videos, documents and voice notes. They also support
replies, forwarding, editing, deleting (with media cleanup), emoji reactions and
@mentions, which are highlighted and validated against members. Other features: pinned
messages banner, in-chat search, typing indicators, sent/delivered/read ticks with a
per-member "Message info", keyset pagination, idempotent sends via `client_id`, and
retry for failed sends. The app polls when realtime is down and re-syncs after
reconnecting.

## Next phases

1. Edge Function `call-signal` plus 1:1 WebRTC (`io.getstream:stream-webrtc-android`),
   using a TURN server for calls across networks.
2. Edge Function `livekit-token`. It verifies membership and issues short-lived
   room-scoped grants. Then the group calls UI with LiveKit Android.
3. FCM high-priority data messages, `CallStyle` notifications, and a foreground service
   of type `phoneCall`.
