package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.SubScreen
import com.past9.phoneaos.ui.theme.LocalExtra

/** Set up Claude Code inside the app and sign in with the user's Claude subscription. */
@Composable
fun SubSetupScreen(kind: com.past9.phoneaos.data.SubKind, signInHelp: String, info: RuntimeInfo, installed: Boolean, log: List<String>, installing: Boolean, onBack: () -> Unit, onInstall: () -> Unit, onSaveToken: (String) -> Unit, onUseKey: () -> Unit,
                   onSignIn: () -> Unit = {}, signingIn: Boolean = false, signInError: String? = null) {
    var token by remember { mutableStateOf("") }
    SubScreen("${kind.label} ${kind.plan}", info.note, onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!info.available) {
                AppCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("This build doesn't include the ${kind.label} runtime pack yet. Use an API key for now; nothing you set up is lost when you switch.", color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onUseKey, shapes = ButtonDefaults.shapes()) { Text("Use an API key") }
                }
                return@Column
            }
            SectionHeader("Step 1", "Install ${kind.label}'s agent inside the app", Modifier.padding(start = 4.dp))
            AppCard {
                if (installed) Text("Installed.", color = LocalExtra.current.success, style = MaterialTheme.typography.titleSmall)
                else Button(onClick = onInstall, enabled = !installing, modifier = Modifier.fillMaxWidth().height(52.dp), shapes = ButtonDefaults.shapes()) { Text(if (installing) "Installing…" else "Install") }
                if (installing) { Spacer(Modifier.height(12.dp)); LinearWavyProgressIndicator(Modifier.fillMaxWidth()) }
                if (log.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(log.takeLast(6).joinToString("\n"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionHeader("Step 2", "Sign in with your ${kind.label} account", Modifier.padding(start = 4.dp, top = 8.dp))
            AppCard {
                if (info.signedIn) Text("Signed in.", color = LocalExtra.current.success, style = MaterialTheme.typography.titleSmall)
                else {
                    Button(onClick = onSignIn, enabled = installed && !signingIn, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) {
                        Text(if (signingIn) "Finish signing in, in your browser…" else "Sign in with ${kind.label}")
                    }
                    if (signInError != null) { Spacer(Modifier.height(8.dp)); Text(signInError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(16.dp))
                    Text(signInHelp, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(token, { token = it.trim() }, label = { Text("${kind.label} sign-in") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onSaveToken(token) }, enabled = token.length > 20, modifier = Modifier.fillMaxWidth().height(52.dp), shapes = ButtonDefaults.shapes()) { Text("Save") }
                }
            }
        }
    }
}
