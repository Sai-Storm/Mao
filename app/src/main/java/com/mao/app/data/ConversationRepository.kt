package com.mao.app.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

class ConversationRepository(private val supabase: SupabaseClient) {

    private suspend fun rpc(name: String, args: kotlinx.serialization.json.JsonObject = buildJsonObject { }) =
        supabase.postgrest.rpc(name, args)

    private fun uuids(ids: Collection<String>) = JsonArray(ids.map { JsonPrimitive(it) })

    // ---- reading -------------------------------------------------------

    suspend fun myConversations(): List<ConversationSummary> = rpc("my_conversations").decodeList()

    suspend fun get(id: String): Conversation? =
        supabase.from("conversations").select { filter { eq("id", id) } }.decodeSingleOrNull()

    suspend fun members(conversationId: String): List<Member> =
        supabase.from("conversation_members").select(
            Columns.raw("*, profile:profiles!conversation_members_user_id_fkey(*)")
        ) {
            filter { eq("conversation_id", conversationId) }
            order("joined_at", Order.ASCENDING)
        }.decodeList()

    suspend fun publicGroups(query: String): List<Conversation> {
        val q = sanitizeSearch(query)
        return supabase.from("conversations").select {
            filter {
                eq("kind", "group")
                eq("is_public", true)
                if (q.isNotBlank()) ilike("name", "%$q%")
            }
            order("last_message_at", Order.DESCENDING)
            limit(50)
        }.decodeList()
    }

    // ---- private chats & groups -----------------------------------------

    suspend fun getOrCreateDirect(otherUserId: String): String =
        rpc("get_or_create_direct", buildJsonObject { put("p_other", otherUserId) }).decodeAs()

    suspend fun createGroup(
        name: String, description: String, isPublic: Boolean, requiresApproval: Boolean, memberIds: List<String>,
    ): String = rpc("create_group", buildJsonObject {
        put("p_name", name)
        put("p_description", description)
        put("p_is_public", isPublic)
        put("p_join_requires_approval", requiresApproval)
        put("p_member_ids", uuids(memberIds))
    }).decodeAs()

    suspend fun updateGroupInfo(
        conversationId: String,
        name: String? = null, description: String? = null, avatarUrl: String? = null,
        isPublic: Boolean? = null, joinRequiresApproval: Boolean? = null,
        onlyAdminsCanSend: Boolean? = null, onlyAdminsCanEditInfo: Boolean? = null,
        clearAvatar: Boolean = false,
    ) {
        rpc("update_group_info", buildJsonObject {
            put("p_conversation", conversationId)
            name?.let { put("p_name", it) }
            description?.let { put("p_description", it) }
            avatarUrl?.let { put("p_avatar_url", it) }
            isPublic?.let { put("p_is_public", it) }
            joinRequiresApproval?.let { put("p_join_requires_approval", it) }
            onlyAdminsCanSend?.let { put("p_only_admins_can_send", it) }
            onlyAdminsCanEditInfo?.let { put("p_only_admins_can_edit_info", it) }
            if (clearAvatar) put("p_clear_avatar", true)
        })
    }

    suspend fun addMembers(conversationId: String, userIds: List<String>): Int =
        rpc("add_group_members", buildJsonObject {
            put("p_conversation", conversationId)
            put("p_user_ids", uuids(userIds))
        }).decodeAs()

    suspend fun removeMember(conversationId: String, userId: String) {
        rpc("remove_group_member", buildJsonObject { put("p_conversation", conversationId); put("p_user", userId) })
    }

    suspend fun setRole(conversationId: String, userId: String, role: MemberRole) {
        rpc("set_member_role", buildJsonObject {
            put("p_conversation", conversationId); put("p_user", userId); put("p_role", role.name)
        })
    }

    suspend fun transferOwnership(conversationId: String, userId: String) {
        rpc("transfer_group_ownership", buildJsonObject { put("p_conversation", conversationId); put("p_user", userId) })
    }

    /** [until] null lifts the restriction. */
    suspend fun restrictMember(conversationId: String, userId: String, until: Instant?) {
        rpc("restrict_group_member", buildJsonObject {
            put("p_conversation", conversationId); put("p_user", userId)
            put("p_until", until?.toString())
        })
    }

    suspend fun leaveGroup(conversationId: String) {
        rpc("leave_group", buildJsonObject { put("p_conversation", conversationId) })
    }

    // ---- invitations & join requests --------------------------------------

    suspend fun invites(conversationId: String): List<GroupInvite> =
        supabase.from("group_invites").select {
            filter { eq("conversation_id", conversationId) }
            order("created_at", Order.DESCENDING)
        }.decodeList()

    suspend fun createInvite(conversationId: String, requiresApproval: Boolean, expiresInHours: Int?, maxUses: Int?): GroupInvite =
        rpc("create_group_invite", buildJsonObject {
            put("p_conversation", conversationId)
            put("p_requires_approval", requiresApproval)
            expiresInHours?.let { put("p_expires_in_hours", it) }
            maxUses?.let { put("p_max_uses", it) }
        }).decodeAs()

    suspend fun revokeInvite(inviteId: String) {
        rpc("revoke_group_invite", buildJsonObject { put("p_invite", inviteId) })
    }

    suspend fun previewInvite(code: String): InvitePreview? =
        rpc("preview_group_invite", buildJsonObject { put("p_code", code) }).decodeList<InvitePreview>().firstOrNull()

    /** @return joined | requested | already_member | already_requested */
    suspend fun joinWithInvite(code: String): String =
        rpc("join_group_with_invite", buildJsonObject { put("p_code", code) }).decodeAs()

    suspend fun joinPublicGroup(conversationId: String): String =
        rpc("join_public_group", buildJsonObject { put("p_conversation", conversationId) }).decodeAs()

    suspend fun joinRequests(conversationId: String): List<JoinRequest> =
        supabase.from("group_join_requests").select(
            Columns.raw("*, profile:profiles!group_join_requests_user_id_fkey(*)")
        ) {
            filter { eq("conversation_id", conversationId); eq("status", "pending") }
            order("created_at", Order.ASCENDING)
        }.decodeList()

    suspend fun myPendingRequests(): Set<String> {
        val me = supabase.myId ?: return emptySet()
        return supabase.from("group_join_requests").select(Columns.list("conversation_id")) {
            filter { eq("user_id", me); eq("status", "pending") }
        }.decodeList<Map<String, String>>().mapNotNull { it["conversation_id"] }.toSet()
    }

    suspend fun decideJoinRequest(requestId: String, approve: Boolean) {
        rpc("decide_join_request", buildJsonObject { put("p_request", requestId); put("p_approve", approve) })
    }

    suspend fun cancelJoinRequest(conversationId: String) {
        rpc("cancel_join_request", buildJsonObject { put("p_conversation", conversationId) })
    }

    // ---- receipts -------------------------------------------------------

    suspend fun markAllDelivered() { rpc("mark_all_delivered") }

    suspend fun markRead(conversationId: String, upTo: String) {
        rpc("mark_conversation_read", buildJsonObject { put("p_conversation", conversationId); put("p_up_to", upTo) })
    }

    // ---- realtime -------------------------------------------------------

    /**
     * Emits whenever anything that affects the conversation list changes:
     * new messages (conversations.last_message_at), membership changes, join requests.
     * RLS limits the rows each subscriber receives.
     */
    fun listChanges(): Flow<Unit> = channelFlow {
        val me = supabase.requireMyId()
        val channel = supabase.channel("home-${UUID.randomUUID()}")
        val conversations = channel.postgresChangeFlow<PostgresAction>("public") { table = "conversations" }
        val memberships = channel.postgresChangeFlow<PostgresAction>("public") {
            table = "conversation_members"
            filter("user_id", FilterOperator.EQ, me)
        }
        launch { conversations.collect { send(Unit) } }
        launch { memberships.collect { send(Unit) } }
        channel.subscribe()
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                supabase.realtime.removeChannel(channel)
            }
        }
    }
}
