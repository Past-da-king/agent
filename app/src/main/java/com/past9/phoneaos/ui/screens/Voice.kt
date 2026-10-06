package com.past9.phoneaos.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
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
                OutlinedTextField(key, { key = it.trim() }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
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
                        Switch(s.liveEnabled, actions.setLive, enabled = actions.hasKey("gemini"))
                    }
                    if (!actions.hasKey("gemini")) {
                        Spacer(Modifier.height(8.dp))
                        Text("Needs a Google (Gemini) key: pick Google above and save one. It runs on Gemini Live.", style = MaterialTheme.typography.bodySmall, color = cs.tertiary)
                    }
                }
            }
        }
    }
}

/** The call: the character, big, breathing with whoever is talking. */
@Composable
fun CallScreen(name: String, st: CallState, onMute: () -> Unit, onEnd: () -> Unit, onStart: () -> Unit, actions: List<String> = emptyList(),
               /** What the main agent is waiting on: shown right here so the call never hides it. */
               question: com.past9.phoneaos.data.ChatItem? = null, onAnswer: (Long, String) -> Unit = { _, _ -> }, onBrowser: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val level by animateFloatAsState(maxOf(st.mine, st.theirs), spring(0.6f, 800f), label = "lvl")
    LaunchedEffect(Unit) { if (!st.active) onStart() }
    Surface(color = cs.primaryContainer, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Text(name, style = MaterialTheme.typography.headlineMedium, color = cs.onPrimaryContainer)
            Text(when (st.phase) { "connecting" -> "Connecting…"; "listening" -> if (st.muted) "Muted" else "Listening"; "speaking" -> "Talking"; "failed" -> "Couldn't connect"; "ended" -> "Call ended"; else -> "" },
                style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer.copy(alpha = 0.8f))
            Spacer(Modifier.weight(1f))
            AgentAvatar(working = st.phase == "speaking" || st.phase == "connecting", size = 200.dp, modifier = Modifier.graphicsLayer { val sc = 1f + 0.18f * level; scaleX = sc; scaleY = sc })
            Spacer(Modifier.height(28.dp))
            if (st.delegated.isNotBlank()) Surface(shape = RoundedCornerShape(50), color = cs.surface.copy(alpha = 0.85f)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp), tint = cs.primary); Spacer(Modifier.width(6.dp))
                    Text("Working on: ${st.delegated.take(60)}", style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1)
                }
            }
            // The main agent needs the user: the same card as in the chat, on top of the call.
            if (question != null) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    when {
                        question.text.startsWith("APPROVAL|") -> ApprovalCard(question, onAnswer)
                        question.text.startsWith(com.past9.phoneaos.tools.ConnectRequest.PREFIX) -> ConnectCard(question, onAnswer)
                        else -> QuestionCard(question, onAnswer, onBrowser)
                    }
                }
            } else
            // What the main agent is doing while you talk.
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surface.copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("WHAT IT'S DOING", style = Eyebrow, color = cs.primary)
                        actions.takeLast(4).forEach { a ->
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = cs.primary); Spacer(Modifier.width(8.dp))
                                Text(a, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            st.lines.takeLast(3).forEach { (who, t) ->
                Text(t, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = if (who == "user") cs.onPrimaryContainer.copy(alpha = 0.7f) else cs.onPrimaryContainer, maxLines = 3)
            }
            if (st.error.isNotBlank()) Text(st.error, color = cs.error, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onMute, modifier = Modifier.size(64.dp)) { Icon(if (st.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (st.muted) "Unmute" else "Mute") }
                if (st.active || st.phase == "connecting") FilledIconButton(onClick = onEnd, modifier = Modifier.size(80.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFD7141E), contentColor = Color.White)) {
                    Icon(Icons.Rounded.CallEnd, "End call", Modifier.size(34.dp))
                } else FilledIconButton(onClick = onStart, modifier = Modifier.size(80.dp)) { Icon(Icons.Rounded.Call, "Call again", Modifier.size(34.dp)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
