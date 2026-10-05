package com.past9.phoneaos.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SubKind
import com.past9.phoneaos.ui.AgentAvatar
import com.past9.phoneaos.ui.Mascots
import com.past9.phoneaos.ui.MorphShape
import com.past9.phoneaos.ui.unit
import com.past9.phoneaos.ui.theme.Accents
import com.past9.phoneaos.ui.theme.AppTheme
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.LocalExtra
import kotlinx.coroutines.launch

/** Everything onboarding needs from the outside world, so the screen stays testable. */
data class OnboardingActions(
    val saveNames: (user: String, agent: String) -> Unit = { _, _ -> },
    val chooseStyle: (accent: String, mascot: String) -> Unit = { _, _ -> },
    val choosePower: (PowerMode) -> Unit = {},
    val chooseSub: (SubKind) -> Unit = {},
    val subAvailable: (SubKind) -> Boolean = { false },
    val saveKey: (Provider, String) -> Unit = { _, _ -> },
    val saveCustom: (baseUrl: String, model: String) -> Unit = { _, _ -> },
    /** Makes one tiny call with the key the user typed. Returns null on success, else the reason. */
    val testKey: suspend (Provider, String) -> String? = { _, _ -> null },
    val saveComposio: suspend (String) -> String? = { null },
    /** Models this key can use, newest first (live from the provider). */
    val listModels: suspend (Provider, String) -> List<com.past9.phoneaos.agent.ModelInfo> = { _, _ -> emptyList() },
    val saveModel: (String) -> Unit = {},
    val requestNotifications: () -> Unit = {},
    val openUrl: (String) -> Unit = {},
    val openSubSetup: () -> Unit = {},
    /** The real install + sign-in flow for Claude / ChatGPT, shown full screen inside onboarding. */
    val subSetup: (@Composable (SubKind, back: () -> Unit, next: () -> Unit) -> Unit)? = null,
    val finish: () -> Unit = {},
)

enum class OnbStep { HELLO, NAME, STYLE, POWER, KEY, SUB, APPS, READY }

@Composable
fun OnboardingScreen(actions: OnboardingActions, start: OnbStep = OnbStep.HELLO, initialAccent: String = "iris", initialMascot: String = "scout",
                     initialUser: String = "", initialAgent: String = "", initialSub: SubKind = SubKind.CLAUDE) {
    var step by remember { mutableStateOf(start) }
    var user by remember { mutableStateOf(initialUser) }
    var agent by remember { mutableStateOf(initialAgent) }
    var accent by remember { mutableStateOf(initialAccent) }
    var mascot by remember { mutableStateOf(initialMascot) }
    var provider by remember { mutableStateOf(Provider.ANTHROPIC) }
    var sub by remember { mutableStateOf(initialSub) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val order = listOf(OnbStep.NAME, OnbStep.STYLE, OnbStep.POWER, OnbStep.KEY, OnbStep.APPS, OnbStep.READY)
    val back: (() -> Unit)? = when (step) {
        OnbStep.HELLO -> null; OnbStep.NAME -> ({ step = OnbStep.HELLO }); OnbStep.STYLE -> ({ step = OnbStep.NAME })
        OnbStep.POWER -> ({ step = OnbStep.STYLE }); OnbStep.KEY, OnbStep.SUB -> ({ step = OnbStep.POWER })
        OnbStep.APPS -> ({ step = OnbStep.POWER }); OnbStep.READY -> ({ step = OnbStep.APPS })
    }
    // The whole onboarding re-themes live as the user picks a colour and a mascot.
    AppTheme(dark = dark, accent = accent, mascot = mascot) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding()) {
                if (step != OnbStep.HELLO) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Spacer(Modifier.weight(1f))
                        val idx = order.indexOf(if (step == OnbStep.SUB) OnbStep.KEY else step)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(end = 16.dp)) {
                            order.forEachIndexed { i, _ ->
                                val w by animateDpAsState(if (i == idx) 24.dp else 8.dp, spring(0.6f, 380f), label = "dot")
                                Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(if (i <= idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                            }
                        }
                    }
                }
                AnimatedContent(step, transitionSpec = {
                    (slideInHorizontally(spring(0.8f, 380f)) { it / 3 } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(tween(180)) { -it / 5 } + fadeOut(tween(150)))
                }, label = "onboarding", modifier = Modifier.weight(1f)) { s ->
                    when (s) {
                        OnbStep.HELLO -> Hello { step = OnbStep.NAME }
                        OnbStep.NAME -> NameStep(user, agent, { user = it }, { agent = it }) { actions.saveNames(user, agent); step = OnbStep.STYLE }
                        OnbStep.STYLE -> StyleStep(user, agent, accent, mascot, { accent = it }, { mascot = it }) { actions.chooseStyle(accent, mascot); step = OnbStep.POWER }
                        OnbStep.POWER -> PowerStep(actions.subAvailable,
                            onKey = { actions.choosePower(PowerMode.API_KEY); step = OnbStep.KEY },
                            onSub = { k ->
                                if (k == SubKind.OPENCODE) { provider = Provider.OPENCODE_GO; actions.choosePower(PowerMode.API_KEY); step = OnbStep.KEY }
                                else { sub = k; actions.chooseSub(k); actions.choosePower(PowerMode.SUBSCRIPTION); step = OnbStep.SUB } })
                        OnbStep.KEY -> KeyStep(provider, { provider = it }, actions) { step = OnbStep.APPS }
                        OnbStep.SUB -> if (actions.subAvailable(sub) && actions.subSetup != null) actions.subSetup.invoke(sub, { step = OnbStep.POWER }) { step = OnbStep.APPS }
                            else SubStep(sub, actions.subAvailable(sub), onUseKey = { actions.choosePower(PowerMode.API_KEY); step = OnbStep.KEY }, onSetup = actions.openSubSetup) { step = OnbStep.APPS }
                        OnbStep.APPS -> AppsStep(actions) { step = OnbStep.READY }
                        OnbStep.READY -> ReadyStep(user, actions)
                    }
                }
            }
        }
    }
}

// ───────────────────────────── building blocks ─────────────────────────────

@Composable
private fun StepPage(eyebrow: String, title: String, line: String?, bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(4.dp))
            Text(eyebrow.uppercase(), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.displaySmall)
            if (line != null) { Spacer(Modifier.height(10.dp)); Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(28.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = bottom)
    }
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, icon: ImageVector? = Icons.AutoMirrored.Rounded.ArrowForward, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight), enabled = enabled, shapes = ButtonDefaults.shapes()) {
        Text(text, style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
        if (icon != null) { Spacer(Modifier.width(10.dp)); Icon(icon, null) }
    }

/** A shape that slowly drifts: the hero's background life. */
@Composable
private fun Floater(shape: RoundedPolygon, size: Dp, color: Color, x: Dp, y: Dp, phase: Int, spin: Boolean) {
    val t = rememberInfiniteTransition(label = "float")
    val d by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200 + phase * 400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "d")
    val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24000 + phase * 3000, easing = LinearEasing)), label = "r")
    val morph = remember(shape) { Morph(shape.unit(), shape.unit()) }
    Box(Modifier.offset(x, y).size(size).graphicsLayer { translationY = (d - 0.5f) * 18.dp.toPx(); if (spin) rotationZ = r }.clip(MorphShape(morph, 0f)).background(color))
}

@Composable
private fun Hello(next: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(bottomStart = 48.dp, bottomEnd = 48.dp)).background(cs.primaryContainer)) {
            val w = maxWidth; val h = maxHeight
            // One hue, several strengths: lively without turning muddy.
            Floater(MaterialShapes.Clover4Leaf, 64.dp, cs.primary.copy(alpha = 0.20f), w * 0.06f, h * 0.18f, 0, true)
            Floater(MaterialShapes.Pill, 90.dp, cs.surface.copy(alpha = 0.55f), w * 0.70f, h * 0.12f, 1, false)
            Floater(MaterialShapes.Sunny, 54.dp, cs.primary.copy(alpha = 0.32f), w * 0.78f, h * 0.62f, 2, true)
            Floater(MaterialShapes.Gem, 44.dp, cs.surface.copy(alpha = 0.6f), w * 0.12f, h * 0.70f, 3, false)
            Floater(MaterialShapes.Heart, 36.dp, cs.primary.copy(alpha = 0.45f), w * 0.36f, h * 0.84f, 4, false)
            Floater(MaterialShapes.Cookie6Sided, 30.dp, cs.onPrimaryContainer.copy(alpha = 0.25f), w * 0.55f, h * 0.30f, 5, true)
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                AgentAvatar(working = true, size = 156.dp)
                Spacer(Modifier.height(18.dp))
                Surface(shape = RoundedCornerShape(50), color = cs.surface.copy(alpha = 0.9f)) {
                    Text("Hi! I'm your agent.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text("MEET YOUR AGENT", style = Eyebrow, color = cs.primary)
            Spacer(Modifier.height(10.dp))
            Text("It does the busywork.\nYou get your day back.", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text("It browses, plans, remembers and follows up, right here on your phone. Even while you're in other apps.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = cs.primary)
                Spacer(Modifier.width(8.dp))
                Text("Your keys. Your phone. No servers of ours.", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(22.dp))
            BigButton("Get started", onClick = next)
        }
    }
}

@Composable
private fun BigField(value: String, onChange: (String) -> Unit, placeholder: String, big: Boolean, autoFocus: Boolean = false) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    val style = if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall
    val focused = remember { mutableStateOf(false) }
    Column {
        Box {
            if (value.isEmpty()) Text(placeholder, style = style, color = MaterialTheme.colorScheme.outline)
            BasicTextField(value, onChange, singleLine = true, textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(if (value.isNotEmpty()) 3.dp else 2.dp).clip(CircleShape).background(if (value.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun NameStep(user: String, agent: String, onUser: (String) -> Unit, onAgent: (String) -> Unit, next: () -> Unit) {
    StepPage("About you", "First, what should I call you?", null, bottom = { BigButton("Continue", enabled = user.isNotBlank(), onClick = next) }) {
        BigField(user, onUser, "Your name", big = true, autoFocus = true)
        Spacer(Modifier.height(36.dp))
        Text("AND WHAT SHOULD YOU CALL ME?", style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        BigField(agent, onAgent, "Your agent", big = false)
        Spacer(Modifier.height(8.dp))
        Text("Optional. Give me a name if you like.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        AnimatedVisibility(user.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(working = false, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Surface(shape = RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text("Nice to meet you, ${user.trim()}.${if (agent.isNotBlank()) " I'm ${agent.trim()}." else ""}", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun StyleStep(user: String, agent: String, accent: String, mascot: String, onAccent: (String) -> Unit, onMascot: (String) -> Unit, next: () -> Unit) {
    StepPage("Make it yours", "Pick your colour and your sidekick", null, bottom = { BigButton("Looks good", onClick = next) }) {
        StylePicker(user, agent, accent, mascot, onAccent, onMascot)
    }
}

/** Live preview + colour swatches + mascot grid. Used in onboarding and in Settings. */
@Composable
fun StylePicker(user: String, agent: String, accent: String, mascot: String, onAccent: (String) -> Unit, onMascot: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        // Live preview: the chat as it will look.
        Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgentAvatar(working = true, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(agent.ifBlank { "Your agent" }, style = MaterialTheme.typography.titleMedium)
                        Text("Browsing · 2 helpers", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Surface(color = cs.primaryContainer, shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)) {
                        Text("Find me a quiet café near work", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = cs.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("On it${if (user.isNotBlank()) ", ${user.trim()}" else ""}. Three with good Wi-Fi, all under 10 minutes away.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Surface(color = cs.primary, shape = RoundedCornerShape(50)) {
                    Text("Hold to approve", Modifier.padding(horizontal = 18.dp, vertical = 10.dp), color = cs.onPrimary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("YOUR COLOUR", style = Eyebrow, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        val dark = cs.surface.luminance() < 0.5f
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Accents.forEach { a ->
                val selected = a.id == accent
                val p by animateFloatAsState(if (selected) 1f else 0f, spring(0.6f, 800f), label = "sw")
                val morph = remember { Morph(MaterialShapes.Circle.unit(), MaterialShapes.Cookie9Sided.unit()) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { onAccent(a.id) }) {
                    Box(Modifier.size(52.dp).graphicsLayer { rotationZ = p * 20f; val s = 1f + 0.08f * p; scaleX = s; scaleY = s }.clip(MorphShape(morph, p)).background(if (dark) a.dP else a.lP), contentAlignment = Alignment.Center) {
                        if (selected) Icon(Icons.Rounded.Check, "${a.label}, selected", tint = if (dark) a.dOnP else a.lOnP, modifier = Modifier.graphicsLayer { rotationZ = -p * 20f })
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, color = if (selected) cs.onSurface else cs.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("YOUR SIDEKICK", style = Eyebrow, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Mascots.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m ->
                    val selected = m.id == mascot
                    val bg by animateColorAsState(if (selected) cs.primaryContainer else cs.surfaceContainerLow, spring(1f, 1600f), label = "bg")
                    Surface(onClick = { onMascot(m.id) }, shape = MaterialTheme.shapes.large, color = bg, modifier = Modifier.weight(1f).height(108.dp),
                        border = if (selected) BorderStroke(2.dp, cs.primary) else null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            AgentAvatar(working = selected, mascot = m.id, size = 52.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(m.label, style = MaterialTheme.typography.labelLarge, color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** A brand-neutral mark for each subscription: its colour and a shape, no logos. */
private fun subMark(k: SubKind): Pair<Color, RoundedPolygon> = when (k) {
    SubKind.CLAUDE -> Color(0xFFD97757) to MaterialShapes.Burst
    SubKind.CODEX -> Color(0xFF10A37F) to MaterialShapes.Cookie6Sided
    SubKind.OPENCODE -> Color(0xFF8E8C99) to MaterialShapes.Square
}

@Composable
private fun PowerStep(available: (SubKind) -> Boolean, onKey: () -> Unit, onSub: (SubKind) -> Unit) {
    val cs = MaterialTheme.colorScheme
    StepPage("Power", "How do you want to power your agent?", "Your agent thinks with an AI you already pay for. You can switch any time.", bottom = {}) {
        Text("USE A SUBSCRIPTION YOU ALREADY HAVE", style = Eyebrow, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                SubKind.entries.forEachIndexed { i, k ->
                    val (c, shape) = subMark(k)
                    val morph = remember(k) { Morph(shape.unit(), shape.unit()) }
                    Row(Modifier.fillMaxWidth().clickable { onSub(k) }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(MorphShape(morph, 0f)).background(c))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${k.label} ${k.plan}", style = MaterialTheme.typography.titleMedium)
                            Text(if (available(k) || k == SubKind.OPENCODE) k.line else "${k.line.substringBefore(" on ")} on your plan. Needs the runtime pack.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = cs.onSurfaceVariant)
                    }
                    if (i < SubKind.entries.lastIndex) HorizontalDivider(Modifier.padding(start = 78.dp, end = 18.dp), color = cs.outlineVariant.copy(alpha = 0.6f))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("OR BRING AN API KEY", style = Eyebrow, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Surface(onClick = onKey, shape = MaterialTheme.shapes.extraLarge, color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Key, null, tint = cs.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text("I have an API key", style = MaterialTheme.typography.titleLarge, color = cs.onPrimaryContainer, modifier = Modifier.weight(1f))
                    Text("FASTEST", style = Eyebrow, color = cs.onPrimaryContainer)
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Anthropic", "OpenAI", "Gemini", "DeepSeek", "OpenRouter", "Any OpenAI-compatible").forEach {
                        Surface(shape = RoundedCornerShape(50), color = cs.surface.copy(alpha = 0.7f)) { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }
}

val keySteps = mapOf(
    Provider.ANTHROPIC to ("https://console.anthropic.com/settings/keys" to listOf(
        "Tap Open below. It opens Anthropic's console in your browser. Sign up or sign in.",
        "Go to Billing and add a little credit. It's pay as you go, and a few dollars lasts a long time.",
        "Go to API keys and tap Create key. Name it anything, like Agent.",
        "Tap Copy, come back to this app, paste the key in the box above and tap Check key.")),
    Provider.OPENAI to ("https://platform.openai.com/api-keys" to listOf(
        "Tap Open below. It opens OpenAI's platform in your browser. Sign up or sign in. (This is separate from a ChatGPT subscription.)",
        "Go to Billing and add a little credit. A few dollars lasts a long time.",
        "Go to API keys and tap Create new secret key. Name it anything, like Agent.",
        "Tap Copy (you can only see the key once), come back here, paste it above and tap Check key.")),
    Provider.GEMINI to ("https://aistudio.google.com/app/apikey" to listOf(
        "Tap Open below. It opens Google AI Studio. Sign in with any Google account.",
        "Tap Create API key. If it asks you to pick a project, choose the one it suggests.",
        "It's free to start, no card needed.",
        "Tap the copy button next to your new key, come back here, paste it above and tap Check key.")),
    Provider.DEEPSEEK to ("https://platform.deepseek.com/api_keys" to listOf(
        "Tap Open below. It opens DeepSeek's platform. Sign up or sign in.",
        "Go to Top up and add a few dollars. DeepSeek is cheap, so it goes a long way.",
        "Go to API keys and tap Create new API key. Name it anything.",
        "Tap Copy (you can only see the key once), come back here, paste it above and tap Check key.")),
    Provider.OPENCODE_GO to ("https://opencode.ai/auth" to listOf(
        "Tap Open below. It opens OpenCode. Sign in.",
        "Subscribe to the Go plan if you haven't yet.",
        "Find API keys in your account and create one.",
        "Copy it, come back here, paste it above and tap Check key.")),
    Provider.OPENCODE_ZEN to ("https://opencode.ai/auth" to listOf(
        "Tap Open below. It opens OpenCode. Sign in.",
        "Add credit to Zen (pay as you go).",
        "Find API keys in your account and create one.",
        "Copy it, come back here, paste it above and tap Check key.")),
    Provider.OPENROUTER to ("https://openrouter.ai/settings/keys" to listOf(
        "Tap Open below. It opens OpenRouter. Sign up or sign in.",
        "Add credits to use paid models. Some models are free and need no credit.",
        "Go to Keys and tap Create key. Name it anything.",
        "Copy it (you can only see it once), come back here, paste it above and tap Check key.")),
    Provider.CUSTOM to ("https://platform.openai.com/docs/api-reference/chat" to listOf(
        "Any service with an OpenAI-style chat API works: Groq, Together, Mistral, or a server on your own computer.",
        "In the boxes above, enter its base URL (it usually ends in /v1) and the exact model name from its docs.",
        "Paste its API key and tap Check key.")),
)

@Composable
fun KeyStep(provider: Provider, onProvider: (Provider) -> Unit, actions: OnboardingActions, next: () -> Unit) {
    var key by remember(provider) { mutableStateOf("") }
    var baseUrl by remember(provider) { mutableStateOf("") }
    var model by remember(provider) { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember(provider) { mutableStateOf<String?>(null) } // "" = ok
    val scope = rememberCoroutineScope()
    val extra = LocalExtra.current
    val custom = provider == Provider.CUSTOM
    var models by remember(provider) { mutableStateOf<List<com.past9.phoneaos.agent.ModelInfo>?>(null) }
    var picked by remember(provider) { mutableStateOf<com.past9.phoneaos.agent.ModelInfo?>(null) }
    var picking by remember { mutableStateOf(false) }
    // The moment the key works, show what it can run: pick a model right there.
    LaunchedEffect(result) { if (result == "" && !custom) { models = null; picking = true; models = runCatching { actions.listModels(provider, key) }.getOrDefault(emptyList()) } }
    val ready = key.length > 10 && (!custom || (baseUrl.startsWith("http") && model.isNotBlank()))
    StepPage("API key", "Paste your key", "Stored encrypted on this phone. Only ever sent to ${if (custom) "the service you enter" else provider.label}.", bottom = {
        BigButton(if (result == "") "Continue" else if (busy) "Checking…" else "Check key", enabled = ready && !busy, icon = if (result == "") Icons.AutoMirrored.Rounded.ArrowForward else null) {
            if (result == "") { actions.saveKey(provider, key); (picked ?: models?.firstOrNull())?.let { actions.saveModel(it.id) }; next() }
            else scope.launch {
                busy = true
                if (custom) actions.saveCustom(baseUrl, model)
                result = actions.testKey(provider, key) ?: ""; busy = false
                if (result == "") actions.saveKey(provider, key)
            }
        }
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Provider.entries.forEach { p ->
                val sel = p == provider
                FilterChip(selected = sel, onClick = { onProvider(p) }, label = { Text(if (p == Provider.CUSTOM) "Other" else p.label.removePrefix("Google ")) },
                    leadingIcon = if (sel) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null, shape = RoundedCornerShape(50), modifier = Modifier.height(40.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        if (custom) {
            OutlinedTextField(baseUrl, { baseUrl = it.trim(); result = null }, label = { Text("Base URL") }, placeholder = { Text("https://api.example.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(model, { model = it.trim(); result = null }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(key, { key = it.trim(); result = null }, label = { Text("${if (custom) "Service" else provider.label} API key") }, placeholder = { Text(provider.keyHint) }, singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide key" else "Show key") } },
            supportingText = when (result) {
                null -> null
                "" -> ({ Text("Key works.", color = extra.success) })
                else -> ({ Text(result!!, color = MaterialTheme.colorScheme.error) })
            })
        if (result == "" && !custom) {
            Spacer(Modifier.height(8.dp))
            Surface(onClick = { picking = true }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MODEL", style = Eyebrow, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(picked?.name ?: models?.firstOrNull()?.name ?: provider.defaultModel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        if (picking) ModelPickerSheet("Pick your model", models, picked?.id ?: "", null, onPick = { picked = it; actions.saveModel(it.id); picking = false }, onDismiss = { picking = false })
        Spacer(Modifier.height(16.dp))
        val (url, steps) = keySteps.getValue(provider)
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("WHERE TO GET ONE", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                steps.forEachIndexed { i, s -> NumberedLine(i + 1, s) }
                if (!custom) {
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = { actions.openUrl(url) }, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open ${provider.label}")
                    }
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
private fun SubStep(kind: SubKind, available: Boolean, onUseKey: () -> Unit, onSetup: () -> Unit, next: () -> Unit) {
    val (c, shape) = subMark(kind)
    val morph = remember(kind) { Morph(shape.unit(), shape.unit()) }
    StepPage("${kind.label} subscription", "Use your ${kind.label} ${kind.plan}", "${kind.line} No API bill: it uses your plan's limits.", bottom = {
        if (available) BigButton("Set it up") { onSetup(); next() }
        else {
            BigButton("Use an API key instead", onClick = onUseKey)
            TextButton(onClick = next, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Continue anyway") }
        }
    }) {
        Box(Modifier.size(72.dp).clip(MorphShape(morph, 0f)).background(c))
        Spacer(Modifier.height(20.dp))
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("WHAT HAPPENS", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                NumberedLine(1, "We unpack a small runtime into the app. Nothing else to download.")
                NumberedLine(2, "${kind.label}'s agent installs inside it.")
                NumberedLine(3, "You sign in with your ${kind.label} account.")
                NumberedLine(4, "Switch to an API key later and we delete the runtime to free the space.")
            }
        }
        if (!available) {
            Spacer(Modifier.height(12.dp))
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text("This build doesn't include the ${kind.label} runtime pack yet. Use an API key for now; your memory, tasks and routines carry over when you switch.",
                    Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
fun AppsStep(actions: OnboardingActions, next: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    StepPage("Your apps", "Connect Gmail, Calendar and 250+ apps", "Optional. Connections run through Composio with your own free key, so your accounts stay yours.", bottom = {
        TextButton(onClick = next, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Skip for now") }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            listOf("Gmail" to Color(0xFFEA4335), "Calendar" to Color(0xFF4285F4), "Drive" to Color(0xFF0F9D58), "Slack" to Color(0xFF611F69), "Notion" to Color(0xFF2B2A33), "+250" to cs.primary).forEach { (n, c) ->
                Box(Modifier.size(48.dp).border(3.dp, cs.surface, CircleShape).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
                    Text(if (n.startsWith("+")) n else n.take(1), style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        ComposioKeyForm(actions.saveComposio, actions.openUrl, next)
    }
}

/** Where to get a Composio key, the field, and a Connect button. No scrolling of its own. */
@Composable
fun ComposioKeyForm(save: suspend (String) -> String?, openUrl: (String) -> Unit, onSaved: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("GET YOUR KEY IN A MINUTE", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                NumberedLine(1, "Open platform.composio.dev and sign up (free).")
                NumberedLine(2, "Open your project's Settings, then API Keys.")
                NumberedLine(3, "Create a key and copy it.")
                NumberedLine(4, "Paste it below. Then pick your apps in Connections.")
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { openUrl("https://platform.composio.dev") }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open Composio")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(key, { key = it.trim(); error = null }, label = { Text("Composio API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, isError = error != null, supportingText = error?.let { { Text(it) } })
        Spacer(Modifier.height(12.dp))
        BigButton(if (busy) "Checking…" else "Connect", enabled = key.length > 8 && !busy, icon = null) {
            scope.launch { busy = true; error = save(key); busy = false; if (error == null) onSaved() }
        }
    }
}

@Composable
private fun ReadyStep(user: String, actions: OnboardingActions) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            // A little burst of shapes around the mascot: the one celebratory moment.
            val shapes = listOf(MaterialShapes.Sunny, MaterialShapes.Heart, MaterialShapes.Clover4Leaf, MaterialShapes.Gem, MaterialShapes.Pill, MaterialShapes.Cookie6Sided)
            val enter = remember { Animatable(0f) }
            LaunchedEffect(Unit) { enter.animateTo(1f, spring(0.55f, 200f)) }
            shapes.forEachIndexed { i, s ->
                val a = Math.toRadians(i * 60.0 - 90); val r = 92f * enter.value
                val m = remember { Morph(s.unit(), s.unit()) }
                Box(Modifier.offset((kotlin.math.cos(a) * r).dp, (kotlin.math.sin(a) * r).dp).size(26.dp).graphicsLayer { alpha = enter.value; rotationZ = i * 25f }
                    .clip(MorphShape(m, 0f)).background(if (i % 2 == 0) cs.primary else cs.primary.copy(alpha = 0.45f)))
            }
            AgentAvatar(working = false, size = 112.dp)
        }
        Spacer(Modifier.height(24.dp))
        Text(if (user.isNotBlank()) "You're set, ${user.trim()}." else "You're set.", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text("One last thing: let me send you notifications, so I can tell you when something's done or when I need a yes from you.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        BigButton("Allow notifications", icon = Icons.Rounded.NotificationsActive) { actions.requestNotifications(); actions.finish() }
        TextButton(onClick = actions.finish, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Not now") }
        Spacer(Modifier.height(12.dp))
    }
}
