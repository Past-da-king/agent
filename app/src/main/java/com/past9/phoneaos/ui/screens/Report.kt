package com.past9.phoneaos.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.ui.Markdown
import com.past9.phoneaos.ui.SubScreen
import java.io.File
import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.R

/** A report, full screen inside the app: read it, share it, or save it to Downloads. */
@Composable
fun ReportScreen(path: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    val text = remember(path) { runCatching { file.readText() }.getOrDefault("") }
    val title = text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: stringResource(R.string.report_title)
    val body = text.substringAfter("\n").trimStart()
    val words = remember(body) { body.split(Regex("\\s+")).count { it.isNotBlank() } }
    var note by remember { mutableStateOf<String?>(null) }
    val savedNote = stringResource(R.string.report_saved)
    SubScreen(title, stringResource(R.string.report_min_read, (words / 220).coerceAtLeast(1)), onBack, actions = {
        IconButton(onClick = {
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { note = ctx.getString(R.string.report_share_failed, it.message) }
        }, enabled = file.exists()) { Icon(Icons.Rounded.Share, stringResource(R.string.report_share)) }
        IconButton(onClick = {
            note = runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, title.replace(Regex("[^A-Za-z0-9 ._-]"), "").ifBlank { "Report" } + ".md")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/markdown")
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no Downloads folder")
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                savedNote
            }.getOrElse { ctx.getString(R.string.report_save_failed, it.message) }
        }, enabled = file.exists()) { Icon(if (note == savedNote) Icons.Rounded.DownloadDone else Icons.Rounded.Download, stringResource(R.string.report_save)) }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 48.dp)) {
            note?.let {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
            if (text.isBlank()) Text(stringResource(R.string.report_gone), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Markdown(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
