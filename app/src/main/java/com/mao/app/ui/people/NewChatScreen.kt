package com.mao.app.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mao.app.data.Profile
import com.mao.app.data.friendly
import com.mao.app.ui.appContainer
import com.mao.app.ui.components.Avatar
import com.mao.app.ui.components.GlassCard
import com.mao.app.ui.components.GlassTextField
import com.mao.app.ui.components.TopBar
import com.mao.app.ui.theme.MaoColors
import com.mao.app.ui.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounced user search. In single mode [onPick] fires on tap; in multi mode
 * rows toggle membership of [selected].
 */
@Composable
fun UserPicker(
    modifier: Modifier = Modifier,
    selected: Set<String> = emptySet(),
    multi: Boolean = false,
    exclude: Set<String> = emptySet(),
    onPick: (Profile) -> Unit,
) {
    val container = appContainer()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        delay(300)
        error = null
        results = try { container.profiles.search(query).filterNot { it.id in exclude } } catch (e: Exception) { error = e.friendly(); emptyList() }
    }

    Column(modifier) {
        GlassTextField(query, { query = it }, "Search by name or username", leadingIcon = Icons.Rounded.Search,
            modifier = Modifier.padding(horizontal = 16.dp))
        error?.let { Text(it, color = MaoColors.Danger, modifier = Modifier.padding(16.dp)) }
        if (query.trim().length < 2) {
            Text("Type at least 2 characters", color = MaoColors.TextMuted, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = { it.id }) { p ->
                GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), onClick = { onPick(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.avatarUrl, p.displayName)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(p.displayName, style = MaterialTheme.typography.titleMedium)
                            Text("@${p.username}", color = MaoColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (multi) {
                            val on = p.id in selected
                            Icon(
                                if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                if (on) "Selected" else "Not selected",
                                tint = if (on) MaoColors.Gold else MaoColors.TextMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NewChatScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("New chat", onBack = onBack)
        UserPicker(Modifier.weight(1f)) { p ->
            if (busy) return@UserPicker
            busy = true
            scope.launch {
                try { onOpenChat(container.conversations.getOrCreateDirect(p.id)) }
                catch (e: Exception) { context.toast(e.friendly()) }
                finally { busy = false }
            }
        }
    }
}
