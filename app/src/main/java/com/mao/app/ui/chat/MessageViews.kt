package com.mao.app.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forward
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.mao.app.data.Member
import com.mao.app.data.Message
import com.mao.app.data.Reaction
import com.mao.app.media.PlaybackState
import com.mao.app.ui.components.Avatar
import com.mao.app.ui.formatDuration
import com.mao.app.ui.formatSize
import com.mao.app.ui.formatTime
import com.mao.app.ui.theme.MaoColors
import com.mao.app.ui.toast

/** Everything a bubble needs from the screen, bundled to keep signatures short. */
class BubbleContext(
    val me: String,
    val myUsername: String?,
    val isGroup: Boolean,
    val members: Map<String, Member>,
    val playback: PlaybackState,
    val signedUrl: suspend (String) -> String,
    val resolveReply: (String) -> Message?,
    val onToggleVoice: (key: String, url: String) -> Unit,
    val onOpenImage: (String) -> Unit,
    val onOpenVideo: (String) -> Unit,
    val onJump: (String) -> Unit,
    val onReact: (String, String) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageRow(
    item: ChatItem,
    showSender: Boolean,
    reactions: List<Reaction>,
    highlighted: Boolean,
    ctx: BubbleContext,
    onLongPress: () -> Unit,
    onFailedTap: () -> Unit,
) {
    val m = item.message
    if (m.isSystem) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                m.body.orEmpty(), textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall, color = MaoColors.TextSecondary,
            )
        }
        return
    }
    val mine = m.senderId == ctx.me
    val sender = ctx.members[m.senderId]?.profile
    val mentionsMe = ctx.me in m.mentions && !mine

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (ctx.isGroup && !mine) {
            if (showSender) Avatar(sender?.avatarUrl, sender?.displayName ?: "?", size = 30.dp) else Spacer(Modifier.width(30.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 300.dp)) {
            val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 6.dp, bottomEnd = if (mine) 6.dp else 18.dp)
            Column(
                Modifier
                    .clip(shape)
                    .background(if (mine) MaoColors.MineBubble else MaoColors.TheirBubble)
                    .border(1.dp, when {
                        highlighted -> MaoColors.Gold
                        mentionsMe -> MaoColors.Gold.copy(alpha = 0.6f)
                        mine -> MaoColors.Gold.copy(alpha = 0.25f)
                        else -> Color.White.copy(alpha = 0.06f)
                    }, shape)
                    .combinedClickable(
                        onClick = { if (item.sendState == SendState.Failed) onFailedTap() },
                        onLongClick = onLongPress,
                        onLongClickLabel = "Message actions",
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (ctx.isGroup && !mine && showSender) {
                    Text(sender?.displayName ?: "Former member", color = MaoColors.Gold, style = MaterialTheme.typography.labelLarge)
                }
                if (m.forwarded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Forward, null, tint = MaoColors.TextSecondary, modifier = Modifier.size(14.dp))
                        Text(" Forwarded", color = MaoColors.TextSecondary, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
                    }
                }
                m.replyToId?.let { rid -> ReplyQuote(ctx.resolveReply(rid), ctx) { ctx.onJump(rid) } }

                if (m.isDeleted) {
                    Text("This message was deleted", fontStyle = FontStyle.Italic, color = MaoColors.TextSecondary)
                } else {
                    when (m.kind) {
                        "image" -> ImageContent(m, item.localBytes, ctx)
                        "video" -> FileCard(m, "Video", Icons.Rounded.PlayArrow) { m.mediaPath?.let(ctx.onOpenVideo) }
                        "document" -> DocumentContent(m, ctx)
                        "voice" -> VoiceContent(m, ctx)
                    }
                    if (!m.body.isNullOrEmpty()) {
                        Text(mentionText(m.body, ctx.myUsername), style = MaterialTheme.typography.bodyLarge, color = MaoColors.TextPrimary)
                    }
                }
                Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (m.editedAt != null && !m.isDeleted) Text("edited · ", style = MaterialTheme.typography.labelSmall, color = MaoColors.TextMuted)
                    Text(formatTime(m.createdInstant), style = MaterialTheme.typography.labelSmall, color = MaoColors.TextMuted)
                    if (mine) {
                        Spacer(Modifier.width(4.dp))
                        StatusIcon(item)
                    }
                }
            }
            if (reactions.isNotEmpty() && !m.isDeleted) {
                FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    reactions.groupBy { it.emoji }.forEach { (emoji, list) ->
                        val iReacted = list.any { it.userId == ctx.me }
                        Text(
                            "$emoji ${list.size}",
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (iReacted) MaoColors.Gold.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
                                .border(1.dp, if (iReacted) MaoColors.Gold else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button, onClickLabel = if (iReacted) "Remove reaction" else "Add reaction") { ctx.onReact(m.id, emoji) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (item.sendState == SendState.Failed) {
                Text("Not sent · tap to retry", color = MaoColors.Danger, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StatusIcon(item: ChatItem) {
    val (icon, tint, label) = when {
        item.sendState == SendState.Sending -> Triple(Icons.Rounded.Schedule, MaoColors.TextMuted, "Sending")
        item.sendState == SendState.Failed -> Triple(Icons.Rounded.ErrorOutline, MaoColors.Danger, "Failed")
        item.receipt == Receipt.Read -> Triple(Icons.Rounded.DoneAll, MaoColors.ReadBlue, "Read")
        item.receipt == Receipt.Delivered -> Triple(Icons.Rounded.DoneAll, MaoColors.TextMuted, "Delivered")
        else -> Triple(Icons.Rounded.Done, MaoColors.TextMuted, "Sent")
    }
    Icon(icon, label, tint = tint, modifier = Modifier.size(15.dp))
}

fun mentionText(body: String, myUsername: String?): AnnotatedString = buildAnnotatedString {
    var last = 0
    Regex("@([a-z0-9_]{3,24})").findAll(body).forEach { match ->
        append(body.substring(last, match.range.first))
        withStyle(SpanStyle(color = MaoColors.Gold, fontWeight = if (match.groupValues[1] == myUsername) FontWeight.Bold else FontWeight.SemiBold,
            background = if (match.groupValues[1] == myUsername) MaoColors.Gold.copy(alpha = 0.15f) else Color.Transparent)) {
            append(match.value)
        }
        last = match.range.last + 1
    }
    append(body.substring(last))
}

fun previewOf(m: Message): String = when {
    m.isDeleted -> "Deleted message"
    m.kind == "image" -> "📷 Photo"
    m.kind == "video" -> "🎬 Video"
    m.kind == "voice" -> "🎤 Voice message"
    m.kind == "document" -> "📄 ${m.mediaName ?: "Document"}"
    else -> m.body.orEmpty()
}

@Composable
private fun ReplyQuote(target: Message?, ctx: BubbleContext, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(role = Role.Button, onClickLabel = "Go to replied message", onClick = onClick)
            .padding(end = 10.dp),
    ) {
        Box(Modifier.width(3.dp).height(40.dp).background(MaoColors.Gold))
        Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            val who = target?.senderId?.let { if (it == ctx.me) "You" else ctx.members[it]?.profile?.displayName } ?: ""
            Text(who, color = MaoColors.Gold, style = MaterialTheme.typography.labelSmall)
            Text(target?.let(::previewOf) ?: "…", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaoColors.TextSecondary)
        }
    }
}

@Composable
private fun rememberSignedUrl(path: String?, ctx: BubbleContext): String? {
    val url by produceState<String?>(null, path) { value = path?.let { runCatching { ctx.signedUrl(it) }.getOrNull() } }
    return url
}

@Composable
private fun ImageContent(m: Message, localBytes: ByteArray?, ctx: BubbleContext) {
    val url = rememberSignedUrl(m.mediaPath, ctx)
    val model: Any? = localBytes ?: url
    Box(
        Modifier
            .padding(bottom = 4.dp)
            .size(width = 240.dp, height = 220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(enabled = m.mediaPath != null, role = Role.Image, onClickLabel = "Open photo") { m.mediaPath?.let(ctx.onOpenImage) },
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) AsyncImage(model, "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(color = MaoColors.Gold, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun FileCard(m: Message, fallback: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(bottom = 4.dp)
            .widthIn(min = 200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(enabled = m.mediaPath != null, role = Role.Button, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaoColors.Gold), contentAlignment = Alignment.Center) {
            Icon(icon, "Open", tint = MaoColors.OnGold)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(m.mediaName ?: fallback, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(listOf(fallback, formatSize(m.mediaSize)).filter { it.isNotBlank() }.joinToString(" · "), color = MaoColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DocumentContent(m: Message, ctx: BubbleContext) {
    val context = LocalContext.current
    val url = rememberSignedUrl(m.mediaPath, ctx)
    FileCard(m, "Document", Icons.AutoMirrored.Rounded.InsertDriveFile) {
        if (url == null) context.toast("Still preparing the file…")
        else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { context.toast("No app can open this file") }
    }
}

@Composable
private fun VoiceContent(m: Message, ctx: BubbleContext) {
    val url = rememberSignedUrl(m.mediaPath, ctx)
    val active = ctx.playback.key == m.id
    val playing = active && ctx.playback.playing
    val total = (if (active && ctx.playback.durationMs > 0) ctx.playback.durationMs else m.mediaDurationMs ?: 0).toLong()
    Row(Modifier.padding(bottom = 4.dp).width(220.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaoColors.Gold)
                .clickable(enabled = url != null, role = Role.Button, onClickLabel = if (playing) "Pause" else "Play voice message") {
                    url?.let { ctx.onToggleVoice(m.id, it) }
                },
            contentAlignment = Alignment.Center,
        ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = MaoColors.OnGold) }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            LinearProgressIndicator(
                progress = { if (active && total > 0) ctx.playback.positionMs.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = MaoColors.Gold, trackColor = Color.White.copy(alpha = 0.15f),
            )
            Text(formatDuration(if (active) ctx.playback.positionMs.toLong() else total), style = MaterialTheme.typography.labelSmall,
                color = MaoColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun ImageViewerDialog(path: String, signedUrl: suspend (String) -> String, onDismiss: () -> Unit) {
    val url by produceState<String?>(null, path) { value = runCatching { signedUrl(path) }.getOrNull() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClickLabel = "Close", onClick = onDismiss), contentAlignment = Alignment.Center) {
            if (url != null) AsyncImage(url, "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else CircularProgressIndicator(color = MaoColors.Gold)
        }
    }
}

@Composable
fun VideoPlayerDialog(path: String, signedUrl: suspend (String) -> String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val url by produceState<String?>(null, path) { value = runCatching { signedUrl(path) }.getOrNull() }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    DisposableEffect(url) {
        url?.let { player.setMediaItem(MediaItem.fromUri(it)); player.prepare(); player.playWhenReady = true }
        onDispose { }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (url == null) CircularProgressIndicator(color = MaoColors.Gold)
            else AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxWidth().heightIn(min = 200.dp))
        }
    }
}
