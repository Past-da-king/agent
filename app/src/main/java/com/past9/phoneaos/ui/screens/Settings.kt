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
import androidx.compose.ui.platform.testTag
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
    /** Settings > Model routing: which account and model chat, routines and watchers run on (issue #64). */
    val routing: RoutingActions = RoutingActions(),
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
    var routeSheet by remember { mutableStateOf<com.past9.phoneaos.data.ModelRoute?>(null) }
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
                    com.past9.phoneaos.system.AppLocale.choices.firstOrNull { it.tag == actions.language }?.name ?: stringResource(R.string.settings_language_phone)) { pickLanguage = true }
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
            // Only while the agent runs on an API key: routes choose between the user's API accounts.
            if (s.mode == PowerMode.API_KEY) {
                SectionHeader(stringResource(R.string.routing_title), stringResource(R.string.routing_sub), Modifier.padding(start = 4.dp, top = 12.dp))
                AppCard(padding = PaddingValues(vertical = 4.dp)) {
                    com.past9.phoneaos.data.ModelRoute.entries.forEach { r ->
                        Line(routeIcon(r), routeName(r), routeSummary(s.routes[r]) ?: stringResource(R.string.routing_default), tag = "routing-row-${r.name}") { routeSheet = r }
                    }
                }
            }
            if (s.mode == PowerMode.SUBSCRIPTION || runtime.installedBytes > 0) AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Terminal, stringResource(R.string.settings_runtime, s.subKind.label), runtime.note) { actions.onClaudeSetup() }
                if (s.mode == PowerMode.SUBSCRIPTION) Line(Icons.Rounded.Psychology, stringResource(R.string.settings_model), stringResource(R.string.settings_tap_to_switch, s.subModel.ifBlank { stringResource(R.string.settings_default) }.replaceFirstChar { it.uppercase() })) { actions.onModel() }
                if (s.mode == PowerMode.SUBSCRIPTION) Line(Icons.Rounded.Groups, stringResource(R.string.settings_helpers), helpersLine(s.helperRoster, s.subHelperModel.ifBlank { stringResource(R.string.settings_same_as_agent) }.replaceFirstChar { it.uppercase() })) { actions.onHelperModel() }
                if (runtime.installedBytes > 0) Line(Icons.Rounded.DeleteSweep, stringResource(R.string.settings_delete_runtime), stringResource(R.string.settings_delete_runtime_sub, runtime.installedBytes / 1_000_000)) { actions.onDeleteRuntime() }
            }
            SectionHeader(stringResource(R.string.settings_voice), stringResource(R.string.settings_voice_sub), Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.RecordVoiceOver, stringResource(R.string.settings_voice),
                    stringResource(mapOf("system" to R.string.settings_voice_phone, "gemini" to R.string.settings_voice_google, "openai" to R.string.settings_voice_openai, "elevenlabs" to R.string.settings_voice_elevenlabs)[s.ttsProvider] ?: R.string.settings_voice_phone)) { voiceSheet = true }
                // The phone's digital assistant: on or off, and a sheet that walks through the two taps in the phone's Settings.
                val actx = androidx.compose.ui.platform.LocalContext.current
                var assistant by remember { mutableStateOf(false) }
                var assistTick by remember { mutableIntStateOf(0) }
                androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { assistTick++; onPauseOrDispose { } }
                val assistHeld = remember(assistant, assistTick) { com.past9.phoneaos.assist.AssistRole.held(actx) }
                Line(Icons.Rounded.Assistant, stringResource(R.string.settings_default_assistant), com.past9.phoneaos.assist.AssistRole.line(assistHeld)) { assistant = true }
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
                Line(Icons.Rounded.Alarm, stringResource(R.string.settings_wakeups), stringResource(R.string.settings_wakeups_sub)) { wakeups = true }
                if (wakeups) WakeupsSheet { wakeups = false }
                var wakeWord by remember { mutableStateOf(false) }
                val wakeWordOn by com.past9.phoneaos.wakeword.WakeWord.store(androidx.compose.ui.platform.LocalContext.current).state.collectAsState()
                Line(Icons.Rounded.RecordVoiceOver, stringResource(R.string.settings_wake_word), if (wakeWordOn.enabled) stringResource(R.string.settings_wake_word_listening, wakeWordOn.customPhrase.ifBlank { com.past9.phoneaos.wakeword.WakePhrase.defaultFor(s.agentName) }) else stringResource(R.string.settings_wake_word_off)) { wakeWord = true }
                if (wakeWord) WakeWordSheet { wakeWord = false }
                Line(Icons.Rounded.BatteryChargingFull, stringResource(R.string.settings_battery), stringResource(R.string.settings_battery_sub)) { actions.onBattery() }
            }
            SectionHeader(stringResource(R.string.settings_automation), stringResource(R.string.settings_automation_sub), Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Bolt, stringResource(R.string.settings_automation_title),
                    when {
                        !automation.running -> stringResource(R.string.settings_automation_not_listening)
                        automationAllowedNow -> stringResource(R.string.settings_automation_on, automation.url)
                        else -> stringResource(R.string.settings_automation_off)
                    }) { automationSheet = true }
                Line(Icons.Rounded.SmartToy, stringResource(R.string.settings_macro_title),
                    MacroRulesUi.allowedLine(actions.macro.allowlist(), actions.macro.intents(), androidx.compose.ui.platform.LocalContext.current)) { macroSheet = true }
                // Read again each time the sheet closes, so the line says what the sheet just changed.
                val mctx = androidx.compose.ui.platform.LocalContext.current
                val mediaLine = remember(mediaSheet) { MediaAppsUi.line(actions.media.rows(), actions.media.music(), actions.media.video(), mctx) }
                Line(Icons.Rounded.LibraryMusic, stringResource(R.string.settings_media_apps), mediaLine) { mediaSheet = true }
            }
            SectionHeader(stringResource(R.string.settings_bg_screen), stringResource(R.string.settings_bg_screen_sub), Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                val screenCtx = androidx.compose.ui.platform.LocalContext.current
                var screenOn by remember { mutableStateOf(com.past9.phoneaos.screen.Screens.on(screenCtx)) }
                // Off by default: it is new and still being proven on real phones. Switched off, nothing of it exists in the app.
                val screenState = if (screenOn) com.past9.phoneaos.screen.Screens.session(screenCtx).state.value else null
                Line(Icons.Rounded.Layers, stringResource(R.string.settings_bg_screen), if (screenState?.stopped == true) stringResource(R.string.settings_bg_screen_stopped) else if (screenOn) stringResource(R.string.settings_bg_screen_on) else stringResource(R.string.settings_bg_screen_off),
                    trailing = { Switch(screenOn, { on -> screenOn = on; com.past9.phoneaos.screen.Screens.setOn(screenCtx, on) }) }) {
                    if (screenState?.stopped == true) { com.past9.phoneaos.screen.Screens.session(screenCtx).userAllow(); com.past9.phoneaos.ui.AppNotice.say(screenCtx.getString(R.string.settings_bg_screen_allowed)) }
                    else if (screenOn) com.past9.phoneaos.screen.ScreenUi.show.value = com.past9.phoneaos.screen.ScreenUi.SETUP
                }
                if (screenOn) Line(Icons.Rounded.Movie, stringResource(R.string.settings_bg_screen_runs), stringResource(R.string.settings_bg_screen_runs_sub)) { com.past9.phoneaos.screen.ScreenUi.show.value = com.past9.phoneaos.screen.ScreenUi.RUNS }
            }
            SectionHeader(stringResource(R.string.settings_browser), stringResource(R.string.settings_browser_sub), Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                // Read again each time the sheet closes, so the line says what the sheet just changed.
                val sites = remember(browserSheet) { actions.browser.origins() }
                Line(Icons.Rounded.Public, stringResource(R.string.settings_browser_sites), com.past9.phoneaos.browser.IsolatedOrigins.line(sites)) { browserSheet = true }
            }
            SectionHeader(stringResource(R.string.settings_linked), stringResource(R.string.settings_linked_sub), Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Devices, stringResource(R.string.settings_linked), actions.linkedLine) { actions.onLinked() }
            }
            SectionHeader(stringResource(R.string.settings_data), stringResource(R.string.settings_data_sub), Modifier.padding(start = 4.dp, top = 12.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Mic, stringResource(R.string.settings_recordings), stringResource(R.string.settings_recordings_sub)) { actions.onRecordings() }
                Line(Icons.Rounded.ChatBubbleOutline, stringResource(R.string.settings_clear_chat), stringResource(R.string.settings_clear_chat_sub)) { actions.onClearChat() }
                Line(Icons.Rounded.RestartAlt, stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_sub), danger = true) { confirmReset = true }
            }
            Text(stringResource(R.string.settings_footer, version),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp))
        }
    }
    // Slide-up sheets, never pop-up dialogs.
    if (pickSub) com.past9.phoneaos.ui.ChoiceSheet(androidx.compose.material.icons.Icons.Rounded.Bolt, stringResource(R.string.settings_what_powers), stringResource(R.string.settings_which_sub),
        com.past9.phoneaos.data.SubKind.entries.map { k -> com.past9.phoneaos.ui.SheetChoice(k.name, "${k.label} ${stringResource(k.plan)}", if (k == com.past9.phoneaos.data.SubKind.OPENCODE) stringResource(R.string.settings_paste_opencode) else stringResource(R.string.settings_sign_in_with, k.label),
            selected = s.mode == PowerMode.SUBSCRIPTION && s.subKind == k) },
        onPick = { c -> pickSub = false; actions.onChooseSub(com.past9.phoneaos.data.SubKind.valueOf(c.id)) }, onDismiss = { pickSub = false })
    // The language picker slides up from the bottom (never a centred dialog) and is built from the app's own sheet.
    if (pickLanguage) com.past9.phoneaos.ui.ChoiceSheet(Icons.Rounded.Language, stringResource(R.string.settings_you), stringResource(R.string.settings_language),
        com.past9.phoneaos.system.AppLocale.choices.map { lang ->
            com.past9.phoneaos.ui.SheetChoice(lang.tag, lang.name ?: stringResource(R.string.settings_language_phone),
                line = if (lang.machine) com.past9.phoneaos.system.AppLocale.machineLine(androidx.compose.ui.platform.LocalContext.current, lang.tag) else "",
                selected = lang.tag == actions.language) },
        onPick = { c -> pickLanguage = false; if (c.id != actions.language) actions.setLanguage(c.id) }, onDismiss = { pickLanguage = false })
    edit?.let { (label, save) ->
        com.past9.phoneaos.ui.AppSheet(androidx.compose.material.icons.Icons.Rounded.Edit, stringResource(R.string.settings_title), label, { edit = null }, primary = stringResource(R.string.settings_save), primaryEnabled = true, onPrimary = { save(editValue); edit = null }) {
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
    routeSheet?.let { r -> ModelRoutingSheet(r, s.routes[r], actions.routing) { routeSheet = null } }
    if (confirmReset) com.past9.phoneaos.ui.ConfirmSheet(androidx.compose.material.icons.Icons.Rounded.DeleteForever, stringResource(R.string.settings_this_phone), stringResource(R.string.settings_reset_confirm),
        stringResource(R.string.settings_reset_confirm_body),
        confirm = stringResource(R.string.settings_reset), onConfirm = { confirmReset = false; actions.onResetAll() }, onDismiss = { confirmReset = false }, cancel = stringResource(R.string.settings_keep), destructive = true)
}

@Composable
private fun Line(icon: ImageVector, title: String, value: String, danger: Boolean = false, trailing: @Composable (() -> Unit)? = null, tag: String? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent, modifier = if (tag != null) Modifier.testTag(tag) else Modifier) {
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var confirmRotate by remember { mutableStateOf(false) }
    com.past9.phoneaos.ui.AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            com.past9.phoneaos.ui.SheetHead(Icons.Rounded.Bolt, stringResource(R.string.settings_title), stringResource(R.string.settings_automation_title),
                stringResource(R.string.settings_automation_body))
            Spacer(Modifier.height(20.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Line(Icons.Rounded.Link, stringResource(R.string.machines_address), info.url) {}
                Line(Icons.Rounded.Key, stringResource(R.string.settings_access), stringResource(R.string.settings_access_sub)) {
                    if (token.isNotBlank()) { clip.setText(AnnotatedString(token)); com.past9.phoneaos.ui.AppNotice.say(ctx.getString(R.string.settings_access_copied)) }
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
                        if (token.isNotBlank()) { clip.setText(AnnotatedString(token)); com.past9.phoneaos.ui.AppNotice.say(ctx.getString(R.string.settings_access_copied)) }
                    }, enabled = token.isNotBlank()) { Text(stringResource(R.string.settings_copy_access), style = MaterialTheme.typography.labelLarge) }
                    OutlinedButton(onClick = { confirmRotate = true }) { Text(stringResource(R.string.settings_new_access), style = MaterialTheme.typography.labelLarge) }
                }
                if (confirmRotate) Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                    Text(stringResource(R.string.settings_new_access_warning),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRotate(); confirmRotate = false }) { Text(stringResource(R.string.settings_make_new_access), style = MaterialTheme.typography.labelLarge) }
                        TextButton(onClick = { confirmRotate = false }) { Text(stringResource(R.string.settings_keep_it), style = MaterialTheme.typography.labelLarge) }
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_allow_outside), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (allowed) stringResource(R.string.settings_allow_outside_on)
                            else stringResource(R.string.settings_allow_outside_off),
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
                    Icon(com.past9.phoneaos.ui.SendIcon, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_by_intent), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.settings_by_intent_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { clip.setText(AnnotatedString(intentRecipe)); com.past9.phoneaos.ui.AppNotice.say(ctx.getString(R.string.settings_recipe_copied)) }) { Text(stringResource(R.string.settings_copy), style = MaterialTheme.typography.labelLarge) }
                }
            }
            Text(stringResource(R.string.settings_automation_footer),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
        }
    }
}
