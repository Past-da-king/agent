package com.past9.phoneaos.ui.screens

import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.voice.noteSeek
import com.past9.phoneaos.voice.noteTime
import com.past9.phoneaos.voice.noteSpokenTime

@Composable
internal fun NoteWaveform(duration: Int, position: Int, started: Boolean, onSeek: (Int) -> Unit, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f)
    var preview by remember { mutableStateOf<Int?>(null) }
    val shown = preview ?: position
    val latestSeek by rememberUpdatedState(onSeek)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics {
                contentDescription = "Voice note position"
                stateDescription = "${noteSpokenTime(shown)} of ${noteSpokenTime(duration)}"
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..duration.coerceAtLeast(1).toFloat())
                setProgress { value ->
                    if (duration > 0 && value.isFinite()) { latestSeek(value.toInt().coerceIn(0, duration)); true } else false
                }
            }
            .pointerInput(duration, rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (duration <= 0) return@awaitEachGesture
                    preview = noteSeek(down.position.x, size.width.toFloat(), duration, rtl)
                    down.consume()
                    var released = false
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            preview = noteSeek(change.position.x, size.width.toFloat(), duration, rtl)
                            change.consume()
                            if (!change.pressed) { released = true; break }
                        } while (true)
                        if (released) latestSeek(preview ?: position)
                    } finally { preview = null }
                }
            }) {
            val count = 28
            val fraction = if (duration > 0) shown.toFloat() / duration else 0f
            repeat(count) { i ->
                val logical = (i + 0.5f) / count
                val x = (if (rtl) 1f - logical else logical) * size.width
                val height = (8 + (i * 37 % 17)).dp.toPx()
                drawLine(if (logical <= fraction) accent else muted,
                    Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2),
                    strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        Text(if (duration <= 0) "Loading audio" else if (started || preview != null) "${noteTime(shown)} / ${noteTime(duration)}" else noteTime(duration),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
internal fun VoiceNoteControls(
    playing: Boolean, duration: Int, position: Int, started: Boolean, showText: Boolean,
    onPlay: () -> Unit, onSeek: (Int) -> Unit, onRead: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(com.past9.phoneaos.ui.theme.Radius.lg),
        color = cs.primaryContainer
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            com.past9.phoneaos.ui.MorphIconButton(onClick = onPlay, size = 48.dp, container = cs.primary) {
                androidx.compose.material3.Icon(
                    if (playing) androidx.compose.material.icons.Icons.Rounded.Pause else androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                    if (playing) "Pause voice note" else "Play voice note", tint = cs.onPrimary)
            }
            Spacer(Modifier.width(10.dp))
            NoteWaveform(duration, position, started, onSeek, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            androidx.compose.material3.TextButton(onClick = onRead) {
                Text(androidx.compose.ui.res.stringResource(if (showText) com.past9.phoneaos.R.string.chat_hide else com.past9.phoneaos.R.string.chat_read_it), color = cs.onPrimaryContainer)
            }
        }
    }
}
