package com.mao.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mao.app.AppContainer
import com.mao.app.MaoApp
import com.mao.app.data.parseTs
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as MaoApp).container

/** viewModel() with a constructor lambda; [key] keeps one instance per conversation. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key) { create(container) }
}

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
private val longDayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())

fun formatTime(ts: String?): String = parseTs(ts)?.let { timeFmt.format(it.atZone(ZoneId.systemDefault())) } ?: ""

fun formatTime(i: Instant): String = timeFmt.format(i.atZone(ZoneId.systemDefault()))

/** "9:41 PM" today, "Mon" this week, otherwise a date. */
fun formatListTime(ts: String?): String {
    val i = parseTs(ts) ?: return ""
    val z = i.atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when {
        z.toLocalDate() == today -> timeFmt.format(z)
        z.toLocalDate().isAfter(today.minusDays(7)) -> dayFmt.format(z)
        else -> dateFmt.format(z)
    }
}

fun dayHeader(i: Instant): String {
    val d = i.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (d.year == today.year) longDayFmt.format(d) else dateFmt.format(d)
    }
}

fun lastSeen(ts: String?): String {
    val i = parseTs(ts) ?: return ""
    val ago = Duration.between(i, Instant.now())
    return when {
        ago.toMinutes() < 2 -> "online recently"
        ago.toMinutes() < 60 -> "last seen ${ago.toMinutes()} min ago"
        ago.toHours() < 24 -> "last seen ${ago.toHours()} h ago"
        else -> "last seen ${formatListTime(ts)}"
    }
}

fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return ""
    val kb = bytes / 1024.0
    return when {
        kb < 1024 -> String.format(Locale.US, "%.0f KB", kb)
        else -> String.format(Locale.US, "%.1f MB", kb / 1024)
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

fun Context.copyToClipboard(label: String, text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

fun Context.shareText(text: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}

/** Accepts "mao://join/CODE", any URL ending in /join/CODE, or a bare code. */
fun extractInviteCode(input: String): String? {
    val t = input.trim()
    if (t.isEmpty()) return null
    val fromUrl = Regex("join/([A-Za-z0-9_-]{6,32})").find(t)?.groupValues?.get(1)
    return fromUrl ?: t.takeIf { Regex("^[A-Za-z0-9_-]{6,32}$").matches(it) }
}
