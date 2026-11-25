package com.mao.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class Profile(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

enum class MemberRole { owner, admin, member;
    val isAdmin get() = this == owner || this == admin
    val label get() = when (this) { owner -> "Owner"; admin -> "Admin"; member -> "Member" }
}

/** Row returned by the my_conversations() RPC. */
@Serializable
data class ConversationSummary(
    val id: String,
    val kind: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("is_public") val isPublic: Boolean = false,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("last_message_preview") val lastMessagePreview: String? = null,
    @SerialName("my_role") val myRole: MemberRole = MemberRole.member,
    @SerialName("unread_count") val unreadCount: Long = 0,
    @SerialName("member_count") val memberCount: Long = 0,
    @SerialName("peer_id") val peerId: String? = null,
    @SerialName("peer_username") val peerUsername: String? = null,
    @SerialName("peer_display_name") val peerDisplayName: String? = null,
    @SerialName("peer_avatar_url") val peerAvatarUrl: String? = null,
    @SerialName("peer_last_seen_at") val peerLastSeenAt: String? = null,
) {
    val isGroup get() = kind == "group"
    val title get() = if (isGroup) name ?: "Group" else peerDisplayName ?: "Mao user"
    val avatar get() = if (isGroup) avatarUrl else peerAvatarUrl
}

@Serializable
data class Conversation(
    val id: String,
    val kind: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("is_public") val isPublic: Boolean = false,
    @SerialName("join_requires_approval") val joinRequiresApproval: Boolean = true,
    @SerialName("only_admins_can_send") val onlyAdminsCanSend: Boolean = false,
    @SerialName("only_admins_can_edit_info") val onlyAdminsCanEditInfo: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val isGroup get() = kind == "group"
}

@Serializable
data class Member(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    val role: MemberRole = MemberRole.member,
    @SerialName("restricted_until") val restrictedUntil: String? = null,
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("last_delivered_at") val lastDeliveredAt: String? = null,
    @SerialName("last_read_at") val lastReadAt: String? = null,
    val profile: Profile? = null,
) {
    val isRestricted get() = parseTs(restrictedUntil)?.isAfter(Instant.now()) == true
}

@Serializable
data class Message(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("client_id") val clientId: String,
    val kind: String = "text",
    val body: String? = null,
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("media_name") val mediaName: String? = null,
    @SerialName("media_mime") val mediaMime: String? = null,
    @SerialName("media_size") val mediaSize: Long? = null,
    @SerialName("media_duration_ms") val mediaDurationMs: Int? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
    val forwarded: Boolean = false,
    val mentions: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    val isDeleted get() = deletedAt != null
    val isSystem get() = kind == "system"
    val createdInstant: Instant by lazy { parseTs(createdAt) ?: Instant.EPOCH }
}

@Serializable
data class MessageInsert(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("client_id") val clientId: String,
    val kind: String,
    val body: String? = null,
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("media_name") val mediaName: String? = null,
    @SerialName("media_mime") val mediaMime: String? = null,
    @SerialName("media_size") val mediaSize: Long? = null,
    @SerialName("media_duration_ms") val mediaDurationMs: Int? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
    val mentions: List<String> = emptyList(),
)

@Serializable
data class Reaction(
    val id: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String,
)

@Serializable
data class ReactionInsert(
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String,
)

@Serializable
data class PinnedMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("pinned_by") val pinnedBy: String? = null,
    @SerialName("pinned_at") val pinnedAt: String? = null,
)

@Serializable
data class GroupInvite(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val code: String,
    @SerialName("requires_approval") val requiresApproval: Boolean = false,
    @SerialName("max_uses") val maxUses: Int? = null,
    @SerialName("use_count") val useCount: Int = 0,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val link get() = "mao://join/$code"
    val isUsable get() = revokedAt == null &&
        (parseTs(expiresAt)?.isAfter(Instant.now()) ?: true) &&
        (maxUses == null || useCount < maxUses)
}

@Serializable
data class JoinRequest(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
    val profile: Profile? = null,
)

@Serializable
data class InvitePreview(
    @SerialName("conversation_id") val conversationId: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("member_count") val memberCount: Long = 0,
    @SerialName("requires_approval") val requiresApproval: Boolean = false,
    @SerialName("is_member") val isMember: Boolean = false,
    @SerialName("has_pending_request") val hasPendingRequest: Boolean = false,
)

/** Typing broadcast payload on the private conv:<id> channel. */
@Serializable
data class TypingEvent(
    @SerialName("user_id") val userId: String,
    val name: String,
    val typing: Boolean,
)
