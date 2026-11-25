package com.mao.app

import android.app.Application
import com.mao.app.data.AuthRepository
import com.mao.app.data.ConversationRepository
import com.mao.app.data.MediaRepository
import com.mao.app.data.MessageRepository
import com.mao.app.data.ProfileRepository
import com.mao.app.data.createMaoSupabase
import io.github.jan.supabase.SupabaseClient

class MaoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency container: one Supabase client and the repositories built on it. */
class AppContainer(val app: Application) {
    val isConfigured: Boolean = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val supabase: SupabaseClient by lazy { createMaoSupabase() }
    val auth by lazy { AuthRepository(supabase) }
    val profiles by lazy { ProfileRepository(supabase) }
    val media by lazy { MediaRepository(supabase, app) }
    val conversations by lazy { ConversationRepository(supabase) }
    val messages by lazy { MessageRepository(supabase) }
}
