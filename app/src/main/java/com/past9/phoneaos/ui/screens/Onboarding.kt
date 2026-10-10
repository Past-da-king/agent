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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.past9.phoneaos.R
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
    val saveCustom: (baseUrl: String, model: String, allowHttp: Boolean) -> Unit = { _, _, _ -> },
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
                        if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.onb_back)) }
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
                    Text(stringResource(R.string.onb_hello_bubble), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(stringResource(R.string.onb_hello_eyebrow), style = Eyebrow, color = cs.primary)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.onb_hello_title), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.onb_hello_body), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = cs.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.onb_hello_privacy), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(22.dp))
            BigButton(stringResource(R.string.onb_get_started), onClick = next)
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
    StepPage(stringResource(R.string.onb_name_eyebrow), stringResource(R.string.onb_name_title), null, bottom = { BigButton(stringResource(R.string.onb_continue), enabled = user.isNotBlank(), onClick = next) }) {
        BigField(user, onUser, stringResource(R.string.onb_name_hint), big = true, autoFocus = true)
        Spacer(Modifier.height(36.dp))
        Text(stringResource(R.string.onb_name_agent_q), style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        BigField(agent, onAgent, stringResource(R.string.onb_your_agent), big = false)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onb_name_optional), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        AnimatedVisibility(user.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(working = false, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Surface(shape = RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(if (agent.isNotBlank()) stringResource(R.string.onb_nice_to_meet_named, user.trim(), agent.trim()) else stringResource(R.string.onb_nice_to_meet, user.trim()), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun StyleStep(user: String, agent: String, accent: String, mascot: String, onAccent: (String) -> Unit, onMascot: (String) -> Unit, next: () -> Unit) {
    StepPage(stringResource(R.string.onb_style_eyebrow), stringResource(R.string.onb_style_title), null, bottom = { BigButton(stringResource(R.string.onb_looks_good), onClick = next) }) {
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
                        Text(agent.ifBlank { stringResource(R.string.onb_your_agent) }, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.onb_preview_status), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Surface(color = cs.primaryContainer, shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)) {
                        Text(stringResource(R.string.onb_preview_user_msg), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = cs.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(if (user.isNotBlank()) stringResource(R.string.onb_preview_reply_named, user.trim()) else stringResource(R.string.onb_preview_reply), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Surface(color = cs.primary, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.onb_hold_to_approve), Modifier.padding(horizontal = 18.dp, vertical = 10.dp), color = cs.onPrimary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.onb_your_colour), style = Eyebrow, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        val dark = cs.surface.luminance() < 0.5f
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Accents.forEach { a ->
                val selected = a.id == accent
                val p by animateFloatAsState(if (selected) 1f else 0f, spring(0.6f, 800f), label = "sw")
                val morph = remember { Morph(MaterialShapes.Circle.unit(), MaterialShapes.Cookie9Sided.unit()) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { onAccent(a.id) }) {
                    Box(Modifier.size(52.dp).graphicsLayer { rotationZ = p * 20f; val s = 1f + 0.08f * p; scaleX = s; scaleY = s }.clip(MorphShape(morph, p)).background(if (dark) a.dP else a.lP), contentAlignment = Alignment.Center) {
                        if (selected) Icon(Icons.Rounded.Check, stringResource(R.string.onb_selected, a.label), tint = if (dark) a.dOnP else a.lOnP, modifier = Modifier.graphicsLayer { rotationZ = -p * 20f })
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, color = if (selected) cs.onSurface else cs.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.onb_your_sidekick), style = Eyebrow, color = cs.onSurfaceVariant)
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
    StepPage(stringResource(R.string.onb_power_eyebrow), stringResource(R.string.onb_power_title), stringResource(R.string.onb_power_line), bottom = {}) {
        Text(stringResource(R.string.onb_power_sub_header), style = Eyebrow, color = cs.onSurfaceVariant)
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
                            Text("${k.label} ${stringResource(k.plan)}", style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(if (available(k) || k == SubKind.OPENCODE) k.line else k.needsPack), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = cs.onSurfaceVariant)
                    }
                    if (i < SubKind.entries.lastIndex) HorizontalDivider(Modifier.padding(start = 78.dp, end = 18.dp), color = cs.outlineVariant.copy(alpha = 0.6f))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onb_power_key_header), style = Eyebrow, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Surface(onClick = onKey, shape = MaterialTheme.shapes.extraLarge, color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Key, null, tint = cs.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.onb_have_key), style = MaterialTheme.typography.titleLarge, color = cs.onPrimaryContainer, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.onb_fastest), style = Eyebrow, color = cs.onPrimaryContainer)
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Anthropic", "OpenAI", "Gemini", "DeepSeek", "OpenRouter", stringResource(R.string.onb_any_openai_compatible)).forEach {
                        Surface(shape = RoundedCornerShape(50), color = cs.surface.copy(alpha = 0.7f)) { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }
}

val keySteps = mapOf(
    Provider.ANTHROPIC to ("https://console.anthropic.com/settings/keys" to listOf(
        R.string.onb_key_anthropic_1,
        R.string.onb_key_anthropic_2,
        R.string.onb_key_anthropic_3,
        R.string.onb_key_anthropic_4)),
    Provider.OPENAI to ("https://platform.openai.com/api-keys" to listOf(
        R.string.onb_key_openai_1,
        R.string.onb_key_openai_2,
        R.string.onb_key_openai_3,
        R.string.onb_key_step_copy_once)),
    Provider.GEMINI to ("https://aistudio.google.com/app/apikey" to listOf(
        R.string.onb_key_gemini_1,
        R.string.onb_key_gemini_2,
        R.string.onb_key_gemini_3,
        R.string.onb_key_gemini_4)),
    Provider.DEEPSEEK to ("https://platform.deepseek.com/api_keys" to listOf(
        R.string.onb_key_deepseek_1,
        R.string.onb_key_deepseek_2,
        R.string.onb_key_deepseek_3,
        R.string.onb_key_step_copy_once)),
    Provider.OPENCODE_GO to ("https://opencode.ai/auth" to listOf(
        R.string.onb_key_opencode_1,
        R.string.onb_key_opencode_go_2,
        R.string.onb_key_opencode_3,
        R.string.onb_key_opencode_4)),
    Provider.OPENCODE_ZEN to ("https://opencode.ai/auth" to listOf(
        R.string.onb_key_opencode_1,
        R.string.onb_key_opencode_zen_2,
        R.string.onb_key_opencode_3,
        R.string.onb_key_opencode_4)),
    Provider.OPENROUTER to ("https://openrouter.ai/settings/keys" to listOf(
        R.string.onb_key_openrouter_1,
        R.string.onb_key_openrouter_2,
        R.string.onb_key_openrouter_3,
        R.string.onb_key_openrouter_4)),
    Provider.CUSTOM to ("https://platform.openai.com/docs/api-reference/chat" to listOf(
        R.string.onb_key_custom_1,
        R.string.onb_key_custom_2,
        R.string.onb_key_custom_3)),
)

@Composable
fun KeyStep(provider: Provider, onProvider: (Provider) -> Unit, actions: OnboardingActions, initialBaseUrl: String = "", next: () -> Unit) {
    var key by remember(provider) { mutableStateOf("") }
    var baseUrl by remember(provider) { mutableStateOf(initialBaseUrl) }
    var model by remember(provider) { mutableStateOf("") }
    var allowHttp by remember(provider) { mutableStateOf(false) }
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
    // HTTPS by default; a plain http:// server needs the explicit opt-in below (loopback is always fine).
    val plainHttp = custom && com.past9.phoneaos.agent.HttpPolicy.isHttp(baseUrl) && !com.past9.phoneaos.agent.HttpPolicy.isLoopback(baseUrl)
    val urlProblem = if (custom && baseUrl.isNotBlank()) com.past9.phoneaos.agent.HttpPolicy.blockReason(baseUrl, allowHttp) else null
    val ready = key.length > 10 && (!custom || (baseUrl.isNotBlank() && urlProblem == null && model.isNotBlank()))
    StepPage(stringResource(R.string.onb_key_eyebrow), stringResource(R.string.onb_key_title), stringResource(R.string.onb_key_line, if (custom) stringResource(R.string.onb_key_custom_service) else provider.label), bottom = {
        BigButton(if (result == "") stringResource(R.string.onb_continue) else if (busy) stringResource(R.string.onb_checking) else stringResource(R.string.onb_check_key), enabled = ready && !busy, icon = if (result == "") Icons.AutoMirrored.Rounded.ArrowForward else null) {
            if (result == "") { actions.saveKey(provider, key); (picked ?: models?.firstOrNull())?.let { actions.saveModel(it.id) }; next() }
            else scope.launch {
                busy = true
                if (custom) actions.saveCustom(baseUrl, model, plainHttp && allowHttp)
                result = actions.testKey(provider, key) ?: ""; busy = false
                if (result == "") actions.saveKey(provider, key)
            }
        }
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Provider.entries.forEach { p ->
                val sel = p == provider
                FilterChip(selected = sel, onClick = { onProvider(p) }, label = { Text(if (p == Provider.CUSTOM) stringResource(R.string.onb_other) else p.label.removePrefix("Google ")) },
                    leadingIcon = if (sel) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null, shape = RoundedCornerShape(50), modifier = Modifier.height(40.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        if (custom) {
            OutlinedTextField(baseUrl, { baseUrl = it.trim(); result = null }, label = { Text(stringResource(R.string.onb_base_url)) }, placeholder = { Text("https://api.example.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                isError = urlProblem != null && !plainHttp, supportingText = urlProblem?.takeIf { !plainHttp }?.let { ({ Text(it, color = MaterialTheme.colorScheme.error) }) })
            if (plainHttp) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.onb_http_warning_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Row(Modifier.fillMaxWidth().clickable { allowHttp = !allowHttp; result = null }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(allowHttp, { allowHttp = it; result = null })
                            Text(stringResource(R.string.onb_http_allow),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        if (!allowHttp) Text(stringResource(R.string.onb_http_blocked), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(model, { model = it.trim(); result = null }, label = { Text(stringResource(R.string.onb_model)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(key, { key = it.trim(); result = null }, label = { Text(if (custom) stringResource(R.string.onb_key_label_custom) else stringResource(R.string.onb_key_label, provider.label)) }, placeholder = { Text(stringResource(R.string.onb_key_hint)) }, singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) stringResource(R.string.onb_hide_key) else stringResource(R.string.onb_show_key)) } },
            supportingText = when (result) {
                null -> null
                "" -> ({ Text(stringResource(R.string.onb_key_works), color = extra.success) })
                else -> ({ Text(result!!, color = MaterialTheme.colorScheme.error) })
            })
        if (result == "" && !custom) {
            Spacer(Modifier.height(8.dp))
            Surface(onClick = { picking = true }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.onb_model_eyebrow), style = Eyebrow, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(picked?.name ?: models?.firstOrNull()?.name ?: provider.defaultModel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(stringResource(R.string.onb_change), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        if (picking) ModelPickerSheet(stringResource(R.string.onb_pick_model), models, picked?.id ?: "", null, onPick = { picked = it; actions.saveModel(it.id); picking = false }, onDismiss = { picking = false })
        Spacer(Modifier.height(16.dp))
        val (url, steps) = keySteps.getValue(provider)
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.onb_where_to_get), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                steps.forEachIndexed { i, s -> NumberedLine(i + 1, stringResource(s)) }
                if (!custom) {
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = { actions.openUrl(url) }, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.onb_open_provider, provider.label))
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
    StepPage(stringResource(R.string.onb_sub_eyebrow, kind.label), stringResource(R.string.onb_sub_title, kind.label, stringResource(kind.plan)), stringResource(R.string.onb_sub_line, stringResource(kind.line)), bottom = {
        if (available) BigButton(stringResource(R.string.onb_set_it_up)) { onSetup(); next() }
        else {
            BigButton(stringResource(R.string.onb_use_key_instead), onClick = onUseKey)
            TextButton(onClick = next, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.onb_continue_anyway)) }
        }
    }) {
        Box(Modifier.size(72.dp).clip(MorphShape(morph, 0f)).background(c))
        Spacer(Modifier.height(20.dp))
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.onb_what_happens), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                NumberedLine(1, stringResource(R.string.onb_sub_step_1))
                NumberedLine(2, stringResource(R.string.onb_sub_step_2, kind.label))
                NumberedLine(3, stringResource(R.string.onb_sub_step_3, kind.label))
                NumberedLine(4, stringResource(R.string.onb_sub_step_4))
            }
        }
        if (!available) {
            Spacer(Modifier.height(12.dp))
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onb_sub_unavailable, kind.label),
                    Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
fun AppsStep(actions: OnboardingActions, next: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    StepPage(stringResource(R.string.onb_apps_eyebrow), stringResource(R.string.onb_apps_title), stringResource(R.string.onb_apps_line), bottom = {
        TextButton(onClick = next, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.onb_skip_for_now)) }
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
                Text(stringResource(R.string.onb_composio_header), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                NumberedLine(1, stringResource(R.string.onb_composio_step_1))
                NumberedLine(2, stringResource(R.string.onb_composio_step_2))
                NumberedLine(3, stringResource(R.string.onb_composio_step_3))
                NumberedLine(4, stringResource(R.string.onb_composio_step_4))
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { openUrl("https://platform.composio.dev") }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.onb_open_provider, "Composio"))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(key, { key = it.trim(); error = null }, label = { Text(stringResource(R.string.onb_key_label, "Composio")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, isError = error != null, supportingText = error?.let { { Text(it) } })
        Spacer(Modifier.height(12.dp))
        BigButton(if (busy) stringResource(R.string.onb_checking) else stringResource(R.string.onb_connect), enabled = key.length > 8 && !busy, icon = null) {
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
        Text(if (user.isNotBlank()) stringResource(R.string.onb_ready_title_named, user.trim()) else stringResource(R.string.onb_ready_title), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onb_ready_body), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        BigButton(stringResource(R.string.onb_allow_notifications), icon = Icons.Rounded.NotificationsActive) { actions.requestNotifications(); actions.finish() }
        TextButton(onClick = actions.finish, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.onb_not_now)) }
        Spacer(Modifier.height(12.dp))
    }
}
