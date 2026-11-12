package com.past9.phoneaos.wear

import com.past9.phoneaos.assist.AssistCore
import com.past9.phoneaos.assist.AssistMessage
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.Sender
import com.past9.phoneaos.ui.openui.OpenUiLang
import com.past9.phoneaos.ui.screens.Approval
import com.past9.phoneaos.wearproto.Exchange
import com.past9.phoneaos.wearproto.Limits
import com.past9.phoneaos.wearproto.Pending
import com.past9.phoneaos.wearproto.Phase
import com.past9.phoneaos.wearproto.TurnView
import com.past9.phoneaos.wearproto.WatchState
import org.json.JSONObject

/**
 * What the watch is shown, made from the phone's own chat rows (the rows the main chat and the assist sheet draw), so
 * the watch can never say something the phone does not. Pure: no Android services, no network.
 */
object WatchStateBuilder {
    /** Where the watch's conversation starts: rows after [afterId] are this turn's. */
    data class Follow(val afterId: Long, val heard: String = "")

    fun build(rows: List<ChatItem>, working: Boolean, follow: Follow?, agentName: String, link: String): WatchState =
        WatchState(agent = agentName, link = link, working = working, turn = turn(rows, working, follow), pending = pending(rows), recent = recent(rows))

    /** Every approval and question that waits for him, newest last. */
    fun pending(rows: List<ChatItem>): List<Pending> = rows.filter { AssistCore.waits(it) }.takeLast(Limits.PENDING_MAX).map { r ->
        val meta = AssistCore.meta(r)
        if (Approval.isApproval(r)) {
            val a = Approval.of(r)
            Pending(r.id, Pending.KIND_APPROVAL, Limits.clip(a.summary, 200), Limits.clip(a.details, Limits.DETAILS_MAX), Pending.APPROVAL_OPTIONS, a.askedBy)
        } else {
            val opts = meta.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                .filter { it.isNotBlank() && it != "Open browser" }.take(Limits.OPTIONS_MAX).map { Limits.clip(it, Limits.OPTION_MAX) }
            val why = meta.optString("why").takeIf { it.isNotBlank() }.orEmpty()
            Pending(r.id, Pending.KIND_QUESTION, Limits.clip(OpenUiLang.plainText(r.text), 400), Limits.clip(why, Limits.DETAILS_MAX), opts, meta.optString("whyBy"))
        }
    }

    /**
     * May the watch answer this card with this option? Only a card that still waits, and only an answer the card itself
     * offers (an approval takes Approve or Decline, a question one of its own options): the watch cannot invent either.
     */
    fun checkAnswer(rows: List<ChatItem>, card: Long, option: String): com.past9.phoneaos.wearproto.RelayResult {
        val waiting = pending(rows).firstOrNull { it.id == card } ?: return com.past9.phoneaos.wearproto.RelayResult(false, "That is not waiting any more.")
        return if (waiting.accepts(option)) com.past9.phoneaos.wearproto.RelayResult(true) else com.past9.phoneaos.wearproto.RelayResult(false, "That is not one of the answers.")
    }

    /** The last few things he said and the agent's answers, newest first. */
    fun recent(rows: List<ChatItem>): List<Exchange> {
        val out = ArrayList<Exchange>()
        var you: ChatItem? = null
        for (r in rows) {
            when {
                r.kind == "user" && Sender.isHis(r.kind, r.meta) -> you = r
                r.kind == "agent" && r.text.isNotBlank() && you != null -> {
                    out += Exchange(you.id, cleanUser(you.text), Limits.clip(r.text.trim(), Limits.RECENT_REPLY_MAX), r.createdAt); you = null
                }
            }
        }
        return out.takeLast(Limits.RECENT).reversed()
    }

    /** The turn the watch started (or the phone's sheet started for it): the same reading as the assist sheet. */
    fun turn(rows: List<ChatItem>, working: Boolean, follow: Follow?): TurnView? {
        if (follow == null) return null
        val core = AssistCore(micAvailable = false)
        val mine = rows.filter { it.id > follow.afterId }
        val said = follow.heard.ifBlank { mine.firstOrNull { it.kind == "user" }?.text?.let(::cleanUser).orEmpty() }
        core.finished(said.ifBlank { "…" }, locked = false, byKeyboard = true)
        core.sent(follow.afterId)
        val v = core.view(rows, working)
        val phase = when (v.phase) {
            com.past9.phoneaos.assist.AssistPhase.APPROVAL -> Phase.APPROVAL
            com.past9.phoneaos.assist.AssistPhase.ASKING -> Phase.ASKING
            com.past9.phoneaos.assist.AssistPhase.ERROR -> Phase.ERROR
            com.past9.phoneaos.assist.AssistPhase.ACTING -> Phase.ACTING
            com.past9.phoneaos.assist.AssistPhase.ANSWERING -> Phase.ANSWERING
            com.past9.phoneaos.assist.AssistPhase.THINKING -> Phase.THINKING
            else -> Phase.SENDING
        }
        return TurnView(phase, v.heard, v.label, Limits.clip(v.answer, Limits.ANSWER_MAX), v.answerId ?: 0L, v.problem)
    }

    /** His words without the note the phone adds for the agent. */
    fun cleanUser(text: String): String = text.substringBefore(AssistMessage.MARK).trim()

    /** The note the agent reads with a message from the watch (the chat cuts the bubble at the same mark). */
    fun compose(words: String, via: String): String =
        words.trim() + AssistMessage.MARK + ": the user " + (if (via == "speech" || via == "phone") "said" else "typed") +
            " this on their watch, a small round screen. Answer in one or two short lines that fit it; act at once on a clear command.]"
}
