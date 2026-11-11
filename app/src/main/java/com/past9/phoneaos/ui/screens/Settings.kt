package com.past9.phoneaos.ui.screens

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
    /** The app's language tag, "" when it follows the phone. */
    val language: String = "",
    val setLanguage: (String) -> Unit = {},
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
    /** Settings > Linked devices: use the agent on a computer or server. */
    val onLinked: () -> Unit = {},
    /** Settings > Recordings: the dictations kept on this phone. */
    val onRecordings: () -> Unit = {},
    /** Settings > Automation from other apps (GitHub issue #18): the switch that lets a macro fire a routine. */
    val setAutomationAllowed: (Boolean) -> Unit = {},
    /** A fresh token; every macro holding the old one stops working. */
    val rotateAutomationToken: () -> String = { "" },
    /** Settings > Automation, the other direction: macros the agent may run without asking, the test and the webhook. */
    val macro: MacroActions = MacroActions(),
    /** Settings > Media apps: which app plays music and which plays video. */
    val media: MediaAppsActions = MediaAppsActions(),
    /** Settings > Browser: sites that may use shared memory in the agent's browser, and the agent's voice in WhatsApp calls. */
    val browser: BrowserSettingsActions = BrowserSettingsActions(),
    val linkedLine: String = "Use the agent on your computer from this phone",
)

/** Settings > Automation from other apps: the address MacroDroid and Tasker reach, the token, and the switch. */
data class AutomationInfo(
    val url: String = "http://127.0.0.1:47321",
    val token: String = "",
    val allowed: Boolean = false,
    val running: Boolean = false,
)

/** The agent runs MacroDroid and Tasker macros: what the sheet reads and writes. */
data class MacroActions(
    val allowlist: () -> String = { "" },
    val setAllowlist: (String) -> Unit = {},
    val webhookOn: () -> Boolean = { false },
    val setWebhookOn: (Boolean) -> Unit = {},
    val webhookId: () -> String = { "" },
    val setWebhookId: (String) -> Unit = {},
    /** Allowed app intents, one "action | package" per line. */
    val intents: () -> String = { "" },
    val setIntents: (String) -> Unit = {},
    /** Opens Google Maps on a place through android_intent's own path, as a second app to try. */
    val testMaps: () -> String = { "failed: not available here" },
    /** Sends the test macro now and returns "sent" or "failed: why". */
    val test: (String) -> String = { "failed: not available here" },
)

data class RuntimeInfo(val available: Boolean, val installedBytes: Long, val signedIn: Boolean, val note: String)

@Composable
fun SettingsScreen(s: AgentSettings, runtime: RuntimeInfo, version: String, actions: SettingsActions, automation: AutomationInfo = AutomationInfo()) {
    var edit by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var editValue by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var pickSub by remember { mutableStateOf(false) }
    var pickLanguage by remember { mutableStateOf(false) }
    var voiceSheet by remember { mutableStateOf(false) }
    var automationSheet by remember { mutableStateOf(false) }
    var macroSheet by remember { mutableStateOf(false) }
    var mediaSheet by remember { mutableStateOf(false) }
    var browserSheet by remember { mutableStateOf(false) }
    // The sheet keeps what it shows, so a flip of the switch or a new token lands without re-reading Settings.
    var automationAllowedNow by remember { mutableStateOf(automation.allowed) }
    var automationTokenNow by remember { mutableStateOf(automation.token) }
    if (voiceSheet) VoiceSheet(s, actions.voice) { voiceSheet = false }
    SubScreen(stringResource(R.string.settings_title), null, actions.onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = com.past9.phoneaos.ui.sidePad(16.dp)).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Line(Icons.Rounded.RecordVoiceOver, "Voice",
                    mapOf("system" to "Phone voice", "gemini" to "Google voice", "openai" to "OpenAI voice", "elevenlabs" to "ElevenLabs voice")[s.ttsProvider] ?: "Phone voice") { voiceSheet = true }
                // The phone's digital assistant: on or off, and a sheet that walks through the two taps in the phone's Settings.
                val actx = androidx.compose.ui.platform.LocalContext.current
                var assistant by remember { mutableStateOf(false) }
                var assistTick by remember { mutableIntStateOf(0) }
                androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { assistTick++; onPauseOrDispose { } }
                val assistHeld = remember(assistant, assistTick) { com.past9.phoneaos.assist.AssistRole.held(actx) }
                Line(Icons.Rounded.Assistant, "Default assistant", com.past9.phoneaos.assist.AssistRole.line(assistHeld)) { assistant = true }
                if (assistant) AssistantSheet(s.agentName, onDismiss = { assistant = false })
            }
            SectionHeader(stringResource(R.string.settings_background), stringResource(R.string.settings_background_sub), Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var reliability by remember { mutableStateOf(false) }
                val problems = remember(reliability) { com.past9.phoneaos.system.Background.problems(com.past9.phoneaos.system.Background.check(ctx), ctx) }
                Line(Icons.Rounded.NotificationsActive, stringResource(R.string.settings_reminders_on_time), if (problems.isEmpty()) stringResource(R.string.settings_reminders_all_set) else problems.first().replaceFirstChar { it.uppercase() }) { reliability = true }
                if (reliability) ReliabilitySheet { reliability = false }
                var handoffs by remember { mutableStateOf(false) }
                Line(Icons.Rounded.Sync, stringResource(R.string.settings_handoffs), com.past9.phoneaos.handoff.HandoffStore.peek(ctx)?.let { stringResource(R.string.settings_handoffs_signed_in, it) } ?: stringResource(R.string.settings_handoffs_sub)) { handoffs = true }
                if (handoffs) com.past9.phoneaos.handoff.HandoffSheet { handoffs = false }
                var wakeups by remember { mutableStateOf(false) }
                Line(Icons.Rounded.Alarm, "Wake-ups", "Rings through Do Not Disturb until you answer: permissions and a test") { wakeups = true }
                if (wakeups) WakeupsSheet { wakeups = false }
                var wakeWord by remember { mutableStateOf(false) }
                val wakeWordOn by com.past9.phoneaos.wakeword.WakeWord.store(androidx.compose.ui.platform.LocalContext.current).state.collectAsState()
                Line(Icons.Rounded.RecordVoiceOver, "Wake word", if (wakeWordOn.enabled) "Listening for \"" + wakeWordOn.customPhrase.ifBlank { com.past9.phoneaos.wakeword.WakePhrase.defaultFor(s.agentName) } + "\"" else "Off. Say your agent's name to open it from anywhere") { wakeWord = true }
                if (wakeWord) WakeWordSheet { wakeWord = false }
                Line(Icons.Rounded.BatteryChargingFull, stringResource(R.string.settings_battery), stringResource(R.string.settings_battery_sub)) { actions.onBattery() }
            }
            SectionHeader("Automation", "Other apps, both ways", Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Bolt, "Automation from other apps",
                    when {
                        !automation.running -> "Not listening yet: open the app once"
                        automationAllowedNow -> "On \u00b7 ${automation.url}"
                        else -> "Off \u00b7 tap to set it up"
                    }) { automationSheet = true }
                Line(Icons.Rounded.SmartToy, "Agent talks to your apps",
                    MacroRulesUi.allowedLine(actions.macro.allowlist(), actions.macro.intents())) { macroSheet = true }
                // Read again each time the sheet closes, so the line says what the sheet just changed.
                val mediaLine = remember(mediaSheet) { MediaAppsUi.line(actions.media.rows(), actions.media.music(), actions.media.video()) }
                Line(Icons.Rounded.LibraryMusic, "Media apps", mediaLine) { mediaSheet = true }
            }
            SectionHeader("Background screen", "Other apps, while you keep your screen", Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                val screenCtx = androidx.compose.ui.platform.LocalContext.current
                var screenOn by remember { mutableStateOf(com.past9.phoneaos.screen.Screens.on(screenCtx)) }
                // Off by default: it is new and still being proven on real phones. Switched off, nothing of it exists in the app.
                val screenState = if (screenOn) com.past9.phoneaos.screen.Screens.session(screenCtx).state.value else null
                Line(Icons.Rounded.Layers, "Background screen", if (screenState?.stopped == true) "Stopped by you. Tap to allow your agent to use it again" else if (screenOn) "On. Setup, rules and blocked apps" else "Off. Experimental: your agent uses other apps on a hidden screen while you keep yours",
                    trailing = { Switch(screenOn, { on -> screenOn = on; com.past9.phoneaos.screen.Screens.setOn(screenCtx, on) }) }) {
                    if (screenState?.stopped == true) { com.past9.phoneaos.screen.Screens.session(screenCtx).userAllow(); com.past9.phoneaos.ui.AppNotice.say("Your agent can use the background screen again") }
                    else if (screenOn) com.past9.phoneaos.screen.ScreenUi.show.value = com.past9.phoneaos.screen.ScreenUi.SETUP
                }
                if (screenOn) Line(Icons.Rounded.Movie, "Recordings and learned apps", "Watch what it did there, and teach it a job yourself") { com.past9.phoneaos.screen.ScreenUi.show.value = com.past9.phoneaos.screen.ScreenUi.RUNS }
            }
            SectionHeader("Browser", "The browser your agent uses", Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                // Read again each time the sheet closes, so the line says what the sheet just changed.
                val sites = remember(browserSheet) { actions.browser.origins() }
                Line(Icons.Rounded.Public, "Sites that need advanced browser features", com.past9.phoneaos.browser.IsolatedOrigins.line(sites)) { browserSheet = true }
            }
            SectionHeader("Linked devices", "Your computers and servers", Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Devices, "Linked devices", actions.linkedLine) { actions.onLinked() }
            }
            SectionHeader("Your data", "It all lives on this phone", Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Mic, "Recordings", "What you dictated: the words and the audio") { actions.onRecordings() }
                Line(Icons.Rounded.ChatBubbleOutline, stringResource(R.string.settings_clear_chat), stringResource(R.string.settings_clear_chat_sub)) { actions.onClearChat() }
                Line(Icons.Rounded.RestartAlt, stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_sub), danger = true) { confirmReset = true }
            }
            Text(stringResource(R.string.settings_footer, version),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp))
        }
    }
    // Slide-up sheets, never pop-up dialogs.
    if (pickSub) com.past9.phoneaos.ui.ChoiceSheet(androidx.compose.material.icons.Icons.Rounded.Bolt, "What powers it", "Which subscription?",
        com.past9.phoneaos.data.SubKind.entries.map { k -> com.past9.phoneaos.ui.SheetChoice(k.name, "${k.label} ${stringResource(k.plan)}", if (k == com.past9.phoneaos.data.SubKind.OPENCODE) "Paste your OpenCode key" else "Sign in with your ${k.label} account",
            selected = s.mode == PowerMode.SUBSCRIPTION && s.subKind == k) },
        onPick = { c -> pickSub = false; actions.onChooseSub(com.past9.phoneaos.data.SubKind.valueOf(c.id)) }, onDismiss = { pickSub = false })
    // The language picker slides up from the bottom (never a centred dialog) and is built from the app's own sheet.
    if (pickLanguage) com.past9.phoneaos.ui.ChoiceSheet(Icons.Rounded.Language, stringResource(R.string.settings_you), stringResource(R.string.settings_language),
        com.past9.phoneaos.system.AppLocale.choices.map { (tag, label) ->
            com.past9.phoneaos.ui.SheetChoice(tag, label ?: stringResource(R.string.settings_language_phone), selected = tag == actions.language) },
        onPick = { c -> pickLanguage = false; if (c.id != actions.language) actions.setLanguage(c.id) }, onDismiss = { pickLanguage = false })
    edit?.let { (label, save) ->
        com.past9.phoneaos.ui.AppSheet(androidx.compose.material.icons.Icons.Rounded.Edit, "Settings", label, { edit = null }, primary = "Save", primaryEnabled = true, onPrimary = { save(editValue); edit = null }) {
            com.past9.phoneaos.ui.SheetField(editValue, { editValue = it }, label, "", singleLine = true)
        }
    }
    if (automationSheet) AutomationSheet(
        info = automation,
        token = automationTokenNow,
        allowed = automationAllowedNow,
        onAllowed = { on -> automationAllowedNow = on; actions.setAutomationAllowed(on) },
        onToken = { t -> automationTokenNow = t },
        onRotate = { automationTokenNow = actions.rotateAutomationToken() },
        onDismiss = { automationSheet = false },
    )
    if (macroSheet) MacroSheet(actions.macro) { macroSheet = false }
    if (mediaSheet) MediaAppsSheet(actions.media) { mediaSheet = false }
    if (browserSheet) BrowserSheet(actions.browser) { browserSheet = false }
    if (confirmReset) com.past9.phoneaos.ui.ConfirmSheet(androidx.compose.material.icons.Icons.Rounded.DeleteForever, "This phone", "Reset everything?",
        "This deletes your keys, memory, tasks, routines and the conversation from this phone. It can't be undone.",
        confirm = "Reset everything", onConfirm = { confirmReset = false; actions.onResetAll() }, onDismiss = { confirmReset = false }, cancel = "Keep my stuff", destructive = true)
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

/**
 * Settings > Automation from other apps (GitHub issue #18): the address, the token and the switch. A slide-up sheet,
 * never a pop-up, built from the app's own pieces and tokens.
 */
@Composable
private fun AutomationSheet(
    info: AutomationInfo,
    token: String,
    allowed: Boolean,
    onAllowed: (Boolean) -> Unit,
    onToken: (String) -> Unit,
    onRotate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clip = LocalClipboardManager.current
    var confirmRotate by remember { mutableStateOf(false) }
    com.past9.phoneaos.ui.AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            com.past9.phoneaos.ui.SheetHead(Icons.Rounded.Bolt, "Settings", "Automation from other apps",
                "Let MacroDroid, Tasker or any app on this phone fire one of your routines, over the address below or by intent. Anything that needs your approval is refused.")
            Spacer(Modifier.height(20.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Link, "Address", info.url) {}
                Line(Icons.Rounded.Key, "Token", "Copy it, then paste it into the macro as a Bearer token") {
                    if (token.isNotBlank()) { clip.setText(AnnotatedString(token)); com.past9.phoneaos.ui.AppNotice.say("Token copied") }
                }
                if (token.isNotBlank()) SelectionContainer {
                    Text(
                        token,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (token.isNotBlank()) { clip.setText(AnnotatedString(token)); com.past9.phoneaos.ui.AppNotice.say("Token copied") }
                    }, enabled = token.isNotBlank()) { Text("Copy token", style = MaterialTheme.typography.labelLarge) }
                    OutlinedButton(onClick = { confirmRotate = true }) { Text("New token", style = MaterialTheme.typography.labelLarge) }
                }
                if (confirmRotate) Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                    Text("A new token stops every macro that holds the old one. You paste the new one in.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRotate(); confirmRotate = false }) { Text("Make a new token", style = MaterialTheme.typography.labelLarge) }
                        TextButton(onClick = { confirmRotate = false }) { Text("Keep it", style = MaterialTheme.typography.labelLarge) }
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Allow outside runs", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (allowed) "A macro may fire a routine. Anything needing your approval is refused."
                            else "Off: every call is refused.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(allowed, onAllowed)
                }
            }
            Spacer(Modifier.height(12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                val intentRecipe = com.past9.phoneaos.automation.IntentTriggers.recipe(token)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Send, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Or by intent", style = MaterialTheme.typography.titleSmall)
                        Text("Works when the app is closed. Same token, same switch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { clip.setText(AnnotatedString(intentRecipe)); com.past9.phoneaos.ui.AppNotice.say("Recipe copied") }) { Text("Copy", style = MaterialTheme.typography.labelLarge) }
                }
            }
            Text("Only this phone can reach the address, and only while the app is running. The intent works when it is closed too.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
        }
    }
}
