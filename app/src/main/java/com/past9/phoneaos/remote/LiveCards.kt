package com.past9.phoneaos.remote

import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.HelperView
import org.json.JSONObject

/** One step on a card (the brain's newest steps for it). */
data class LiveStep(val text: String, val status: String, val at: Long)

/**
 * One helper or project agent card as the computer publishes it (desktop shared/helper-cards.ts, HelperCardInfo):
 * RPC helpers.cards for the list now, event agent:evt:helpers.cards for each new list. The phone keeps nothing else:
 * it only draws these.
 */
data class LiveCard(
    val id: String, val row: String, val chat: String, val label: String, val state: String, val queued: Boolean,
    val line: String, val done: String, val depth: Int, val projectChat: String,
    val ownerKind: String, val ownerName: String, val ownerGlyph: String, val ownerColor: String, val ownerImage: String,
    val model: String, val startedAt: Long, val endedAt: Long, val brief: String, val result: String, val steps: List<LiveStep>,
) {
    val working get() = state == "working"
    /** A project agent's own card (it stands for a project chat). */
    val isProject get() = projectChat.isNotBlank()
}

object LiveCards {
    /** A snapshot { seq, at, cards: [...] }, or null when it is not one. */
    fun parse(o: JSONObject?): Pair<Long, List<LiveCard>>? {
        if (o == null || !o.has("cards")) return null
        val a = o.optJSONArray("cards") ?: return null
        val cards = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::card) }
        return o.optLong("seq") to cards
    }

    fun card(c: JSONObject): LiveCard? {
        val id = c.optString("id").ifBlank { return null }
        val owner = c.optJSONObject("owner") ?: JSONObject()
        val icon = owner.optJSONObject("icon") ?: JSONObject()
        val steps = c.optJSONArray("steps")?.let { s -> (0 until s.length()).mapNotNull { s.optJSONObject(it) }.map { LiveStep(it.optString("text"), it.optString("status", "done"), it.optLong("at")) } }.orEmpty()
        return LiveCard(
            id = id, row = c.optString("row").ifBlank { id }, chat = c.optString("chat", "main"), label = c.optString("label").ifBlank { "Helper" },
            state = c.optString("state").ifBlank { "working" }, queued = c.optBoolean("queued"), line = c.optString("line"), done = c.optString("done"),
            depth = c.optInt("depth").coerceIn(0, 1), projectChat = c.optString("projectChat"),
            ownerKind = owner.optString("kind", "plain"), ownerName = owner.optString("name"), ownerGlyph = icon.optString("glyph"),
            ownerColor = icon.optString("color").ifBlank { "iris" }, ownerImage = icon.optString("image"),
            model = c.optString("model"), startedAt = c.optLong("startedAt"), endedAt = if (c.isNull("endedAt")) 0L else c.optLong("endedAt"),
            brief = c.optString("brief"), result = c.optString("result"), steps = steps,
        )
    }

    /**
     * A card in the shape the phone's helper card and sheet already draw (ui/Helpers.kt), so the linked cards look
     * like the phone's own helpers. Nothing is stored: the row is made from the card each time it is drawn.
     */
    fun view(c: LiveCard, ids: RemoteIds): HelperView {
        val id = ids.local("card:" + c.row, c.startedAt.coerceAtLeast(1L))
        val meta = JSONObject()
            .put("label", c.label).put("state", c.state).put("now", if (c.working) c.line else "")
            .put("result", if (c.working) "" else c.result.ifBlank { c.done })
            .put("startedAt", c.startedAt).put("endedAt", c.endedAt).put("model", c.model)
        if (c.ownerKind == "project") {
            meta.put("projectName", c.ownerName).put("projectGlyph", c.ownerGlyph).put("projectColor", c.ownerColor).put("projectImage", c.ownerImage)
            // A project's helper that is not under its project agent's card says which project it works in.
            if (!c.isProject && c.depth == 0 && c.ownerName.isNotBlank()) meta.put("profile", c.ownerName).put("profileColor", c.ownerColor)
        }
        val item = ChatItem(id, "helper", c.brief.ifBlank { c.label }, meta.toString(), c.startedAt)
        val steps = c.steps.mapIndexed { i, s ->
            // Never stored, so a plain negative id is enough (the sheet only draws them in order).
            ChatItem(-(i + 1).toLong(), "activity", s.text,
                JSONObject().put("tool", "agent").put("status", s.status).toString(), s.at)
        }
        return HelperView(item, steps)
    }

    /** The line a card shows: what it is doing now, or once finished what it did. */
    fun line(c: LiveCard): String = if (c.working) c.line.ifBlank { if (c.queued) "Waiting its turn" else "Starting" } else c.done
}
