package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.ui.AgentSheet
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Decisions carried by chat rows; Undo uses the paired computer's existing person-only RPC. */
@Composable
fun AnsweredForYou(items: List<ChatItem>, agentName: String) {
    val entries = remember(items) { items.mapNotNull { item ->
        val meta = runCatching { JSONObject(item.meta) }.getOrNull()
        meta?.optJSONObject("autoAnswer")?.let { Triple(item, meta, it) }
    }.sortedByDescending { it.third.optLong("at") } }
    if (entries.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf("") }
    var undone by remember { mutableStateOf(emptySet<Long>()) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = { open = true }) { Text("Answered for you") }
    if (open) AgentSheet({ open = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Answered for you", style = MaterialTheme.typography.headlineSmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            entries.forEach { (item, meta, answer) ->
                val approval = Approval.of(item)
                Text(answer.optString("asker"), style = MaterialTheme.typography.labelMedium)
                Text(answer.optString("summary").ifBlank { approval.summary }, style = MaterialTheme.typography.titleMedium)
                Text(when (answer.optString("decision")) { "approve" -> "Approved"; "decline" -> "Declined"; else -> answer.optString("answer") }, style = MaterialTheme.typography.bodyMedium)
                RequestContext(approval, agentName, full = true)
                Text(answer.optString("reason"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val rid = meta.optString("rid")
                when {
                    answer.optLong("undone") > 0L || item.id in undone -> Text("Undone", style = MaterialTheme.typography.bodySmall)
                    answer.optString("undo") in setOf("retry", "reask", "revert-setting") && rid.isNotBlank() -> TextButton(enabled = busy == null, onClick = {
                        busy = item.id
                        error = ""
                        scope.launch {
                            try {
                                val session = RemoteHub.session.value ?: throw IllegalStateException("Not connected to a computer right now.")
                                val result = session.call("acts.undo", listOf(rid)) as? JSONObject
                                if (result?.optBoolean("ok") == true) undone = undone + item.id
                                else error = result?.optString("message").orEmpty().ifBlank { "Undo did not go through. Try again." }
                            } catch (failure: Exception) {
                                error = failure.message ?: "Undo did not go through. Try again."
                            } finally { busy = null }
                        }
                    }) { Text(if (busy == item.id) "Undoing..." else "Undo") }
                    else -> Text("This action cannot be undone.", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
            TextButton(onClick = { open = false }) { Text("Close") }
        }
    }
}
