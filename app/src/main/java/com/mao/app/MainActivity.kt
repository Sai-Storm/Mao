package com.mao.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mao.app.ui.MaoNavHost
import com.mao.app.ui.auth.AuthScreen
import com.mao.app.ui.components.MaoBackground
import com.mao.app.ui.components.MaoLogo
import com.mao.app.ui.extractInviteCode
import com.mao.app.ui.theme.MaoColors
import com.mao.app.ui.theme.MaoTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Invite code from a mao://join/<code> link, consumed by the nav host. */
    private val pendingInvite = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MaoApp).container
        handleIntent(intent)

        setContent {
            MaoTheme {
                MaoBackground {
                    if (!container.isConfigured) {
                        MissingConfig()
                        return@MaoBackground
                    }
                    val status by container.auth.sessionStatus.collectAsStateWithLifecycle()
                    when (status) {
                        is SessionStatus.Authenticated -> {
                            val invite by pendingInvite
                            LaunchedEffect(Unit) {
                                runCatching { container.conversations.markAllDelivered() }
                            }
                            MaoNavHost(pendingInvite = invite, onInviteConsumed = { pendingInvite.value = null })
                        }
                        is SessionStatus.Initializing -> Splash()
                        else -> AuthScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val container = (application as MaoApp).container
        if (!container.isConfigured) return
        when (data.host) {
            "join" -> pendingInvite.value = extractInviteCode(data.toString())
            "auth" -> lifecycleScope.launch { runCatching { container.supabase.handleDeeplinks(intent) } }
        }
    }
}

@Composable
private fun Splash() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        MaoLogo(size = 160.dp)
        CircularProgressIndicator(Modifier.padding(top = 32.dp), color = MaoColors.Gold)
    }
}

@Composable
private fun MissingConfig() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MaoLogo(size = 140.dp)
        Text("Supabase is not configured", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
        Text(
            "Add supabase.url and supabase.anonKey to local.properties, then rebuild the app.",
            color = MaoColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
