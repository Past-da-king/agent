package com.past9.phoneaos.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.ui.AgentAvatar
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.LocalExtra
import kotlinx.coroutines.launch

/** Everything onboarding needs from the outside world, so the screen stays testable. */
data class OnboardingActions(
    val saveNames: (user: String, agent: String) -> Unit = { _, _ -> },
    val choosePower: (PowerMode) -> Unit = {},
    val saveKey: (Provider, String) -> Unit = { _, _ -> },
    /** Makes one tiny call with the key the user typed. Returns null on success, else the reason. */
    val testKey: suspend (Provider, String) -> String? = { _, _ -> null },
    val saveComposio: suspend (String) -> String? = { null },
    val requestNotifications: () -> Unit = {},
    val openUrl: (String) -> Unit = {},
    val finish: () -> Unit = {},
)

enum class OnbStep { HELLO, NAME, POWER, KEY, CLAUDE, APPS, READY }

@Composable
fun OnboardingScreen(actions: OnboardingActions, start: OnbStep = OnbStep.HELLO, claudeRuntimeAvailable: Boolean = false) {
    var step by remember { mutableStateOf(start) }
    var user by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(Provider.ANTHROPIC) }
    val back: (() -> Unit)? = when (step) {
        OnbStep.HELLO -> null; OnbStep.NAME -> ({ step = OnbStep.HELLO }); OnbStep.POWER -> ({ step = OnbStep.NAME })
        OnbStep.KEY, OnbStep.CLAUDE -> ({ step = OnbStep.POWER }); OnbStep.APPS -> ({ step = OnbStep.POWER }); OnbStep.READY -> ({ step = OnbStep.APPS })
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                if (step != OnbStep.HELLO) {
                    val order = listOf(OnbStep.NAME, OnbStep.POWER, OnbStep.KEY, OnbStep.APPS, OnbStep.READY)
                    val idx = order.indexOf(if (step == OnbStep.CLAUDE) OnbStep.KEY else step)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(end = 16.dp)) {
                        order.forEachIndexed { i, _ ->
                            Box(Modifier.height(6.dp).width(if (i == idx) 22.dp else 6.dp).clip(CircleShape)
                                .background(if (i <= idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                }
            }
            AnimatedContent(step, transitionSpec = {
                (slideInHorizontally(spring(0.8f, 380f)) { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
            }, label = "onboarding", modifier = Modifier.weight(1f)) { s ->
                when (s) {
                    OnbStep.HELLO -> Hello { step = OnbStep.NAME }
                    OnbStep.NAME -> NameStep(user, agent, { user = it }, { agent = it }) { actions.saveNames(user, agent); step = OnbStep.POWER }
                    OnbStep.POWER -> PowerStep(claudeRuntimeAvailable) { mode -> actions.choosePower(mode); step = if (mode == PowerMode.API_KEY) OnbStep.KEY else OnbStep.CLAUDE }
                    OnbStep.KEY -> KeyStep(provider, { provider = it }, actions) { step = OnbStep.APPS }
                    OnbStep.CLAUDE -> ClaudeStep(claudeRuntimeAvailable, onUseKey = { actions.choosePower(PowerMode.API_KEY); step = OnbStep.KEY }) { step = OnbStep.APPS }
                    OnbStep.APPS -> AppsStep(actions) { step = OnbStep.READY }
                    OnbStep.READY -> ReadyStep(user, actions)
                }
            }
        }
    }
}

@Composable
private fun StepPage(eyebrow: String, title: String, line: String?, bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(eyebrow.uppercase(), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (line != null) { Spacer(Modifier.height(8.dp)); Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(24.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = bottom)
    }
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight), enabled = enabled, shapes = ButtonDefaults.shapes()) {
        Text(text, style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
    }

@Composable
private fun Hello(next: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.weight(1f))
        AgentAvatar(working = true, size = 120.dp)
        Spacer(Modifier.height(40.dp))
        Text("An agent that works for you.", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text("It browses, plans, remembers and follows up, right here on your phone. Even while you're in other apps.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("Your keys. Your phone. Nothing goes through our servers.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        BigButton("Get started", onClick = next)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun NameStep(user: String, agent: String, onUser: (String) -> Unit, onAgent: (String) -> Unit, next: () -> Unit) {
    StepPage("About you", "What should I call you?", "Your agent uses it when it talks to you. You can change it later.",
        bottom = { BigButton("Continue", enabled = user.isNotBlank(), onClick = next) }) {
        OutlinedTextField(user, onUser, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(agent, onAgent, label = { Text("Name your agent (optional)") }, placeholder = { Text("Your agent") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
    }
}

@Composable
private fun PowerStep(claudeAvailable: Boolean, choose: (PowerMode) -> Unit) {
    StepPage("Power", "How do you want to power your agent?", "Your agent thinks with an AI you already pay for. Pick one; you can switch any time.", bottom = {}) {
        ChoiceCard(Icons.Rounded.Key, "I have an API key", "Anthropic, OpenAI, Google Gemini or OpenRouter. Fastest to set up, nothing else to install.", "RECOMMENDED") { choose(PowerMode.API_KEY) }
        Spacer(Modifier.height(12.dp))
        ChoiceCard(Icons.Rounded.AutoAwesome, "Use my Claude subscription", "Claude Pro or Max. We set up Claude Code inside this app and you sign in with Claude.", if (claudeAvailable) null else "NEEDS RUNTIME PACK") { choose(PowerMode.CLAUDE_SUBSCRIPTION) }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, line: String, tag: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (tag != null) { Text(tag, style = Eyebrow, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(2.dp)) }
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

val keySteps = mapOf(
    Provider.ANTHROPIC to ("https://console.anthropic.com/settings/keys" to listOf("Open console.anthropic.com and sign in.", "Go to Settings, then API keys, then Create key.", "Add a little credit under Billing (pay as you go).", "Copy the key (it starts with sk-ant-) and paste it below.")),
    Provider.OPENAI to ("https://platform.openai.com/api-keys" to listOf("Open platform.openai.com and sign in.", "Go to API keys, then Create new secret key.", "Add credit under Billing.", "Copy the key (it starts with sk-) and paste it below.")),
    Provider.GEMINI to ("https://aistudio.google.com/app/apikey" to listOf("Open aistudio.google.com with your Google account.", "Tap Get API key, then Create API key.", "Gemini has a free tier to start with.", "Copy the key (it starts with AIza) and paste it below.")),
    Provider.OPENROUTER to ("https://openrouter.ai/settings/keys" to listOf("Open openrouter.ai and sign in.", "Go to Keys, then Create key.", "Add credits to use paid models.", "Copy the key (it starts with sk-or-) and paste it below.")),
)

@Composable
fun KeyStep(provider: Provider, onProvider: (Provider) -> Unit, actions: OnboardingActions, next: () -> Unit) {
    var key by remember(provider) { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember(provider) { mutableStateOf<String?>(null) } // "" = ok
    val scope = rememberCoroutineScope()
    val extra = LocalExtra.current
    StepPage("API key", "Paste your key", "It's stored encrypted on this phone and only ever sent to ${provider.label}.", bottom = {
        BigButton(if (result == "") "Continue" else if (busy) "Checking…" else "Check key", enabled = key.length > 10 && !busy) {
            if (result == "") { actions.saveKey(provider, key); next() }
            else scope.launch { busy = true; result = actions.testKey(provider, key) ?: ""; busy = false; if (result == "") actions.saveKey(provider, key) }
        }
    }) {
        Text("PROVIDER", style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        // Connected button group: the M3E way to pick one of a few.
        Column(verticalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            Provider.entries.chunked(2).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    rowItems.forEachIndexed { i, p ->
                        ToggleButton(checked = p == provider, onCheckedChange = { onProvider(p) }, modifier = Modifier.weight(1f).height(52.dp),
                            shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes()) {
                            Text(p.label, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(key, { key = it.trim(); result = null }, label = { Text("${provider.label} API key") }, placeholder = { Text(provider.keyHint) }, singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide key" else "Show key") } },
            supportingText = when (result) {
                null -> null
                "" -> ({ Text("Key works.", color = extra.success) })
                else -> ({ Text(result!!, color = MaterialTheme.colorScheme.error) })
            })
        Spacer(Modifier.height(16.dp))
        val (url, steps) = keySteps.getValue(provider)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("WHERE TO GET ONE", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                steps.forEachIndexed { i, s -> NumberedLine(i + 1, s) }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { actions.openUrl(url) }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open ${provider.label}")
                }
            }
        }
    }
}

@Composable
private fun NumberedLine(n: Int, text: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ClaudeStep(available: Boolean, onUseKey: () -> Unit, next: () -> Unit) {
    StepPage("Claude subscription", "Use Claude Pro or Max", "Claude Code runs inside this app, and you sign in with your Claude account. No API bill: it uses your plan's limits.", bottom = {
        if (available) BigButton("Set it up", onClick = next)
        else { BigButton("Use an API key instead", onClick = onUseKey); OutlinedButton(onClick = next, modifier = Modifier.fillMaxWidth().height(52.dp), shapes = ButtonDefaults.shapes()) { Text("Continue anyway") } }
    }) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("WHAT HAPPENS", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                NumberedLine(1, "We unpack a small runtime (about 60 MB) into the app.")
                NumberedLine(2, "Claude Code installs inside it. Nothing else to download.")
                NumberedLine(3, "You sign in with Claude in your browser and paste the code back.")
                NumberedLine(4, "Switch to an API key later and we delete the runtime to free the space.")
            }
        }
        if (!available) {
            Spacer(Modifier.height(12.dp))
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text("This build doesn't include the runtime pack yet. Use an API key for now; your memory, tasks and routines carry over when you switch.",
                    Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
fun AppsStep(actions: OnboardingActions, next: () -> Unit) {
    StepPage("Your apps", "Connect Gmail, Calendar and 250+ apps", "Optional. Connections run through Composio with your own free key, so your accounts stay yours.", bottom = {
        TextButton(onClick = next, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Skip for now") }
    }) { ComposioKeyForm(actions.saveComposio, actions.openUrl, next) }
}

/** Where to get a Composio key, the field, and a Connect button. No scrolling of its own. */
@Composable
fun ComposioKeyForm(save: suspend (String) -> String?, openUrl: (String) -> Unit, onSaved: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("GET YOUR KEY IN A MINUTE", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                NumberedLine(1, "Open platform.composio.dev and sign up (free).")
                NumberedLine(2, "Open your project's Settings, then API Keys.")
                NumberedLine(3, "Create a key and copy it.")
                NumberedLine(4, "Paste it below. Then pick the apps you want in Connections.")
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { openUrl("https://platform.composio.dev") }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open Composio")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(key, { key = it.trim(); error = null }, label = { Text("Composio API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, isError = error != null, supportingText = error?.let { { Text(it) } })
        Spacer(Modifier.height(12.dp))
        BigButton(if (busy) "Checking…" else "Connect", enabled = key.length > 8 && !busy) {
            scope.launch { busy = true; error = save(key); busy = false; if (error == null) onSaved() }
        }
    }
}

@Composable
private fun ReadyStep(user: String, actions: OnboardingActions) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.weight(1f))
        AgentAvatar(working = false, size = 96.dp)
        Spacer(Modifier.height(32.dp))
        Text(if (user.isNotBlank()) "You're set, $user." else "You're set.", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text("One last thing: let me send you notifications, so I can tell you when something's done or when I need a yes from you.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        BigButton("Allow notifications") { actions.requestNotifications(); actions.finish() }
        TextButton(onClick = actions.finish, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Not now") }
        Spacer(Modifier.height(16.dp))
    }
}
