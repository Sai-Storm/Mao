package com.mao.app.data

import com.mao.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

val MaoJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

fun createMaoSupabase(): SupabaseClient = createSupabaseClient(
    supabaseUrl = BuildConfig.SUPABASE_URL,
    supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
) {
    defaultSerializer = KotlinXSerializer(MaoJson)
    install(Auth) {
        scheme = "mao"
        host = "auth"
    }
    install(Postgrest)
    install(Realtime)
    install(Storage)
    install(Functions)
}

val SupabaseClient.myId: String? get() = auth.currentUserOrNull()?.id

fun SupabaseClient.requireMyId(): String = myId ?: error("Not signed in")

/** Postgres timestamptz arrives as ISO-8601 with an offset (REST) or sometimes without (realtime). */
fun parseTs(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value.replace(' ', 'T')).toInstant() }.getOrNull()
}

/** Human-readable message for anything thrown by the data layer. */
fun Throwable.friendly(): String = when (this) {
    is RestException -> description?.takeIf { it.isNotBlank() } ?: error
    is HttpRequestException -> "Network problem. Check your connection and try again."
    else -> message ?: this::class.simpleName ?: "Something went wrong"
}
