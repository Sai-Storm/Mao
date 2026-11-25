package com.mao.app.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mao.app.data.AuthRepository
import com.mao.app.data.friendly
import com.mao.app.ui.components.GlassCard
import com.mao.app.ui.components.GlassTextField
import com.mao.app.ui.components.GoldButton
import com.mao.app.ui.components.MaoLogo
import com.mao.app.ui.containerViewModel
import com.mao.app.ui.theme.MaoColors
import kotlinx.coroutines.launch

class AuthViewModel(private val auth: AuthRepository) : ViewModel() {
    var signUp by mutableStateOf(false)
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var username by mutableStateOf("")
    var displayName by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<String?>(null)
        private set

    val usernameValid get() = Regex("^[a-z0-9_]{3,24}$").matches(username.trim().lowercase())

    fun submit() {
        error = null; info = null
        if (!email.contains('@')) { error = "Enter a valid e-mail address"; return }
        if (password.length < 8) { error = "Password must be at least 8 characters"; return }
        if (signUp) {
            if (!usernameValid) { error = "Username: 3–24 lowercase letters, numbers or _"; return }
            if (displayName.isBlank()) { error = "Enter your name"; return }
        }
        busy = true
        viewModelScope.launch {
            try {
                if (signUp) {
                    val started = auth.signUp(email, password, username, displayName)
                    if (!started) info = "Check your e-mail to confirm your account, then sign in."
                } else {
                    auth.signIn(email, password)
                }
            } catch (e: Exception) {
                error = e.friendly()
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun AuthScreen() {
    val vm = containerViewModel { AuthViewModel(it.auth) }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(24.dp))
        MaoLogo(size = 150.dp)
        Text("Mao", style = MaterialTheme.typography.headlineLarge, color = MaoColors.Gold, modifier = Modifier.padding(top = 12.dp))
        Text(
            if (vm.signUp) "Create your account" else "Welcome back",
            color = MaoColors.TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        GlassCard(Modifier.fillMaxWidth()) {
            GlassTextField(vm.email, { vm.email = it }, "E-mail", leadingIcon = Icons.Rounded.Mail,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            Spacer(Modifier.height(10.dp))
            GlassTextField(vm.password, { vm.password = it }, "Password", leadingIcon = Icons.Rounded.Lock,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation())
            AnimatedVisibility(vm.signUp) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    GlassTextField(vm.displayName, { vm.displayName = it.take(60) }, "Your name", leadingIcon = Icons.Rounded.Badge)
                    Spacer(Modifier.height(10.dp))
                    GlassTextField(
                        vm.username, { vm.username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(24) },
                        "Username", leadingIcon = Icons.Rounded.AlternateEmail,
                        isError = vm.username.isNotEmpty() && !vm.usernameValid,
                        supportingText = "People find and @mention you by this",
                    )
                }
            }
            vm.error?.let { Text(it, color = MaoColors.Danger, modifier = Modifier.padding(top = 12.dp)) }
            vm.info?.let { Text(it, color = MaoColors.Success, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(16.dp))
            GoldButton(if (vm.signUp) "Create account" else "Sign in", vm::submit, Modifier.fillMaxWidth(), loading = vm.busy)
        }
        TextButton(onClick = { vm.signUp = !vm.signUp }, modifier = Modifier.padding(top = 12.dp)) {
            Text(
                if (vm.signUp) "Already have an account? Sign in" else "New to Mao? Create an account",
                color = MaoColors.Gold, textAlign = TextAlign.Center,
            )
        }
    }
}
