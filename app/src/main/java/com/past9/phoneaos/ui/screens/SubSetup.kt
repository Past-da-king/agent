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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.R
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
                   verifying: Boolean = false, onDone: () -> Unit = {}, modelLabel: String = "", onModel: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    var showToken by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    // Install by itself the moment the screen opens: there is nothing for the user to decide.
    LaunchedEffect(info.available, installed) { if (info.available && !installed && !installing) onInstall() }
    SubScreen("${kind.label} ${stringResource(kind.plan)}", null, onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!info.available) {
                AppCard(container = cs.tertiaryContainer) {
                    Text(stringResource(R.string.sub_not_included, kind.label), color = cs.onTertiaryContainer)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onUseKey, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.sub_use_api_key)) }
                }
                return@Column
            }
            when {
                // 1. Setting up
                !installed -> AppCard {
                    Text(stringResource(R.string.sub_setting_up), style = Eyebrow, color = cs.primary)
                    Text(stringResource(R.string.sub_getting_ready, kind.label), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    if (installing) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    else { Text(stringResource(R.string.sub_setup_stopped), color = cs.error); Spacer(Modifier.height(8.dp)); Button(onClick = onInstall, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.sub_try_again)) } }
                }
                // 4. Done
                info.signedIn -> AppCard(container = LocalExtra.current.successContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = LocalExtra.current.success)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.sub_signed_in, kind.label), style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.sub_runs_on_plan, kind.label), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Surface(onClick = onModel, shape = MaterialTheme.shapes.large, color = cs.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.sub_model), style = Eyebrow, color = cs.primary)
                                Text(modelLabel.ifBlank { stringResource(R.string.sub_default) }, style = MaterialTheme.typography.titleMedium)
                            }
                            Text(stringResource(R.string.sub_change), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp), shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.sub_start_chatting)) }
                }
                // 3a. Checking the code
                verifying -> AppCard {
                    Text(stringResource(R.string.sub_checking), style = Eyebrow, color = cs.primary)
                    Text(stringResource(R.string.sub_signing_in), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp)); LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
                // 3. Paste the code (Claude)
                needsCode -> AppCard {
                    var code by remember { mutableStateOf("") }
                    Text(stringResource(R.string.sub_almost_there), style = Eyebrow, color = cs.primary)
                    Text(stringResource(R.string.sub_paste_code_title), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.sub_paste_code_body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(code, { code = it.trim() }, label = { Text(stringResource(R.string.sub_code_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onCode(code) }, enabled = code.length > 10, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.sub_finish_sign_in)) }
                }
                // 2b. Waiting on the browser (ChatGPT/Codex comes back by itself)
                signingIn -> AppCard {
                    Text(stringResource(R.string.sub_in_browser), style = Eyebrow, color = cs.primary)
                    Text(stringResource(R.string.sub_authorize_title), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.sub_no_code), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp)); LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
                // 2. Sign in
                else -> AppCard {
                    Text(stringResource(R.string.sub_one_step), style = Eyebrow, color = cs.primary)
                    Text(stringResource(R.string.sub_sign_in_title, kind.label), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.sub_sign_in_body),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.sub_sign_in_button, kind.label)) }
                }
            }
            if (signInError != null && !info.signedIn) AppCard(container = cs.errorContainer) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = cs.onErrorContainer)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.sub_error, signInError), color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (installed && !info.signedIn && !signingIn && !needsCode && !verifying) {
                TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) stringResource(R.string.sub_hide) else stringResource(R.string.sub_have_token)) }
                AnimatedVisibility(showToken) {
                    var token by remember { mutableStateOf("") }
                    AppCard {
                        Text(signInHelp, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(token, { token = it.trim() }, label = { Text(if (kind == SubKind.CLAUDE) stringResource(R.string.sub_token_label) else stringResource(R.string.sub_auth_json)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { onSaveToken(token) }, enabled = token.length > 20, modifier = Modifier.fillMaxWidth().height(52.dp), shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.sub_use_token)) }
                    }
                }
            }
            if (log.isNotEmpty()) {
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) stringResource(R.string.sub_hide_details) else stringResource(R.string.sub_details)) }
                AnimatedVisibility(showLog) {
                    Text(log.takeLast(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
