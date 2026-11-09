package com.past9.phoneaos.ui.screens

import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.App
import com.past9.phoneaos.ui.AgentSheet
import com.past9.phoneaos.ui.AppNotice
import com.past9.phoneaos.ui.ComposerBoxes
import com.past9.phoneaos.ui.SheetHead
import com.past9.phoneaos.voice.Dictations
import com.past9.phoneaos.voice.PieceTranscriber
import com.past9.phoneaos.voice.SavedDictation
import com.past9.phoneaos.voice.Words
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A write-down from the saved audio that is running, by recording: it goes on when he leaves the screen. */
private object Again { val running = mutableStateMapOf<String, String>() }

private fun whenLine(d: SavedDictation): String =
    java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(d.startedAt))

private fun length(ms: Long): String { val s = (ms + 500) / 1000; return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s" }

/** Plain words for how it ended. Empty: nothing to say. */
private fun stateLine(d: SavedDictation): String = when {
    d.state == "recording" -> "Recording now"
    d.state == "interrupted" -> "Cut off"
    d.state == "kept" -> "Some of it was not written down"
    d.text.isBlank() && d.audio != null -> "Audio saved, no words yet"
    d.audio == null -> "Audio gone, words kept"
    else -> ""
}

/** Every dictation kept on this phone, newest first, with its words and audio. Insert goes into [boxKey]'s message box. */
@Composable
fun RecordingsScreen(boxKey: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val folder = remember { Dictations.of(ctx) }
    var rows by remember { mutableStateOf(folder.list()) }
    var open by remember { mutableStateOf<String?>(null) }
    // A running one shows its words as they come.
    // Only while this screen is in front: the folder is not re-read every 1.5 s behind another app or a locked screen.
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(life) { life.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { rows = folder.list(); while (true) { delay(1_500); rows = folder.list() } } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("Recordings", style = MaterialTheme.typography.titleMedium)
                    Text("What you dictated, kept on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    ) { pad ->
        if (rows.isEmpty()) Column(Modifier.fillMaxSize().padding(pad).padding(24.dp)) {
            Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Tap the mic in any message box. The audio and the words are saved as you talk.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
            items(rows, key = { it.id }) { d -> RecordingRow(d) { open = d.id } }
        }
    }
    open?.let { id ->
        rows.firstOrNull { it.id == id }?.let { d -> RecordingSheet(d, boxKey, onDone = { open = null; onBack() }, onDismiss = { open = null }) }
    }
}

@Composable
private fun RecordingRow(d: SavedDictation, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = c.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Mic, null, tint = c.primary)
                Spacer(Modifier.width(10.dp))
                Text(whenLine(d), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(length(d.durationMs), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            val again = Again.running[d.id]
            val state = again ?: stateLine(d)
            if (state.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(state, style = MaterialTheme.typography.bodySmall, color = c.tertiary) }
            if (d.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(d.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RecordingSheet(d: SavedDictation, boxKey: String, onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val again = Again.running[d.id]
    AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            SheetHead(Icons.Rounded.Mic, length(d.durationMs), whenLine(d), stateLine(d).ifEmpty { null })
            Spacer(Modifier.height(16.dp))
            if (d.text.isNotBlank()) SelectionContainer { Text(d.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.heightIn(min = 48.dp)) }
            else Text("No words saved for this one yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (d.text.isNotBlank()) {
                FilledTonalButton(onClick = {
                    val box = ComposerBoxes.box(boxKey)
                    box.draft = Words.join(box.draft, d.text)
                    onDone()
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Insert into message") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { clip.setText(AnnotatedString(d.text)); AppNotice.say("Copied") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Copy") }
                Spacer(Modifier.height(8.dp))
            }
            val audio = d.audio
            if (audio != null && d.state != "recording") {
                OutlinedButton(enabled = again == null, onClick = {
                    Again.running[d.id] = "Writing it down..."
                    val g = App.graph(ctx)
                    g.scope.launch {
                        try {
                            Dictations.of(ctx).transcribeAgain(d.id, { pcm -> PieceTranscriber.transcribe(ctx, pcm) }) { i, n -> Again.running[d.id] = "Writing it down: part ${minOf(i + 1, n)} of $n" }
                            com.past9.phoneaos.remote.RemoteHub.session.value?.pushDictations()
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: Exception) { AppNotice.say("Couldn't write it down: ${e.message}. The audio is still here.") }
                        finally { Again.running.remove(d.id) }
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(again ?: "Transcribe again") }
                Spacer(Modifier.height(4.dp))
                val mb = audio.length() * 4 / 3 / (1024.0 * 1024.0)
                Text("From the saved audio. Uses about ${if (mb < 1) "1" else String.format(java.util.Locale.US, "%.0f", kotlin.math.ceil(mb))} MB of data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
