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
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.AgentSettings
import com.past9.phoneaos.data.PowerMode
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
    /** Claude / ChatGPT open their sign-in; OpenCode goes to the key screen. */
    val onChooseSub: (com.past9.phoneaos.data.SubKind) -> Unit = {},
    val voice: VoiceActions = VoiceActions(),
    val onDeleteRuntime: () -> Unit = {},
    val onClearChat: () -> Unit = {},
    val onResetAll: () -> Unit = {},
    val onBattery: () -> Unit = {},
)

data class RuntimeInfo(val available: Boolean, val installedBytes: Long, val signedIn: Boolean, val note: String)

@Composable
fun SettingsScreen(s: AgentSettings, runtime: RuntimeInfo, version: String, actions: SettingsActions) {
    var edit by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var editValue by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var pickSub by remember { mutableStateOf(false) }
    var voiceSheet by remember { mutableStateOf(false) }
    if (voiceSheet) VoiceSheet(s, actions.voice) { voiceSheet = false }
    SubScreen("Settings", null, actions.onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("You", "How your agent knows you", Modifier.padding(start = 4.dp, top = 4.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Person, "Your name", s.userName.ifBlank { "Not set" }) { editValue = s.userName; edit = "Your name" to actions.setUserName }
                Line(Icons.Rounded.Face, "Your agent's name", s.agentName) { editValue = s.agentName; edit = "Your agent's name" to actions.setAgentName }
                Line(Icons.Rounded.AddToHomeScreen, "Put ${s.agentName} on your home screen", "An icon with ${s.agentName}'s name and face") { actions.onPinHome() }
                Line(Icons.Rounded.Palette, "Colour and sidekick", "${com.past9.phoneaos.ui.theme.accentOf(s.accent).label} · ${com.past9.phoneaos.ui.mascotOf(s.mascot).label}") { actions.onStyle() }
            }
            SectionHeader("Power", "What your agent thinks with", Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Key, "API key", if (s.mode == PowerMode.API_KEY) "${s.provider.label}${if (s.hasKey) " · key saved" else " · no key yet"}" else "Not in use",
                    trailing = { RadioButton(s.mode == PowerMode.API_KEY, { actions.setMode(PowerMode.API_KEY) }) }) { actions.setMode(PowerMode.API_KEY) }
                Line(Icons.Rounded.AutoAwesome, "Subscription", if (s.mode == PowerMode.SUBSCRIPTION) "${s.subKind.label} ${s.subKind.plan} · ${runtime.note}" else "Claude, ChatGPT or OpenCode plan",
                    trailing = { RadioButton(s.mode == PowerMode.SUBSCRIPTION, { pickSub = true }) }) { pickSub = true }
            }
            if (s.mode == PowerMode.API_KEY) AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Hub, "Provider", s.provider.label) { actions.onChangeKey() }
                Line(Icons.Rounded.Psychology, "Model", s.model + " · tap to switch") { actions.onModel() }
                Line(Icons.Rounded.Groups, "Helper model", s.helperModel + " (used by helper agents)") { editValue = s.helperModel; edit = "Helper model" to actions.setHelperModel }
                Line(Icons.Rounded.Password, "Change key", "Paste a new key or switch provider") { actions.onChangeKey() }
            }
            if (s.mode == PowerMode.SUBSCRIPTION || runtime.installedBytes > 0) AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Terminal, "${s.subKind.label} runtime", runtime.note) { actions.onClaudeSetup() }
                if (s.mode == PowerMode.SUBSCRIPTION) Line(Icons.Rounded.Psychology, "Model", s.subModel.ifBlank { "Default" }.replaceFirstChar { it.uppercase() } + " · tap to switch") { actions.onModel() }
                if (runtime.installedBytes > 0) Line(Icons.Rounded.DeleteSweep, "Delete runtime data", "Frees ${runtime.installedBytes / 1_000_000} MB. You can set it up again later.") { actions.onDeleteRuntime() }
            }
            SectionHeader("Voice", "Talking instead of typing", Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.RecordVoiceOver, "Voice", listOf(
                    mapOf("system" to "Phone voice", "gemini" to "Google voice", "openai" to "OpenAI voice", "elevenlabs" to "ElevenLabs voice")[s.ttsProvider] ?: "Phone voice",
                    if (s.liveEnabled) "live calls on" else null).filterNotNull().joinToString(" · ")) { voiceSheet = true }
            }
            SectionHeader("Background", "So routines and browsing keep going", Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.BatteryChargingFull, "Battery: unrestricted", "Samsung and others stop background apps. Allow this one to keep working.") { actions.onBattery() }
            }
            SectionHeader("Your data", "It all lives on this phone", Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.ChatBubbleOutline, "Clear the conversation", "Memory, tasks and routines stay") { actions.onClearChat() }
                Line(Icons.Rounded.RestartAlt, "Reset everything", "Deletes keys, memory, tasks and routines", danger = true) { confirmReset = true }
            }
            Text("Version $version · No account, no servers of ours. Your AI provider sees your messages; nothing else leaves the phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp))
        }
    }
    // Slide-up sheets, never pop-up dialogs.
    if (pickSub) ModalBottomSheet(onDismissRequest = { pickSub = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Which subscription?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            com.past9.phoneaos.data.SubKind.entries.forEach { k ->
                Surface(onClick = { pickSub = false; actions.onChooseSub(k) }, shape = MaterialTheme.shapes.large,
                    color = if (s.mode == PowerMode.SUBSCRIPTION && s.subKind == k) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${k.label} ${k.plan}", style = MaterialTheme.typography.titleMedium)
                        Text(if (k == com.past9.phoneaos.data.SubKind.OPENCODE) "Paste your OpenCode key" else "Sign in with your ${k.label} account", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Button(onClick = { save(editValue); edit = null }, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) { Text("Save") }
            }
        }
    }
    if (confirmReset) ModalBottomSheet(onDismissRequest = { confirmReset = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Reset everything?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("This deletes your keys, memory, tasks, routines and the conversation from this phone. It can't be undone.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { confirmReset = false; actions.onResetAll() }, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text("Reset everything") }
            TextButton(onClick = { confirmReset = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Keep my stuff") }
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
