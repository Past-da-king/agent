package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.ChatItem
import org.json.JSONObject

/**
 * A live assistant session is a call: it is drawn by the same collapsed call block as any voice call, and its hand-offs
 * (the jobs Live gave the agent) are listed inside it with the user's own words, never as messages the user typed.
 * The call record's fields (docs/live-voice.md): kind "live_assistant", handoffs [{at, request, spoken, include_screen}];
 * on this phone's own chat the same facts ride on call rows (meta live, handoff, spoken).
 */
object LiveCallParts {
    data class Hand(val request: String, val spoken: String, val includeScreen: Boolean)

    private fun meta(i: ChatItem): JSONObject = runCatching { JSONObject(i.meta) }.getOrDefault(JSONObject())

    /** The computer's own marking of a live row (`meta.live`, an object with origin/spoken/session) or this phone's (`meta.live` true). */
    private fun liveOf(m: JSONObject): JSONObject? = m.optJSONObject("live") ?: if (m.optBoolean("live")) m else null

    fun isLive(items: List<ChatItem>): Boolean = items.any { liveOf(meta(it)) != null }

    /**
     * A row that belongs inside a call block even though it is not one of this phone's own call lines: a hand-off the
     * computer replicated back, marked `meta.live` (and `meta.via` "call"). It is never drawn as a message the user typed.
     */
    fun isLiveRow(i: ChatItem): Boolean = i.kind == "user" && meta(i).optJSONObject("live") != null

    /** What the agent was asked, without the notes only the agent reads. */
    private fun cleanRequest(text: String): String = text.substringBefore(com.past9.phoneaos.assist.AssistMessage.MARK).substringBefore("\n[Live voice hand-off").trim()

    /**
     * The hand-off a call row stands for, or null for an ordinary line of the call: this phone's own (`meta.handoff`) or
     * one the computer replicated back (`meta.live` object with the user's `spoken` words).
     */
    fun handoffOf(i: ChatItem): Hand? {
        val m = meta(i)
        if (i.kind == "user") m.optJSONObject("live")?.let { l -> return Hand(cleanRequest(i.text), l.optString("spoken").trim(), l.optBoolean("include_screen")) }
        return m.takeIf { it.optBoolean("handoff") }?.let { Hand(cleanRequest(i.text), it.optString("spoken").trim(), it.optBoolean("include_screen")) }
    }

    fun handoffs(items: List<ChatItem>): List<Hand> = items.mapNotNull(::handoffOf)

    /** The hand-offs of a call the computer logged (its record's handoffs array). */
    fun fromMeta(meta: JSONObject): List<Hand> = meta.optJSONArray("handoffs")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Hand(it.optString("request").trim(), it.optString("spoken").trim(), it.optBoolean("include_screen")) }.filter { it.request.isNotBlank() }
    }.orEmpty()

    /** "Live voice · 3 min · 12 messages · 2 hand-offs": the usual call title, with its kind and its hand-offs. */
    fun title(base: String, live: Boolean, hands: Int): String =
        if (!live) base else base.replaceFirst("Voice call", "Live voice") + if (hands > 0) " · $hands hand-off${if (hands == 1) "" else "s"}" else ""

    /** The one line shown while the call is folded: what it handed to the agent, else the last thing said. */
    fun summary(last: String, hands: List<Hand>): String = if (hands.isEmpty()) last else "Handed to your agent: " + hands.last().request
}

/** One hand-off inside the opened call: what Live asked the agent to do, and what the user actually said, as a quote. */
@Composable
fun HandoffEntry(request: String, spoken: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bolt, null, Modifier.size(14.dp), tint = cs.primary)
            Spacer(Modifier.width(6.dp))
            Text("Sent to your agent", style = MaterialTheme.typography.labelMedium, color = cs.primary)
        }
        Text(request, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        if (spoken.isNotBlank()) Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(cs.outlineVariant, androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("You said: “$spoken”", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}
