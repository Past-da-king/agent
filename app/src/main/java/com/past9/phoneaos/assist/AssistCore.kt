package com.past9.phoneaos.assist

import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.screens.Approval
import org.json.JSONObject

/** Where the sheet is. */
enum class AssistPhase {
    /** The microphone is open; his words appear as he speaks. */
    LISTENING,
    /** He chose the keyboard (or there is no microphone to use). */
    TYPING,
    /** The phone is locked: the words are kept and go once he unlocks. */
    LOCKED,
    /** The words are on their way to the agent. */
    SENDING,
    THINKING,
    /** The agent is doing something; [AssistView.label] says what. */
    ACTING,
    ANSWERING,
    /** Something waits for his yes before it goes out: the app's own approval, inside the sheet. */
    APPROVAL,
    /** The agent asked him something with buttons. */
    ASKING,
    ERROR,
}

/** Everything the sheet draws, and nothing else. */
data class AssistView(
    val phase: AssistPhase,
    /** His words: live while he speaks, then what was sent. */
    val heard: String = "",
    /** What the agent is doing, in a few words; blank shows the sheet's own "Working", in the app's language. */
    val label: String = "",
    val answer: String = "",
    /** The approval or question that waits for him. */
    val card: ChatItem? = null,
    /** A plain reason, for [AssistPhase.ERROR] and for a microphone that could not start. */
    val problem: String = "",
    /** The chat row of the answer, so it is read out once. */
    val answerId: Long? = null,
)

/**
 * The assist sheet's state machine, free of Android. One opening of the sheet is one conversation of one or more
 * turns: listen (or type), send, then follow the agent through the same chat rows the main chat shows, until an
 * answer, a card that needs him, or an error. The sheet never decides what a request means: every word goes to the
 * agent as it is.
 */
class AssistCore(private val micAvailable: Boolean = true) {
    private enum class Mode { LISTENING, TYPING, LOCKED, SENDING, SENT }

    private var mode = if (micAvailable) Mode.LISTENING else Mode.TYPING
    private var heard = ""
    private var problem = ""
    /** Chat rows after this id belong to the turn. */
    private var afterId = Long.MAX_VALUE
    private var sawWorking = false
    private var typed = false

    /** True while the microphone should be open. */
    val listening get() = mode == Mode.LISTENING
    val wasTyped get() = typed
    val waitingForUnlock get() = mode == Mode.LOCKED
    val words get() = heard

    /** The sheet opened again (a second press, a new wake word) or he tapped the mic: start over, listening. */
    fun listen() {
        heard = ""; problem = ""; typed = false
        mode = if (micAvailable) Mode.LISTENING else Mode.TYPING
    }

    /** He tapped the keyboard: the microphone closes and a text box takes its place. */
    fun type() { if (mode == Mode.LISTENING || mode == Mode.SENT || mode == Mode.TYPING) { mode = Mode.TYPING; problem = ""; heard = ""; typed = true } }

    /** Live words while he speaks. */
    fun partial(text: String) { if (mode == Mode.LISTENING) heard = text }

    /**
     * He finished speaking, or sent what he typed. Returns the words to send now, or null when there is nothing to
     * send (silence) or the phone must be unlocked first (the words are kept: [unlocked] returns them).
     */
    fun finished(text: String, locked: Boolean = false, byKeyboard: Boolean = false): String? {
        if (mode != Mode.LISTENING && mode != Mode.TYPING) return null
        val clean = text.trim()
        if (clean.isEmpty()) { if (mode == Mode.LISTENING) { mode = Mode.TYPING; problem = "I didn't catch anything. Tap the mic to try again, or type." }; return null }
        heard = clean; typed = byKeyboard; problem = ""
        if (locked) { mode = Mode.LOCKED; return null }
        mode = Mode.SENDING
        return clean
    }

    /** The phone was unlocked: the kept words go now. */
    fun unlocked(): String? {
        if (mode != Mode.LOCKED) return null
        mode = Mode.SENDING
        return heard
    }

    /** The microphone could not start or stopped with an error: the keyboard takes over and the sheet says why. */
    fun micFailed(reason: String) {
        if (mode != Mode.LISTENING) return
        if (heard.isNotBlank()) return
        mode = Mode.TYPING; problem = reason
    }

    /** The agent took the message. Rows after [lastIdBefore] are this turn's. */
    fun sent(lastIdBefore: Long) { if (mode == Mode.SENDING) { mode = Mode.SENT; afterId = lastIdBefore; sawWorking = false } }

    fun sendFailed(reason: String) { if (mode == Mode.SENDING) { mode = Mode.TYPING; typed = true; problem = reason } }

    /** What to draw, from the chat rows and whether the agent is at work. */
    fun view(rows: List<ChatItem>, working: Boolean): AssistView = when (mode) {
        Mode.LISTENING -> AssistView(AssistPhase.LISTENING, heard)
        Mode.TYPING -> AssistView(AssistPhase.TYPING, heard, problem = problem)
        Mode.LOCKED -> AssistView(AssistPhase.LOCKED, heard)
        Mode.SENDING -> AssistView(AssistPhase.SENDING, heard)
        Mode.SENT -> turn(rows.filter { it.id > afterId }, working)
    }

    private fun turn(mine: List<ChatItem>, working: Boolean): AssistView {
        if (working) sawWorking = true
        // Something waits for him: that comes before everything else, and nothing else can answer it.
        mine.lastOrNull { waits(it) }?.let { card ->
            return AssistView(if (Approval.isApproval(card)) AssistPhase.APPROVAL else AssistPhase.ASKING, heard, card = card)
        }
        val reply = mine.lastOrNull { it.kind == "agent" && it.text.isNotBlank() }
        val failure = mine.lastOrNull { it.kind == "notice" && meta(it).optBoolean("error") }
        val step = mine.lastOrNull { it.kind == "activity" || it.kind == "helper" }
        return when {
            failure != null && (reply == null || failure.id > reply.id) -> AssistView(AssistPhase.ERROR, heard, problem = failure.text)
            working -> {
                val label = step?.takeIf { reply == null || it.id > reply.id }?.let { stepLabel(it) }.orEmpty()
                if (label.isNotEmpty() || step != null && reply == null) AssistView(AssistPhase.ACTING, heard, label = label, answer = reply?.text.orEmpty(), answerId = reply?.id)
                else if (reply != null) AssistView(AssistPhase.ACTING, heard, answer = reply.text, answerId = reply.id)
                else AssistView(AssistPhase.THINKING, heard)
            }
            reply != null -> AssistView(AssistPhase.ANSWERING, heard, answer = reply.text, answerId = reply.id)
            // The agent stopped without a word (he stopped it, or it handed the work on): say so rather than spin forever.
            sawWorking -> AssistView(AssistPhase.ANSWERING, heard, answer = mine.lastOrNull { it.kind == "notice" }?.text ?: if (step != null) "On it. The rest is in the chat." else "Done.")
            else -> AssistView(AssistPhase.THINKING, heard)
        }
    }

    companion object {
        fun meta(item: ChatItem): JSONObject = runCatching { JSONObject(item.meta) }.getOrNull() ?: JSONObject()

        /** An approval or a question nobody answered yet. */
        fun waits(item: ChatItem): Boolean = (item.kind == "question" || item.kind == "approval") && meta(item).optString("answer").isEmpty()

        /** A step in a few words: a helper row says what it was started for. */
        fun stepLabel(item: ChatItem): String {
            val m = meta(item)
            val text = if (item.kind == "helper") "Started ${m.optString("label").ifBlank { "a helper" }}" else item.text
            return text.lineSequence().firstOrNull().orEmpty().trim().take(90)
        }
    }
}
