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
    /** For a helper working inside a project chat: that chat ('' otherwise). */
    val parentChat: String = "",
    /** The project it belongs to ('' for the main chat's own). */
    val ownerId: String = "",
    /** A project chat that is done and waits on him (its agent spoke last). */
    val waiting: Boolean = false,
    /** When its group last moved (older computers: its start or end). */
    val activityAt: Long = 0L,
) {
    val working get() = state == "working"
    /** A project agent's own card (it stands for a project chat). */
    val isProject get() = projectChat.isNotBlank()
    /** The chat a tap on "Go to chat" opens: a project chat's own, else the chat it works in, else the main thread. */
    val homeChat get() = projectChat.ifBlank { parentChat.ifBlank { chat.ifBlank { "main" } } }
    /** When it last moved, for a card he swiped away: it comes back only once it moves again. */
    val movedAt get() = maxOf(activityAt, startedAt, endedAt)
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
            parentChat = c.optString("parentChat"), ownerId = owner.optString("id"), waiting = c.optBoolean("waiting"),
            activityAt = c.optLong("activityAt"),
        )
    }

    /**
     * The cards for the chat on screen: the main thread shows every card across all projects; a project chat only
     * that project's (its own cards and the helpers working in its chats). [projectId] and [projectName] say which
     * project holds [chat] when it is known.
     */
    fun forChat(cards: List<LiveCard>, chat: String, projectId: String = "", projectName: String = ""): List<LiveCard> {
        if (chat.isBlank() || chat == "main") return cards
        return cards.filter { c ->
            c.projectChat == chat || c.parentChat == chat || c.chat == chat ||
                (c.ownerKind == "project" && ((projectId.isNotBlank() && c.ownerId == projectId) || (projectId.isBlank() && projectName.isNotBlank() && c.ownerName == projectName)))
        }
    }

    /** The cards he has not swiped away: one swiped at [hidden] comes back only once it moves again. */
    fun visible(cards: List<LiveCard>, hidden: Map<String, Long>): List<LiveCard> {
        if (hidden.isEmpty()) return cards
        val gone = cards.filter { c -> (hidden[c.row] ?: -1L) >= c.movedAt }.map { it.row }.toSet()
        // A project chat's card swiped away takes the helpers drawn under it along.
        val out = ArrayList<LiveCard>()
        var skipping = false
        for (c in cards) {
            if (c.depth == 0) skipping = c.row in gone
            if (c.row in gone || (skipping && c.depth == 1 && !c.working)) continue
            out += c
        }
        return out
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
            // A card is live right now: it is only here because the computer answered a moment ago (reconcile drops the
            // list when the brain stops answering), so its beat is now and the phone's quiet window (HelperLive) never
            // hides a card the computer still lists.
            .put("beat", System.currentTimeMillis())
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

    /**
     * The whole list the phone may show, from the computer's newest answer (30 Oct 2026: the Working list showed 14
     * helpers "working" for 6 h 10 min, none of them running anywhere, because the phone kept rows whose end never
     * reached it after the computer restarted at 13:17).
     *
     * - [snapshot] is not null: the computer published its list, so it wins OUTRIGHT, however short. A computer that
     *   restarted numbers its lists afresh, so a new, lower number is a real list, never an old one.
     * - [snapshot] is null (a computer too old to publish cards): what is here stays only while that computer answers.
     * - no brain answering: nothing is working, so nothing is left to draw.
     */
    fun reconcile(prev: List<LiveCard>?, snapshot: List<LiveCard>?, brainAlive: Boolean): List<LiveCard>? {
        if (snapshot != null) return snapshot
        if (prev == null) return null
        return if (brainAlive) prev else emptyList()
    }

    /**
     * How many agents are at work, from the ONE list the phone draws. The Home screen's header, the agents pill and the
     * Working list all take their number from here, so they can never disagree (30 Oct 2026: the header said "Free"
     * while 8 helpers were listed).
     */
    fun workingCount(cards: List<LiveCard>?, brainAlive: Boolean = true): Int =
        if (!brainAlive || cards == null) 0 else cards.count { it.working }

    // A card's "working" is the computer's own newest list, and that list only exists while the computer answers
    // (reconcile, STALE_CARDS_MS): it is the same rule as the phone's rows (ui/HelperLive.kt), read at the source.
    // A row in the phone's own copy is the one that could say working for ever, and HelperLive is what fixes it.

    /** Same rule as [workingCount]: is anything at work now? */
    fun anyWorking(cards: List<LiveCard>?, brainAlive: Boolean = true): Boolean = workingCount(cards, brainAlive) > 0

    /** The line a card shows: what it is doing now, or once finished what it did. */
    fun line(c: LiveCard): String = if (c.working) c.line.ifBlank { if (c.queued) "Waiting its turn" else "Starting" } else c.done
}
