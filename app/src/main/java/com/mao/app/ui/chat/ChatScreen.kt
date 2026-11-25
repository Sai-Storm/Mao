package com.mao.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mao.app.data.ConversationSummary
import com.mao.app.data.Member
import com.mao.app.data.Message
import com.mao.app.data.parseTs
import com.mao.app.media.AudioPlayer
import com.mao.app.media.VoiceRecorder
import com.mao.app.ui.components.Avatar
import com.mao.app.ui.components.CenteredLoading
import com.mao.app.ui.components.GlassIconButton
import com.mao.app.ui.components.GoldButton
import com.mao.app.ui.components.TopBar
import com.mao.app.ui.components.glass
import com.mao.app.ui.containerViewModel
import com.mao.app.ui.copyToClipboard
import com.mao.app.ui.dayHeader
import com.mao.app.ui.formatDuration
import com.mao.app.ui.formatTime
import com.mao.app.ui.group.SheetItem
import com.mao.app.ui.theme.MaoColors
import com.mao.app.ui.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.ZoneId

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(conversationId: String, onBack: () -> Unit, onOpenInfo: () -> Unit, onOpenChat: (String) -> Unit) {
    val vm = containerViewModel(key = "chat-$conversationId") { ChatViewModel(it, conversationId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()
    val replyCache by vm.replyCache.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val audio = remember { AudioPlayer(scope) }
    val playback by audio.state.collectAsStateWithLifecycle()
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    var recording by remember { mutableStateOf(false) }
    var recordMs by remember { mutableLongStateOf(0L) }

    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var actionFor by remember { mutableStateOf<ChatItem?>(null) }
    var forwardFor by remember { mutableStateOf<Message?>(null) }
    var infoFor by remember { mutableStateOf<Message?>(null) }
    var confirmDelete by remember { mutableStateOf<Message?>(null) }
    var imagePath by remember { mutableStateOf<String?>(null) }
    var videoPath by remember { mutableStateOf<String?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var pinIndex by remember { mutableIntStateOf(0) }
    var highlight by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(event) { event?.let { context.toast(it); vm.events.value = null } }
    LifecycleResumeEffect(Unit) {
        vm.setVisible(true)
        onPauseOrDispose {
            vm.setVisible(false)
            audio.release()
            if (recorder.isRecording) { recorder.cancel(); recording = false }
        }
    }
    // When editing starts, put the message text into the composer.
    LaunchedEffect(state.editing?.id) {
        state.editing?.body?.let { draft = TextFieldValue(it, TextRange(it.length)) }
    }
    // Pagination: load older messages near the top of the (reversed) list.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= state.items.size - 8) vm.loadOlder() }
    }
    // Stick to the newest message when already at the bottom.
    val newestId = state.items.firstOrNull()?.message?.id
    LaunchedEffect(newestId) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }
    LaunchedEffect(state.scrollTo) {
        val target = state.scrollTo ?: return@LaunchedEffect
        val idx = state.items.indexOfFirst { it.message.id == target }
        if (idx >= 0) { listState.animateScrollToItem(idx); highlight = target }
        vm.consumeScroll()
        delay(1800); highlight = null
    }
    LaunchedEffect(recording) {
        while (recording) { recordMs = recorder.elapsedMs; delay(200) }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            vm.sendAttachment(uri, if (mime.startsWith("video/")) "video" else "image")
        }
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.sendAttachment(it, "document") } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) context.toast("Microphone permission is needed for voice messages")
        else runCatching { recorder.start(); recording = true }.onFailure { context.toast("Couldn't start recording") }
    }
    fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runCatching { recorder.start(); recording = true }.onFailure { context.toast("Couldn't start recording") }
        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val myUsername = state.members[state.me]?.profile?.username
    val ctx = BubbleContext(
        me = state.me, myUsername = myUsername, isGroup = state.isGroup, members = state.members, playback = playback,
        signedUrl = vm::signedUrl,
        resolveReply = { id -> replyCache[id] ?: vm.resolveReply(id) },
        onToggleVoice = { key, url -> audio.toggle(key, url) },
        onOpenImage = { imagePath = it }, onOpenVideo = { videoPath = it },
        onJump = { vm.jumpTo(it) },
        onReact = { id, e -> vm.toggleReaction(id, e) },
    )

    Column(Modifier.fillMaxSize().imePadding()) {
        // ---- header ----
        if (searching) {
            SearchBar(searchQuery, onChange = { searchQuery = it; vm.search(it) }, onClose = { searching = false; searchQuery = ""; vm.clearSearch() })
        } else {
            TopBar(
                title = state.title, subtitle = state.subtitle, onBack = onBack,
                leading = { Avatar(state.avatar, state.title, size = 40.dp, isGroup = state.isGroup) },
                onTitleClick = onOpenInfo,
                actions = {
                    if (!state.live && !state.loading) {
                        Text("offline", color = MaoColors.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp))
                    }
                    GlassIconButton(Icons.Rounded.Search, "Search messages", { searching = true })
                    Spacer(Modifier.width(8.dp))
                },
            )
        }
        if (state.loading) { CenteredLoading(); return@Column }
        state.error?.let { Text(it, color = MaoColors.Danger, modifier = Modifier.padding(24.dp)); return@Column }

        // ---- pinned banner ----
        if (state.pins.isNotEmpty() && !searching) {
            val pin = state.pins[pinIndex % state.pins.size]
            val pinned = state.items.firstOrNull { it.message.id == pin.messageId }?.message ?: replyCache[pin.messageId] ?: vm.resolveReply(pin.messageId)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).glass(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClickLabel = "Go to pinned message") {
                        vm.jumpTo(pin.messageId); pinIndex = (pinIndex + 1) % state.pins.size
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PushPin, null, tint = MaoColors.Gold, modifier = Modifier.size(18.dp))
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(if (state.pins.size > 1) "Pinned message ${pinIndex % state.pins.size + 1} of ${state.pins.size}" else "Pinned message",
                        color = MaoColors.Gold, style = MaterialTheme.typography.labelSmall)
                    Text(pinned?.let(::previewOf) ?: "…", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // ---- search results or message list ----
        val results = state.searchResults
        if (searching && results != null) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (results.isEmpty()) item { Text("No messages found", color = MaoColors.TextMuted, modifier = Modifier.padding(12.dp)) }
                items(results, key = { "s-" + it.id }) { m ->
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).clickable {
                        searching = false; searchQuery = ""; vm.clearSearch(); vm.jumpTo(m.id)
                    }.padding(12.dp)) {
                        Row {
                            Text(if (m.senderId == state.me) "You" else state.members[m.senderId]?.profile?.displayName ?: "", color = MaoColors.Gold,
                                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Text(dayHeader(m.createdInstant) + " " + formatTime(m.createdInstant), color = MaoColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(mentionText(m.body.orEmpty(), myUsername), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(state.items, key = { _, it -> it.message.id }) { i, item ->
                        val older = state.items.getOrNull(i + 1)?.message
                        val day = item.message.createdInstant.atZone(ZoneId.systemDefault()).toLocalDate()
                        val newDay = older == null || older.createdInstant.atZone(ZoneId.systemDefault()).toLocalDate() != day
                        val showSender = newDay || older?.senderId != item.message.senderId || older?.isSystem == true
                        Column {
                            if (newDay) DayChip(dayHeader(item.message.createdInstant))
                            MessageRow(
                                item = item, showSender = showSender,
                                reactions = state.reactions[item.message.id].orEmpty(),
                                highlighted = highlight == item.message.id, ctx = ctx,
                                onLongPress = { if (!item.message.isSystem) actionFor = item },
                                onFailedTap = { vm.retry(item) },
                            )
                        }
                    }
                    if (state.loadingOlder) item { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = MaoColors.Gold, modifier = Modifier.size(24.dp)) } }
                    if (state.reachedStart && state.items.isEmpty()) item {
                        Text("No messages yet. Say hello!", color = MaoColors.TextMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
                    }
                }
                val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }
                if (showJump) {
                    GlassIconButton(Icons.Rounded.KeyboardArrowDown, "Scroll to newest", { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
                }
            }
        }

        // ---- composer ----
        val blocked = state.sendBlockedReason
        if (blocked != null) {
            Text(blocked, color = MaoColors.TextSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).glass(RoundedCornerShape(16.dp)).padding(14.dp))
        } else {
            Composer(
                state = state, draft = draft,
                onDraft = { draft = it; vm.onDraftChanged(it.text) },
                recording = recording, recordMs = recordMs,
                attachMenu = attachMenu, onAttachMenu = { attachMenu = it },
                onPickMedia = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onPickDoc = { docPicker.launch(arrayOf("*/*")) },
                onSend = { vm.sendText(draft.text); draft = TextFieldValue("") },
                onMic = ::startRecording,
                onStopRecording = { send ->
                    val result = recorder.stop()
                    recording = false
                    if (send) { if (result != null) vm.sendVoice(result.first, result.second) else context.toast("Hold on a little longer to record") }
                    else result?.first?.delete()
                },
                onCancelCompose = { vm.cancelCompose(); if (state.editing != null) draft = TextFieldValue("") },
                resolve = { id -> state.items.firstOrNull { it.message.id == id }?.message },
            )
        }
    }

    // ---- sheets & dialogs ----
    actionFor?.let { item ->
        val m = item.message
        ModalBottomSheet(onDismissRequest = { actionFor = null }, containerColor = MaoColors.Charcoal800) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                if (item.sendState != null) {
                    if (item.sendState == SendState.Failed) SheetItem("Retry sending") { actionFor = null; vm.retry(item) }
                    SheetItem("Discard", danger = true) { actionFor = null; vm.discard(item) }
                    return@Column
                }
                if (!m.isDeleted && state.sendBlockedReason == null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        QUICK_REACTIONS.forEach { e ->
                            Text(e, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "React $e") {
                                actionFor = null; vm.toggleReaction(m.id, e)
                            }.padding(6.dp))
                        }
                    }
                    HorizontalDivider(color = MaoColors.GlassBorder)
                }
                if (!m.isDeleted) {
                    if (state.sendBlockedReason == null) SheetItem("Reply") { actionFor = null; vm.startReply(m) }
                    if (!m.body.isNullOrEmpty()) SheetItem("Copy text") { actionFor = null; context.copyToClipboard("Message", m.body); context.toast("Copied") }
                    SheetItem("Forward") { actionFor = null; forwardFor = m }
                    if (m.senderId == state.me && m.kind == "text" && state.sendBlockedReason == null) SheetItem("Edit") { actionFor = null; vm.startEdit(m) }
                    if (state.canPin()) {
                        val pinned = vm.isPinned(m.id)
                        SheetItem(if (pinned) "Unpin" else "Pin") { actionFor = null; vm.setPinned(m, !pinned) }
                    }
                    if (m.senderId == state.me && state.isGroup) SheetItem("Message info") { actionFor = null; infoFor = m }
                }
                if (!m.isDeleted && state.canDelete(m)) SheetItem("Delete", danger = true) { actionFor = null; confirmDelete = m }
            }
        }
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = MaoColors.Charcoal800,
            title = { Text("Delete message?") },
            text = { Text("It will be removed for everyone in this chat.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; vm.delete(m) }) { Text("Delete", color = MaoColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = MaoColors.TextSecondary) } },
        )
    }

    forwardFor?.let { m -> ForwardSheet(load = vm::forwardTargets, currentId = conversationId, onDismiss = { forwardFor = null }) { targets -> forwardFor = null; vm.forward(m, targets) } }
    infoFor?.let { m -> MessageInfoSheet(m, state.members.values.filter { it.userId != state.me }) { infoFor = null } }
    imagePath?.let { ImageViewerDialog(it, vm::signedUrl) { imagePath = null } }
    videoPath?.let { VideoPlayerDialog(it, vm::signedUrl) { videoPath = null } }
}

@Composable
private fun DayChip(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaoColors.Gold,
            modifier = Modifier.glass(RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 36.dp, start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close search", tint = MaoColors.Gold) }
        TextField(
            query, onChange, placeholder = { Text("Search in this chat") }, singleLine = true,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = 0.06f), unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = MaoColors.Gold,
            ),
        )
    }
}

@Composable
private fun Composer(
    state: ChatState,
    draft: TextFieldValue,
    onDraft: (TextFieldValue) -> Unit,
    recording: Boolean,
    recordMs: Long,
    attachMenu: Boolean,
    onAttachMenu: (Boolean) -> Unit,
    onPickMedia: () -> Unit,
    onPickDoc: () -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onStopRecording: (send: Boolean) -> Unit,
    onCancelCompose: () -> Unit,
    resolve: (String) -> Message?,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
        // reply / edit preview
        val target = state.editing ?: state.replyTo
        if (target != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).glass(RoundedCornerShape(14.dp)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (state.editing != null) Icons.Rounded.Edit else Icons.AutoMirrored.Rounded.Reply, null, tint = MaoColors.Gold, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        if (state.editing != null) "Editing message"
                        else "Replying to " + (if (target.senderId == state.me) "yourself" else state.members[target.senderId]?.profile?.displayName ?: ""),
                        color = MaoColors.Gold, style = MaterialTheme.typography.labelSmall,
                    )
                    Text(previewOf(target), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onCancelCompose) { Icon(Icons.Rounded.Close, "Cancel", tint = MaoColors.TextSecondary) }
            }
        }
        // @mention suggestions
        val token = Regex("@([a-z0-9_]*)$").find(draft.text.substring(0, draft.selection.end.coerceIn(0, draft.text.length)))
        if (state.isGroup && token != null) {
            val prefix = token.groupValues[1]
            val matches = state.members.values.filter { it.userId != state.me && it.profile?.username?.startsWith(prefix) == true }.take(5)
            if (matches.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).glass(RoundedCornerShape(14.dp), strong = true)) {
                    matches.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val before = draft.text.substring(0, token.range.first)
                                val after = draft.text.substring(draft.selection.end)
                                val inserted = "@${m.profile!!.username} "
                                onDraft(TextFieldValue(before + inserted + after, TextRange(before.length + inserted.length)))
                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(m.profile?.avatarUrl, m.profile?.displayName ?: "?", size = 28.dp)
                            Text(m.profile?.displayName.orEmpty(), modifier = Modifier.padding(start = 10.dp))
                            Text("  @${m.profile?.username}", color = MaoColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            if (recording) {
                Row(
                    Modifier.weight(1f).heightIn(min = 52.dp).glass(RoundedCornerShape(26.dp)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { onStopRecording(false) }) { Icon(Icons.Rounded.Delete, "Discard recording", tint = MaoColors.Danger) }
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MaoColors.Danger))
                    Text("  Recording  ${formatDuration(recordMs)}", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.width(8.dp))
                RoundAction(Icons.AutoMirrored.Rounded.Send, "Send voice message") { onStopRecording(true) }
            } else {
                Row(
                    Modifier.weight(1f).heightIn(min = 52.dp).glass(RoundedCornerShape(26.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        IconButton(onClick = { onAttachMenu(true) }, enabled = state.editing == null) {
                            Icon(Icons.Rounded.AttachFile, "Attach", tint = MaoColors.Gold)
                        }
                        DropdownMenu(attachMenu, onDismissRequest = { onAttachMenu(false) }, containerColor = MaoColors.Charcoal700) {
                            DropdownMenuItem(text = { Text("Photo or video") }, leadingIcon = { Icon(Icons.Rounded.Image, null, tint = MaoColors.Gold) },
                                onClick = { onAttachMenu(false); onPickMedia() })
                            DropdownMenuItem(text = { Text("Document") }, leadingIcon = { Icon(Icons.Rounded.Description, null, tint = MaoColors.Gold) },
                                onClick = { onAttachMenu(false); onPickDoc() })
                        }
                    }
                    TextField(
                        value = draft, onValueChange = { if (it.text.length <= 4000) onDraft(it) },
                        placeholder = { Text(if (state.isGroup) "Message · @ to mention" else "Message") },
                        modifier = Modifier.weight(1f), maxLines = 6,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = MaoColors.Gold,
                        ),
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (draft.text.isNotBlank() || state.editing != null) {
                    RoundAction(if (state.editing != null) Icons.Rounded.CheckCircle else Icons.AutoMirrored.Rounded.Send,
                        if (state.editing != null) "Save edit" else "Send", onSend)
                } else {
                    RoundAction(Icons.Rounded.Mic, "Record voice message", onMic)
                }
            }
        }
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(MaoColors.GoldSoft, MaoColors.Gold, MaoColors.GoldDeep)))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = MaoColors.OnGold) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardSheet(load: suspend () -> List<ConversationSummary>, currentId: String, onDismiss: () -> Unit, onSend: (List<String>) -> Unit) {
    var list by remember { mutableStateOf<List<ConversationSummary>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { list = runCatching { load() }.getOrElse { emptyList() } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaoColors.Charcoal800) {
        Column(Modifier.fillMaxHeight(0.8f)) {
            Text("Forward to…", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            val l = list
            if (l == null) CenteredLoading()
            else LazyColumn(Modifier.weight(1f)) {
                items(l.filter { it.id != currentId }, key = { it.id }) { c ->
                    val on = c.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = if (on) selected - c.id else selected + c.id }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(c.avatar, c.title, isGroup = c.isGroup)
                        Text(c.title, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (on) MaoColors.Gold else MaoColors.TextMuted)
                    }
                }
            }
            GoldButton("Forward to ${selected.size}", enabled = selected.isNotEmpty(), onClick = { onSend(selected.toList()) },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageInfoSheet(m: Message, others: List<Member>, onDismiss: () -> Unit) {
    val readBy = others.filter { (parseTs(it.lastReadAt)?.isBefore(m.createdInstant) == false) }
    val deliveredTo = others.filter { it !in readBy && parseTs(it.lastDeliveredAt)?.isBefore(m.createdInstant) == false }
    val waiting = others - readBy.toSet() - deliveredTo.toSet()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaoColors.Charcoal800) {
        LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            item { Text("Message info", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
            listOf("Read by" to readBy, "Delivered to" to deliveredTo, "Not delivered yet" to waiting).forEach { (title, people) ->
                if (people.isNotEmpty()) {
                    item { Text("$title (${people.size})", color = MaoColors.Gold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                    items(people, key = { title + it.userId }) { p ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p.profile?.avatarUrl, p.profile?.displayName ?: "?", size = 36.dp)
                            Text(p.profile?.displayName ?: "Unknown", modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
    }
}
