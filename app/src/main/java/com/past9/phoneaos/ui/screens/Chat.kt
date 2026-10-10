package com.past9.phoneaos.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Article
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.R
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    /** Open a live card (by id) in its sheet. */
    val onCard: (String) -> Unit = {},
    val onCall: (() -> Unit)? = null,
    /** Non-null while a reply is being read aloud: tap to stop it. */
    val onStopSpeaking: (() -> Unit)? = null,
    /** Open a report (its file path) in the full-screen reader. */
    val onReport: (String) -> Unit = {},
)

/**
 * The user's message this reply answers, when that isn't obvious: the reply came later, from helpers' results,
 * or the user said something else in between (helpers keep working while they talk).
 */
fun quotedFor(item: ChatItem, items: List<ChatItem>, byId: Map<Long, ChatItem>): ChatItem? {
    val meta = JSONObject(item.meta)
    val q = meta.optLong("replyTo").takeIf { it > 0 }?.let { byId[it] }?.takeIf { it.kind == "user" } ?: return null
    val between = items.filter { it.id > q.id && it.id < item.id }
    val interrupted = meta.optBoolean("late") || meta.has("branch") || between.any { it.kind == "user" }
    // Quote once: the first message of a late answer carries it, the rest of that answer follows it.
    val alreadyQuoted = between.lastOrNull { it.kind == "agent" || it.kind == "report" || it.kind == "user" }
        ?.let { prev -> prev.kind != "user" && JSONObject(prev.meta).optLong("replyTo") == q.id && JSONObject(prev.meta).optBoolean("late") == meta.optBoolean("late") } == true
    return q.takeIf { interrupted && !alreadyQuoted }
}

/** Chat rows after grouping: consecutive activity lines fold into one "steps" row. */
sealed interface Row_ { val key: String
    data class Single(val item: ChatItem) : Row_ { override val key = "i${item.id}" }
    /** A voice call's back-and-forth, folded into one row so the chat stays one readable thread. */
    data class Call(val items: List<ChatItem>) : Row_ { override val key = "c${items.first().id}" }
    data class Steps(val items: List<ChatItem>) : Row_ { override val key = "s${items.first().id}" }
    /** "New messages" divider: sits above the first thing he has not seen. */
    data class NewMarker(val count: Int) : Row_ { override val key = "new" }
    /** Top of a long history that is only partly loaded. */
    data object Earlier : Row_ { override val key = "earlier" }
}

/** How much of the history is drawn at once, and how much each "Earlier messages" tap adds. */
const val CHAT_PAGE = 120

/** The id of the first message after [readMark] that did not come from him, or null when nothing is unread. */
fun firstUnread(items: List<ChatItem>, readMark: Long): ChatItem? =
    items.firstOrNull { it.id > readMark && it.kind != "user" && !(it.kind == "activity") && !(it.kind == "helper") && !JSONObject(it.meta).has("helper") }

/** Rows with the "new messages" divider put above the row that holds [firstId]. */
fun withMarker(rows: List<Row_>, firstId: Long?, count: Int): List<Row_> {
    if (firstId == null) return rows
    val at = rows.indexOfFirst { r -> when (r) {
        is Row_.Single -> r.item.id >= firstId
        is Row_.Call -> r.items.any { it.id >= firstId }
        is Row_.Steps -> r.items.any { it.id >= firstId }
        else -> false } }
    return if (at < 0) rows else rows.toMutableList().apply { add(at, Row_.NewMarker(count)) }
}

fun group(items: List<ChatItem>): List<Row_> {
    val out = mutableListOf<Row_>(); val buf = mutableListOf<ChatItem>(); val call = mutableListOf<ChatItem>()
    fun flushCall() { if (call.isNotEmpty()) { out += Row_.Call(call.toList()); call.clear() } }
    fun flush() { if (buf.isNotEmpty()) { out += Row_.Steps(buf.toList()); buf.clear() } }
    // A finished helper leaves the chat once the main agent has replied after it (its result lives in that reply).
    // If that reply never came (the turn failed), the helper stays so its result isn't lost.
    val lastAgentAt = items.filter { it.kind == "agent" }.maxOfOrNull { it.createdAt } ?: 0L
    items.forEach {
        val m = JSONObject(it.meta)
        if (m.optBoolean("call") && (it.kind == "user" || it.kind == "agent")) { flush(); call += it; return@forEach }
        flushCall()
        when {
            it.kind == "activity" && m.has("helper") -> {} // shown inside its helper's card
            it.kind == "activity" && m.has("branch") -> {} // shown inside its branch's sheet
            // A helper only shows while it works: once it's done or stopped it leaves the chat; its result lives in the agent's reply.
            it.kind == "helper" && m.optString("state", "working").let { st -> st == "stopped" || (st != "working" && lastAgentAt > m.optLong("endedAt", Long.MAX_VALUE)) } -> {}
            it.kind == "activity" && m.optString("image").isEmpty() && m.optString("file").isEmpty() -> buf += it
            else -> { flush(); out += Row_.Single(it) }
        }
    }
    flush(); flushCall(); return out
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
    /** Newest message id he had seen when he last looked at the chat (Long.MAX_VALUE: first run, nothing is unread). */
    readMark: () -> Long = { Long.MAX_VALUE },
    onRead: (Long) -> Unit = {},
    listening: Boolean = false,
) {
    val byId = remember(items) { items.associateBy { it.id } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The chat opens where he left off: at the first unread message, else at the latest. `baseline` is what he had
    // seen when he came in; it stays put while he reads so the divider does not vanish under his thumb.
    var baseline by remember { mutableLongStateOf(readMark()) }
    // 0: not placed yet, 1: divider decided (rows rebuild), 2: scrolled into place.
    var phase by remember { mutableIntStateOf(0) }
    val positioned = phase == 2
    var markerId by remember { mutableStateOf<Long?>(null) }
    var markerCount by remember { mutableIntStateOf(0) }
    var window by remember { mutableIntStateOf(CHAT_PAGE) }
    // A window onto the history: a very long chat is not all composed at once. The unread divider's message is always inside it.
    val firstShown = remember(items.size, window, markerId) {
        val byWindow = (items.size - window).coerceAtLeast(0)
        val byUnread = markerId?.let { u -> items.indexOfFirst { it.id == u }.let { i -> if (i < 0) byWindow else (i - 8).coerceAtLeast(0) } } ?: byWindow
        minOf(byWindow, byUnread)
    }
    val rows = remember(items, firstShown, markerId, markerCount) {
        val g = withMarker(group(items.drop(firstShown)), markerId, markerCount)
        if (firstShown > 0) listOf<Row_>(Row_.Earlier) + g else g
    }
    /** Tap a quote: jump to the message it quotes (loading earlier history first if it is not on screen). */
    val jumpTo: (Long) -> Unit = { id ->
        val idx = items.indexOfFirst { it.id == id }
        if (idx in 0 until firstShown) window = items.size - idx + 20
        scope.launch {
            snapshotFlow { rows.indexOfFirst { it.key == "i$id" } }.let { f -> kotlinx.coroutines.withTimeoutOrNull(1500) { f.first { it >= 0 } } }?.let { list.animateScrollToItem(it) }
        }
    }
    val atBottom by remember { derivedStateOf { val li = list.layoutInfo; li.totalItemsCount == 0 || (li.visibleItemsInfo.lastOrNull()?.index ?: -1) >= li.totalItemsCount - 1 } }
    // Following the bottom: true while he is at the end, so new messages scroll into view; false once he scrolls up to read.
    var stick by remember { mutableStateOf(true) }
    var seenId by remember { mutableLongStateOf(items.lastOrNull()?.id ?: 0L) }
    val lastItem = items.lastOrNull()
    val lastId = lastItem?.id ?: 0L

    // First open (and every return from the background): land on the first unread, else the latest.
    LaunchedEffect(phase, items.isNotEmpty()) {
        if (items.isEmpty()) return@LaunchedEffect
        when (phase) {
            0 -> { val u = firstUnread(items, baseline); markerId = u?.id; markerCount = if (u == null) 0 else items.count { it.id >= u.id && (it.kind == "agent" || it.kind == "report") }; phase = 1 }
            1 -> { val at = rows.indexOfFirst { it is Row_.NewMarker }
                if (at >= 0) { list.scrollToItem(at); stick = false } else { list.scrollToItem(rows.size - 1); stick = true }
                phase = 2 }
        }
    }
    LaunchedEffect(list) { snapshotFlow { list.isScrollInProgress }.filter { !it }.collect { if (phase == 2) { stick = atBottom; if (atBottom) seenId = lastId } } }
    // Grow with the conversation, but only while he is following it. A message of his own always brings him back down.
    LaunchedEffect(lastId, lastItem?.meta, lastItem?.text?.length) {
        if (phase != 2 || rows.isEmpty()) return@LaunchedEffect
        if (lastItem?.kind == "user") stick = true
        if (stick) { list.animateScrollToItem(rows.size - 1); seenId = lastId }
    }
    // Reading the latest counts as having read it; leaving and coming back re-lands him on what is new.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    val latestId by rememberUpdatedState(lastId)
    val latestItems by rememberUpdatedState(items)
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> resumed = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> resumed = false
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { if (stick) onRead(latestId) }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    // Back from the background: if he was following the end, or something new came in, re-land; if he was reading
                    // older messages and nothing new came, leave him exactly where he was.
                    val m = readMark()
                    if (stick || latestItems.any { it.id > m && it.kind != "user" && it.id > seenId }) { baseline = m; phase = 0 }
                }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(resumed, atBottom, lastId, positioned) { if (resumed && positioned && atBottom && lastId > 0) onRead(lastId) }
    val newSince = items.count { it.id > seenId && it.kind != "user" && it.kind != "activity" && !JSONObject(it.meta).has("helper") }
    val waitingOnYou = items.any { it.kind == "question" && !JSONObject(it.meta).has("answer") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { ChatTopBar(if (agentName == "Your agent") stringResource(R.string.profile_your_agent) else agentName, status, browserLive, waitingOnYou, openCount, actions, modelLabel) },
        bottomBar = { Composer(draft, onDraft, status.working, actions, attachments, docs, listening) },
    ) { pad ->
        if (items.isEmpty() || morning != null) Welcome(userName, Modifier.padding(pad), actions.onSend, morning, hasChat = items.isNotEmpty())
        else LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(rows, key = { it.key }) { r ->
                when (r) {
                    is Row_.Earlier -> TextButton(onClick = { window += CHAT_PAGE }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chat_earlier_messages)) }
                    is Row_.NewMarker -> NewMarker(r.count)
                    is Row_.Steps -> StepsRow(r.items)
                    is Row_.Call -> CallRow(r.items, agentName)
                    is Row_.Single -> when (r.item.kind) {
                        "user" -> UserBubble(r.item)
                        "agent" -> AgentMessage(r.item, quotedFor(r.item, items, byId), jumpTo)
                        "report" -> { val path = JSONObject(r.item.meta).optString("path")
                            Column { quotedFor(r.item, items, byId)?.let { ReplyQuote(it) { jumpTo(it.id) }; Spacer(Modifier.height(8.dp)) }; ReportCard(r.item) { actions.onReport(path) } } }
                        "question" -> when {
                            r.item.text.startsWith("APPROVAL|") -> ApprovalCard(r.item, actions.onAnswer)
                            r.item.text.startsWith(com.past9.phoneaos.tools.ConnectRequest.PREFIX) -> ConnectCard(r.item, actions.onAnswer)
                            else -> QuestionCard(r.item, actions.onAnswer, actions.onBrowser)
                        }
                        "voice" -> VoiceBubble(r.item)
                        "card" -> { val id = JSONObject(r.item.meta).optString("card"); com.past9.phoneaos.ui.CardChatRow(LocalCards.current.firstOrNull { it.id == id }, r.item.text) { actions.onCard(id) } }
                        "branch" -> BranchRow(r.item, items.filter { a -> JSONObject(a.meta).optLong("branch") == r.item.id && (a.kind == "activity" || a.kind == "agent") })
                        "helper" -> HelperRow(r.item, items.filter { a -> a.kind == "activity" && JSONObject(a.meta).optLong("helper") == r.item.id })
                        "activity" -> if (JSONObject(r.item.meta).has("file")) FileCard(r.item) else ImageActivity(r.item)
                        else -> Notice(r.item)
                    }
                }
            }
            if (status.working && !waitingOnYou) item(key = "working") { WorkingRow(status) }
        }
        // Reading older messages while new ones arrive: say so, and offer the way down.
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(!atBottom && items.isNotEmpty() && morning == null, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                ExtendedFloatingActionButton(
                    onClick = { scope.launch { stick = true; list.animateScrollToItem(rows.size); seenId = lastId } },
                    icon = { Icon(Icons.Rounded.KeyboardArrowDown, null) },
                    text = { Text(if (newSince > 0) pluralStringResource(R.plurals.chat_new_count, newSince, newSince) else stringResource(R.string.chat_jump_to_latest)) },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

/** The line above the first message he has not read yet. */
@Composable
private fun NewMarker(count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        Text("  " + (if (count > 1) stringResource(R.string.chat_new_messages_count, count) else stringResource(R.string.chat_new_messages)) + "  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    }
}

@Composable
private fun ChatTopBar(agentName: String, status: AgentStatus, browserLive: Boolean, needsYou: Boolean, openCount: Int, actions: ChatActions, modelLabel: String = "") {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onMenu) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.chat_back_to_home)) }
            Row(Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable(onClick = actions.onProfile).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(working = status.working, needsYou = needsYou, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(agentName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = when {
                    needsYou -> stringResource(R.string.chat_waiting_for_you)
                    status.working -> status.label + if (status.helpers > 0) " · " + pluralStringResource(R.plurals.chat_helpers_count, status.helpers, status.helpers) else ""
                    status.helpers > 0 -> pluralStringResource(R.plurals.chat_helpers_working, status.helpers, status.helpers)
                    else -> stringResource(R.string.chat_ready)
                }
                AnimatedContent(line, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "status") {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (needsYou) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }
            if (modelLabel.isNotBlank()) Surface(onClick = actions.onModel, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(end = 6.dp)) {
                Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(modelLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
                    Icon(Icons.Rounded.ArrowDropDown, stringResource(R.string.chat_switch_model), Modifier.size(20.dp))
                }
            }
            if (actions.onStopSpeaking != null) FilledTonalIconButton(onClick = actions.onStopSpeaking) { Icon(Icons.Rounded.VolumeOff, stringResource(R.string.chat_stop_reading_aloud)) }
            if (actions.onCall != null) IconButton(onClick = actions.onCall) { Icon(Icons.Rounded.Call, stringResource(R.string.chat_call_your_agent)) }
            AnimatedVisibility(browserLive, enter = scaleIn(spring(0.6f, 800f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                FilledTonalIconButton(onClick = actions.onBrowser) { Icon(Icons.Rounded.Language, stringResource(R.string.chat_watch_browser)) }
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
    val greeting = morning?.greeting?.ifBlank { null } ?: if (userName.isNotBlank()) stringResource(R.string.chat_hi_name, userName) else stringResource(R.string.chat_hi_there)
    val line = morning?.line?.ifBlank { null } ?: stringResource(R.string.chat_welcome_line)
    val body = morning?.body?.ifBlank { null } ?: stringResource(R.string.chat_welcome_body)
    val ideas = morning?.ideas?.takeIf { it.isNotEmpty() }?.map { ideaIcon(it.first) to it.second } ?: listOf(
        Icons.Rounded.TravelExplore to stringResource(R.string.chat_idea_contracts),
        Icons.Rounded.EventNote to stringResource(R.string.chat_idea_week),
        Icons.Rounded.Alarm to stringResource(R.string.chat_idea_news),
        Icons.Rounded.Psychology to stringResource(R.string.chat_idea_flights),
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
        Text(if (morning?.preparing == true) stringResource(R.string.chat_try_thinking) else stringResource(R.string.chat_try), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
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
                Icon(Icons.Rounded.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.chat_pick_up))
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
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) stringResource(R.string.chat_pause) else stringResource(R.string.chat_play), tint = if (hasAudio) cs.onPrimary else cs.onSurfaceVariant) }
                Spacer(Modifier.width(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(18) { i ->
                        val h = (8 + ((i * 37) % 17)) * (if (playing) 0.6f + 0.6f * ((wave + i * 0.13f) % 1f) else 1f)
                        Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(50)).background(cs.onPrimaryContainer.copy(alpha = if (playing) 0.9f else 0.5f)))
                    }
                }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = { showText = !showText }) { Text(if (showText) stringResource(R.string.chat_hide) else stringResource(R.string.chat_read_it), color = cs.onPrimaryContainer) }
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
        com.past9.phoneaos.ui.ImageStrip(images.map { com.past9.phoneaos.ui.ChatImage(java.io.File(it).toURI().toString(), stringResource(R.string.chat_your_photo)) })
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
            Text(text.substringBefore("\n\n[Attached document:").ifBlank { stringResource(R.string.chat_heres_a_document) }, Modifier.padding(horizontal = 16.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun AgentMessage(item: ChatItem, quoted: ChatItem? = null, onQuote: (Long) -> Unit = {}) {
    val routine = JSONObject(item.meta).optString("routine")
    Column(Modifier.fillMaxWidth().padding(end = 12.dp, top = 2.dp)) {
        if (quoted != null) { ReplyQuote(quoted) { onQuote(quoted.id) }; Spacer(Modifier.height(8.dp)) }
        if (routine.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.chat_routine_eyebrow, routine.uppercase()), style = Eyebrow, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Markdown(com.past9.phoneaos.voice.Tts.stripEmoji(item.text))
    }
}

/** What this reply answers, like a quoted message on WhatsApp: a bar in the accent, who said it, and the first lines. */
@Composable
fun ReplyQuote(quoted: ChatItem, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerLow, modifier = Modifier.widthIn(max = 340.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(cs.primary))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.Reply, null, Modifier.size(14.dp), tint = cs.primary); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_replying_to_you), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                }
                Text(quoted.text.substringBefore("\n\n[Attached document:").trim().ifBlank { stringResource(R.string.chat_photo_or_document) }, style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A long answer, kept out of the chat: what it is, the takeaway, and one way in. */
@Composable
fun ReportCard(item: ChatItem, onOpen: () -> Unit) {
    val meta = JSONObject(item.meta)
    val cs = MaterialTheme.colorScheme
    val minutes = (meta.optInt("words") / 220).coerceAtLeast(1)
    Surface(onClick = onOpen, shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.Article, null, tint = cs.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_report_eyebrow, minutes), style = Eyebrow, color = cs.primary)
                    Text(meta.optString("title").ifBlank { stringResource(R.string.chat_report) }, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (item.text.isNotBlank()) Text(item.text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(14.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.chat_read_the_report)); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
}

fun iconFor(tool: String): ImageVector = when (tool) {
    "browser" -> Icons.Rounded.Language; "memory" -> Icons.Rounded.Psychology; "tasks" -> Icons.Rounded.TaskAlt
    "apps" -> Icons.Rounded.Apps; "routines" -> Icons.Rounded.Schedule; "web" -> Icons.Rounded.Public
    "machine" -> Icons.Rounded.Dns; "files" -> Icons.Rounded.Description; "agent" -> Icons.Rounded.Replay; "cards" -> Icons.Rounded.Dashboard
    else -> Icons.Rounded.Bolt
}

/** A voice call in the chat: one line ("Voice call · 12 messages · last thing said"), tap for the whole conversation. */
@Composable
private fun CallRow(items: List<ChatItem>, agentName: String) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Surface(onClick = { open = !open }, shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Call, null, Modifier.size(16.dp), tint = cs.onPrimaryContainer)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(pluralStringResource(R.plurals.chat_voice_call_messages, items.size, items.size), style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
                    if (!open) Text(items.last().text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) stringResource(R.string.chat_hide_call) else stringResource(R.string.chat_show_call), tint = cs.onSurfaceVariant)
            }
            if (open) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { i ->
                    val me = i.kind == "user"
                    Text(if (me) stringResource(R.string.chat_you) else agentName, style = MaterialTheme.typography.labelMedium, color = if (me) cs.onSurfaceVariant else cs.primary)
                    Text(i.text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                }
            }
        }
    }
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
            if (items.size > 1) Text(pluralStringResource(R.plurals.chat_steps_count, items.size, items.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            if (items.size > 1) Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) stringResource(R.string.chat_hide_steps) else stringResource(R.string.chat_show_steps), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open && (items.size > 1 || JSONObject(last.meta).has("result")), enter = expandVertically(spring(0.8f, 380f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 38.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconFor(JSONObject(a.meta).optString("tool")), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(8.dp))
                        val by = JSONObject(a.meta).optString("by").takeIf { it.isNotBlank() && it != "main" }
                        Text((by?.let { "$it: " } ?: "") + a.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // A command on a machine: its last lines of output, terminal style.
                    JSONObject(a.meta).optString("result").takeIf { it.isNotBlank() && JSONObject(a.meta).optString("tool") == "machine" }?.let { out ->
                        Surface(shape = RoundedCornerShape(12.dp), color = androidx.compose.ui.graphics.Color(0xFF15161A), modifier = Modifier.fillMaxWidth().padding(start = 22.dp)) {
                            Text(out.trim().lines().takeLast(8).joinToString("\n"), Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = androidx.compose.ui.graphics.Color(0xFFD8F3DC))
                        }
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
        else Text(stringResource(R.string.chat_screenshot_gone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp))
    }
}

/** A helper agent: one calm row in the chat; tap it for the full sheet (brief, live step, steps, result). */
@Composable
private fun HelperRow(item: ChatItem, steps: List<ChatItem>) {
    var open by remember { mutableStateOf(false) }
    val h = com.past9.phoneaos.ui.HelperView(item, steps)
    val onStop = LocalStopHelper.current
    com.past9.phoneaos.ui.HelperCard(h, onClick = { open = true })
    if (open) com.past9.phoneaos.ui.HelperSheet(h, onStop, onDismiss = { open = false })
}

/**
 * A message sent while the agent was busy ran as a branch of it. One quiet line in the chat; tap for the branch's own steps
 * and what it said (a bottom sheet, never a dialog).
 */
@Composable
fun BranchRow(item: ChatItem, steps: List<ChatItem>) {
    var open by remember { mutableStateOf(false) }
    val m = JSONObject(item.meta); val cs = MaterialTheme.colorScheme
    val state = m.optString("state")
    val label = branchStateLabel(state, m.optBoolean("merged"))
    Surface(onClick = { open = true }, shape = RoundedCornerShape(50), color = cs.surfaceContainer, modifier = Modifier.padding(start = 4.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CallSplit, null, Modifier.size(14.dp), tint = if (state == "failed") cs.error else cs.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.chat_branch_row, label, item.text.take(32) + if (item.text.length > 32) "..." else ""), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.chat_branch_eyebrow), style = com.past9.phoneaos.ui.theme.Eyebrow, color = cs.onSurfaceVariant)
            Text(item.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            Text("${relationLabel(m.optString("relation"))} · $label", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
            if (steps.isEmpty()) Text(stringResource(R.string.chat_branch_nothing), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            steps.forEach { a ->
                Text(if (a.kind == "agent") stringResource(R.string.chat_branch_said, a.text) else a.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 5.dp))
            }
            m.optString("error").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

/** "working", "done", "merged"... */
@Composable
fun branchStateLabel(state: String, merged: Boolean) = when {
    merged -> stringResource(R.string.chat_branch_merged)
    state == "running" -> stringResource(R.string.chat_branch_working)
    state == "done" -> stringResource(R.string.chat_branch_done)
    state == "superseded" -> stringResource(R.string.chat_branch_superseded)
    state == "interrupted" -> stringResource(R.string.chat_branch_interrupted)
    state == "failed" -> stringResource(R.string.chat_branch_failed)
    state == "stopped" -> stringResource(R.string.chat_branch_stopped)
    else -> state
}

/** How a branch relates to the work it joined (Relation.word), for its sheet. */
@Composable
private fun relationLabel(word: String) = when (word) {
    "supersede" -> stringResource(R.string.chat_branch_rel_supersede)
    "related" -> stringResource(R.string.chat_branch_rel_related)
    "parallel" -> stringResource(R.string.chat_branch_rel_parallel)
    "ack" -> stringResource(R.string.chat_branch_rel_ack)
    else -> word.replaceFirstChar { it.uppercase() }
}

/** The user's cards, so a card in the chat can show its live headline. */
val LocalCards = androidx.compose.runtime.staticCompositionLocalOf<List<com.past9.phoneaos.cards.Card>> { emptyList() }

/** How a helper row reaches the runtime to stop its helper. */
val LocalStopHelper = androidx.compose.runtime.staticCompositionLocalOf<((Long) -> Unit)?> { null }

@Composable
fun QuestionCard(item: ChatItem, onAnswer: (Long, String) -> Unit, onBrowser: () -> Unit) {
    val meta = JSONObject(item.meta); val answer = meta.optString("answer")
    val opts = meta.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    AppCard(container = if (answer.isEmpty()) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(if (answer.isEmpty()) stringResource(R.string.chat_needs_you) else stringResource(R.string.chat_you_answered), style = Eyebrow, color = if (answer.isEmpty()) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (answer.isEmpty()) WhyLine(meta, MaterialTheme.colorScheme.onTertiaryContainer)
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

/**
 * A helper's request the main agent passed on: its one line on why the user is needed ("Nova: X wants a code
 * sent to your phone"), so a forwarded card never arrives without a reason.
 */
@Composable
private fun WhyLine(meta: JSONObject, color: androidx.compose.ui.graphics.Color) {
    val why = meta.optString("why").takeIf { it.isNotBlank() } ?: return
    Text("${meta.optString("whyBy").ifBlank { stringResource(R.string.chat_your_agent) }}: $why", style = MaterialTheme.typography.bodyMedium, color = color.copy(alpha = 0.85f))
    Spacer(Modifier.height(8.dp))
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
            StatusPill(if (ok) stringResource(R.string.chat_opened_sign_in) else if (answer == "Decline") stringResource(R.string.chat_declined) else stringResource(R.string.chat_answered), if (ok) cs.secondaryContainer else cs.surfaceContainerHigh, if (ok) cs.onSecondaryContainer else cs.onSurfaceVariant)
        }
        return
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLogo(req.name, req.logo, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_connect_an_app), style = Eyebrow, color = cs.primary)
                    Spacer(Modifier.height(2.dp))
                    Text(req.name, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(14.dp))
            WhyLine(JSONObject(item.meta), cs.onSurfaceVariant)
            val why = req.reason.removePrefix("so I can ").removePrefix("So I can ").trim().trimEnd('.', '?')
            Text(if (why.isNotBlank()) stringResource(R.string.chat_connect_why, why) else stringResource(R.string.chat_connect_needs, req.name), style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp).padding(top = 2.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.chat_connect_privacy, req.name), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onAnswer(item.id, "Decline") }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.chat_decline)) }
                Button(onClick = { onAnswer(item.id, "Connect") }, modifier = Modifier.weight(1.4f).heightIn(min = 52.dp), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.chat_connect))
                }
            }
        }
    }
}

/**
 * Nothing goes out in the user's name without this card. It shows EXACTLY what will happen, in words
 * a person reads (a terminal line for commands, labelled fields for app actions, never raw JSON), and
 * Approve is press-and-hold so a stray tap can never send an email or spend money. Once answered it
 * folds to one line; tap it to see what was approved.
 */
@Composable
fun ApprovalCard(item: ChatItem, onAnswer: (Long, String) -> Unit) {
    val parts = item.text.split("|", limit = 3)
    val action = parts.getOrElse(1) { "" }.replaceFirstChar { it.uppercase() }; val details = parts.getOrElse(2) { "" }
    val answer = JSONObject(item.meta).optString("answer")
    val pending = answer.isEmpty()
    val cs = MaterialTheme.colorScheme
    if (!pending) {
        var open by remember { mutableStateOf(false) }
        val ok = answer == "Approve"
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { open = !open }.padding(vertical = 4.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).background(if (ok) LocalExtra.current.successContainer else cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(16.dp), tint = if (ok) LocalExtra.current.success else cs.onSurfaceVariant)
                }
                Spacer(Modifier.width(10.dp))
                Text(action, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (ok) stringResource(R.string.chat_approved) else if (answer == "Decline") stringResource(R.string.chat_declined) else stringResource(R.string.chat_answered), style = MaterialTheme.typography.labelMedium, color = if (ok) LocalExtra.current.success else cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) stringResource(R.string.chat_hide) else stringResource(R.string.chat_show_approved), tint = cs.onSurfaceVariant)
            }
            if (open) Box(Modifier.padding(start = 38.dp, top = 8.dp)) { ApprovalDetails(details) }
        }
        return
    }
    AppCard(container = cs.tertiaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (details.startsWith("$ ")) Icons.Rounded.Terminal else Icons.Rounded.Shield, null, tint = cs.onTertiaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_approve_before), style = Eyebrow, color = cs.onTertiaryContainer)
        }
        Spacer(Modifier.height(6.dp))
        WhyLine(JSONObject(item.meta), cs.onTertiaryContainer)
        Text(action, style = MaterialTheme.typography.titleLarge, color = cs.onTertiaryContainer)
        Spacer(Modifier.height(12.dp))
        ApprovalDetails(details)
        Spacer(Modifier.height(14.dp))
        HoldToApprove { onAnswer(item.id, "Approve") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { onAnswer(item.id, "Decline") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.chat_decline), color = cs.onTertiaryContainer)
        }
    }
}

/** What an approval will do: a shell line, an app action's fields, or plain words. */
@Composable
private fun ApprovalDetails(details: String) {
    val cs = MaterialTheme.colorScheme
    val json = remember(details) { runCatching { JSONObject(details) }.getOrNull() }
    when {
        details.startsWith("$ ") -> Surface(shape = MaterialTheme.shapes.medium, color = androidx.compose.ui.graphics.Color(0xFF15161A), modifier = Modifier.fillMaxWidth()) {
            Text(details, Modifier.horizontalScroll(rememberScrollState()).padding(14.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = androidx.compose.ui.graphics.Color(0xFFD8F3DC))
        }
        json != null -> Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                readableFields(json).forEach { (k, v) ->
                    if (k.startsWith("#")) Text(k.removePrefix("#"), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                    else Column {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        else -> Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
            Text(details, Modifier.padding(14.dp).heightIn(max = 260.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        }
    }
}

/** App-action JSON as label/value pairs a person can read: "to" -> "To", nested tool calls become headed groups. "#" marks a heading. */
@Composable
fun readableFields(o: JSONObject): List<Pair<String, String>> {
    val none = stringResource(R.string.chat_field_none); val yes = stringResource(R.string.chat_field_yes); val no = stringResource(R.string.chat_field_no)
    val actionWord = stringResource(R.string.chat_field_action); val accountWord = stringResource(R.string.chat_field_account)
    fun label(k: String) = k.replace('_', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }
    fun value(v: Any?): String = when (v) {
        null, JSONObject.NULL -> none
        is org.json.JSONArray -> (0 until v.length()).joinToString(", ") { value(v.opt(it)) }
        is JSONObject -> v.keys().asSequence().joinToString(" · ") { "${label(it)}: ${value(v.opt(it))}" }
        is Boolean -> if (v) yes else no
        else -> v.toString()
    }
    val out = mutableListOf<Pair<String, String>>()
    val tools = o.optJSONArray("tools")
    if (tools != null) {
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            out += ("#" + label(t.optString("tool_slug").substringAfter('_')).ifBlank { actionWord } + (t.optString("tool_slug").substringBefore('_').takeIf { it.isNotBlank() }?.let { " · ${label(it)}" } ?: "")) to ""
            t.optString("account").takeIf { it.isNotBlank() }?.let { out += accountWord to it }
            t.optJSONObject("arguments")?.let { a -> a.keys().forEach { k -> out += label(k) to value(a.opt(k)) } }
        }
        return out
    }
    o.keys().forEach { k -> if (k != "thought") out += label(k) to value(o.opt(k)) }
    return out
}

/** A file the agent handed over (e.g. from one of your machines): what it is, and Open or Save it to Downloads. */
@Composable
private fun FileCard(item: ChatItem) {
    val meta = JSONObject(item.meta)
    val file = java.io.File(meta.optString("file"))
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cs = MaterialTheme.colorScheme
    var saved by remember { mutableStateOf<String?>(null) }
    val savedMsg = stringResource(R.string.chat_saved_to_downloads)
    val ext = file.extension.lowercase()
    val icon = when (ext) {
        "pdf" -> Icons.Rounded.PictureAsPdf; "png", "jpg", "jpeg", "webp", "gif" -> Icons.Rounded.Image; "mp4", "mov", "mkv" -> Icons.Rounded.Movie
        "mp3", "wav", "m4a" -> Icons.Rounded.AudioFile; "zip", "tar", "gz", "7z" -> Icons.Rounded.FolderZip; "apk" -> Icons.Rounded.Android
        else -> Icons.Rounded.Description
    }
    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) { Icon(icon, null, tint = cs.onPrimaryContainer) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(humanSize(file.length()), meta.optString("from").takeIf { it.isNotBlank() }?.let { stringResource(R.string.chat_file_from, it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            if (!file.exists()) { Text(stringResource(R.string.chat_file_gone), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp)); return@Column }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    saved = runCatching { saveToDownloads(ctx, file, mime) }.fold({ savedMsg }, { ctx.getString(R.string.chat_couldnt_save, it.message) })
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                    Icon(if (saved == savedMsg) Icons.Rounded.Check else Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (saved == savedMsg) stringResource(R.string.chat_saved) else stringResource(R.string.chat_save))
                }
                Button(onClick = {
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), file.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { saved = ctx.getString(R.string.chat_no_app_opens, ext) }
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.chat_open))
                }
            }
            saved?.takeIf { it != savedMsg }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

private fun humanSize(b: Long) = when { b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0); b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0); b >= 1024 -> "${b / 1024} KB"; else -> "$b B" }

private fun saveToDownloads(ctx: android.content.Context, file: java.io.File, mime: String) {
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name); put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
    }
    val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no Downloads folder")
    ctx.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
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
            Text(if (holding) stringResource(R.string.chat_keep_holding) else stringResource(R.string.chat_hold_to_approve), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
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
        Text(status.label.ifBlank { stringResource(R.string.chat_working) } + "…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, working: Boolean, actions: ChatActions, attachments: List<String>, docs: List<String> = emptyList(), listening: Boolean = false) {
    var attachMenu by remember { mutableStateOf(false) }
    if (attachMenu) ModalBottomSheet(onDismissRequest = { attachMenu = false }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            listOf(Triple(Icons.Rounded.Image, stringResource(R.string.chat_attach_photo), stringResource(R.string.chat_attach_photo_sub)) to actions.onAttach, Triple(Icons.Rounded.Description, stringResource(R.string.chat_attach_document), stringResource(R.string.chat_attach_document_sub)) to actions.onAttachDoc).forEach { (t, act) ->
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
                    leadingIcon = { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)) }, trailingIcon = { Icon(Icons.Rounded.Close, stringResource(R.string.chat_remove), Modifier.size(16.dp)) }, shape = RoundedCornerShape(50))
            }
        }
        if (attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.forEach { path ->
                Box {
                    coil.compose.AsyncImage(java.io.File(path), stringResource(R.string.chat_photo_to_send), Modifier.size(72.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
                    Surface(onClick = { actions.onRemoveAttachment(path) }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.chat_remove_photo), Modifier.padding(4.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp)) {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Rounded.AttachFile, stringResource(R.string.chat_attach), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Box(Modifier.weight(1f).padding(vertical = 16.dp)) {
                        if (draft.isEmpty()) Text(stringResource(R.string.chat_ask_for_anything), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BasicTextField(draft, onDraft, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 6, modifier = Modifier.fillMaxWidth())
                    }
                    IconButton(onClick = actions.onMic) { Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, if (listening) stringResource(R.string.chat_stop_listening) else stringResource(R.string.chat_speak), tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
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
                        voiceMode -> Icon(Icons.Rounded.Headphones, stringResource(R.string.chat_send_voice), tint = tint)
                        working && !canSend -> Icon(Icons.Rounded.Stop, stringResource(R.string.chat_stop), tint = tint)
                        else -> Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.chat_send), tint = tint)
                    }
                }
            }
        }
      }
    }
}
