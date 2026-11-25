package com.mao.app.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProfileRepository(private val supabase: SupabaseClient) {

    suspend fun me(): Profile? = supabase.myId?.let { get(it) }

    suspend fun get(id: String): Profile? =
        supabase.from("profiles").select { filter { eq("id", id) } }.decodeSingleOrNull<Profile>()

    /** Search by username or display name, excluding yourself. */
    suspend fun search(query: String, limit: Long = 30): List<Profile> {
        val q = sanitizeSearch(query)
        if (q.length < 2) return emptyList()
        val me = supabase.myId
        return supabase.from("profiles").select {
            filter {
                or {
                    ilike("username", "%$q%")
                    ilike("display_name", "%$q%")
                }
                if (me != null) neq("id", me)
            }
            limit(limit)
        }.decodeList()
    }

    suspend fun update(displayName: String? = null, bio: String? = null, avatarUrl: String? = null) {
        val me = supabase.requireMyId()
        val patch = buildJsonObject {
            displayName?.let { put("display_name", it.trim()) }
            bio?.let { put("bio", it.trim()) }
            avatarUrl?.let { put("avatar_url", it) }
        }
        if (patch.isEmpty()) return
        supabase.from("profiles").update(patch) { filter { eq("id", me) } }
    }
}

/** Strip characters that have meaning in PostgREST filter syntax or LIKE patterns. */
fun sanitizeSearch(query: String): String =
    query.trim().replace(Regex("[%_*,()\\\\\"]"), " ").replace(Regex("\\s+"), " ").take(64)
