package com.past9.phoneaos.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.AgentSettings
import com.past9.phoneaos.ui.AgentAvatar
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.voice.CallState
import com.past9.phoneaos.voice.Tts

data class VoiceActions(
    val setTts: (provider: String, voice: String) -> Unit = { _, _ -> },
    val setKey: (provider: String, key: String) -> Unit = { _, _ -> },
    val hasKey: (provider: String) -> Boolean = { false },
    val preview: () -> Unit = {},
    val setSpeak: (Boolean) -> Unit = {},
    val setLive: (Boolean) -> Unit = {},
    val setAutoPlay: (Boolean) -> Unit = {},
)

/** Slide-up: who speaks for your agent, with which key, and whether you can call it. */
@Composable
fun VoiceSheet(s: AgentSettings, actions: VoiceActions, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val providers = listOf("system" to "Phone", "gemini" to "Google", "openai" to "OpenAI", "elevenlabs" to "ElevenLabs")
    com.past9.phoneaos.ui.AgentSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = com.past9.phoneaos.ui.sidePad(24.dp, com.past9.phoneaos.ui.Readable.FORM)).padding(bottom = 32.dp)) {
            Text("VOICE", style = Eyebrow, color = cs.primary)
            Text("How ${s.agentName} sounds", style = MaterialTheme.typography.headlineSmall)
            Text("Use any key you already have. The phone's own voice works with none.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                providers.forEachIndexed { i, (id, label) ->
                    ToggleButton(s.ttsProvider == id, { actions.setTts(id, "") }, modifier = Modifier.weight(1f),
                        shapes = when (i) { 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes(); providers.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes(); else -> ButtonGroupDefaults.connectedMiddleButtonShapes() }) {
                        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (s.ttsProvider != "system") {
                Spacer(Modifier.height(16.dp))
                var key by remember(s.ttsProvider) { mutableStateOf("") }
                val has = actions.hasKey(s.ttsProvider)
                com.past9.phoneaos.ui.AppTextField(key, { key = it.trim() }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                    label = { Text(when (s.ttsProvider) { "gemini" -> "Google AI Studio key"; "openai" -> "OpenAI key"; else -> "ElevenLabs key" }) },
                    supportingText = { Text(if (has) "Key saved. Paste a new one to replace it." else "Needed for this voice.") },
                    trailingIcon = { if (key.length > 10) TextButton(onClick = { actions.setKey(s.ttsProvider, key); key = "" }) { Text("Save") } })
                Spacer(Modifier.height(8.dp))
                Text("VOICE", style = Eyebrow, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tts.voices[s.ttsProvider].orEmpty().forEachIndexed { i, v ->
                        val sel = s.ttsVoice == v.id || (s.ttsVoice.isBlank() && i == 0)
                        FilterChip(sel, { actions.setTts(s.ttsProvider, v.id) }, label = { Text(v.label) }, shape = RoundedCornerShape(50))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = actions.preview, enabled = has, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Hear it") }
            }
            Spacer(Modifier.height(20.dp))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-play with earphones in", style = MaterialTheme.typography.titleSmall)
                    Text("Voice notes play by themselves when you're wearing earphones", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Switch(s.autoPlayEarphones, actions.setAutoPlay)
            }
            Spacer(Modifier.height(16.dp))
            Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Call, null, tint = cs.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Live calls", style = MaterialTheme.typography.titleMedium)
                            Text("Call ${s.agentName} and just talk. It hands real work to your main agent and tells you when something matters.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // No switch: the phone button in the chat is always there, and works whenever a Google key is saved.
                    if (actions.hasKey("gemini")) Text("Ready: tap the phone in the chat to call.", style = MaterialTheme.typography.bodySmall, color = cs.primary)
                    else Text("Needs a Google (Gemini) key: pick Google above and save one. It runs on Gemini Live.", style = MaterialTheme.typography.bodySmall, color = cs.tertiary)
                }
            }
        }
    }
}

/** What the call is doing right now, in a word or two. Always one of these, never blank. */
fun callStatusLabel(st: CallState): String {
    val base = when (st.phase) {
        "listening" -> if (st.muted) "Muted" else "Listening"
        "thinking" -> "Working on it"
        "speaking" -> "Talking"
        "reconnecting" -> "Reconnecting"
        "failed" -> if (st.needsMic) "Microphone needed" else "Couldn't connect"
        "ended" -> if (st.error.isNotBlank()) "Call cut off" else "Call ended"
        else -> "Connecting"
    }
    return if (st.muted && st.active && st.phase != "listening") "$base, you're muted" else base
}

/** The sound button's label and icon for where the sound comes out. */
private fun routeLabel(route: String): String = when (route) {
    "EARPIECE" -> "Earpiece"; "BLUETOOTH" -> "Bluetooth"; "WIRED" -> "Earphones"; "USB" -> "Headset"; "HEARING_AID" -> "Hearing aid"; else -> "Speaker"
}

/** The call: the character, breathing with whoever is talking, what was said, and the controls. */
@Composable
fun CallScreen(name: String, st: CallState, onMute: () -> Unit, onEnd: () -> Unit, onStart: () -> Unit, actions: List<String> = emptyList(),
               /** What the main agent is waiting on: shown right here so the call never hides it. */
               question: com.past9.phoneaos.data.ChatItem? = null, onAnswer: (Long, String) -> Unit = { _, _ -> }, onBrowser: () -> Unit = {},
               /** Moves the sound to the next place it can go (earphones, loudspeaker, earpiece). */
               onRoute: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val level by animateFloatAsState(maxOf(st.mine, st.theirs), spring(0.6f, 800f), label = "lvl")
    LaunchedEffect(Unit) { if (!st.active) onStart() }
    // Back ends the call, the same as End and the notification's End.
    androidx.activity.compose.BackHandler(enabled = st.active) { onEnd() }
    // The microphone was refused: Try again asks for it again, then starts the call.
    val registry = androidx.activity.compose.LocalActivityResultRegistryOwner.current
    val askMic = if (registry != null) androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { onStart() } else null
    val again: () -> Unit = { if (st.needsMic && askMic != null) askMic.launch(android.Manifest.permission.RECORD_AUDIO) else onStart() }
    Surface(color = cs.primaryContainer, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Text(name, style = MaterialTheme.typography.headlineMedium, color = cs.onPrimaryContainer, textAlign = TextAlign.Center, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            CallStatus(st)
            // A card waiting on him makes the call compact: a small avatar, the card scrolling in the middle, and the
            // mute and end buttons always on screen (22 Oct 2026: a big approval card pushed Decline and End call off it).
            val compact = question != null
            val captions = st.lines.isNotEmpty() && !compact
            Column(Modifier.weight(1f).fillMaxWidth().then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = if (compact || captions) Arrangement.Top else Arrangement.Center) {
            Spacer(Modifier.height(if (compact) 8.dp else if (captions) 20.dp else 0.dp))
            AgentAvatar(working = st.phase == "speaking" || st.phase == "connecting" || st.phase == "thinking" || st.phase == "reconnecting" || (st.active && st.phase.isEmpty()),
                size = if (compact) 88.dp else if (captions) 96.dp else if (st.reply.isNotBlank() || actions.isNotEmpty() || st.error.isNotBlank()) 150.dp else 200.dp,
                modifier = Modifier.graphicsLayer { val sc = 1f + 0.18f * level; scaleX = sc; scaleY = sc })
            Spacer(Modifier.height(if (compact) 16.dp else 20.dp))
            if (st.delegated.isNotBlank()) Surface(shape = RoundedCornerShape(50), color = cs.surface.copy(alpha = 0.85f)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp), tint = cs.primary); Spacer(Modifier.width(6.dp))
                    Text("Working on: ${st.delegated.take(60)}", style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            // The agent's latest reply to what the call handed it, so the outcome is on screen as well as said.
            if (st.reply.isNotBlank() && !compact) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surface.copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("YOUR AGENT", style = Eyebrow, color = cs.primary)
                        Text(st.reply, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = if (captions) 2 else 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            // The agent needs him: the same card as in the chat, on top of the call. Answer by tapping, or out loud.
            if (question != null) {
                Spacer(Modifier.height(12.dp))
                when {
                    question.text.startsWith("APPROVAL|") -> CallApprovalCard(question, onAnswer)
                    question.text.startsWith(com.past9.phoneaos.tools.ConnectRequest.PREFIX) -> ConnectCard(question, onAnswer)
                    else -> QuestionCard(question, onAnswer, onBrowser)
                }
                Spacer(Modifier.height(8.dp))
                Text("Or just say it", style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer.copy(alpha = 0.7f))
            } else
            // What the agent is doing while you talk. The captions take its place once there is talk to show.
            if (actions.isNotEmpty() && !captions) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surface.copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("WHAT IT'S DOING", style = Eyebrow, color = cs.primary)
                        actions.takeLast(2).forEach { a ->
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = cs.primary); Spacer(Modifier.width(8.dp))
                                Text(a, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            // Why the call is not on (no key, no microphone, no network, cut off), in plain words.
            if (st.error.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(20.dp), tint = cs.onErrorContainer); Spacer(Modifier.width(12.dp))
                        Text(st.error, style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer)
                    }
                }
            }
            if (captions) {
                Spacer(Modifier.height(12.dp))
                CallCaptions(st.lines, name, Modifier.weight(1f).fillMaxWidth())
            }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                if (st.active) {
                    CallControl(if (st.muted) "Unmute" else "Mute") {
                        FilledTonalIconButton(onClick = onMute, modifier = Modifier.size(64.dp),
                            colors = if (st.muted) IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.inverseSurface, contentColor = cs.inverseOnSurface) else IconButtonDefaults.filledTonalIconButtonColors()) {
                            Icon(if (st.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (st.muted) "Unmute the microphone" else "Mute the microphone")
                        }
                    }
                    CallControl("End") {
                        FilledIconButton(onClick = onEnd, modifier = Modifier.size(80.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = com.past9.phoneaos.ui.theme.Lights.endCall, contentColor = com.past9.phoneaos.ui.theme.Lights.onEndCall)) {
                            Icon(Icons.Rounded.CallEnd, "End call", Modifier.size(34.dp))
                        }
                    }
                    CallControl(routeLabel(st.route)) {
                        FilledTonalIconButton(onClick = onRoute, enabled = st.route.isNotBlank(), modifier = Modifier.size(64.dp)) {
                            Icon(when (st.route) { "EARPIECE" -> Icons.Rounded.PhoneInTalk; "BLUETOOTH" -> Icons.Rounded.Bluetooth; "WIRED", "USB" -> Icons.Rounded.Headset; "HEARING_AID" -> Icons.Rounded.Hearing; else -> Icons.Rounded.Speaker },
                                "Sound: ${routeLabel(st.route)}. Tap to change")
                        }
                    }
                } else {
                    val label = when { st.needsMic -> "Allow microphone"; st.phase == "failed" || st.error.isNotBlank() -> "Try again"; else -> "Call again" }
                    CallControl(label) {
                        FilledIconButton(onClick = again, modifier = Modifier.size(80.dp)) { Icon(if (st.needsMic) Icons.Rounded.Mic else Icons.Rounded.Call, label, Modifier.size(34.dp)) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The state pill under the name: a dot and a word, read out when it changes. */
@Composable
private fun CallStatus(st: CallState) {
    val cs = MaterialTheme.colorScheme
    val dot = when {
        st.phase == "failed" || (st.phase == "ended" && st.error.isNotBlank()) -> cs.error
        st.phase == "ended" || st.muted -> cs.outline
        st.phase == "listening" || st.phase == "speaking" -> cs.primary
        else -> cs.tertiary
    }
    Surface(shape = RoundedCornerShape(50), color = cs.surface.copy(alpha = 0.7f), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(callStatusLabel(st), style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1)
        }
    }
}

/** One call button with its name under it. */
@Composable
private fun CallControl(label: String, button: @Composable () -> Unit) {
    Column(Modifier.widthIn(min = 88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(80.dp), contentAlignment = Alignment.Center) { button() }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
    }
}

/**
 * What was said, both sides, newest at the bottom: the user's words on the right, the agent's on the left. It holds
 * the bottom as text grows, so a line being spoken fills in place and nothing jumps.
 */
@Composable
private fun CallCaptions(lines: List<Pair<String, String>>, name: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.verticalScroll(rememberScrollState(), reverseScrolling = true), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { (who, text) ->
            val me = who == "in" || who == "user" || who == "you"
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (me) Alignment.End else Alignment.Start) {
                Text(if (me) "You" else name, style = MaterialTheme.typography.labelSmall, color = cs.onPrimaryContainer.copy(alpha = 0.75f), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                Spacer(Modifier.height(2.dp))
                Surface(shape = MaterialTheme.shapes.large, color = if (me) cs.primary else cs.surface.copy(alpha = 0.9f), modifier = Modifier.widthIn(max = 300.dp)) {
                    Text(text.trim(), style = MaterialTheme.typography.bodyMedium, color = if (me) cs.onPrimary else cs.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/**
 * An approval on the call screen: what it wants to do, the start of the details, and Decline beside Hold to approve,
 * so both answers fit on screen with the call's own buttons (the chat's card, with its full details, is taller).
 */
@Composable
private fun CallApprovalCard(item: com.past9.phoneaos.data.ChatItem, onAnswer: (Long, String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val parts = item.text.split("|", limit = 3)
    val action = parts.getOrElse(1) { "" }.trim().replaceFirstChar { it.uppercase() }
    val details = parts.getOrElse(2) { "" }.trim()
    val by = runCatching { org.json.JSONObject(item.meta).optString("whyBy") }.getOrDefault("")
    com.past9.phoneaos.ui.AppCard(container = cs.tertiaryContainer) {
        Text(if (by.isNotBlank()) "$by NEEDS YOUR OK".uppercase() else "NEEDS YOUR OK", style = Eyebrow, color = cs.onTertiaryContainer)
        Spacer(Modifier.height(6.dp))
        Text(action, style = MaterialTheme.typography.titleMedium, color = cs.onTertiaryContainer)
        if (details.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = cs.surface.copy(alpha = 0.9f), modifier = Modifier.fillMaxWidth()) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, maxLines = 12, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onAnswer(item.id, "Decline") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Decline", color = cs.onTertiaryContainer) }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) { HoldToApprove { onAnswer(item.id, "Approve") } }
        }
    }
}

/**
 * A call held through the computer, as the computer logged it (one "call" row): how long it was and the last thing
 * said, tap for the whole conversation. A row with no transcript in it still shows its own line of text.
 */
@Composable
fun CallLogRow(item: com.past9.phoneaos.data.ChatItem, agentName: String) {
    val cs = MaterialTheme.colorScheme
    val meta = remember(item.meta) { runCatching { org.json.JSONObject(item.meta) }.getOrDefault(org.json.JSONObject()) }
    val lines = remember(item.meta) {
        meta.optJSONArray("lines")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("who") to it.optString("text").trim() } }.orEmpty().filter { it.second.isNotBlank() }
    }
    var open by remember { mutableStateOf(false) }
    val last = lines.lastOrNull()?.second ?: item.text.trim()
    Surface(onClick = { if (lines.isNotEmpty()) open = !open }, shape = RoundedCornerShape(com.past9.phoneaos.ui.theme.Radius.md), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(cs.primaryContainer, RoundedCornerShape(com.past9.phoneaos.ui.theme.Radius.sm)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Call, null, Modifier.size(16.dp), tint = cs.onPrimaryContainer)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(com.past9.phoneaos.voice.CallCore.rowTitle(lines.size, 0L, meta.optLong("durationMs")), style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
                    if (!open && last.isNotBlank()) Text(last, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (lines.isNotEmpty()) Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Hide the call" else "Show the call", tint = cs.onSurfaceVariant)
            }
            if (open) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { (who, text) ->
                    val me = who == "you" || who == "in" || who == "user"
                    Text(if (me) "You" else agentName, style = MaterialTheme.typography.labelMedium, color = if (me) cs.onSurfaceVariant else cs.primary)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                }
            }
        }
    }
}
