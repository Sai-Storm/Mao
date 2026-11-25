package com.mao.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mao.app.data.ConversationRepository
import com.mao.app.data.ConversationSummary
import com.mao.app.data.friendly
import com.mao.app.ui.components.Avatar
import com.mao.app.ui.components.EmptyState
import com.mao.app.ui.components.GlassCard
import com.mao.app.ui.components.GlassIconButton
import com.mao.app.ui.components.GlassTextField
import com.mao.app.ui.components.GoldButton
import com.mao.app.ui.components.MaoLogo
import com.mao.app.ui.components.TopBar
import com.mao.app.ui.components.glass
import com.mao.app.ui.containerViewModel
import com.mao.app.ui.extractInviteCode
import com.mao.app.ui.formatListTime
import com.mao.app.ui.theme.MaoColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class HomeState(
    val conversations: List<ConversationSummary> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class HomeViewModel(private val repo: ConversationRepository) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init {
        refresh()
        viewModelScope.launch {
            repo.listChanges()
                .retryWhen { _, attempt -> delay(minOf(30_000L, 1_000L shl attempt.toInt().coerceAtMost(5))); true }
                .debounce(400)
                .collect {
                    load()
                    runCatching { repo.markAllDelivered() }
                }
        }
    }

    fun refresh(pull: Boolean = false) {
        if (pull) _state.value = _state.value.copy(refreshing = true)
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val list = repo.myConversations()
            _state.value = HomeState(conversations = list, loading = false)
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, refreshing = false, error = e.friendly())
        }
    }
}

private enum class HomeTab(val label: String) { All("All"), Groups("Groups"), Private("Private") }

@Composable
fun HomeScreen(
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    onDiscover: () -> Unit,
    onJoinCode: (String) -> Unit,
    onProfile: () -> Unit,
) {
    val vm = containerViewModel { HomeViewModel(it.conversations) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(HomeTab.All) }
    var fabOpen by remember { mutableStateOf(false) }
    var joinDialog by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = "Mao",
                leading = { MaoLogo(Modifier.padding(start = 8.dp), size = 40.dp) },
                actions = {
                    GlassIconButton(Icons.Rounded.Explore, "Discover public groups", onDiscover)
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(Icons.Rounded.Person, "Your profile", onProfile)
                    Spacer(Modifier.width(8.dp))
                },
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeTab.entries.forEach { t ->
                    val selected = t == tab
                    Text(
                        t.label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .then(
                                if (selected) Modifier.background(Brush.horizontalGradient(listOf(MaoColors.GoldSoft, MaoColors.Gold)))
                                else Modifier.glass(RoundedCornerShape(50))
                            )
                            .clickable(role = Role.Tab) { tab = t }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                        color = if (selected) MaoColors.OnGold else MaoColors.TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            val visible = state.conversations.filter {
                when (tab) { HomeTab.All -> true; HomeTab.Groups -> it.isGroup; HomeTab.Private -> !it.isGroup }
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.refresh(pull = true) }, modifier = Modifier.weight(1f)) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize())
                    visible.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            EmptyState(
                                title = if (state.error != null) "Couldn't load chats" else "No conversations yet",
                                body = state.error ?: "Start a private chat, create a group, or join one with an invite link.",
                            )
                        }
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.id }) { c -> ConversationRow(c) { onOpenChat(c.id) } }
                    }
                }
            }
        }

        // Speed-dial FAB
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(fabOpen) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FabAction("Join with link", Icons.Rounded.Link) { fabOpen = false; joinDialog = true }
                    FabAction("Public groups", Icons.Rounded.Public) { fabOpen = false; onDiscover() }
                    FabAction("New group", Icons.Rounded.GroupAdd) { fabOpen = false; onNewGroup() }
                    FabAction("New chat", Icons.Rounded.PersonAdd) { fabOpen = false; onNewChat() }
                }
            }
            Box(
                Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MaoColors.GoldSoft, MaoColors.Gold, MaoColors.GoldDeep)))
                    .clickable(role = Role.Button, onClickLabel = "New conversation") { fabOpen = !fabOpen },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (fabOpen) Icons.Rounded.Close else Icons.Rounded.Add, "New conversation", tint = MaoColors.OnGold, modifier = Modifier.size(30.dp))
            }
        }
    }

    if (joinDialog) {
        var text by remember { mutableStateOf("") }
        val code = extractInviteCode(text)
        AlertDialog(
            onDismissRequest = { joinDialog = false },
            containerColor = MaoColors.Charcoal800,
            title = { Text("Join a group") },
            text = { GlassTextField(text, { text = it }, "Invite link or code", supportingText = "e.g. mao://join/AbC123xyz") },
            confirmButton = {
                TextButton(enabled = code != null, onClick = { joinDialog = false; code?.let(onJoinCode) }) {
                    Text("Continue", color = MaoColors.Gold)
                }
            },
            dismissButton = { TextButton(onClick = { joinDialog = false }) { Text("Cancel", color = MaoColors.TextSecondary) } },
        )
    }
}

@Composable
private fun FabAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .glass(RoundedCornerShape(50), strong = true)
            .background(MaoColors.Charcoal800.copy(alpha = 0.85f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaoColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Icon(icon, null, tint = MaoColors.Gold)
    }
}

@Composable
private fun ConversationRow(c: ConversationSummary, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.avatar, c.title, size = 52.dp, isGroup = c.isGroup)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (c.isGroup && c.isPublic) {
                        Icon(Icons.Rounded.Public, "Public group", tint = MaoColors.Gold.copy(alpha = 0.7f), modifier = Modifier.padding(start = 6.dp).size(14.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    c.lastMessagePreview ?: if (c.isGroup) "${c.memberCount} members" else "Say hello 👋",
                    color = if (c.unreadCount > 0) MaoColors.TextPrimary else MaoColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatListTime(c.lastMessageAt), style = MaterialTheme.typography.labelSmall,
                    color = if (c.unreadCount > 0) MaoColors.Gold else MaoColors.TextMuted)
                Spacer(Modifier.height(6.dp))
                if (c.unreadCount > 0) {
                    Box(
                        Modifier.widthIn(min = 22.dp).height(22.dp).clip(CircleShape).background(MaoColors.Gold).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (c.unreadCount > 99) "99+" else c.unreadCount.toString(), color = MaoColors.OnGold,
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
