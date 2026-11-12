package com.past9.phoneaos.assist

import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.LiveCard
import com.past9.phoneaos.remote.LiveCards
import com.past9.phoneaos.remote.Sender
import com.past9.phoneaos.ui.HelperLive
import com.past9.phoneaos.ui.HelperView
import com.past9.phoneaos.ui.openui.OpenUiLang
import com.past9.phoneaos.ui.screens.Approval
import org.json.JSONObject

/** One recent turn of the main chat: [who] is "user" or "agent". */
data class CtxTurn(val who: String, val text: String)

/**
 * An agent the assistant should know about: the main agent, a project agent or a helper. [state] is working, waiting
 * (it needs the user) or done (finished a moment ago).
 */
data class CtxAgent(val name: String, val kind: String, val state: String, val doing: String, val waitingOn: String = "") {
    companion object { const val WORKING = "working"; const val WAITING = "waiting"; const val DONE = "done" }
}

/**
 * Something an agent waits on: a question (with its options when it has them) or an approval. [ref] is the short handle
 * Live voice names it by ("A1"); it stays the same for as long as the session lives. [by] is who asks, blank for the main agent.
 */
data class CtxAsk(val id: Long, val ref: String, val approval: Boolean, val text: String, val options: List<String>, val by: String, val why: String = "")

/** A notification the agent sent recently. */
data class CtxNote(val title: String, val body: String, val at: Long)

/**
 * What the assistant knows when it opens: the tail of the main chat, who is at work, what waits on the user, what the
 * agent notified. Built only from what the user's own agent already holds on this phone or on the linked computer, and
 * capped so it stays small ([prompt]).
 */
data class AssistContext(
    val turns: List<CtxTurn> = emptyList(),
    val agents: List<CtxAgent> = emptyList(),
    val asks: List<CtxAsk> = emptyList(),
    val notes: List<CtxNote> = emptyList(),
) {
    val isEmpty get() = turns.isEmpty() && agents.isEmpty() && asks.isEmpty() && notes.isEmpty()
    /** Questions the assistant may answer for the user. Approvals are never answered by words: they are held to approve. */
    val questions get() = asks.filter { !it.approval }
    val working get() = agents.count { it.state == CtxAgent.WORKING }

    /** The context as the instruction block the voice reads. Never longer than [cap] characters. */
    fun prompt(cap: Int = MAX_CHARS): String {
        if (isEmpty) return ""
        var a = asks; var g = agents; var t = turns; var n = notes
        fun render(): String {
            val out = StringBuilder("What is going on right now, from your own agents (this is all you know; nothing else):")
            if (a.isNotEmpty()) {
                out.append("\nWaiting on the user:")
                a.forEach { k ->
                    val who = if (k.by.isBlank()) "your main chat" else k.by
                    if (k.approval) out.append("\n- [${k.ref}] Approval, from $who: ${k.text}. They hold the approve button on the screen; never approve for them.")
                    else out.append("\n- [${k.ref}] Question, from $who: ${k.text}" + (if (k.options.isNotEmpty()) " Options: ${k.options.joinToString(", ")}." else ""))
                }
            }
            if (g.isNotEmpty()) {
                out.append("\nAgents:")
                g.forEach { x -> out.append("\n- ${x.name} (${x.kind}), ${x.state}" + (if (x.doing.isNotBlank()) ": ${x.doing}" else "") + (if (x.waitingOn.isNotBlank()) ". Waiting on: ${x.waitingOn}" else "")) }
            }
            if (t.isNotEmpty()) {
                out.append("\nThe last of the main chat:")
                t.forEach { x -> out.append("\n- ${if (x.who == "user") "User" else "You"}: ${x.text}") }
            }
            if (n.isNotEmpty()) {
                out.append("\nRecent notifications you sent:")
                n.forEach { x -> out.append("\n- ${x.title}: ${x.body}") }
            }
            return out.toString()
        }
        // Over the cap: the least useful goes first (old notifications, old chat turns, agents beyond the first few), the asks last.
        var s = render()
        while (s.length > cap) {
            when {
                n.isNotEmpty() -> n = n.dropLast(1)
                t.size > 2 -> t = t.drop(1)
                g.size > 3 -> g = g.dropLast(1)
                t.isNotEmpty() -> t = t.drop(1)
                g.isNotEmpty() -> g = g.dropLast(1)
                a.size > 1 -> a = a.drop(1)
                else -> return s.take(cap)
            }
            s = render()
        }
        return s
    }

    companion object {
        const val MAX_CHARS = 3_200
        const val TURNS = 6
        const val TURN_CHARS = 180
        const val AGENTS = 8
        const val ASKS = 5
        const val NOTES = 4
        /** A finished agent stays in the list this long. */
        const val DONE_MS = 20 * 60_000L
        /** A notification is recent for this long. */
        const val NOTE_MS = 6 * 60 * 60_000L
    }
}

/** Hands out the same short handle ("A1", "A2"...) to the same ask for the life of one session. */
class AskRefs {
    private val map = HashMap<Long, String>()
    @Synchronized fun of(id: Long): String = map.getOrPut(id) { "A" + (map.size + 1) }
}

/** Builds an [AssistContext], free of Android, from the rows and cards the phone already holds. */
object AssistContextBuilder {
    /**
     * [rows] are the chat rows of the brain's chat (this phone's own, or the linked computer's mirror: it also holds the
     * open cards of project chats and helpers). [cards] are the computer's helper and project cards (null on this phone's
     * own agent, or a computer too old to list them). [mainWorking] and [mainDoing] are the main agent's own status.
     */
    fun build(
        rows: List<ChatItem>, cards: List<LiveCard>?, notes: List<CtxNote>, mainWorking: Boolean, mainDoing: String,
        now: Long, refs: AskRefs = AskRefs(), agentName: String = "",
    ): AssistContext {
        val asks = asks(rows, refs)
        val agents = ArrayList<CtxAgent>()
        if (mainWorking) agents += CtxAgent(agentName.ifBlank { "Main chat" }, "main", CtxAgent.WORKING, clip(mainDoing, 120))
        agents += if (cards != null) fromCards(cards, asks, now) else fromRows(rows, asks, now)
        return AssistContext(
            turns = turns(rows), agents = agents.take(AssistContext.AGENTS), asks = asks,
            notes = notes.filter { now - it.at in 0..AssistContext.NOTE_MS }.sortedByDescending { it.at }.take(AssistContext.NOTES).reversed(),
        )
    }

    fun asks(rows: List<ChatItem>, refs: AskRefs = AskRefs()): List<CtxAsk> = rows.filter { AssistCore.waits(it) }.takeLast(AssistContext.ASKS).map { r ->
        val meta = AssistCore.meta(r)
        if (Approval.isApproval(r)) {
            val a = Approval.of(r)
            CtxAsk(r.id, refs.of(r.id), true, clip(a.summary, 240), emptyList(), a.askedBy)
        } else {
            val opts = meta.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it.isNotBlank() && it != "Open browser" }.take(6)
            val q = com.past9.phoneaos.tools.ConnectRequest.parse(r.text)?.summary ?: r.text
            CtxAsk(r.id, refs.of(r.id), false, clip(OpenUiLang.plainText(q), 260), opts.map { clip(it, 40) }, meta.optString("whyBy"), clip(meta.optString("why"), 160))
        }
    }

    /** The tail of the main chat: his own words and the agent's replies, short. */
    fun turns(rows: List<ChatItem>): List<CtxTurn> = rows
        .filter { r -> val m = AssistCore.meta(r); m.optString("chat").let { it.isEmpty() || it == "main" } && !m.optBoolean("call") }
        .mapNotNull { r ->
            when {
                r.kind == "user" && Sender.isHis(r.kind, r.meta) -> CtxTurn("user", clip(AssistMessage.visible(r.text), AssistContext.TURN_CHARS))
                r.kind == "agent" && r.text.isNotBlank() -> CtxTurn("agent", clip(OpenUiLang.plainText(r.text), AssistContext.TURN_CHARS))
                else -> null
            }
        }.filter { it.text.isNotBlank() }.takeLast(AssistContext.TURNS)

    private fun fromCards(cards: List<LiveCard>, asks: List<CtxAsk>, now: Long): List<CtxAgent> = cards.mapNotNull { c ->
        val state = when {
            c.waiting -> CtxAgent.WAITING
            c.working -> CtxAgent.WORKING
            c.endedAt > 0 && now - c.endedAt <= AssistContext.DONE_MS -> CtxAgent.DONE
            else -> return@mapNotNull null
        }
        val name = if (c.isProject) "project " + c.ownerName.ifBlank { c.label } else c.label
        val on = if (state == CtxAgent.WAITING) asks.firstOrNull { it.by.equals(c.label, true) || (c.ownerName.isNotBlank() && it.by.equals(c.ownerName, true)) }?.text.orEmpty() else ""
        CtxAgent(clip(name, 60), if (c.isProject) "project" else "helper", state, clip(LiveCards.line(c), 140), clip(on, 100))
    }.sortedBy { when (it.state) { CtxAgent.WAITING -> 0; CtxAgent.WORKING -> 1; else -> 2 } }

    /** This phone's own agent (or a computer too old to list cards): its helper rows. */
    private fun fromRows(rows: List<ChatItem>, asks: List<CtxAsk>, now: Long): List<CtxAgent> = rows.filter { it.kind == "helper" }.mapNotNull { r ->
        val h = runCatching { HelperView(r, emptyList()) }.getOrNull() ?: return@mapNotNull null
        val m = AssistCore.meta(r)
        val ended = m.optLong("endedAt")
        val state = when {
            HelperLive.isLive(h, now) -> CtxAgent.WORKING
            h.state == "done" && ended > 0 && now - ended <= AssistContext.DONE_MS -> CtxAgent.DONE
            else -> return@mapNotNull null
        }
        val doing = if (state == CtxAgent.WORKING) m.optString("now").ifBlank { m.optString("summary") } else m.optString("result")
        CtxAgent(clip(h.label, 60), "helper", state, clip(doing, 140), asks.firstOrNull { it.by.equals(h.label, true) }?.text?.let { clip(it, 100) }.orEmpty())
    }.sortedBy { if (it.state == CtxAgent.WORKING) 0 else 1 }

    fun clip(s: String, max: Int): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= max) one else one.take(max - 1).trimEnd() + "…"
    }
}
