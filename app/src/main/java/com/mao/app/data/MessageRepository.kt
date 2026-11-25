package com.mao.app.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MessageRepository(private val supabase: SupabaseClient) {

    companion object {
        const val PAGE_SIZE = 40L
    }

    /** Newest-first page of messages strictly older than [before] (keyset pagination on created_at, id). */
    suspend fun page(conversationId: String, before: Message? = null, limit: Long = PAGE_SIZE): List<Message> =
        supabase.from("messages").select {
            filter {
                eq("conversation_id", conversationId)
                if (before != null) {
                    or {
                        lt("created_at", before.createdAt)
                        and {
                            eq("created_at", before.createdAt)
                            lt("id", before.id)
                        }
                    }
                }
            }
            order("created_at", Order.DESCENDING)
            order("id", Order.DESCENDING)
            limit(limit)
        }.decodeList()

    suspend fun get(messageId: String): Message? =
        supabase.from("messages").select { filter { eq("id", messageId) } }.decodeSingleOrNull()

    /**
     * Idempotent send: (sender_id, client_id) is unique, so a retried request after a
     * timeout cannot create a duplicate - the existing row is returned instead.
     */
    suspend fun send(insert: MessageInsert): Message {
        val inserted = supabase.from("messages").upsert(insert) {
            onConflict = "sender_id,client_id"
            ignoreDuplicates = true
            select()
        }.decodeList<Message>().firstOrNull()
        return inserted ?: supabase.from("messages").select {
            filter { eq("sender_id", insert.senderId); eq("client_id", insert.clientId) }
        }.decodeSingle()
    }

    suspend fun edit(messageId: String, body: String, mentions: List<String>) {
        supabase.postgrest.rpc("edit_message", buildJsonObject {
            put("p_message", messageId)
            put("p_body", body)
            put("p_mentions", JsonArray(mentions.map { JsonPrimitive(it) }))
        })
    }

    /** @return the storage path the caller uploaded, if any, so it can be removed. */
    suspend fun delete(messageId: String): String? =
        supabase.postgrest.rpc("delete_message", buildJsonObject { put("p_message", messageId) })
            .decodeAsOrNull<String>()

    suspend fun forward(messageId: String, targets: List<String>): Int =
        supabase.postgrest.rpc("forward_message", buildJsonObject {
            put("p_message", messageId)
            put("p_targets", JsonArray(targets.map { JsonPrimitive(it) }))
        }).decodeAs()

    suspend fun search(conversationId: String, query: String): List<Message> {
        val q = sanitizeSearch(query)
        if (q.length < 2) return emptyList()
        return supabase.from("messages").select {
            filter {
                eq("conversation_id", conversationId)
                ilike("body", "%$q%")
                exact("deleted_at", null)
            }
            order("created_at", Order.DESCENDING)
            limit(50)
        }.decodeList()
    }

    // ---- reactions & pins ---------------------------------------------------

    suspend fun reactions(messageIds: Collection<String>): List<Reaction> {
        if (messageIds.isEmpty()) return emptyList()
        return supabase.from("message_reactions").select {
            filter { isIn("message_id", messageIds.toList()) }
        }.decodeList()
    }

    suspend fun addReaction(messageId: String, emoji: String): Reaction? =
        supabase.from("message_reactions").upsert(ReactionInsert(messageId, supabase.requireMyId(), emoji)) {
            onConflict = "message_id,user_id,emoji"
            ignoreDuplicates = true
            select()
        }.decodeList<Reaction>().firstOrNull()

    suspend fun removeReaction(reactionId: String) {
        supabase.from("message_reactions").delete { filter { eq("id", reactionId) } }
    }

    suspend fun pins(conversationId: String): List<PinnedMessage> =
        supabase.from("pinned_messages").select {
            filter { eq("conversation_id", conversationId) }
            order("pinned_at", Order.DESCENDING)
        }.decodeList()

    suspend fun setPinned(messageId: String, pinned: Boolean) {
        supabase.postgrest.rpc("set_message_pinned", buildJsonObject {
            put("p_message", messageId); put("p_pinned", pinned)
        })
    }

    fun openChannel(conversationId: String) = ConversationChannel(supabase, conversationId)
}

/**
 * One private realtime channel per open conversation (topic conv:<id>).
 * Realtime Authorization policies only let members join it. All flows must be
 * collected before [subscribe] is called.
 */
class ConversationChannel(private val supabase: SupabaseClient, val conversationId: String) {
    private val channel: RealtimeChannel = supabase.channel("conv:$conversationId") { isPrivate = true }

    val messages: Flow<PostgresAction> = channel.postgresChangeFlow("public") {
        table = "messages"
        filter("conversation_id", FilterOperator.EQ, conversationId)
    }

    // DELETE events cannot be filtered server-side; they only carry the row id,
    // so the view model matches them against what it already holds.
    val reactions: Flow<PostgresAction> = channel.postgresChangeFlow("public") { table = "message_reactions" }
    val pins: Flow<PostgresAction> = channel.postgresChangeFlow("public") { table = "pinned_messages" }

    val members: Flow<PostgresAction> = channel.postgresChangeFlow("public") {
        table = "conversation_members"
        filter("conversation_id", FilterOperator.EQ, conversationId)
    }

    val conversation: Flow<PostgresAction> = channel.postgresChangeFlow("public") {
        table = "conversations"
        filter("id", FilterOperator.EQ, conversationId)
    }

    val typing: Flow<TypingEvent> = channel.broadcastFlow<TypingEvent>("typing")

    val status: StateFlow<RealtimeChannel.Status> get() = channel.status

    suspend fun subscribe() = channel.subscribe(blockUntilSubscribed = true)

    suspend fun sendTyping(event: TypingEvent) = channel.broadcast<TypingEvent>("typing", event)

    suspend fun close() = supabase.realtime.removeChannel(channel)
}
