package com.past9.phoneaos.ui

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.RemoteSession
import com.past9.phoneaos.remote.ShownFile
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/**
 * A video, picture or sound an agent showed on the computer, in this chat (0.15.27).
 *
 * The row carries what the computer said about the file (its kind, name, type and size, and the token it is shown
 * by). Nothing is fetched until it is needed: a picture when the row is drawn, a video when he presses play, and a
 * video then asks the computer for the range it is about to show, so a film starts at once. While it comes, a quiet
 * placeholder; when the computer cannot answer, one plain line and the reason, never a dead player.
 *
 * A picture opens full screen on a tap (pinch to zoom, closed by the back gesture or the close button); a video gets
 * its own player with play, pause, scrub and full screen. Both full screen views are full-window, never a centred
 * pop-up: on the phone everything that opens opens into the whole screen or slides up from the bottom.
 */
@Composable
fun MediaChatRow(item: ChatItem, rs: RemoteSession?) {
    val meta = remember(item.meta) { runCatching { JSONObject(item.meta) }.getOrDefault(JSONObject()) }
    val info = remember(meta, rs) { if (rs == null) null else rs.shownFromMeta(meta) }
    val name = info?.name ?: meta.optString("name").ifBlank { "A file" }
    val caption = meta.optString("caption").ifBlank { if (item.text == name) "" else item.text }
    var state by remember(info?.token) { mutableStateOf<ShownState>(ShownState.Waiting) }
    var tapped by remember(info?.token) { mutableStateOf(false) }

    if (info == null) {
        MediaLine(name, "The computer did not say what this file is.")
        return
    }
    if (rs == null) {
        MediaLine(name, "Link to your computer to play this.")
        return
    }
    val metered = rs.metered
    val waitingForTap = info.kind == "image" && metered && !tapped

    LaunchedEffect(info.token, tapped) {
        if (waitingForTap) return@LaunchedEffect
        state = ShownState.Working
        state = if (info.kind == "image") {
            val file = rs.shownWhole(info)
            if (file == null) ShownState.Failed(problem(rs)) else ShownState.Picture(file)
        } else {
            val url = rs.shownUrl(info)
            if (url == null) ShownState.Failed(problem(rs)) else ShownState.Playable(url)
        }
    }

    when (val now = state) {
        is ShownState.Waiting, is ShownState.Working -> if (waitingForTap) {
            MediaPlaceholder(info, caption, tappable = true) { tapped = true }
        } else {
            MediaPlaceholder(info, caption, tappable = false) {}
        }
        is ShownState.Failed -> MediaLine(name, now.why)
        is ShownState.Picture -> PictureCard(now.file, caption)
        is ShownState.Playable -> when (info.kind) {
            "audio" -> SoundCard(now.url, caption.ifBlank { name })
            else -> VideoCard(now.url, caption)
        }
    }
}

/** What a row is doing right now. */
private sealed interface ShownState {
    data object Waiting : ShownState
    data object Working : ShownState
    data class Picture(val file: File) : ShownState
    data class Playable(val url: String) : ShownState
    data class Failed(val why: String) : ShownState
}

private fun problem(rs: RemoteSession): String =
    rs.mediaProblem.ifBlank { "The computer could not send this file. Tap it to try again." }

/** A quiet card while the file comes: a plain paperclip is not enough to say what is arriving. */
@Composable
private fun MediaPlaceholder(info: ShownFile, caption: String, tappable: Boolean, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().let { m -> if (tappable) m.clickable(onClick = onTap) else m },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).background(cs.surfaceContainer), contentAlignment = Alignment.Center) {
                if (tappable) Icon(mediaIcon(info.kind), null, tint = cs.onSurfaceVariant) else LoadingIndicator(Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        tappable -> "On your computer. Tap to load it on mobile data."
                        info.kind == "image" -> "Getting the picture..."
                        else -> mediaSize(info.size) + " on your computer"
                    },
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2,
                )
            }
        }
    }
    if (caption.isNotBlank()) Caption(caption)
}

/** One plain line when the file cannot be shown: what it was, and why. Never a dead player. */
@Composable
private fun MediaLine(name: String, why: String) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BrokenImage, null, tint = cs.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(why, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    if (text.isBlank()) return
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
}

@Composable
private fun PictureCard(file: File, caption: String) {
    var open by remember(file.path) { mutableStateOf(false) }
    SubcomposeAsyncImage(
        model = file,
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.heightIn(max = 340.dp).clip(MaterialTheme.shapes.large).clickable { open = true },
        loading = { Box(Modifier.size(220.dp, 160.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) } },
        error = { MediaLine(file.name, "This picture could not be drawn.") },
    )
    Caption(caption)
    if (open) FullPicture(file) { open = false }
}

/** Full screen, pinch to zoom, drag to pan, close button and the back gesture. */
@Composable
private fun FullPicture(file: File, onClose: () -> Unit) {
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) { // design: full-screen picture viewer, not a centred pop-up
        Box(Modifier.fillMaxSize().background(com.past9.phoneaos.ui.theme.Media.stage)) {
            SubcomposeAsyncImage(
                model = file, contentDescription = file.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, by, factor, _ ->
                        zoom = (zoom * factor).coerceIn(1f, 5f)
                        pan = if (zoom == 1f) Offset.Zero else pan + by
                    }
                }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
            )
            FilledTonalIconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
                Icon(Icons.Rounded.Close, "Close")
            }
        }
    }
}

/** A video with its own player: play, pause, scrub, and full screen. The bytes come from the computer as it plays. */
@Composable
private fun VideoCard(url: String, caption: String) {
    var view by remember(url) { mutableStateOf<VideoView?>(null) }
    var fullView by remember(url) { mutableStateOf<VideoView?>(null) }
    var resumeAt by remember(url) { mutableLongStateOf(0L) }
    var full by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) {
        MediaLine("Video", "The computer stopped sending this video. Open it again in the chat.")
        return
    }
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(com.past9.phoneaos.ui.theme.Media.stage)) {
        VideoStage(url, Modifier.fillMaxWidth().height(230.dp), resumeAt, false, { view = it }) { failed = true }
        FilledTonalIconButton(
            onClick = { resumeAt = (view?.currentPosition ?: 0).toLong(); full = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) { Icon(Icons.Rounded.Fullscreen, "Full screen") }
    }
    Caption(caption)
    if (full) Dialog( // design: full-screen video, not a centred pop-up
        onDismissRequest = { resumeAt = (fullView?.currentPosition ?: 0).toLong(); full = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(com.past9.phoneaos.ui.theme.Media.stage)) {
            VideoStage(url, Modifier.fillMaxSize(), resumeAt, true, { fullView = it }) { failed = true }
            FilledTonalIconButton(
                onClick = { resumeAt = (fullView?.currentPosition ?: 0).toLong(); full = false },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            ) { Icon(Icons.Rounded.Close, "Close") }
        }
    }
}

/**
 * The player itself. A VideoView drawn first, so it shows the first frame of the range it gets as soon as that
 * range arrives; the controller underneath is the phone's own scrub bar (play, pause, drag).
 */
@Composable
private fun VideoStage(url: String, modifier: Modifier, startAt: Long, autoPlay: Boolean, onView: (VideoView?) -> Unit, onFailed: () -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(Uri.parse(url))
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                setOnPreparedListener { prepared ->
                    if (startAt > 0) seekTo(startAt.toInt())
                    if (autoPlay) start()
                    controller.show()
                }
                setOnErrorListener { _, _, _ -> onFailed(); true }
            }.also { onView(it) }
        },
        onRelease = { runCatching { it.stopPlayback() }; onView(null) },
    )
}

/** A sound an agent showed: play and pause, and how far along it is. */
@Composable
private fun SoundCard(url: String, label: String) {
    var playing by remember(url) { mutableStateOf(false) }
    var position by remember(url) { mutableLongStateOf(0L) }
    var duration by remember(url) { mutableLongStateOf(0L) }
    var failed by remember(url) { mutableStateOf(false) }
    val player = remember(url) { android.media.MediaPlayer() }
    DisposableEffect(url) {
        onDispose { runCatching { player.release() } }
    }
    LaunchedEffect(url) {
        runCatching {
            player.setDataSource(url)
            player.setOnPreparedListener { duration = it.duration.toLong(); playing = true; it.start() }
            player.setOnCompletionListener { playing = false; position = 0L }
            player.setOnErrorListener { _, _, _ -> failed = true; true }
            player.prepareAsync()
        }.onFailure { failed = true }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            delay(250)
        }
    }
    if (failed) {
        MediaLine(label, "The computer stopped sending this sound.")
        return
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = {
                    if (playing) { runCatching { player.pause() }; playing = false } else { runCatching { player.start() }; playing = true }
                }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(mediaClock(position) + " of " + mediaClock(duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun mediaClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return minutes.toString() + ":" + seconds.toString().padStart(2, '0')
}

internal fun mediaSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> (bytes / (1024L * 1024L * 1024L)).toString() + " GB"
    bytes >= 1024L * 1024L -> (bytes / (1024L * 1024L)).toString() + " MB"
    bytes >= 1024L -> (bytes / 1024L).toString() + " KB"
    else -> bytes.toString() + " bytes"
}

private fun mediaIcon(kind: String) = when (kind) {
    "image" -> Icons.Rounded.Image
    "audio" -> Icons.Rounded.AudioFile
    else -> Icons.Rounded.Movie
}
