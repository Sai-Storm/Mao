package com.mao.app.ui.group

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mao.app.AppContainer
import com.mao.app.data.Conversation
import com.mao.app.data.GroupInvite
import com.mao.app.data.JoinRequest
import com.mao.app.data.Member
import com.mao.app.data.MemberRole
import com.mao.app.data.friendly
import com.mao.app.data.myId
import com.mao.app.ui.components.Avatar
import com.mao.app.ui.components.CenteredLoading
import com.mao.app.ui.components.GhostButton
import com.mao.app.ui.components.GlassCard
import com.mao.app.ui.components.GlassTextField
import com.mao.app.ui.components.RoleBadge
import com.mao.app.ui.components.SectionLabel
import com.mao.app.ui.components.TopBar
import com.mao.app.ui.containerViewModel
import com.mao.app.ui.copyToClipboard
import com.mao.app.ui.formatListTime
import com.mao.app.ui.lastSeen
import com.mao.app.ui.people.UserPicker
import com.mao.app.ui.shareText
import com.mao.app.ui.theme.MaoColors
import com.mao.app.ui.toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

data class GroupInfoState(
    val loading: Boolean = true,
    val conversation: Conversation? = null,
    val members: List<Member> = emptyList(),
    val invites: List<GroupInvite> = emptyList(),
    val requests: List<JoinRequest> = emptyList(),
    val me: String = "",
    val error: String? = null,
    val busy: Boolean = false,
) {
    val myRole: MemberRole? get() = members.firstOrNull { it.userId == me }?.role
    val isAdmin get() = myRole?.isAdmin == true
    val canEditInfo get() = isAdmin || (myRole != null && conversation?.onlyAdminsCanEditInfo == false)
}

class GroupInfoViewModel(private val c: AppContainer, val conversationId: String) : ViewModel() {
    private val _state = MutableStateFlow(GroupInfoState(me = c.supabase.myId.orEmpty()))
    val state: StateFlow<GroupInfoState> = _state
    val messages = MutableStateFlow<String?>(null)

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            val conv = c.conversations.get(conversationId)
            val members = c.conversations.members(conversationId)
            val admin = members.firstOrNull { it.userId == _state.value.me }?.role?.isAdmin == true && conv?.isGroup == true
            _state.update {
                it.copy(
                    loading = false, conversation = conv, members = sortMembers(members),
                    invites = if (admin) c.conversations.invites(conversationId) else emptyList(),
                    requests = if (admin) c.conversations.joinRequests(conversationId) else emptyList(),
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.friendly()) }
        }
    }

    private fun sortMembers(list: List<Member>) =
        list.sortedWith(compareBy<Member> { it.role.ordinal }.thenBy { it.profile?.displayName?.lowercase() })

    /** Runs a privileged action, reports the error, and reloads on success. */
    fun act(success: String? = null, block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            block()
            success?.let { messages.value = it }
            load().join()
        } catch (e: Exception) {
            messages.value = e.friendly()
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun uploadPhoto(uri: android.net.Uri) = act("Group photo updated") {
        val url = c.media.uploadAvatar(uri, groupId = conversationId)
        c.conversations.updateGroupInfo(conversationId, avatarUrl = url)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(conversationId: String, onBack: () -> Unit, onOpenChat: (String) -> Unit, onLeft: () -> Unit) {
    val vm = containerViewModel(key = "info-$conversationId") { GroupInfoViewModel(it, conversationId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val toastMsg by vm.messages.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val container = com.mao.app.ui.appContainer()

    LaunchedEffect(toastMsg) { toastMsg?.let { context.toast(it); vm.messages.value = null } }

    var editField by remember { mutableStateOf<String?>(null) }      // "name" | "description"
    var memberSheet by remember { mutableStateOf<Member?>(null) }
    var addMembers by remember { mutableStateOf(false) }
    var newInvite by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::uploadPhoto) }

    Column(Modifier.fillMaxSize()) {
        val conv = state.conversation
        TopBar(if (conv?.isGroup == false) "Contact info" else "Group info", onBack = onBack,
            actions = { if (state.busy) CircularProgressIndicator(Modifier.padding(12.dp), color = MaoColors.Gold, strokeWidth = 2.dp) })
        if (state.loading) { CenteredLoading(); return@Column }
        if (conv == null) { Text(state.error ?: "Not found", Modifier.padding(24.dp), color = MaoColors.Danger); return@Column }

        if (!conv.isGroup) {
            DirectInfo(state)
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(conv.avatarUrl, conv.name ?: "Group", size = 112.dp, isGroup = true,
                        modifier = Modifier.clickable(enabled = state.canEditInfo, role = Role.Button, onClickLabel = "Change group photo") {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Text(conv.name ?: "Group", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        if (state.canEditInfo) IconButton(onClick = { editField = "name" }) { Icon(Icons.Rounded.Edit, "Edit name", tint = MaoColors.Gold) }
                    }
                    Text(
                        "${if (conv.isPublic) "Public" else "Private"} group · ${state.members.size} members",
                        color = MaoColors.TextSecondary,
                    )
                }
                SectionLabel("Description")
                GlassCard(Modifier.fillMaxWidth(), onClick = if (state.canEditInfo) ({ editField = "description" }) else null) {
                    Text(conv.description?.takeIf { it.isNotBlank() } ?: if (state.canEditInfo) "Add a description" else "No description",
                        color = if (conv.description.isNullOrBlank()) MaoColors.TextMuted else MaoColors.TextPrimary)
                }
            }

            if (state.isAdmin) {
                item {
                    SectionLabel("Group settings")
                    GlassCard(Modifier.fillMaxWidth()) {
                        GoldSwitchRow("Public group", "Listed in Public groups", conv.isPublic) { v ->
                            vm.act { container.conversations.updateGroupInfo(conversationId, isPublic = v) }
                        }
                        GoldSwitchRow("Approve new members", "Join requests need admin approval", conv.joinRequiresApproval) { v ->
                            vm.act { container.conversations.updateGroupInfo(conversationId, joinRequiresApproval = v) }
                        }
                        GoldSwitchRow("Only admins can send", "Restrict messaging to administrators", conv.onlyAdminsCanSend) { v ->
                            vm.act { container.conversations.updateGroupInfo(conversationId, onlyAdminsCanSend = v) }
                        }
                        GoldSwitchRow("Only admins edit info", "Name, description and photo", conv.onlyAdminsCanEditInfo) { v ->
                            vm.act { container.conversations.updateGroupInfo(conversationId, onlyAdminsCanEditInfo = v) }
                        }
                    }
                }

                if (state.requests.isNotEmpty()) {
                    item { SectionLabel("Join requests (${state.requests.size})") }
                    items(state.requests, key = { "req-" + it.id }) { r ->
                        GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = PaddingValues(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(r.profile?.avatarUrl, r.profile?.displayName ?: "?")
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(r.profile?.displayName ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                                    Text("@${r.profile?.username.orEmpty()} · ${formatListTime(r.createdAt)}", color = MaoColors.TextSecondary)
                                }
                                IconButton(onClick = { vm.act("Request declined") { container.conversations.decideJoinRequest(r.id, false) } }) {
                                    Icon(Icons.Rounded.Close, "Decline", tint = MaoColors.Danger)
                                }
                                IconButton(onClick = { vm.act("Member approved") { container.conversations.decideJoinRequest(r.id, true) } }) {
                                    Icon(Icons.Rounded.Check, "Approve", tint = MaoColors.Success)
                                }
                            }
                        }
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Invite links", Modifier.weight(1f))
                        TextButton(onClick = { newInvite = true }) {
                            Icon(Icons.Rounded.AddLink, null, tint = MaoColors.Gold); Spacer(Modifier.width(6.dp)); Text("New link", color = MaoColors.Gold)
                        }
                    }
                    if (state.invites.none { it.isUsable }) Text("No active links", color = MaoColors.TextMuted, modifier = Modifier.padding(start = 6.dp))
                }
                items(state.invites.filter { it.isUsable }, key = { "inv-" + it.id }) { inv ->
                    GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = PaddingValues(12.dp)) {
                        Text(inv.link, color = MaoColors.Gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                if (inv.requiresApproval) "Needs approval" else "Joins instantly",
                                inv.maxUses?.let { "${inv.useCount}/$it uses" } ?: "${inv.useCount} uses",
                                inv.expiresAt?.let { "expires ${formatListTime(it)}" },
                            ).joinToString(" · "),
                            color = MaoColors.TextSecondary, style = MaterialTheme.typography.bodyMedium,
                        )
                        Row {
                            IconButton(onClick = { context.copyToClipboard("Invite link", inv.link); context.toast("Link copied") }) {
                                Icon(Icons.Rounded.ContentCopy, "Copy link", tint = MaoColors.Gold)
                            }
                            IconButton(onClick = { context.shareText("Join \"${conv.name}\" on Mao: ${inv.link}") }) {
                                Icon(Icons.Rounded.Share, "Share link", tint = MaoColors.Gold)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.act("Link revoked") { container.conversations.revokeInvite(inv.id) } }) {
                                Text("Revoke", color = MaoColors.Danger)
                            }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("${state.members.size} members", Modifier.weight(1f))
                    if (state.isAdmin) TextButton(onClick = { addMembers = true }) {
                        Icon(Icons.Rounded.PersonAdd, null, tint = MaoColors.Gold); Spacer(Modifier.width(6.dp)); Text("Add", color = MaoColors.Gold)
                    }
                }
            }
            items(state.members, key = { it.id }) { m ->
                val isMe = m.userId == state.me
                GlassCard(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = PaddingValues(12.dp),
                    onClick = if (isMe) null else ({ memberSheet = m }),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m.profile?.avatarUrl, m.profile?.displayName ?: "?")
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(if (isMe) "You" else m.profile?.displayName ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                            Text("@${m.profile?.username.orEmpty()}", color = MaoColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (m.role != MemberRole.member) RoleBadge(m.role.label)
                            if (m.isRestricted) RoleBadge("Restricted", MaoColors.Danger)
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                GhostButton("Leave group", icon = Icons.AutoMirrored.Rounded.ExitToApp, color = MaoColors.Danger,
                    modifier = Modifier.fillMaxWidth(), onClick = { confirmLeave = true })
            }
        }
    }

    // ---- dialogs & sheets ----------------------------------------------------
    val conv = state.conversation
    editField?.let { field ->
        var text by remember(field) { mutableStateOf(if (field == "name") conv?.name.orEmpty() else conv?.description.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editField = null },
            containerColor = MaoColors.Charcoal800,
            title = { Text(if (field == "name") "Group name" else "Description") },
            text = { GlassTextField(text, { text = it.take(if (field == "name") 80 else 500) }, if (field == "name") "Name" else "Description", singleLine = field == "name") },
            confirmButton = {
                TextButton(enabled = field != "name" || text.isNotBlank(), onClick = {
                    editField = null
                    vm.act {
                        if (field == "name") container.conversations.updateGroupInfo(conversationId, name = text)
                        else container.conversations.updateGroupInfo(conversationId, description = text)
                    }
                }) { Text("Save", color = MaoColors.Gold) }
            },
            dismissButton = { TextButton(onClick = { editField = null }) { Text("Cancel", color = MaoColors.TextSecondary) } },
        )
    }

    if (newInvite) {
        var approval by remember { mutableStateOf(false) }
        var expiry by remember { mutableStateOf<Int?>(24 * 7) }
        AlertDialog(
            onDismissRequest = { newInvite = false },
            containerColor = MaoColors.Charcoal800,
            title = { Text("New invite link") },
            text = {
                Column {
                    GoldSwitchRow("Require approval", "Admins approve people who use this link", approval) { approval = it }
                    Text("Expires", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        listOf(24 to "1 day", 24 * 7 to "7 days", null to "Never").forEach { (h, label) ->
                            val on = expiry == h
                            TextButton(onClick = { expiry = h }) { Text(label, color = if (on) MaoColors.Gold else MaoColors.TextSecondary) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    newInvite = false
                    vm.act("Invite link created") { container.conversations.createInvite(conversationId, approval, expiry, null) }
                }) { Text("Create", color = MaoColors.Gold) }
            },
            dismissButton = { TextButton(onClick = { newInvite = false }) { Text("Cancel", color = MaoColors.TextSecondary) } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = MaoColors.Charcoal800,
            title = { Text("Leave group?") },
            text = {
                Text(if (state.myRole == MemberRole.owner) "Ownership passes to the longest-serving admin (or member). If you're the last member, the group is deleted."
                     else "You will stop receiving messages from this group.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    vm.act { container.conversations.leaveGroup(conversationId); onLeft() }
                }) { Text("Leave", color = MaoColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel", color = MaoColors.TextSecondary) } },
        )
    }

    if (addMembers) {
        var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
        ModalBottomSheet(onDismissRequest = { addMembers = false }, containerColor = MaoColors.Charcoal800) {
            Column(Modifier.fillMaxHeight(0.85f)) {
                Text("Add members", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                UserPicker(Modifier.weight(1f), selected = selected, multi = true, exclude = state.members.map { it.userId }.toSet()) { p ->
                    selected = if (p.id in selected) selected - p.id else selected + p.id
                }
                com.mao.app.ui.components.GoldButton(
                    "Add ${selected.size}", enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    onClick = {
                        addMembers = false
                        vm.act("Members added") { container.conversations.addMembers(conversationId, selected.toList()) }
                    },
                )
            }
        }
    }

    memberSheet?.let { m ->
        ModalBottomSheet(onDismissRequest = { memberSheet = null }, containerColor = MaoColors.Charcoal800) {
            MemberActions(
                member = m, myRole = state.myRole,
                onMessage = {
                    memberSheet = null
                    vm.act { onOpenChat(container.conversations.getOrCreateDirect(m.userId)) }
                },
                onAction = { label, block -> memberSheet = null; vm.act(label, block) },
                container = container, conversationId = conversationId,
            )
        }
    }
}

@Composable
private fun MemberActions(
    member: Member,
    myRole: MemberRole?,
    onMessage: () -> Unit,
    onAction: (String, suspend () -> Unit) -> Unit,
    container: AppContainer,
    conversationId: String,
) {
    val name = member.profile?.displayName ?: "member"
    val iAmOwner = myRole == MemberRole.owner
    val iAmAdmin = myRole?.isAdmin == true
    // mirrors the server rules in remove_group_member / set_member_role / restrict_group_member
    val canManage = iAmAdmin && member.role != MemberRole.owner && (member.role == MemberRole.member || iAmOwner)

    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(member.profile?.avatarUrl, name, size = 56.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge)
                Text("@${member.profile?.username.orEmpty()} · ${member.role.label}", color = MaoColors.TextSecondary)
            }
        }
        HorizontalDivider(color = MaoColors.GlassBorder)
        SheetItem("Message $name", onClick = onMessage)
        if (iAmAdmin && member.role == MemberRole.member) {
            SheetItem("Make admin") { onAction("$name is now an admin") { container.conversations.setRole(conversationId, member.userId, MemberRole.admin) } }
        }
        if (iAmOwner && member.role == MemberRole.admin) {
            SheetItem("Remove as admin") { onAction("Admin rights removed") { container.conversations.setRole(conversationId, member.userId, MemberRole.member) } }
        }
        if (canManage) {
            if (member.isRestricted) {
                SheetItem("Allow to send messages") { onAction("Restriction lifted") { container.conversations.restrictMember(conversationId, member.userId, null) } }
            } else {
                SheetItem("Restrict for 1 hour") { onAction("Restricted for 1 hour") { container.conversations.restrictMember(conversationId, member.userId, Instant.now().plus(Duration.ofHours(1))) } }
                SheetItem("Restrict for 24 hours") { onAction("Restricted for 24 hours") { container.conversations.restrictMember(conversationId, member.userId, Instant.now().plus(Duration.ofDays(1))) } }
                SheetItem("Restrict until lifted") { onAction("Restricted") { container.conversations.restrictMember(conversationId, member.userId, Instant.now().plus(Duration.ofDays(36500))) } }
            }
            SheetItem("Remove from group", danger = true) { onAction("$name removed") { container.conversations.removeMember(conversationId, member.userId) } }
        }
        if (iAmOwner) {
            SheetItem("Transfer ownership", danger = true) { onAction("Ownership transferred") { container.conversations.transferOwnership(conversationId, member.userId) } }
        }
    }
}

@Composable
fun SheetItem(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(text, color = if (danger) MaoColors.Danger else MaoColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DirectInfo(state: GroupInfoState) {
    val peer = state.members.firstOrNull { it.userId != state.me }?.profile
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(peer?.avatarUrl, peer?.displayName ?: "?", size = 112.dp)
        Text(peer?.displayName ?: "Unknown", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Text("@${peer?.username.orEmpty()}", color = MaoColors.Gold)
        Text(lastSeen(peer?.lastSeenAt), color = MaoColors.TextSecondary)
        peer?.bio?.takeIf { it.isNotBlank() }?.let {
            GlassCard(Modifier.fillMaxWidth().padding(top = 20.dp)) { Text(it) }
        }
    }
}
