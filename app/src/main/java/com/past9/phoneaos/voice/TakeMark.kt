package com.past9.phoneaos.voice

import org.json.JSONObject

/**
 * What a dictated message says about its whole take (instant send, 19 Nov 2026): "Refining" while the clean text is
 * still being written down behind it, a Retry when that failed. Read from the message's own meta (`dictation: {id, state}`,
 * set by this phone or by the computer that owns the chat) and from the takes on this phone.
 */
object TakeMark {
    enum class Kind { NONE, REFINING, FAILED }
    data class Mark(val kind: Kind, val id: String = "")

    private val NONE = Mark(Kind.NONE)

    fun of(meta: String, text: String, takes: Map<String, Take>, now: Long): Mark {
        val d = runCatching { JSONObject(meta.ifBlank { "{}" }).optJSONObject("dictation") }.getOrNull()
        val state = d?.optString("state").orEmpty()
        val id = d?.optString("id")?.takeIf { it.isNotBlank() }
            ?: takes.values.lastOrNull { it.state != "done" && it.live == text && text.isNotBlank() }?.id
            ?: return NONE
        if (state == "done") return NONE
        val t = takes[id]
        if (state == "failed" || t?.state == "failed") return Mark(Kind.FAILED, id)
        if (t == null) return if (state == "refining") Mark(Kind.REFINING, id) else NONE
        return when (t.state) {
            "queued", "running" -> Mark(Kind.REFINING, id)
            // The computer has it: it replaces the message when the pass is done. A message whose words changed is done; one that
            // never changed after the take's own cap was answered some other way (an older computer): no marker forever.
            "handed" -> if (text != t.live) NONE else if (now - t.acceptedAt > TakeFlow.holdCapMs(t.seconds) + 15_000) NONE else Mark(Kind.REFINING, id)
            else -> NONE
        }
    }
}
