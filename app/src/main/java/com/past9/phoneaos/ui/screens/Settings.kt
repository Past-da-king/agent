package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.AgentSettings
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.R
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.SectionHeader
import com.past9.phoneaos.ui.SubScreen

data class SettingsActions(
    val onBack: () -> Unit = {},
    val setUserName: (String) -> Unit = {},
    val setAgentName: (String) -> Unit = {},
    val setMode: (PowerMode) -> Unit = {},
    val setProvider: (Provider) -> Unit = {},
    val setModel: (String) -> Unit = {},
    val setHelperModel: (String) -> Unit = {},
    val onChangeKey: () -> Unit = {},
    val setSpeak: (Boolean) -> Unit = {},
    val onClaudeSetup: () -> Unit = {},
    val onStyle: () -> Unit = {},
    val onPinHome: () -> Unit = {},
    val onModel: () -> Unit = {},
    /** Opens the slide-up picker for the model helpers run on. */
    val onHelperModel: () -> Unit = {},
    /** Claude / ChatGPT open their sign-in; OpenCode goes to the key screen. */
    val onChooseSub: (com.past9.phoneaos.data.SubKind) -> Unit = {},
    val voice: VoiceActions = VoiceActions(),
    val onDeleteRuntime: () -> Unit = {},
    val onClearChat: () -> Unit = {},
    val onResetAll: () -> Unit = {},
    val onBattery: () -> Unit = {},
    /** The app's language tag, "" when it follows the phone. */
    val language: String = "",
    val setLanguage: (String) -> Unit = {},
)

data class RuntimeInfo(val available: Boolean, val installedBytes: Long, val signedIn: Boolean, val note: String)

@Composable
fun SettingsScreen(s: AgentSettings, runtime: RuntimeInfo, version: String, actions: SettingsActions) {
    var edit by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var editValue by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var pickSub by remember { mutableStateOf(false) }
    var voiceSheet by remember { mutableStateOf(false) }
    var pickLanguage by remember { mutableStateOf(false) }
    if (voiceSheet) VoiceSheet(s, actions.voice) { voiceSheet = false }
    SubScreen(stringResource(R.string.settings_title), null, actions.onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(R.string.settings_you), stringResource(R.string.settings_you_sub), Modifier.padding(start = 4.dp, top = 4.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                val yourName = stringResource(R.string.settings_your_name)
                val agentNameLabel = stringResource(R.string.settings_agent_name)
                Line(Icons.Rounded.Person, yourName, s.userName.ifBlank { stringResource(R.string.settings_not_set) }) { editValue = s.userName; edit = yourName to actions.setUserName }
                Line(Icons.Rounded.Face, agentNameLabel, s.agentName) { editValue = s.agentName; edit = agentNameLabel to actions.setAgentName }
                Line(Icons.Rounded.AddToHomeScreen, stringResource(R.string.settings_pin_home, s.agentName), stringResource(R.string.settings_pin_home_sub, s.agentName)) { actions.onPinHome() }
                Line(Icons.Rounded.Palette, stringResource(R.string.settings_style), "${com.past9.phoneaos.ui.theme.accentOf(s.accent).label} · ${com.past9.phoneaos.ui.mascotOf(s.mascot).label}") { actions.onStyle() }
                Line(Icons.Rounded.Language, stringResource(R.string.settings_language),
                    com.past9.phoneaos.system.AppLocale.choices.firstOrNull { it.first == actions.language }?.second ?: stringResource(R.string.settings_language_phone)) { pickLanguage = true }
            }
            SectionHeader(stringResource(R.string.settings_power), stringResource(R.string.settings_power_sub), Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Key, stringResource(R.string.settings_api_key), if (s.mode == PowerMode.API_KEY) "${s.provider.label} · ${stringResource(if (s.hasKey) R.string.settings_key_saved else R.string.settings_no_key)}" else stringResource(R.string.settings_not_in_use),
                    trailing = { RadioButton(s.mode == PowerMode.API_KEY, { actions.setMode(PowerMode.API_KEY) }) }) { actions.setMode(PowerMode.API_KEY) }
                Line(Icons.Rounded.AutoAwesome, stringResource(R.string.settings_subscription), if (s.mode == PowerMode.SUBSCRIPTION) "${s.subKind.label} ${stringResource(s.subKind.plan)} · ${runtime.note}" else stringResource(R.string.settings_subscription_sub),
                    trailing = { RadioButton(s.mode == PowerMode.SUBSCRIPTION, { pickSub = true }) }) { pickSub = true }
            }
            if (s.mode == PowerMode.API_KEY) AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Hub, stringResource(R.string.settings_provider), s.provider.label) { actions.onChangeKey() }
                Line(Icons.Rounded.Psychology, stringResource(R.string.settings_model), stringResource(R.string.settings_tap_to_switch, s.model)) { actions.onModel() }
                Line(Icons.Rounded.Groups, stringResource(R.string.settings_helpers), helpersLine(s.helperRoster, s.helperModel.substringAfterLast('/').ifBlank { stringResource(R.string.settings_same_as_agent) })) { actions.onHelperModel() }
                Line(Icons.Rounded.Password, stringResource(R.string.settings_change_key), stringResource(R.string.settings_change_key_sub)) { actions.onChangeKey() }
            }
            if (s.mode == PowerMode.SUBSCRIPTION || runtime.installedBytes > 0) AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Terminal, stringResource(R.string.settings_runtime, s.subKind.label), runtime.note) { actions.onClaudeSetup() }
                if (s.mode == PowerMode.SUBSCRIPTION) Line(Icons.Rounded.Psychology, stringResource(R.string.settings_model), stringResource(R.string.settings_tap_to_switch, s.subModel.ifBlank { stringResource(R.string.settings_default) }.replaceFirstChar { it.uppercase() })) { actions.onModel() }
                if (s.mode == PowerMode.SUBSCRIPTION) Line(Icons.Rounded.Groups, stringResource(R.string.settings_helpers), helpersLine(s.helperRoster, s.subHelperModel.ifBlank { stringResource(R.string.settings_same_as_agent) }.replaceFirstChar { it.uppercase() })) { actions.onHelperModel() }
                if (runtime.installedBytes > 0) Line(Icons.Rounded.DeleteSweep, stringResource(R.string.settings_delete_runtime), stringResource(R.string.settings_delete_runtime_sub, runtime.installedBytes / 1_000_000)) { actions.onDeleteRuntime() }
            }
            SectionHeader(stringResource(R.string.settings_voice), stringResource(R.string.settings_voice_sub), Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.RecordVoiceOver, stringResource(R.string.settings_voice), listOf(
                    stringResource(mapOf("system" to R.string.settings_voice_phone, "gemini" to R.string.settings_voice_google, "openai" to R.string.settings_voice_openai, "elevenlabs" to R.string.settings_voice_elevenlabs)[s.ttsProvider] ?: R.string.settings_voice_phone),
                    if (s.liveEnabled) stringResource(R.string.settings_live_calls_on) else null).filterNotNull().joinToString(" · ")) { voiceSheet = true }
            }
            SectionHeader(stringResource(R.string.settings_background), stringResource(R.string.settings_background_sub), Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.BatteryChargingFull, stringResource(R.string.settings_battery), stringResource(R.string.settings_battery_sub)) { actions.onBattery() }
            }
            SectionHeader(stringResource(R.string.settings_data), stringResource(R.string.settings_data_sub), Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.ChatBubbleOutline, stringResource(R.string.settings_clear_chat), stringResource(R.string.settings_clear_chat_sub)) { actions.onClearChat() }
                Line(Icons.Rounded.RestartAlt, stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_sub), danger = true) { confirmReset = true }
            }
            Text(stringResource(R.string.settings_footer, version),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp))
        }
    }
    // Slide-up sheets, never pop-up dialogs.
    if (pickSub) ModalBottomSheet(onDismissRequest = { pickSub = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.settings_which_sub), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            com.past9.phoneaos.data.SubKind.entries.forEach { k ->
                Surface(onClick = { pickSub = false; actions.onChooseSub(k) }, shape = MaterialTheme.shapes.large,
                    color = if (s.mode == PowerMode.SUBSCRIPTION && s.subKind == k) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${k.label} ${stringResource(k.plan)}", style = MaterialTheme.typography.titleMedium)
                        Text(if (k == com.past9.phoneaos.data.SubKind.OPENCODE) stringResource(R.string.settings_paste_opencode) else stringResource(R.string.settings_sign_in_with, k.label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    edit?.let { (label, save) ->
        ModalBottomSheet(onDismissRequest = { edit = null }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(label, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(editValue, { editValue = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { save(editValue); edit = null }, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.settings_save)) }
            }
        }
    }
    if (pickLanguage) ModalBottomSheet(onDismissRequest = { pickLanguage = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            com.past9.phoneaos.system.AppLocale.choices.forEach { (tag, label) ->
                Surface(onClick = { pickLanguage = false; if (tag != actions.language) actions.setLanguage(tag) }, shape = MaterialTheme.shapes.large,
                    color = if (tag == actions.language) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(label ?: stringResource(R.string.settings_language_phone), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
    if (confirmReset) ModalBottomSheet(onDismissRequest = { confirmReset = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.settings_reset_confirm), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_reset_confirm_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { confirmReset = false; actions.onResetAll() }, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text(stringResource(R.string.settings_reset)) }
            TextButton(onClick = { confirmReset = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.settings_keep)) }
        }
    }
}

@Composable
private fun Line(icon: ImageVector, title: String, value: String, danger: Boolean = false, trailing: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.invoke()
        }
    }
}

/** "3 helpers: Sonnet, Haiku, DeepSeek" or the single default. */
@Composable
private fun helpersLine(roster: List<com.past9.phoneaos.data.HelperModel>, single: String): String =
    if (roster.isEmpty()) stringResource(R.string.settings_helpers_single, single)
    else pluralStringResource(R.plurals.settings_helpers_count, roster.size, roster.size, roster.joinToString(", ") { it.name.substringAfterLast('/') })
