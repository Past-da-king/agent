package com.past9.phoneaos.share

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.past9.phoneaos.App
import com.past9.phoneaos.MainActivity
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.AgentAvatar
import com.past9.phoneaos.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The share sheet: a light, see-through screen over the app the user shared from. It copies what was shared
 * out of its content:// uris at once (the read grant ends with this screen), shows a preview, and sends it to
 * the agent as a normal chat message, with or without a note. It never opens the full app.
 */
class ShareActivity : ComponentActivity() {
    private val items = mutableStateListOf<Prepared>()
    private var copying by mutableStateOf(true)
    private var failed by mutableStateOf(0)
    private var phase by mutableStateOf(Phase.EDIT)
    private var problem by mutableStateOf<String?>(null)
    private var prep: Job? = null
    private lateinit var input: ShareInput
    private var player: MediaPlayer? = null
    private var playingPath by mutableStateOf<String?>(null)

    private enum class Phase { EDIT, SENDING, SENT }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        input = ShareStore.parse(intent)
        // Theme and name straight from the plain settings file: no database, no secrets, nothing slow.
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val accent = prefs.getString("accent", "iris") ?: "iris"
        val mascot = prefs.getString("mascot", "scout") ?: "scout"
        val name = prefs.getString("agentName", null)?.takeIf { it.isNotBlank() } ?: "your agent"
        val onboarded = prefs.getBoolean("onboarded", false)
        val stamp = System.currentTimeMillis()
        // Copy off the main thread, starting now; the sheet is already on screen while this runs.
        prep = lifecycleScope.launch(Dispatchers.IO) {
            input.streams.forEachIndexed { n, u ->
                val p = runCatching { ShareStore.import(this@ShareActivity, u, n, stamp) }.getOrNull()
                withContext(Dispatchers.Main) { if (p != null) items += p else failed++ }
            }
            withContext(Dispatchers.Main) { copying = false }
        }
        setContent {
            AppTheme(accent = accent, mascot = mascot) {
                Sheet(name = name, onboarded = onboarded)
            }
        }
    }

    override fun onStop() { super.onStop(); stopPlayback() }
    override fun onDestroy() { super.onDestroy(); stopPlayback() }

    private fun stopPlayback() { runCatching { player?.release() }; player = null; playingPath = null }

    private fun togglePlay(path: String) {
        if (playingPath == path) { stopPlayback(); return }
        stopPlayback()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(path); setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ -> stopPlayback(); true }
                prepare(); start()
            }
            playingPath = path
        }.onFailure { stopPlayback() }
    }

    private fun send(note: String) {
        if (phase != Phase.EDIT) return
        phase = Phase.SENDING; problem = null
        lifecycleScope.launch {
            val since = System.currentTimeMillis()
            runCatching {
                prep?.join()
                val metered = withContext(Dispatchers.IO) { ShareStore.isMetered(this@ShareActivity) }
                val files = items.filter { it.kind != ShareKind.IMAGE }.map { it.asFile() }
                val images = items.filter { it.kind == ShareKind.IMAGE }.map { it.path }
                if (files.isEmpty() && images.isEmpty() && input.text.isNullOrBlank() && note.isBlank()) {
                    phase = Phase.EDIT; problem = "Nothing could be read from what you shared."; return@launch
                }
                val msg = ShareLogic.compose(note, input.text, input.subject, files, images, metered)
                val g = withContext(Dispatchers.Default) { App.graph(this@ShareActivity) }
                ShareLogic.holdNotice(files, metered)?.let { n -> g.db.chat().insert(ChatItem(kind = "notice", text = n)) }
                g.runtime.send(msg.text, msg.images, msg.fileNames)
                // Wait (briefly) until the message is really stored, so it can't be lost when this screen closes.
                var tries = 0
                while (tries++ < 40 && g.db.chat().userSince(since) == 0) delay(75)
            }.onFailure { phase = Phase.EDIT; problem = "That did not send. Try again."; return@launch }
            phase = Phase.SENT
            delay(850)
            finish()
        }
    }

    // ---- UI ---------------------------------------------------------------------------------

    @Composable
    private fun Sheet(name: String, onboarded: Boolean) {
        val cs = MaterialTheme.colorScheme
        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
        var dragY by remember { mutableStateOf(0f) }
        var noteOpen by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        fun dismiss() { if (phase != Phase.SENDING) finish() }
        BackHandler { if (noteOpen && phase == Phase.EDIT) noteOpen = false else dismiss() }
        LaunchedEffect(noteOpen) { if (noteOpen) { delay(60); runCatching { focus.requestFocus() }; keyboard?.show() } }
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.42f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() })
            }
            AnimatedVisibility(visible, modifier = Modifier.align(Alignment.BottomCenter), enter = slideInVertically(tween(260)) { it } + fadeIn(tween(120)),
                exit = slideOutVertically(tween(160)) { it }) {
                Surface(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(0, dragY.toInt()) }
                        .draggable(orientation = Orientation.Vertical, state = rememberDraggableState { dy -> dragY = (dragY + dy).coerceAtLeast(0f) },
                            onDragStopped = { if (dragY > 220f) dismiss() else dragY = 0f }),
                    shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = cs.surfaceContainerLow, tonalElevation = 3.dp,
                ) {
                    Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 16.dp)) {
                        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(CircleShape).background(cs.outlineVariant))
                        Spacer(Modifier.height(14.dp))
                        when (phase) {
                            Phase.SENT -> Sent(name)
                            else -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AgentAvatar(working = phase == Phase.SENDING, size = 44.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text("Send to $name", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
                                        Text(summary(), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                Preview()
                                Spacer(Modifier.height(16.dp))
                                if (!onboarded) {
                                    Text("Finish setting up your agent first, then share again.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                                    Spacer(Modifier.height(12.dp))
                                    Button({ startActivity(Intent(this@ShareActivity, MainActivity::class.java)); finish() }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Open the app") }
                                } else {
                                    AnimatedVisibility(noteOpen) {
                                        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().padding(bottom = 12.dp).focusRequester(focus),
                                            placeholder = { Text("Add a message") }, minLines = 1, maxLines = 5, shape = RoundedCornerShape(20.dp),
                                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), enabled = phase == Phase.EDIT)
                                    }
                                    problem?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp)) }
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (!noteOpen) OutlinedButton({ noteOpen = true }, Modifier.weight(1f).heightIn(min = 52.dp), enabled = phase == Phase.EDIT) { Text("Add a message") }
                                        Button({ send(note) }, Modifier.weight(1f).heightIn(min = 52.dp), enabled = phase == Phase.EDIT) {
                                            if (phase == Phase.SENDING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onSurfaceVariant) else Text("Send")
                                        }
                                    }
                                    if (copying && items.isNotEmpty()) Text("Still getting a file ready. It sends as soon as it is.", style = MaterialTheme.typography.labelMedium,
                                        color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun summary(): String {
        val kinds = items.map { it.kind }
        val links = ShareLogic.links(input.text)
        return when {
            kinds.isNotEmpty() -> kinds.groupBy { it }.entries.joinToString(", ") { (k, v) -> if (v.size == 1) ShareLogic.label(k).replaceFirstChar { it.uppercase() } else "${v.size} ${ShareLogic.label(k, true)}" }
            copying && input.streams.isNotEmpty() -> "Getting it ready"
            links.isNotEmpty() -> ShareLogic.label(ShareKind.LINK).replaceFirstChar { it.uppercase() }
            else -> "Some text"
        }
    }

    @Composable
    private fun Sent(name: String) {
        val cs = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = cs.onPrimaryContainer) }
            Spacer(Modifier.width(12.dp))
            Text("Sent to $name", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
        }
    }

    @Composable
    private fun Preview() {
        val cs = MaterialTheme.colorScheme
        val text = input.text?.trim().orEmpty()
        val links = ShareLogic.links(text)
        val images = items.filter { it.kind == ShareKind.IMAGE }
        val others = items.filter { it.kind != ShareKind.IMAGE }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (links.isNotEmpty()) links.take(2).forEach { LinkCard(it, input.subject) }
            else if (text.isNotEmpty()) Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.TextSnippet, null, tint = cs.primary); Spacer(Modifier.width(12.dp))
                    Text(text, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                }
            }
            if (images.isNotEmpty()) ImageRow(images)
            others.forEach { p ->
                when (p.kind) {
                    ShareKind.VIDEO -> VideoCard(p)
                    ShareKind.AUDIO -> AudioCard(p)
                    else -> FileCard(p)
                }
            }
            if (copying && items.isEmpty() && input.streams.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            if (failed > 0) Text(if (failed == 1) "One item could not be read." else "$failed items could not be read.", style = MaterialTheme.typography.labelMedium, color = cs.error)
        }
    }

    @Composable
    private fun LinkCard(link: String, title: String?) {
        val cs = MaterialTheme.colorScheme
        Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh) {
            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Link, null, tint = cs.onPrimaryContainer) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ShareLogic.domain(link), style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(title?.takeIf { it.isNotBlank() } ?: link, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun ImageRow(images: List<Prepared>) {
        val single = images.size == 1
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            images.take(4).forEachIndexed { n, p ->
                Box(Modifier.then(if (single) Modifier.fillMaxWidth().height(200.dp) else Modifier.weight(1f).aspectRatio(1f)).clip(RoundedCornerShape(20.dp))) {
                    AsyncImage(File(p.path), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (n == 3 && images.size > 4) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        Text("+${images.size - 3}", style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        }
    }

    @Composable
    private fun VideoCard(p: Prepared) {
        val cs = MaterialTheme.colorScheme
        var thumb by remember(p.path) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(p.path) { thumb = withContext(Dispatchers.IO) { ShareStore.videoThumb(p.path) } }
        Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.size(52.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(30.dp))
            }
            Surface(Modifier.align(Alignment.BottomEnd).padding(10.dp), shape = CircleShape, color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)) {
                Text(listOfNotNull(if (p.durationMs > 0) ShareLogic.formatDuration(p.durationMs) else null, ShareLogic.formatSize(p.size)).joinToString("  "),
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }

    @Composable
    private fun AudioCard(p: Prepared) {
        val cs = MaterialTheme.colorScheme
        val playing = playingPath == p.path
        Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton({ togglePlay(p.path) }, Modifier.size(48.dp)) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
                Spacer(Modifier.width(12.dp))
                val phase by rememberInfinitePhase(playing)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(26) { i ->
                        val base = 6 + ((i * 37 + p.name.length * 11) % 19)
                        val h = base * (if (playing) 0.55f + 0.7f * ((phase + i * 0.11f) % 1f) else 1f)
                        Box(Modifier.width(3.dp).height(h.dp).clip(CircleShape).background(cs.primary.copy(alpha = if (playing) 0.95f else 0.55f)))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(if (p.durationMs > 0) ShareLogic.formatDuration(p.durationMs) else ShareLogic.formatSize(p.size), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun rememberInfinitePhase(on: Boolean): State<Float> {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "bars")
        val v = t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "p")
        return if (on) v else remember { mutableStateOf(0f) }
    }

    @Composable
    private fun FileCard(p: Prepared) {
        val cs = MaterialTheme.colorScheme
        val icon: ImageVector = when (p.kind) { ShareKind.PDF -> Icons.Rounded.PictureAsPdf; ShareKind.VIDEO -> Icons.Rounded.Movie; ShareKind.AUDIO -> Icons.Rounded.GraphicEq
            ShareKind.TEXT -> Icons.Rounded.Description; else -> Icons.Rounded.InsertDriveFile }
        Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh) {
            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) { Icon(icon, null, tint = cs.onPrimaryContainer) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ShareLogic.formatSize(p.size) + "  " + (p.mime.substringAfter('/').uppercase().take(12)), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
