package com.past9.phoneaos.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.AgentAvatar
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.Markdown
import com.past9.phoneaos.ui.MorphIconButton
import com.past9.phoneaos.ui.StatusPill
import com.past9.phoneaos.ui.theme.Eyebrow
import com.past9.phoneaos.ui.theme.LocalExtra
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

data class ChatActions(
    val onSend: (String) -> Unit = {},
    /** Swipe-up send: the agent answers with a voice note. */
    val onSendVoice: (String) -> Unit = {},
    val onStop: () -> Unit = {},
    val onAnswer: (Long, String) -> Unit = { _, _ -> },
    val onMenu: () -> Unit = {},
    val onBrowser: () -> Unit = {},
    val onMic: () -> Unit = {},
    val onProfile: () -> Unit = {},
    val onAttach: () -> Unit = {},
    val onAttachDoc: () -> Unit = {},
    val onRemoveDoc: (String) -> Unit = {},
    val onRemoveAttachment: (String) -> Unit = {},
    val onModel: () -> Unit = {},
    val onCall: (() -> Unit)? = null,
    /** Non-null while a reply is being read aloud: tap to stop it. */
    val onStopSpeaking: (() -> Unit)? = null,
)

/** Chat rows after grouping: consecutive activity lines fold into one "steps" row. */
sealed interface Row_ { val key: String
    data class Single(val item: ChatItem) : Row_ { override val key = "i${item.id}" }
    data class Steps(val items: List<ChatItem>) : Row_ { override val key = "s${items.first().id}" }
}

fun group(items: List<ChatItem>): List<Row_> {
    val out = mutableListOf<Row_>(); val buf = mutableListOf<ChatItem>()
    fun flush() { if (buf.isNotEmpty()) { out += Row_.Steps(buf.toList()); buf.clear() } }
    items.forEach {
        val m = JSONObject(it.meta)
        when {
            it.kind == "activity" && m.has("helper") -> {} // shown inside its helper's card
            it.kind == "activity" && m.optString("image").isEmpty() -> buf += it
            else -> { flush(); out += Row_.Single(it) }
        }
    }
    flush(); return out
}

@Composable
fun ChatScreen(
    items: List<ChatItem>,
    status: AgentStatus,
    agentName: String,
    userName: String,
    browserLive: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    actions: ChatActions,
    openCount: Int = 0,
    attachments: List<String> = emptyList(),
    modelLabel: String = "",
    docs: List<String> = emptyList(),
    morning: MorningUi? = null,
) {
    val rows = remember(items) { group(items) }
    val list = rememberLazyListState()
    LaunchedEffect(rows.size, items.lastOrNull()?.meta) { if (rows.isNotEmpty()) list.animateScrollToItem(rows.size) }
    val waitingOnYou = items.any { it.kind == "question" && !JSONObject(it.meta).has("answer") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { ChatTopBar(agentName, status, browserLive, waitingOnYou, openCount, actions, modelLabel) },
        bottomBar = { Composer(draft, onDraft, status.working, actions, attachments, docs) },
    ) { pad ->
        if (items.isEmpty() || morning != null) Welcome(userName, Modifier.padding(pad), actions.onSend, morning, hasChat = items.isNotEmpty())
        else LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(rows, key = { it.key }) { r ->
                when (r) {
                    is Row_.Steps -> StepsRow(r.items)
                    is Row_.Single -> when (r.item.kind) {
                        "user" -> UserBubble(r.item)
                        "agent" -> AgentMessage(r.item)
                        "question" -> when {
                            r.item.text.startsWith("APPROVAL|") -> ApprovalCard(r.item, actions.onAnswer)
                            r.item.text.startsWith(com.past9.phoneaos.tools.ConnectRequest.PREFIX) -> ConnectCard(r.item, actions.onAnswer)
                            else -> QuestionCard(r.item, actions.onAnswer, actions.onBrowser)
                        }
                        "voice" -> VoiceBubble(r.item)
                        "helper" -> HelperRow(r.item, items.filter { a -> a.kind == "activity" && JSONObject(a.meta).optLong("helper") == r.item.id })
                        "activity" -> ImageActivity(r.item)
                        else -> Notice(r.item)
                    }
                }
            }
            if (status.working && !waitingOnYou) item(key = "working") { WorkingRow(status) }
        }
    }
}

@Composable
private fun ChatTopBar(agentName: String, status: AgentStatus, browserLive: Boolean, needsYou: Boolean, openCount: Int, actions: ChatActions, modelLabel: String = "") {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onMenu) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to home") }
            Row(Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable(onClick = actions.onProfile).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(working = status.working, needsYou = needsYou, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(agentName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = when {
                    needsYou -> "Waiting for you"
                    status.working -> status.label + if (status.helpers > 0) " · ${status.helpers} helper${if (status.helpers > 1) "s" else ""}" else ""
                    else -> "Ready"
                }
                AnimatedContent(line, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "status") {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (needsYou) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }
            if (modelLabel.isNotBlank()) Surface(onClick = actions.onModel, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(end = 6.dp)) {
                Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(modelLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
                    Icon(Icons.Rounded.ArrowDropDown, "Switch model", Modifier.size(20.dp))
                }
            }
            if (actions.onStopSpeaking != null) FilledTonalIconButton(onClick = actions.onStopSpeaking) { Icon(Icons.Rounded.VolumeOff, "Stop reading aloud") }
            if (actions.onCall != null) IconButton(onClick = actions.onCall) { Icon(Icons.Rounded.Call, "Call your agent") }
            AnimatedVisibility(browserLive, enter = scaleIn(spring(0.6f, 800f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                FilledTonalIconButton(onClick = actions.onBrowser) { Icon(Icons.Rounded.Language, "Watch the agent's browser") }
            }
        }
    }
}

/** What the night shift wrote for this morning. Null fields fall back to the first-day screen. */
data class MorningUi(
    val greeting: String = "", val line: String = "", val body: String = "",
    val ideas: List<Pair<String, String>> = emptyList(),
    /** The agent is still writing today's screen. */
    val preparing: Boolean = false,
    val onBackToChat: () -> Unit = {},
)

private fun ideaIcon(kind: String) = when (kind) {
    "browse" -> Icons.Rounded.TravelExplore; "plan" -> Icons.Rounded.EventNote; "routine" -> Icons.Rounded.Alarm
    "memory" -> Icons.Rounded.Psychology; "task" -> Icons.Rounded.TaskAlt; "mail" -> Icons.Rounded.Mail
    "call" -> Icons.Rounded.Call; else -> Icons.Rounded.Lightbulb
}

@Composable
private fun Welcome(userName: String, modifier: Modifier, onSend: (String) -> Unit, morning: MorningUi?, hasChat: Boolean) {
    val greeting = morning?.greeting?.ifBlank { null } ?: if (userName.isNotBlank()) "Hi $userName." else "Hi there."
    val line = morning?.line?.ifBlank { null } ?: "What should I take off your plate?"
    val body = morning?.body?.ifBlank { null } ?: "I can browse for you in the background, keep your tasks, remember what matters, and run things on a schedule."
    val ideas = morning?.ideas?.takeIf { it.isNotEmpty() }?.map { ideaIcon(it.first) to it.second } ?: listOf(
        Icons.Rounded.TravelExplore to "Compare three phone contracts under R500 a month",
        Icons.Rounded.EventNote to "Plan my week: what's open and what's due",
        Icons.Rounded.Alarm to "Every morning at 7, give me the news that matters to me",
        Icons.Rounded.Psychology to "Remember that I prefer window seats and no red-eye flights",
    )
    // Centred like the first-day screen, but it scrolls when the agent writes longer lines.
    BoxWithConstraints(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.Center) {
        AgentAvatar(working = morning?.preparing == true, size = 88.dp)
        Spacer(Modifier.height(28.dp))
        Text(greeting, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
        Text(line, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Text(if (morning?.preparing == true) "TRY  ·  THINKING ABOUT YOUR DAY" else "TRY", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ideas.forEach { (icon, text) ->
                Surface(onClick = { onSend(text) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        if (morning != null && hasChat) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = morning.onBackToChat, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Rounded.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Pick up where we left off")
            }
        }
    }
}
}

/** A voice note: play it, or tap "Read it" to scan the words instead. */
@Composable
private fun VoiceBubble(item: ChatItem) {
    val cs = MaterialTheme.colorScheme
    val path = JSONObject(item.meta).optString("path")
    var playing by remember { mutableStateOf(false) }
    val hasAudio = path.isNotBlank() && java.io.File(path).exists()
    var showText by remember { mutableStateOf(!hasAudio) }
    val t = rememberInfiniteTransition(label = "voice")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "wave")
    Column(Modifier.fillMaxWidth().padding(end = 48.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = cs.primaryContainer) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MorphIconButton(onClick = {
                    if (playing) { com.past9.phoneaos.voice.Tts.stop(); playing = false }
                    else if (hasAudio) { com.past9.phoneaos.voice.Tts.play(java.io.File(path)) { playing = false }; playing = true }
                }, size = 48.dp, container = if (hasAudio) cs.primary else cs.surfaceContainerHigh, enabled = hasAudio) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = if (hasAudio) cs.onPrimary else cs.onSurfaceVariant) }
                Spacer(Modifier.width(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(18) { i ->
                        val h = (8 + ((i * 37) % 17)) * (if (playing) 0.6f + 0.6f * ((wave + i * 0.13f) % 1f) else 1f)
                        Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(50)).background(cs.onPrimaryContainer.copy(alpha = if (playing) 0.9f else 0.5f)))
                    }
                }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = { showText = !showText }) { Text(if (showText) "Hide" else "Read it", color = cs.onPrimaryContainer) }
            }
        }
        AnimatedVisibility(showText) { Markdown(item.text, Modifier.padding(top = 8.dp, start = 4.dp), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun UserBubble(item: ChatItem) {
    val text = item.text
    val images = JSONObject(item.meta).optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    if (images.isNotEmpty()) Box(Modifier.fillMaxWidth().padding(start = 64.dp, bottom = 6.dp), contentAlignment = Alignment.CenterEnd) {
        com.past9.phoneaos.ui.ImageStrip(images.map { com.past9.phoneaos.ui.ChatImage(java.io.File(it).toURI().toString(), "Your photo") })
    }
    val files = JSONObject(item.meta).optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    if (files.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 48.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
        files.forEach { f ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Description, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp))
                    Text(f, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp), modifier = Modifier.widthIn(max = 320.dp).padding(start = 48.dp)) {
            Text(text.substringBefore("\n\n[Attached document:").ifBlank { "Here's a document." }, Modifier.padding(horizontal = 16.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun AgentMessage(item: ChatItem) {
    val routine = JSONObject(item.meta).optString("routine")
    Column(Modifier.fillMaxWidth().padding(end = 12.dp, top = 2.dp)) {
        if (routine.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("ROUTINE · ${routine.uppercase()}", style = Eyebrow, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Markdown(com.past9.phoneaos.voice.Tts.stripEmoji(item.text))
    }
}

fun iconFor(tool: String): ImageVector = when (tool) {
    "browser" -> Icons.Rounded.Language; "memory" -> Icons.Rounded.Psychology; "tasks" -> Icons.Rounded.TaskAlt
    "apps" -> Icons.Rounded.Apps; "routines" -> Icons.Rounded.Schedule; "web" -> Icons.Rounded.Public
    else -> Icons.Rounded.Bolt
}

/** The transparent activity log: what the agent did, folded to one line, tap for every step. */
@Composable
private fun StepsRow(items: List<ChatItem>) {
    var open by remember { mutableStateOf(false) }
    val last = items.last()
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { open = !open }.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(iconFor(JSONObject(last.meta).optString("tool")), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(10.dp))
            Text(last.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (items.size > 1) Text("${items.size} steps", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            if (items.size > 1) Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Hide steps" else "Show steps", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open && items.size > 1, enter = expandVertically(spring(0.8f, 380f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 38.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconFor(JSONObject(a.meta).optString("tool")), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(8.dp))
                        val by = JSONObject(a.meta).optString("by").takeIf { it.isNotBlank() && it != "main" }
                        Text((by?.let { "$it: " } ?: "") + a.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageActivity(item: ChatItem) {
    val path = JSONObject(item.meta).optString("image")
    val bmp = remember(path) { runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    AppCard(padding = PaddingValues(10.dp)) {
        Text(item.text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp, top = 2.dp))
        if (bmp != null) androidx.compose.foundation.Image(bmp, item.text, Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        else Text("Screenshot no longer available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp))
    }
}

/** A helper agent: one line in the chat; tap it to see every step it took and what it found. */
@Composable
private fun HelperRow(item: ChatItem, steps: List<ChatItem>) {
    val meta = JSONObject(item.meta); val state = meta.optString("state")
    var open by remember { mutableStateOf(false) }
    val extra = LocalExtra.current
    AppCard(container = MaterialTheme.colorScheme.surfaceContainerLow, padding = PaddingValues(14.dp), onClick = { open = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                "working" -> LoadingIndicator(Modifier.size(28.dp))
                "done" -> Icon(Icons.Rounded.CheckCircle, null, tint = extra.success, modifier = Modifier.size(24.dp))
                else -> Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.optString("label", "Helper").uppercase() + when (state) { "working" -> " · WORKING"; "done" -> " · DONE"; else -> " · FAILED" }, style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val last = steps.lastOrNull()
                if (state == "working" && last != null) Text(last.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (steps.isNotEmpty()) Text("${steps.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            Icon(Icons.Rounded.ChevronRight, "See what it did", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(meta.optString("label", "Helper").uppercase(), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
            Text(item.text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text("WHAT IT DID", style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (steps.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            steps.forEach { a ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconFor(JSONObject(a.meta).optString("tool")), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(a.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state == "working") Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { LoadingIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(8.dp)); Text("Still working…", style = MaterialTheme.typography.bodyMedium) }
            val result = meta.optString("result")
            if (result.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("WHAT IT FOUND", style = Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Markdown(result, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun QuestionCard(item: ChatItem, onAnswer: (Long, String) -> Unit, onBrowser: () -> Unit) {
    val meta = JSONObject(item.meta); val answer = meta.optString("answer")
    val opts = meta.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    AppCard(container = if (answer.isEmpty()) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(if (answer.isEmpty()) "NEEDS YOU" else "YOU ANSWERED", style = Eyebrow, color = if (answer.isEmpty()) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(item.text, style = MaterialTheme.typography.titleMedium, color = if (answer.isEmpty()) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(14.dp))
        if (answer.isNotEmpty()) StatusChip(answer)
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            opts.forEachIndexed { i, o ->
                val click = { if (o == "Open browser") onBrowser() else onAnswer(item.id, o) }
                if (i == 0) Button(onClick = click, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shapes = ButtonDefaults.shapes()) { Text(o) }
                else OutlinedButton(onClick = click, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shapes = ButtonDefaults.shapes()) { Text(o, color = MaterialTheme.colorScheme.onTertiaryContainer) }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * The agent needs an app connected: the app's logo and name, why, and Connect / Decline.
 * Connect opens the app's sign-in; the agent waits and carries on once it's connected.
 */
@Composable
fun ConnectCard(item: ChatItem, onAnswer: (Long, String) -> Unit) {
    val req = com.past9.phoneaos.tools.ConnectRequest.parse(item.text) ?: return
    val answer = JSONObject(item.meta).optString("answer")
    val cs = MaterialTheme.colorScheme
    if (answer.isNotEmpty()) {
        // Settled: a quiet one-liner so the chat isn't full of old cards.
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            AppLogo(req.name, req.logo, 32.dp)
            Spacer(Modifier.width(12.dp))
            Text(req.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val ok = answer == "Connect"
            StatusPill(if (ok) "Opened sign-in" else if (answer == "Decline") "Declined" else "Answered", if (ok) cs.secondaryContainer else cs.surfaceContainerHigh, if (ok) cs.onSecondaryContainer else cs.onSurfaceVariant)
        }
        return
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLogo(req.name, req.logo, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("CONNECT AN APP", style = Eyebrow, color = cs.primary)
                    Spacer(Modifier.height(2.dp))
                    Text(req.name, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(14.dp))
            val why = req.reason.removePrefix("so I can ").removePrefix("So I can ").trim().trimEnd('.', '?')
            Text(if (why.isNotBlank()) "So I can $why." else "This task needs ${req.name}.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp).padding(top = 2.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("You sign in to ${req.name} yourself, through Composio. Disconnect any time in Connections.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onAnswer(item.id, "Decline") }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shapes = ButtonDefaults.shapes()) { Text("Decline") }
                Button(onClick = { onAnswer(item.id, "Connect") }, modifier = Modifier.weight(1.4f).heightIn(min = 52.dp), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Connect")
                }
            }
        }
    }
}

/**
 * Nothing goes out in the user's name without this card. It shows EXACTLY what will happen,
 * and Approve is press-and-hold so a stray tap can never send an email or spend money.
 */
@Composable
private fun ApprovalCard(item: ChatItem, onAnswer: (Long, String) -> Unit) {
    val parts = item.text.split("|", limit = 3)
    val action = parts.getOrElse(1) { "" }; val details = parts.getOrElse(2) { "" }
    val answer = JSONObject(item.meta).optString("answer")
    val pending = answer.isEmpty()
    AppCard(container = if (pending) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = if (pending) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (pending) "APPROVE BEFORE I DO THIS" else if (answer == "Approve") "APPROVED" else "DECLINED", style = Eyebrow,
                color = if (pending) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text(action.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge, color = if (pending) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(12.dp))
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
            Text(details, Modifier.padding(14.dp).heightIn(max = 260.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (pending) {
            Spacer(Modifier.height(14.dp))
            HoldToApprove { onAnswer(item.id, "Approve") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onAnswer(item.id, "Decline") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Decline", color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
fun HoldToApprove(onDone: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    val p by animateFloatAsState(if (holding) 1f else 0f, if (holding) tween(900) else spring(1f, 1600f), label = "hold")
    LaunchedEffect(holding) { if (holding) { delay(900); if (holding) { holding = false; onDone() } } }
    val fill = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
    Surface(
        color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth().height(56.dp).graphicsLayer { val s = 1f - 0.03f * p; scaleX = s; scaleY = s }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); holding = true; waitForUpOrCancellation(); holding = false } },
    ) {
        Box(Modifier.fillMaxSize().drawBehind { drawRect(fill, size = size.copy(width = size.width * p)) }, contentAlignment = Alignment.Center) {
            Text(if (holding) "Keep holding…" else "Hold to approve", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun Notice(item: ChatItem) {
    val err = JSONObject(item.meta).optBoolean("error")
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (err) { Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp)) }
        Text(item.text, style = MaterialTheme.typography.bodySmall, color = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkingRow(status: AgentStatus) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(32.dp))
        Spacer(Modifier.width(8.dp))
        Text(status.label.ifBlank { "Working" } + "…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, working: Boolean, actions: ChatActions, attachments: List<String>, docs: List<String> = emptyList()) {
    var attachMenu by remember { mutableStateOf(false) }
    if (attachMenu) ModalBottomSheet(onDismissRequest = { attachMenu = false }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            listOf(Triple(Icons.Rounded.Image, "Photo", "From your gallery") to actions.onAttach, Triple(Icons.Rounded.Description, "Document", "PDF, Word, text, CSV, scans") to actions.onAttachDoc).forEach { (t, act) ->
                Surface(onClick = { attachMenu = false; act() }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.first, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp))
                        Column { Text(t.second, style = MaterialTheme.typography.titleMedium); Text(t.third, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
      Column(Modifier.navigationBarsPadding().imePadding()) {
        if (docs.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            docs.forEach { name ->
                InputChip(selected = false, onClick = { actions.onRemoveDoc(name) }, label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
                    leadingIcon = { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)) }, trailingIcon = { Icon(Icons.Rounded.Close, "Remove", Modifier.size(16.dp)) }, shape = RoundedCornerShape(50))
            }
        }
        if (attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.forEach { path ->
                Box {
                    coil.compose.AsyncImage(java.io.File(path), "Photo to send", Modifier.size(72.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
                    Surface(onClick = { actions.onRemoveAttachment(path) }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)) {
                        Icon(Icons.Rounded.Close, "Remove photo", Modifier.padding(4.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp)) {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Rounded.AttachFile, "Attach a photo or document", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Box(Modifier.weight(1f).padding(vertical = 16.dp)) {
                        if (draft.isEmpty()) Text("Ask for anything", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BasicTextField(draft, onDraft, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 6, modifier = Modifier.fillMaxWidth())
                    }
                    IconButton(onClick = actions.onMic) { Icon(Icons.Rounded.Mic, "Speak", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.width(8.dp))
            val canSend = draft.isNotBlank() || attachments.isNotEmpty() || docs.isNotEmpty()
            // Swipe up on Send = "answer me with a voice note" (for walking). A tap sends as usual.
            var lift by remember { mutableFloatStateOf(0f) }
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            val density = androidx.compose.ui.platform.LocalDensity.current
            val threshold = with(density) { 56.dp.toPx() }
            val voiceMode = canSend && lift > threshold
            LaunchedEffect(voiceMode) { if (voiceMode) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
            // The button never moves (moving it clipped it against the composer); it grows a little and
            // turns into headphones once the swipe is far enough.
            Box(Modifier.graphicsLayer { val sc = 1f + 0.12f * (lift / threshold).coerceIn(0f, 1f); scaleX = sc; scaleY = sc }
                .pointerInput(canSend, working, draft) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var dy = 0f; lift = 0f
                        try {
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                dy = down.position.y - c.position.y; lift = dy.coerceAtLeast(0f)
                                if (dy > 8f) c.consume()
                            }
                        } finally { lift = 0f }
                        val wasVoice = canSend && dy > threshold
                        when {
                            wasVoice -> { actions.onSendVoice(draft); onDraft("") }
                            dy < 24f && working && !canSend -> actions.onStop()
                            dy < 24f && canSend -> { actions.onSend(draft); onDraft("") }
                        }
                    }
                }) {
                val bg = if (voiceMode) MaterialTheme.colorScheme.tertiary else if (canSend || working) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
                val tint = if (voiceMode) MaterialTheme.colorScheme.onTertiary else if (canSend || working) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Box(Modifier.size(56.dp).clip(if (working && !canSend) RoundedCornerShape(16.dp) else CircleShape).background(bg), contentAlignment = Alignment.Center) {
                    when {
                        voiceMode -> Icon(Icons.Rounded.Headphones, "Send, reply with a voice note", tint = tint)
                        working && !canSend -> Icon(Icons.Rounded.Stop, "Stop", tint = tint)
                        else -> Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Send (swipe up for a voice reply)", tint = tint)
                    }
                }
            }
        }
      }
    }
}
