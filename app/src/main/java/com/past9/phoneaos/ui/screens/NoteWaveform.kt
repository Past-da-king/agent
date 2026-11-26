package com.past9.phoneaos.ui.screens

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.R
import com.past9.phoneaos.voice.noteSeek
import com.past9.phoneaos.voice.noteTime
import com.past9.phoneaos.voice.noteTimeParts
import java.util.Locale

/** Android ICU supplies CLDR plural rules and translated units for the current app locale. */
internal fun noteSpokenTime(ms: Int, locale: Locale): String {
    val (minutes, seconds) = noteTimeParts(ms)
    val formatter = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
    return if (minutes == 0) formatter.formatMeasures(Measure(seconds, MeasureUnit.SECOND))
    else formatter.formatMeasures(Measure(minutes, MeasureUnit.MINUTE), Measure(seconds, MeasureUnit.SECOND))
}

@Composable
internal fun NoteWaveform(duration: Int, position: () -> Int, started: Boolean, onSeek: (Int) -> Unit,
                          modifier: Modifier = Modifier, failed: Boolean = false) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val locale = LocalConfiguration.current.locales[0]
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f)
    var preview by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(duration, rtl) { preview = null }
    val shown = preview ?: position()
    val latestSeek by rememberUpdatedState(onSeek)
    val positionLabel = stringResource(R.string.note_position)
    val spokenCurrent = remember(shown / 1000, locale) { noteSpokenTime(shown, locale) }
    val spokenTotal = remember(duration / 1000, locale) { noteSpokenTime(duration, locale) }
    val spokenPosition = if (duration > 0) stringResource(R.string.note_progress, spokenCurrent, spokenTotal)
        else if (failed) "" else stringResource(R.string.chat_getting_audio)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(48.dp)
            .semantics {
                contentDescription = positionLabel
                stateDescription = spokenPosition
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..duration.coerceAtLeast(1).toFloat())
                setProgress { value ->
                    if (duration > 0 && value.isFinite()) { latestSeek(value.toInt().coerceIn(0, duration)); true } else false
                }
            }
            .pointerInput(duration, rtl) {
                if (duration <= 0) return@pointerInput
                detectTapGestures { point -> latestSeek(noteSeek(point.x, size.width.toFloat(), duration, rtl)) }
            }
            .pointerInput(duration, rtl) {
                if (duration <= 0) return@pointerInput
                try {
                    detectHorizontalDragGestures(
                        onDragStart = { point -> preview = noteSeek(point.x, size.width.toFloat(), duration, rtl) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            preview = noteSeek(change.position.x, size.width.toFloat(), duration, rtl)
                        },
                        onDragEnd = { preview?.let { latestSeek(it) }; preview = null },
                        onDragCancel = { preview = null }
                    )
                } finally { preview = null }
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
        Text(if (duration <= 0) { if (failed) "" else stringResource(R.string.chat_getting_audio) }
            else if (started || preview != null) "${noteTime(shown)} / ${noteTime(duration)}" else noteTime(duration),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
internal fun VoiceNoteControls(
    playing: Boolean, duration: Int, position: () -> Int, started: Boolean, showText: Boolean,
    onPlay: () -> Unit, onSeek: (Int) -> Unit, onRead: () -> Unit, failed: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val description = stringResource(if (playing) R.string.note_pause else R.string.note_play)
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(com.past9.phoneaos.ui.theme.Radius.lg),
        color = cs.primaryContainer
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            com.past9.phoneaos.ui.MorphIconButton(onClick = onPlay, size = 48.dp, container = cs.primary) {
                androidx.compose.material3.Icon(
                    if (playing) androidx.compose.material.icons.Icons.Rounded.Pause else androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                    description, tint = cs.onPrimary)
            }
            Spacer(Modifier.width(10.dp))
            NoteWaveform(duration, position, started, onSeek, Modifier.weight(1f), failed)
            Spacer(Modifier.width(10.dp))
            androidx.compose.material3.TextButton(onClick = onRead) {
                Text(stringResource(if (showText) R.string.chat_hide else R.string.chat_read_it), color = cs.onPrimaryContainer)
            }
        }
    }
}
