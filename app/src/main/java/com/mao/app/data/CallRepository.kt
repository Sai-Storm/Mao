package com.mao.app.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

@Serializable
data class CallRow(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val scope: String,
    val media: String,
    @SerialName("initiator_id") val initiatorId: String? = null,
    @SerialName("room_name") val roomName: String,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("end_reason") val endReason: String? = null,
) {
    val isOver get() = status !in setOf("ringing", "active")
}

@Serializable
data class CallInvitation(
    val id: String,
    @SerialName("call_id") val callId: String,
    @SerialName("inviter_id") val inviterId: String? = null,
    @SerialName("invitee_id") val inviteeId: String,
    val status: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class CallHistoryItem(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val scope: String,
    val media: String,
    val status: String,
    val outgoing: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("peer_id") val peerId: String? = null,
    @SerialName("peer_display_name") val peerDisplayName: String? = null,
    @SerialName("peer_avatar_url") val peerAvatarUrl: String? = null,
    @SerialName("group_name") val groupName: String? = null,
)

@Serializable
data class IceServerDto(val urls: List<String>, val username: String? = null, val credential: String? = null)

@Serializable
private data class IceResponse(val iceServers: List<IceServerDto> = emptyList())

/** WebRTC signaling message exchanged on the private call:<id> topic. */
@Serializable
data class CallSignal(
    val type: String,                 // ready | offer | answer | candidate | hangup | state
    val from: String,                 // user id
    val device: String,               // per-install id, so a second device of the same user is ignored
    val sdp: String? = null,
    val candidate: String? = null,
    @SerialName("sdp_mid") val sdpMid: String? = null,
    @SerialName("sdp_mline_index") val sdpMLineIndex: Int? = null,
    val muted: Boolean? = null,
)

class CallRepository(private val supabase: SupabaseClient) {

    companion object {
        val FALLBACK_ICE = listOf(IceServerDto(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")))
    }

    suspend fun startDirectCall(conversationId: String, media: String): CallRow =
        supabase.postgrest.rpc("start_direct_call", buildJsonObject {
            put("p_conversation", conversationId); put("p_media", media)
        }).decodeAs()

    /** Returns the call as it is after the attempt; status != "active" means it was no longer available. */
    suspend fun accept(callId: String, deviceId: String): CallRow =
        supabase.postgrest.rpc("accept_call", buildJsonObject { put("p_call", callId); put("p_device", deviceId) }).decodeAs()

    suspend fun decline(callId: String) {
        supabase.postgrest.rpc("decline_call", buildJsonObject { put("p_call", callId) })
    }

    suspend fun end(callId: String, reason: String) {
        supabase.postgrest.rpc("end_call", buildJsonObject { put("p_call", callId); put("p_reason", reason) })
    }

    suspend fun logEvent(callId: String, type: String) {
        runCatching { supabase.postgrest.rpc("log_call_event", buildJsonObject { put("p_call", callId); put("p_type", type) }) }
    }

    suspend fun get(callId: String): CallRow? =
        supabase.from("calls").select { filter { eq("id", callId) } }.decodeSingleOrNull()

    suspend fun history(): List<CallHistoryItem> = supabase.postgrest.rpc("my_call_history").decodeList()

    /** STUN + TURN from the turn-credentials Edge Function; STUN only if it is not deployed. */
    suspend fun iceServers(): List<IceServerDto> = runCatching {
        val text = supabase.functions.invoke("turn-credentials").bodyAsText()
        MaoJson.decodeFromString<IceResponse>(text).iceServers.ifEmpty { FALLBACK_ICE }
    }.getOrDefault(FALLBACK_ICE)

    /** Invitations still ringing for me (catches calls that arrived before the listener subscribed). */
    suspend fun pendingInvitations(): List<CallInvitation> {
        val me = supabase.myId ?: return emptyList()
        return supabase.from("call_invitations").select {
            filter {
                eq("invitee_id", me); eq("status", "ringing")
                gt("expires_at", Instant.now().toString())
            }
            order("created_at", Order.DESCENDING)
        }.decodeList()
    }

    /** New ringing invitations addressed to me (RLS: invitee/inviter only). */
    fun incomingInvitations(): Flow<CallInvitation> = channelFlow {
        val me = supabase.requireMyId()
        val channel = supabase.channel("incoming-${UUID.randomUUID()}")
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>("public") {
            table = "call_invitations"
            filter("invitee_id", FilterOperator.EQ, me)
        }
        launch {
            inserts.mapNotNull { runCatching { it.decodeRecord<CallInvitation>() }.getOrNull() }
                .collect { if (it.status == "ringing") send(it) }
        }
        channel.subscribe()
        pendingInvitations().forEach { send(it) }
        try { awaitCancellation() } finally {
            withContext(NonCancellable) { supabase.realtime.removeChannel(channel) }
        }
    }

    fun openSession(callId: String) = CallSignalingChannel(supabase, callId)
}

/** Private realtime channel call:<id>: WebRTC signaling broadcasts + the call row's status changes. */
class CallSignalingChannel(private val supabase: SupabaseClient, val callId: String) {
    private val channel: RealtimeChannel = supabase.channel("call:$callId") { isPrivate = true }

    val signals: Flow<CallSignal> = channel.broadcastFlow<CallSignal>("signal")

    val callUpdates: Flow<CallRow> = channel.postgresChangeFlow<PostgresAction.Update>("public") {
        table = "calls"
        filter("id", FilterOperator.EQ, callId)
    }.mapNotNull { runCatching { it.decodeRecord<CallRow>() }.getOrNull() }

    val status: StateFlow<RealtimeChannel.Status> get() = channel.status

    suspend fun subscribe() = channel.subscribe(blockUntilSubscribed = true)

    suspend fun send(signal: CallSignal) = channel.broadcast<CallSignal>("signal", signal)

    suspend fun close() = supabase.realtime.removeChannel(channel)
}
