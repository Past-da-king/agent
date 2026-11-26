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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class AnswerHistory(val item: ChatItem, val answer: JSONObject, val remoteId: String)

/** One paired-computer history across chats, with the original request and the existing person-only Undo RPC. */
@Composable
fun AnsweredForYou(items: List<ChatItem>, agentName: String) {
    val local = remember(items) { items.mapNotNull { item ->
        val meta = runCatching { JSONObject(item.meta) }.getOrNull()
        meta?.optJSONObject("autoAnswer")?.let { AnswerHistory(item, it, meta.optString("rid")) }
    }.sortedByDescending { it.answer.optLong("at") } }
    val session by RemoteHub.session.collectAsState()
    if (local.isEmpty() && session == null) return
    var open by remember { mutableStateOf(false) }
    var remote by remember(session) { mutableStateOf<List<AnswerHistory>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(open, session, revision) {
        if (!open || session == null) return@LaunchedEffect
        loading = true
        error = ""
        try {
            val connected = session ?: return@LaunchedEffect
            val rows = connected.call("acts.list", emptyList()) as? JSONArray ?: throw IllegalStateException("Could not load the answers.")
            val loaded = mutableListOf<AnswerHistory>()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val rid = row.optString("id")
                if (rid.isBlank()) continue
                val known = items.firstOrNull { runCatching { JSONObject(it.meta).optString("rid") == rid }.getOrDefault(false) }
                val source = if (known == null) connected.call("data.get", listOf("chat_item", rid)) as? JSONObject else null
                val item = known ?: ChatItem(id = -index.toLong() - 1L, kind = "approval", text = row.optString("summary"), meta = source?.optString("meta").orEmpty().ifBlank { "{}" }, createdAt = row.optLong("at"))
                loaded += AnswerHistory(item, row, rid)
            }
            remote = loaded
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "Could not load the answers."
        } finally { loading = false }
    }
    TextButton(onClick = { open = true }) { Text("Answered for you") }
    if (open) AgentSheet({ open = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Answered for you", style = MaterialTheme.typography.headlineSmall)
            if (loading) Text("Loading answers...", style = MaterialTheme.typography.bodyMedium)
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { revision++ }) { Text("Retry") }
            }
            val entries = remote ?: local
            if (!loading && entries.isEmpty() && error.isBlank()) Text("Your agent has not answered any cards for you yet.")
            entries.forEach { entry ->
                val answer = entry.answer
                val approval = Approval.of(entry.item)
                val at = answer.optLong("at")
                Text(if (at > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(at)) + " · " + answer.optString("asker") else answer.optString("asker"), style = MaterialTheme.typography.labelMedium)
                Text(answer.optString("summary").ifBlank { approval.summary }, style = MaterialTheme.typography.titleMedium)
                Text(when (answer.optString("decision")) { "approve" -> "Approved"; "decline" -> "Declined"; else -> answer.optString("answer") }, style = MaterialTheme.typography.bodyMedium)
                RequestContext(approval, agentName, full = true)
                Text(answer.optString("reason"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    answer.optLong("undone") > 0L -> Text("Undone", style = MaterialTheme.typography.bodySmall)
                    answer.optString("undo") in setOf("retry", "reask", "revert-setting") && entry.remoteId.isNotBlank() -> TextButton(enabled = busy == null && !loading, onClick = {
                        busy = entry.remoteId
                        error = ""
                        scope.launch {
                            try {
                                val connected = session ?: throw IllegalStateException("Not connected to a computer right now.")
                                val result = connected.call("acts.undo", listOf(entry.remoteId)) as? JSONObject
                                if (result?.optBoolean("ok") == true) revision++
                                else error = result?.optString("message").orEmpty().ifBlank { "Undo did not go through. Try again." }
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                error = failure.message ?: "Undo did not go through. Try again."
                            } finally { busy = null }
                        }
                    }) { Text(if (busy == entry.remoteId) "Undoing..." else "Undo") }
                    else -> Text("This action cannot be undone.", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
            TextButton(onClick = { open = false }) { Text("Close") }
        }
    }
}
