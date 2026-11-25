package com.mao.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mao.app.data.Profile
import com.mao.app.data.friendly
import com.mao.app.ui.appContainer
import com.mao.app.ui.components.Avatar
import com.mao.app.ui.components.CenteredLoading
import com.mao.app.ui.components.GhostButton
import com.mao.app.ui.components.GlassCard
import com.mao.app.ui.components.GlassTextField
import com.mao.app.ui.components.GoldButton
import com.mao.app.ui.components.TopBar
import com.mao.app.ui.theme.MaoColors
import com.mao.app.ui.toast
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var profile by remember { mutableStateOf<Profile?>(null) }
    var name by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            profile = container.profiles.me()
            name = profile?.displayName.orEmpty()
            bio = profile?.bio.orEmpty()
        } catch (e: Exception) { context.toast(e.friendly()) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            try {
                val url = container.media.uploadAvatar(uri)
                container.profiles.update(avatarUrl = url)
                profile = profile?.copy(avatarUrl = url)
            } catch (e: Exception) { context.toast(e.friendly()) }
            finally { uploading = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Your profile", onBack = onBack)
        val p = profile
        if (p == null) { CenteredLoading(); return@Column }
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Avatar(p.avatarUrl, p.displayName, size = 112.dp,
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Change photo") {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                if (uploading) CircularProgressIndicator(color = MaoColors.Gold)
            }
            Text("Tap to change photo", color = MaoColors.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            Text("@${p.username}", color = MaoColors.Gold, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(20.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                GlassTextField(name, { name = it.take(60) }, "Display name")
                Spacer(Modifier.height(10.dp))
                GlassTextField(bio, { bio = it.take(200) }, "About", singleLine = false, minLines = 2)
                Spacer(Modifier.height(16.dp))
                GoldButton("Save", enabled = name.isNotBlank(), loading = saving, modifier = Modifier.fillMaxWidth(), onClick = {
                    saving = true
                    scope.launch {
                        try { container.profiles.update(displayName = name, bio = bio); context.toast("Saved") }
                        catch (e: Exception) { context.toast(e.friendly()) }
                        finally { saving = false }
                    }
                })
            }
            Spacer(Modifier.height(24.dp))
            GhostButton("Sign out", icon = Icons.AutoMirrored.Rounded.Logout, color = MaoColors.Danger, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch { runCatching { container.auth.signOut() } }
            })
        }
    }
}
