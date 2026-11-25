// Mao - ICE server list for WebRTC calls.
//
// Only signed-in users get credentials. TURN secrets stay on the server.
// Configure ONE of these (Dashboard -> Edge Functions -> Secrets):
//   coturn REST API (recommended, short-lived creds):
//     TURN_URLS="turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349?transport=tcp"
//     TURN_SECRET="<static-auth-secret from turnserver.conf>"
//   static credentials (e.g. a hosted TURN provider):
//     TURN_URLS=...  TURN_USERNAME=...  TURN_CREDENTIAL=...
// Without TURN, only STUN is returned: calls work on most home/Wi-Fi networks but
// can fail behind symmetric NAT / some mobile carriers.
//
// Deploy: supabase functions deploy turn-credentials

import { createClient } from "jsr:@supabase/supabase-js@2";

const STUN = ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"];
const TTL_SECONDS = 6 * 60 * 60;

async function hmacSha1Base64(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-1" }, false, ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message));
  return btoa(String.fromCharCode(...new Uint8Array(sig)));
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

Deno.serve(async (req) => {
  const auth = req.headers.get("Authorization") ?? "";
  if (!auth.startsWith("Bearer ")) return json({ error: "unauthorized" }, 401);

  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: auth } },
  });
  const { data, error } = await supabase.auth.getUser();
  if (error || !data.user) return json({ error: "unauthorized" }, 401);

  const iceServers: Array<{ urls: string[]; username?: string; credential?: string }> = [{ urls: STUN }];
  const turnUrls = (Deno.env.get("TURN_URLS") ?? "").split(",").map((s) => s.trim()).filter(Boolean);

  if (turnUrls.length > 0) {
    const secret = Deno.env.get("TURN_SECRET");
    if (secret) {
      const username = `${Math.floor(Date.now() / 1000) + TTL_SECONDS}:${data.user.id}`;
      iceServers.push({ urls: turnUrls, username, credential: await hmacSha1Base64(secret, username) });
    } else if (Deno.env.get("TURN_USERNAME") && Deno.env.get("TURN_CREDENTIAL")) {
      iceServers.push({
        urls: turnUrls,
        username: Deno.env.get("TURN_USERNAME")!,
        credential: Deno.env.get("TURN_CREDENTIAL")!,
      });
    }
  }
  return json({ iceServers, ttl: TTL_SECONDS });
});
