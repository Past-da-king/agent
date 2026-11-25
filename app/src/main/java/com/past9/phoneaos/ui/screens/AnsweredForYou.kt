package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.AgentSheet
import org.json.JSONObject

/** Decisions already carried by the chat rows, shown with their original request. */
@Composable
fun AnsweredForYou(items: List<ChatItem>, agentName: String) {
    val entries = remember(items) { items.mapNotNull { item ->
        val meta = runCatching { JSONObject(item.meta) }.getOrNull()
        meta?.optJSONObject("autoAnswer")?.let { Triple(item, meta, it) }
    }.sortedByDescending { it.third.optLong("at") } }
    if (entries.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Answered for you") }
    if (open) AgentSheet({ open = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Answered for you", style = MaterialTheme.typography.headlineSmall)
            entries.forEach { (item, _, answer) ->
                val approval = Approval.of(item)
                Text(answer.optString("asker"), style = MaterialTheme.typography.labelMedium)
                Text(answer.optString("summary").ifBlank { approval.summary }, style = MaterialTheme.typography.titleMedium)
                Text(when (answer.optString("decision")) { "approve" -> "Approved"; "decline" -> "Declined"; else -> answer.optString("answer") }, style = MaterialTheme.typography.bodyMedium)
                RequestContext(approval, agentName, full = true)
                Text(answer.optString("reason"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
            }
            TextButton(onClick = { open = false }) { Text("Close") }
        }
    }
}
