package com.badger.trucks.ui.login

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.badger.trucks.data.AuthManager
import com.badger.trucks.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    val focus    = LocalFocusManager.current

    var email      by remember { mutableStateOf(AuthManager.getSavedEmail(context)) }
    var password   by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(AuthManager.getRememberMe(context)) }
    var showPass   by remember { mutableStateOf(false) }
    var loading    by remember { mutableStateOf(false) }
    var errorMsg   by remember { mutableStateOf<String?>(null) }

    val biometricAvailable = remember {
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    fun doSignIn() {
        if (email.isBlank() || password.isBlank()) { errorMsg = "Please enter your email and password"; return }
        loading = true; errorMsg = null
        scope.launch {
            AuthManager.signIn(email.trim(), password)
                .onSuccess { if (rememberMe) AuthManager.saveEmail(context, email.trim(), true) else AuthManager.saveEmail(context, "", false) }
                .onFailure { e ->
                    errorMsg = when {
                        e.message?.contains("Invalid login", true) == true -> "Incorrect email or password"
                        e.message?.contains("network", true) == true       -> "Network error — check connection"
                        else -> e.message ?: "Sign in failed"
                    }
                }
            loading = false
        }
    }

    fun doBiometric() {
        val activity = context as? FragmentActivity ?: return
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val saved = AuthManager.getSavedEmail(context)
                    if (saved.isBlank()) { errorMsg = "Sign in with password first, then enable Remember Me"; return }
                    scope.launch {
                        loading = true
                        AuthManager.init()
                        if (!AuthManager.isLoggedIn) errorMsg = "Session expired — sign in with password"
                        loading = false
                    }
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON)
                        errorMsg = "Biometric error: $msg"
                }
                override fun onAuthenticationFailed() { errorMsg = "Fingerprint not recognized" }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Badger Access")
                .setSubtitle("Sign in with fingerprint")
                .setNegativeButtonText("Use Password")
                .build()
        )
    }

    Box(Modifier.fillMaxSize().background(DarkBg), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(0.88f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(40.dp))
            Text("🦡", fontSize = 56.sp, textAlign = TextAlign.Center)
            Text("Badger Access", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Amber500)
            Text("Sign in to continue", fontSize = 13.sp, color = MutedText)
            Spacer(Modifier.height(4.dp))

            AnimatedVisibility(visible = errorMsg != null) {
                Surface(color = Color(0xFF3A1A1A), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(errorMsg ?: "", modifier = Modifier.padding(12.dp), color = Red500, fontSize = 13.sp)
                }
            }

            OutlinedTextField(
                value = email, onValueChange = { email = it; errorMsg = null },
                label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                colors = fieldColors()
            )

            OutlinedTextField(
                value = password, onValueChange = { password = it; errorMsg = null },
                label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); doSignIn() }),
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = MutedText)
                    }
                },
                colors = fieldColors()
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it },
                    colors = CheckboxDefaults.colors(checkedColor = Amber500, uncheckedColor = MutedText, checkmarkColor = Color.Black))
                Text("Remember me", color = MutedText, fontSize = 13.sp)
            }

            Button(
                onClick = { focus.clearFocus(); doSignIn() }, enabled = !loading,
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black, disabledContainerColor = Amber500.copy(alpha = 0.4f))
            ) {
                if (loading) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Sign In", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }

            if (biometricAvailable) {
                OutlinedButton(
                    onClick = { doBiometric() }, enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber500),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Amber500.copy(alpha = 0.5f))
                ) {
                    Text("👆  Sign in with Fingerprint", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Amber500, unfocusedBorderColor = DarkBorder,
    cursorColor = Amber500, focusedTextColor = LightText,
    unfocusedTextColor = LightText, focusedLabelColor = Amber500, unfocusedLabelColor = MutedText
)
