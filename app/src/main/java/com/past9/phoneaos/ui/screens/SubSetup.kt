package com.past9.phoneaos.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.SubKind
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.SubScreen
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.LocalExtra

/**
 * One path, one thing to do at a time: it installs by itself, then a single "Sign in" button,
 * then (Claude only) a single box for the code Claude shows, then a clear "signed in" or a clear
 * error. The token route is tucked away under "Have a token instead?".
 */
@Composable
fun SubSetupScreen(kind: SubKind, signInHelp: String, info: RuntimeInfo, installed: Boolean, log: List<String>, installing: Boolean, onBack: () -> Unit, onInstall: () -> Unit, onSaveToken: (String) -> Unit, onUseKey: () -> Unit,
                   onSignIn: () -> Unit = {}, signingIn: Boolean = false, signInError: String? = null, needsCode: Boolean = false, onCode: (String) -> Unit = {},
                   verifying: Boolean = false, onDone: () -> Unit = {}, modelLabel: String = "", onModel: () -> Unit = {}, onSignOut: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    var showToken by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    // Install by itself the moment the screen opens: there is nothing for the user to decide.
    LaunchedEffect(info.available, installed) { if (info.available && !installed && !installing) onInstall() }
    SubScreen("${kind.label} ${kind.plan}", null, onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!info.available) {
                AppCard(container = cs.tertiaryContainer) {
                    Text("This build doesn't include ${kind.label} yet. Use an API key for now; nothing is lost when you switch.", color = cs.onTertiaryContainer)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onUseKey, shapes = ButtonDefaults.shapes()) { Text("Use an API key") }
                }
                return@Column
            }
            when {
                // 1. Setting up
                !installed -> AppCard {
                    Text("SETTING UP", style = Eyebrow, color = cs.primary)
                    Text("Getting ${kind.label} ready on your phone", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    if (installing) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    else { Text("Setup stopped.", color = cs.error); Spacer(Modifier.height(8.dp)); Button(onClick = onInstall, shapes = ButtonDefaults.shapes()) { Text("Try again") } }
                }
                // 4. Done
                info.signedIn -> AppCard(container = LocalExtra.current.successContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = LocalExtra.current.success)
                        Spacer(Modifier.width(10.dp))
                        Text("You're signed in to ${kind.label}", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Your agent now runs on your ${kind.label} plan.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Surface(onClick = onModel, shape = MaterialTheme.shapes.large, color = cs.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("MODEL", style = Eyebrow, color = cs.primary)
                                Text(modelLabel.ifBlank { "Default" }, style = MaterialTheme.typography.titleMedium)
                            }
                            Text("Change", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp), shapes = ButtonDefaults.shapes()) { Text("Start chatting") }
                    TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out to use another account") }
                }
                // 3a. Checking the code
                verifying -> AppCard {
                    Text("CHECKING", style = Eyebrow, color = cs.primary)
                    Text("Signing you in…", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp)); LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
                // 3. Paste the code (Claude)
                needsCode -> AppCard {
                    var code by remember { mutableStateOf("") }
                    Text("ALMOST THERE", style = Eyebrow, color = cs.primary)
                    Text("Paste the code from Claude", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    Text("In the browser: tap Authorize. Claude then shows a code. Copy it, come back here, and paste it below.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(code, { code = it.trim() }, label = { Text("Code from Claude") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onCode(code) }, enabled = code.length > 10, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text("Finish sign-in") }
                }
                // 2b. Waiting on the browser (ChatGPT/Codex comes back by itself)
                signingIn -> AppCard {
                    Text("IN YOUR BROWSER", style = Eyebrow, color = cs.primary)
                    Text("Tap Authorize, then come back", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("No code to copy. This screen finishes by itself once you've approved.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp)); LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
                // 2. Sign in
                else -> AppCard {
                    Text("ONE STEP", style = Eyebrow, color = cs.primary)
                    Text("Sign in with your ${kind.label} account", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Your browser opens. Tap Authorize there, then come back here. That's it.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text("Sign in with ${kind.label}") }
                }
            }
            if (signInError != null && !info.signedIn) AppCard(container = cs.errorContainer) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = cs.onErrorContainer)
                    Spacer(Modifier.width(10.dp))
                    Text("That didn't work. $signInError", color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (installed && !info.signedIn && !signingIn && !needsCode && !verifying) {
                TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Have a token instead?") }
                AnimatedVisibility(showToken) {
                    var token by remember { mutableStateOf("") }
                    AppCard {
                        Text(signInHelp, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(token, { token = it.trim() }, label = { Text(if (kind == SubKind.CLAUDE) "Token (starts with sk-ant-)" else "auth.json contents") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { onSaveToken(token) }, enabled = token.length > 20, modifier = Modifier.fillMaxWidth().height(52.dp), shapes = ButtonDefaults.shapes()) { Text("Use this token") }
                    }
                }
            }
            if (log.isNotEmpty()) {
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide details" else "Details") }
                AnimatedVisibility(showLog) {
                    Text(log.takeLast(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
