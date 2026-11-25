package com.mao.app.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AuthRepository(private val supabase: SupabaseClient) {
    val sessionStatus: StateFlow<SessionStatus> get() = supabase.auth.sessionStatus

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    /** @return true when a session started immediately, false when e-mail confirmation is required. */
    suspend fun signUp(email: String, password: String, username: String, displayName: String): Boolean {
        supabase.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                put("username", username.trim().lowercase())
                put("display_name", displayName.trim())
            }
        }
        return supabase.auth.currentSessionOrNull() != null
    }

    suspend fun signOut() = supabase.auth.signOut()
}
