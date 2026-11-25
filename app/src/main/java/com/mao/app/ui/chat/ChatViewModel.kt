package com.mao.app.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mao.app.AppContainer
import com.mao.app.data.Attachment
import com.mao.app.data.ConversationChannel
import com.mao.app.data.Conversation
import com.mao.app.data.ConversationSummary
import com.mao.app.data.Member
import com.mao.app.data.MemberRole
import com.mao.app.data.Message
import com.mao.app.data.MessageInsert
import com.mao.app.data.MessageRepository
import com.mao.app.data.PinnedMessage
import com.mao.app.data.Reaction
import com.mao.app.data.TypingEvent
import com.mao.app.data.friendly
import com.mao.app.data.parseTs
import com.mao.app.data.requireMyId
import com.mao.app.ui.lastSeen
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.decodeRecord
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.util.UUID

enum class Receipt { Sent, Delivered, Read }
enum class SendState { Sending, Failed }

data class ChatItem(
    val message: Message,
    val sendState: SendState? = null,       // null = stored on the server
    val receipt: Receipt? = null,           // only for my own messages
    val localBytes: ByteArray? = null,      // preview of an image still uploading
)

data class ChatState(
    val me: String,
    val loading: Boolean = true,
    val error: String? = null,
    val conversation: Conversation? = null,
    val members: Map<String, Member> = emptyMap(),
    val items: List<ChatItem> = emptyList(),
    val reactions: Map<String, List<Reaction>> = emptyMap(),
    val pins: List<PinnedMessage> = emptyList(),
    val loadingOlder: Boolean = false,
    val reachedStart: Boolean = false,
    val typing: List<String> = emptyList(),
    val replyTo: Message? = null,
    val editing: Message? = null,
    val live: Boolean = false,
    val removed: Boolean = false,
    val scrollTo: String? = null,
    val searchResults: List<Message>? = null,
) {
    val isGroup get() = conversation?.isGroup == true
    val myMember get() = members[me]
    val myRole: MemberRole? get() = myMember?.role
    val peer get() = if (isGroup) null else members.values.firstOrNull { it.userId != me }?.profile
    val title get() = if (isGroup) conversation?.name ?: "Group" else peer?.displayName ?: "Chat"
    val avatar get() = if (isGroup) conversation?.avatarUrl else peer?.avatarUrl
    val subtitle: String get() = when {
        typing.isNotEmpty() -> if (typing.size == 1) (if (isGroup) "${typing[0]} is typing…" else "typing…") else "${typing.size} people are typing…"
        isGroup -> "${members.size} members"
        else -> lastSeen(peer?.lastSeenAt)
    }
    /** Mirrors public.can_send() so the UI can explain why sending is blocked. */
    val sendBlockedReason: String? get() = when {
        removed -> "You're no longer a member of this group"
        myMember == null -> null
        myMember?.isRestricted == true -> "An admin has restricted you from sending messages"
        isGroup && conversation?.onlyAdminsCanSend == true && myRole?.isAdmin != true -> "Only admins can send messages"
        else -> null
    }
    fun canDelete(m: Message) = m.senderId == me ||
        (isGroup && myRole?.isAdmin == true && (members[m.senderId]?.role != MemberRole.owner || myRole == MemberRole.owner))
    fun canPin() = !isGroup || myRole?.isAdmin == true
}

private class Pending(
    val clientId: String,
    var insert: MessageInsert,
    val attachment: Attachment?,
    val createdAt: Instant,
    var state: SendState = SendState.Sending,
)

@OptIn(FlowPreview::class)
class ChatViewModel(private val c: AppContainer, val conversationId: String) : ViewModel() {
    private val me = c.supabase.requireMyId()
    private val _state = MutableStateFlow(ChatState(me = me))
    val state: StateFlow<ChatState> = _state
    val events = MutableStateFlow<String?>(null)

    // All mutable collections are only touched on the main thread (viewModelScope).
    private val messages = HashMap<String, Message>()
    private val pending = LinkedHashMap<String, Pending>()
    private val reactions = HashMap<String, Reaction>()
    private val pins = LinkedHashMap<String, PinnedMessage>()
    private val members = HashMap<String, Member>()
    private val typing = HashMap<String, Pair<String, Long>>()
    val replyCache = MutableStateFlow<Map<String, Message>>(emptyMap())

    private var channel: ConversationChannel? = null
    private var visible = false
    private var lastTypingSent = 0L
    private val readTarget = MutableStateFlow<String?>(null)
    private var myName = ""

    init {
        viewModelScope.launch { initialLoad() }
        viewModelScope.launch { readTarget.filterNotNull().debounce(700).collect { ts ->
            runCatching { c.conversations.markRead(conversationId, ts) }
        } }
        viewModelScope.launch {       // expire stale typing indicators
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                if (typing.values.any { it.second < now }) { typing.entries.removeAll { it.value.second < now }; emit() }
            }
        }
    }

    // ---- loading -----------------------------------------------------------

    private suspend fun initialLoad() {
        try {
            val conv = viewModelScope.async { c.conversations.get(conversationId) }
            val mem = viewModelScope.async { c.conversations.members(conversationId) }
            val page = viewModelScope.async { c.messages.page(conversationId) }
            val pinList = viewModelScope.async { c.messages.pins(conversationId) }
            val conversation = conv.await() ?: run {
                _state.update { it.copy(loading = false, error = "Conversation not found or you are not a member") }
                return
            }
            mem.await().forEach { members[it.userId] = it }
            myName = members[me]?.profile?.displayName ?: "Someone"
            val first = page.await()
            first.forEach { messages[it.id] = it }
            pinList.await().forEach { pins[it.id] = it }
            loadReactions(first.map { it.id })
            _state.update { it.copy(loading = false, conversation = conversation, reachedStart = first.size < MessageRepository.PAGE_SIZE) }
            emit()
            maybeMarkRead()
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.friendly()) }
            return
        }
        connectRealtime()
    }

    private suspend fun loadReactions(ids: List<String>) {
        runCatching { c.messages.reactions(ids) }.getOrNull()?.forEach { reactions[it.id] = it }
    }

    fun loadOlder() {
        val s = _state.value
        if (s.loadingOlder || s.reachedStart || s.loading) return
        val oldest = messages.values.minWithOrNull(compareBy<Message> { it.createdInstant }.thenBy { it.id }) ?: return
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                val older = c.messages.page(conversationId, before = oldest)
                older.forEach { messages[it.id] = it }
                loadReactions(older.map { it.id })
                _state.update { it.copy(loadingOlder = false, reachedStart = older.size < MessageRepository.PAGE_SIZE) }
                emit()
            } catch (e: Exception) {
                _state.update { it.copy(loadingOlder = false) }
                events.value = e.friendly()
            }
        }
    }

    /** After a reconnect: pull anything missed while offline (newest page, members, pins). */
    private suspend fun resync() {
        runCatching {
            val newest = c.messages.page(conversationId)
            newest.forEach { messages[it.id] = it; pending.remove(it.clientId) }
            loadReactions(newest.map { it.id })
            c.conversations.members(conversationId).let { list -> members.clear(); list.forEach { members[it.userId] = it } }
            c.messages.pins(conversationId).let { list -> pins.clear(); list.forEach { pins[it.id] = it } }
            c.conversations.get(conversationId)?.let { conv -> _state.update { it.copy(conversation = conv) } }
            emit()
            maybeMarkRead()
        }
    }

    // ---- realtime ------------------------------------------------------------

    private fun connectRealtime() {
        val ch = c.messages.openChannel(conversationId)
        channel = ch
        viewModelScope.launch { ch.messages.collect(::onMessageChange) }
        viewModelScope.launch { ch.reactions.collect(::onReactionChange) }
        viewModelScope.launch { ch.pins.collect(::onPinChange) }
        viewModelScope.launch { ch.members.collect(::onMemberChange) }
        viewModelScope.launch {
            ch.conversation.collect { a ->
                if (a is PostgresAction.Update) runCatching { a.decodeRecord<Conversation>() }.getOrNull()?.let { conv ->
                    _state.update { it.copy(conversation = conv) }
                }
            }
        }
        viewModelScope.launch {
            ch.typing.collect { e ->
                if (e.userId == me || e.userId !in members) return@collect
                if (e.typing) typing[e.userId] = e.name to System.currentTimeMillis() + 5000 else typing.remove(e.userId)
                emit()
            }
        }
        viewModelScope.launch {
            var wasSubscribed = false
            ch.status.collect { st ->
                val live = st == RealtimeChannel.Status.SUBSCRIBED
                _state.update { it.copy(live = live) }
                if (live && wasSubscribed) resync()
                if (live) wasSubscribed = true
            }
        }
        viewModelScope.launch {
            try { ch.subscribe() } catch (e: Exception) { events.value = "Live updates unavailable: ${e.friendly()}" }
        }
        // Fallback: if realtime is down, poll so the conversation still updates.
        viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                if (!_state.value.live && !_state.value.loading) resync()
            }
        }
    }

    private fun onMessageChange(a: PostgresAction) {
        when (a) {
            is PostgresAction.Insert, is PostgresAction.Update -> {
                val m = runCatching { (a as io.github.jan.supabase.realtime.HasRecord).decodeRecord<Message>() }.getOrNull() ?: return
                if (m.conversationId != conversationId) return
                messages[m.id] = m
                if (m.senderId == me) pending.remove(m.clientId)
                if (m.deletedAt != null) reactions.values.removeAll { it.messageId == m.id }
                if (m.isSystem) refreshMembersSoon()
                emit()
                if (m.senderId != me) maybeMarkRead()
            }
            is PostgresAction.Delete -> {
                a.oldRecord["id"]?.jsonPrimitive?.content?.let { messages.remove(it); emit() }
            }
            else -> Unit
        }
    }

    private fun onReactionChange(a: PostgresAction) {
        when (a) {
            is PostgresAction.Insert -> runCatching { a.decodeRecord<Reaction>() }.getOrNull()
                ?.takeIf { it.conversationId == conversationId }?.let { reactions[it.id] = it; emit() }
            is PostgresAction.Delete -> a.oldRecord["id"]?.jsonPrimitive?.content?.let { if (reactions.remove(it) != null) emit() }
            else -> Unit
        }
    }

    private fun onPinChange(a: PostgresAction) {
        when (a) {
            is PostgresAction.Insert -> runCatching { a.decodeRecord<PinnedMessage>() }.getOrNull()
                ?.takeIf { it.conversationId == conversationId }?.let { pins[it.id] = it; emit() }
            is PostgresAction.Delete -> a.oldRecord["id"]?.jsonPrimitive?.content?.let { if (pins.remove(it) != null) emit() }
            else -> Unit
        }
    }

    private fun onMemberChange(a: PostgresAction) {
        when (a) {
            is PostgresAction.Insert, is PostgresAction.Update -> {
                val m = runCatching { (a as io.github.jan.supabase.realtime.HasRecord).decodeRecord<Member>() }.getOrNull() ?: return
                val known = members[m.userId]
                members[m.userId] = m.copy(profile = known?.profile)
                if (known?.profile == null) refreshMembersSoon()
                emit()
            }
            is PostgresAction.Delete -> {
                val rowId = a.oldRecord["id"]?.jsonPrimitive?.content ?: return
                val entry = members.entries.firstOrNull { it.value.id == rowId } ?: return
                members.remove(entry.key)
                if (entry.key == me) _state.update { it.copy(removed = true) }
                emit()
            }
            else -> Unit
        }
    }

    private var memberRefresh: Job? = null
    private fun refreshMembersSoon() {
        memberRefresh?.cancel()
        memberRefresh = viewModelScope.launch {
            delay(500)
            runCatching { c.conversations.members(conversationId) }.getOrNull()?.let { list ->
                members.clear(); list.forEach { members[it.userId] = it }
                if (me !in members) _state.update { it.copy(removed = true) }
                emit()
            }
        }
    }

    // ---- derived list -----------------------------------------------------------

    private fun emit() {
        val others = members.values.filter { it.userId != me }
        val minRead = others.minOfOrNull { parseTs(it.lastReadAt) ?: Instant.EPOCH }
        val minDelivered = others.minOfOrNull { parseTs(it.lastDeliveredAt) ?: Instant.EPOCH }

        val server = messages.values.map { m ->
            val receipt = if (m.senderId == me && !m.isSystem) when {
                minRead == null -> Receipt.Sent
                !m.createdInstant.isAfter(minRead) -> Receipt.Read
                minDelivered != null && !m.createdInstant.isAfter(minDelivered) -> Receipt.Delivered
                else -> Receipt.Sent
            } else null
            ChatItem(m, receipt = receipt)
        }
        val local = pending.values.map { p ->
            ChatItem(
                Message(
                    id = "local-" + p.clientId, conversationId = conversationId, senderId = me, clientId = p.clientId,
                    kind = p.insert.kind, body = p.insert.body, mediaName = p.attachment?.name, mediaMime = p.attachment?.mime,
                    mediaSize = p.attachment?.size, mediaDurationMs = p.insert.mediaDurationMs, replyToId = p.insert.replyToId,
                    mentions = p.insert.mentions, createdAt = p.createdAt.toString(),
                ),
                sendState = p.state,
                localBytes = p.attachment?.bytes?.takeIf { p.attachment.kind == "image" },
            )
        }
        val items = (server + local).sortedWith(
            compareByDescending<ChatItem> { it.message.createdInstant }.thenByDescending { it.message.id }
        )
        val now = System.currentTimeMillis()
        _state.update { s ->
            s.copy(
                members = HashMap(members),
                items = items,
                reactions = reactions.values.groupBy { it.messageId },
                pins = pins.values.sortedByDescending { it.pinnedAt },
                typing = typing.filterValues { it.second >= now }.values.map { it.first },
            )
        }
    }

    // ---- read receipts ----------------------------------------------------------

    fun setVisible(v: Boolean) {
        visible = v
        if (v) maybeMarkRead()
    }

    private fun maybeMarkRead() {
        if (!visible) return
        val newest = messages.values.maxByOrNull { it.createdInstant } ?: return
        val myRead = parseTs(members[me]?.lastReadAt)
        if (myRead == null || newest.createdInstant.isAfter(myRead)) readTarget.value = newest.createdAt
    }

    // ---- composing & sending ---------------------------------------------------------

    fun onDraftChanged(text: String) {
        val now = System.currentTimeMillis()
        if (text.isNotBlank() && now - lastTypingSent > 2500) {
            lastTypingSent = now
            viewModelScope.launch { runCatching { channel?.sendTyping(TypingEvent(me, myName, true)) } }
        }
    }

    private fun stopTyping() {
        if (lastTypingSent == 0L) return
        lastTypingSent = 0L
        viewModelScope.launch { runCatching { channel?.sendTyping(TypingEvent(me, myName, false)) } }
    }

    fun startReply(m: Message) = _state.update { it.copy(replyTo = m, editing = null) }
    fun startEdit(m: Message) = _state.update { it.copy(editing = m, replyTo = null) }
    fun cancelCompose() = _state.update { it.copy(replyTo = null, editing = null) }

    /** @-mentions are resolved against current members by username. */
    fun mentionIds(text: String): List<String> {
        val names = Regex("@([a-z0-9_]{3,24})").findAll(text).map { it.groupValues[1] }.toSet()
        return members.values.filter { it.profile?.username in names }.map { it.userId }
    }

    fun sendText(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        stopTyping()
        val editing = _state.value.editing
        if (editing != null) {
            _state.update { it.copy(editing = null) }
            viewModelScope.launch {
                try { c.messages.edit(editing.id, text, mentionIds(text)) } catch (e: Exception) { events.value = e.friendly() }
            }
            return
        }
        enqueue("text", body = text, attachment = null)
    }

    fun sendAttachment(uri: Uri, kindHint: String? = null) {
        viewModelScope.launch {
            try { enqueue(kindHint ?: "document", body = null, attachment = c.media.readAttachment(uri, kindHint)) }
            catch (e: Exception) { events.value = e.friendly() }
        }
    }

    fun sendVoice(file: File, durationMs: Int) {
        viewModelScope.launch {
            try {
                val a = withContext(kotlinx.coroutines.Dispatchers.IO) { c.media.readVoiceFile(file, durationMs) }
                file.delete()
                enqueue("voice", body = null, attachment = a)
            } catch (e: Exception) { events.value = e.friendly() }
        }
    }

    private fun enqueue(kind: String, body: String?, attachment: Attachment?) {
        val clientId = UUID.randomUUID().toString()
        val reply = _state.value.replyTo
        val insert = MessageInsert(
            conversationId = conversationId, senderId = me, clientId = clientId,
            kind = attachment?.kind ?: kind, body = body,
            mediaName = attachment?.name, mediaMime = attachment?.mime, mediaSize = attachment?.size,
            mediaDurationMs = attachment?.durationMs, replyToId = reply?.id,
            mentions = body?.let(::mentionIds) ?: emptyList(),
        )
        pending[clientId] = Pending(clientId, insert, attachment, Instant.now())
        _state.update { it.copy(replyTo = null) }
        emit()
        deliver(clientId)
    }

    private fun deliver(clientId: String) {
        val p = pending[clientId] ?: return
        p.state = SendState.Sending
        emit()
        viewModelScope.launch {
            try {
                if (p.attachment != null && p.insert.mediaPath == null) {
                    // remember the path so a retry doesn't upload the file twice
                    p.insert = p.insert.copy(mediaPath = c.media.uploadChatMedia(conversationId, p.attachment))
                }
                val saved = c.messages.send(p.insert)
                pending.remove(clientId)
                messages[saved.id] = saved
                emit()
            } catch (e: Exception) {
                pending[clientId]?.state = SendState.Failed
                events.value = e.friendly()
                emit()
            }
        }
    }

    fun retry(item: ChatItem) = deliver(item.message.clientId)

    fun discard(item: ChatItem) {
        val p = pending.remove(item.message.clientId) ?: return
        p.insert.mediaPath?.let { path -> viewModelScope.launch(NonCancellable) { c.media.deleteChatMedia(path) } }
        emit()
    }

    // ---- message actions ---------------------------------------------------------------

    fun toggleReaction(messageId: String, emoji: String) {
        val mine = reactions.values.firstOrNull { it.messageId == messageId && it.userId == me && it.emoji == emoji }
        viewModelScope.launch {
            try {
                if (mine != null) {
                    reactions.remove(mine.id); emit()
                    c.messages.removeReaction(mine.id)
                } else {
                    c.messages.addReaction(messageId, emoji)?.let { reactions[it.id] = it; emit() }
                }
            } catch (e: Exception) {
                mine?.let { reactions[it.id] = it; emit() }
                events.value = e.friendly()
            }
        }
    }

    fun setPinned(m: Message, pinned: Boolean) = viewModelScope.launch {
        try { c.messages.setPinned(m.id, pinned); events.value = if (pinned) "Message pinned" else "Message unpinned" }
        catch (e: Exception) { events.value = e.friendly() }
    }

    fun isPinned(messageId: String) = pins.values.any { it.messageId == messageId }

    fun delete(m: Message) = viewModelScope.launch {
        try {
            val path = c.messages.delete(m.id)
            path?.let { c.media.deleteChatMedia(it) }
        } catch (e: Exception) { events.value = e.friendly() }
    }

    suspend fun forwardTargets(): List<ConversationSummary> = c.conversations.myConversations()

    fun forward(m: Message, targets: List<String>) = viewModelScope.launch {
        try {
            val n = c.messages.forward(m.id, targets)
            events.value = if (n == targets.size) "Forwarded" else "Forwarded to $n of ${targets.size} (some chats don't allow you to send)"
        } catch (e: Exception) { events.value = e.friendly() }
    }

    suspend fun signedUrl(path: String) = c.media.signedUrl(path)

    /** Message a reply quotes, fetched if it isn't in the loaded window. */
    fun resolveReply(id: String): Message? {
        messages[id]?.let { return it }
        replyCache.value[id]?.let { return it }
        viewModelScope.launch {
            runCatching { c.messages.get(id) }.getOrNull()?.let { m -> replyCache.update { it + (m.id to m) } }
        }
        return null
    }

    // ---- search & jump ---------------------------------------------------------------

    fun search(query: String) = viewModelScope.launch {
        if (query.isBlank()) { _state.update { it.copy(searchResults = null) }; return@launch }
        try { _state.update { it.copy(searchResults = c.messages.search(conversationId, query)) } }
        catch (e: Exception) { events.value = e.friendly() }
    }

    fun clearSearch() = _state.update { it.copy(searchResults = null) }

    /** Page backwards until the message is loaded (bounded), then ask the UI to scroll to it. */
    fun jumpTo(messageId: String) = viewModelScope.launch {
        var guard = 0
        while (messageId !in messages && !_state.value.reachedStart && guard++ < 50) {
            val oldest = messages.values.minWithOrNull(compareBy<Message> { it.createdInstant }.thenBy { it.id }) ?: break
            val older = try { c.messages.page(conversationId, before = oldest, limit = 100) } catch (e: Exception) { events.value = e.friendly(); break }
            older.forEach { messages[it.id] = it }
            loadReactions(older.map { it.id })
            if (older.size < 100) _state.update { it.copy(reachedStart = true) }
        }
        emit()
        if (messageId in messages) _state.update { it.copy(scrollTo = messageId) } else events.value = "Message is no longer available"
    }

    fun consumeScroll() = _state.update { it.copy(scrollTo = null) }

    override fun onCleared() {
        val ch = channel
        if (ch != null) {
            // viewModelScope is cancelled here; leave the channel on the app-wide client scope
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch(NonCancellable) {
                runCatching { ch.sendTyping(TypingEvent(me, myName, false)) }
                runCatching { ch.close() }
            }
        }
    }
}
