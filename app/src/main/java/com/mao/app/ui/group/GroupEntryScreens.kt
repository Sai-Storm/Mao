package com.mao.app.ui.group

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mao.app.data.Conversation
import com.mao.app.data.InvitePreview
import com.mao.app.data.Profile
import com.mao.app.data.friendly
import com.mao.app.ui.appContainer
import com.mao.app.ui.components.Avatar
import com.mao.app.ui.components.CenteredLoading
import com.mao.app.ui.components.EmptyState
import com.mao.app.ui.components.GhostButton
import com.mao.app.ui.components.GlassCard
import com.mao.app.ui.components.GlassTextField
import com.mao.app.ui.components.GoldButton
import com.mao.app.ui.components.RoleBadge
import com.mao.app.ui.components.SectionLabel
import com.mao.app.ui.components.TopBar
import com.mao.app.ui.people.UserPicker
import com.mao.app.ui.theme.MaoColors
import com.mao.app.ui.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GoldSwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, color = MaoColors.TextSecondary, style = MaterialTheme.typography.bodyMedium) }
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = MaoColors.OnGold, checkedTrackColor = MaoColors.Gold),
        )
    }
}

@Composable
fun CreateGroupScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }
    var approval by remember { mutableStateOf(true) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var selected by remember { mutableStateOf<Map<String, Profile>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { photo = it ?: photo }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (step == 0) "New group" else "Add participants", onBack = { if (step == 1) step = 0 else onBack() },
            subtitle = if (step == 1) "${selected.size} selected" else null)
        if (step == 0) {
            Column(Modifier.weight(1f).padding(16.dp)) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(photo?.toString(), name.ifBlank { "Group" }, size = 72.dp, isGroup = true,
                            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Choose group photo") {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                        Spacer(Modifier.width(14.dp))
                        Text("Tap the circle to add a group photo", color = MaoColors.TextSecondary)
                    }
                    Spacer(Modifier.height(14.dp))
                    GlassTextField(name, { name = it.take(80) }, "Group name")
                    Spacer(Modifier.height(10.dp))
                    GlassTextField(description, { description = it.take(500) }, "Description (optional)", singleLine = false, minLines = 2)
                }
                SectionLabel("Privacy")
                GlassCard(Modifier.fillMaxWidth()) {
                    GoldSwitchRow("Public group", "Anyone can find it in Public groups", isPublic) { isPublic = it }
                    GoldSwitchRow("Approve new members", "Admins approve join requests for public groups", approval, enabled = isPublic) { approval = it }
                }
            }
            GoldButton("Next: add people", onClick = { step = 1 }, enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
        } else {
            UserPicker(Modifier.weight(1f), selected = selected.keys, multi = true) { p ->
                selected = if (p.id in selected) selected - p.id else selected + (p.id to p)
            }
            GoldButton("Create group", loading = busy, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), onClick = {
                busy = true
                scope.launch {
                    try {
                        val id = container.conversations.createGroup(name, description, isPublic, approval || !isPublic, selected.keys.toList())
                        photo?.let { uri ->
                            runCatching {
                                val url = container.media.uploadAvatar(uri, groupId = id)
                                container.conversations.updateGroupInfo(id, avatarUrl = url)
                            }.onFailure { context.toast("Group created, but the photo failed: ${it.friendly()}") }
                        }
                        onCreated(id)
                    } catch (e: Exception) {
                        context.toast(e.friendly())
                    } finally { busy = false }
                }
            })
        }
    }
}

@Composable
fun DiscoverGroupsScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<Conversation>?>(null) }
    var mine by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pending by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        runCatching { mine = container.conversations.myConversations().map { it.id }.toSet() }
        runCatching { pending = container.conversations.myPendingRequests() }
    }
    LaunchedEffect(query) {
        delay(300)
        groups = try { container.conversations.publicGroups(query) } catch (e: Exception) { context.toast(e.friendly()); emptyList() }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Public groups", onBack = onBack)
        GlassTextField(query, { query = it }, "Search groups", leadingIcon = Icons.Rounded.Search, modifier = Modifier.padding(horizontal = 16.dp))
        val list = groups
        when {
            list == null -> CenteredLoading()
            list.isEmpty() -> EmptyState("No public groups found", "Try another name, or create a public group yourself.")
            else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { g ->
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(g.avatarUrl, g.name ?: "Group", size = 52.dp, isGroup = true)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(g.name ?: "Group", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                g.description?.let { Text(it, color = MaoColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                if (g.joinRequiresApproval) RoleBadge("Approval required")
                            }
                            when {
                                g.id in mine -> GhostButton("Open", { onOpenChat(g.id) })
                                g.id in pending -> GhostButton("Requested", {
                                    scope.launch {
                                        runCatching { container.conversations.cancelJoinRequest(g.id) }
                                            .onSuccess { pending = pending - g.id; context.toast("Request cancelled") }
                                    }
                                }, color = MaoColors.TextSecondary)
                                else -> GhostButton(if (g.joinRequiresApproval) "Request" else "Join", {
                                    scope.launch {
                                        try {
                                            when (container.conversations.joinPublicGroup(g.id)) {
                                                "joined", "already_member" -> onOpenChat(g.id)
                                                else -> { pending = pending + g.id; context.toast("Request sent to the admins") }
                                            }
                                        } catch (e: Exception) { context.toast(e.friendly()) }
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JoinGroupScreen(code: String, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<InvitePreview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(code) {
        try {
            preview = container.conversations.previewInvite(code)
            if (preview == null) error = "This invite link is invalid, expired or has been revoked."
        } catch (e: Exception) { error = e.friendly() }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Group invite", onBack = onBack)
        val p = preview
        when {
            loading -> CenteredLoading()
            p == null -> EmptyState("Can't join", error ?: "")
            else -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(p.avatarUrl, p.name ?: "Group", size = 112.dp, isGroup = true)
                Text(p.name ?: "Group", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                Text("${p.memberCount} members", color = MaoColors.TextSecondary)
                p.description?.let { Text(it, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
                Spacer(Modifier.height(28.dp))
                when {
                    p.isMember -> GoldButton("Open group", { onOpenChat(p.conversationId) }, Modifier.fillMaxWidth())
                    p.hasPendingRequest || result == "requested" || result == "already_requested" ->
                        Text("Your request is waiting for an admin to approve it.", color = MaoColors.Gold, textAlign = TextAlign.Center)
                    else -> GoldButton(if (p.requiresApproval) "Request to join" else "Join group", loading = busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val r = container.conversations.joinWithInvite(code)
                                result = r
                                if (r == "joined" || r == "already_member") onOpenChat(p.conversationId)
                            } catch (e: Exception) { error = e.friendly() }
                            finally { busy = false }
                        }
                    })
                }
                error?.let { Text(it, color = MaoColors.Danger, modifier = Modifier.padding(top = 12.dp)) }
            }
        }
    }
}
