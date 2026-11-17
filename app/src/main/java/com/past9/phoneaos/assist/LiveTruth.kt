package com.past9.phoneaos.assist

import com.past9.phoneaos.data.ChatItem
import org.json.JSONObject

/**
 * What keeps the live voice honest (17 Nov 2026: it said "On it. Done." before the agent had answered, then invented "the PDF has
 * been successfully uploaded" when nothing had been). The voice is the mouth, never the source of an outcome: only the agent's
 * own reply states one, read as written ([com.past9.phoneaos.voice.SpokenResult]). These rules are in code, not only in the
 * instructions: the hand-off's answer says the job is NOT done, the sheet's canned "Done." is never read as a result, a spoken
 * claim with no agent reply behind it is caught and corrected, and a late reply is announced once so there is never a silent gap.
 */
object LiveTruth {
    /** The agent has said nothing this long after a hand-off: the voice says so once (and keeps listening). */
    const val LATE_MS = 20_000L

    /** Words that state an outcome. "On it" and "still working" are not claims. */
    private val CLAIM = Regex(
        """\b(done|uploaded|submitted|completed|finished|confirmed|successfully|all set|went through|has been (sent|uploaded|submitted|done|completed|saved|booked)|was (sent|uploaded|submitted|done|completed)|is (done|sent|uploaded|submitted|complete))\b""",
        RegexOption.IGNORE_CASE,
    )
    /** "Not done yet", "not uploaded", "once it is done": a negation or a future is not a claim. */
    private val HEDGE = Regex("""\b(not|n't|never|yet|once|when|until|will|still|if|no result)\b[^.!?]{0,30}\b(done|uploaded|submitted|completed|finished|confirmed|sent)\b""", RegexOption.IGNORE_CASE)

    /** True when [said] (the voice's own words) states an outcome. */
    fun claimsOutcome(said: String): Boolean {
        if (!CLAIM.containsMatchIn(said)) return false
        val unhedged = said.split(Regex("""(?<=[.!?])\s+""")).any { s -> CLAIM.containsMatchIn(s) && !HEDGE.containsMatchIn(s) }
        return unhedged
    }

    /** The tool answer to hand_to_agent: the model is told, in the one place it reads right after calling, that nothing is done yet. */
    fun ack(tracked: Boolean, where: String): String =
        (if (tracked) "The job is with the agent. It is NOT done: there is no result yet. " else "The job went to ${where.ifBlank { "the other chat" }}. It is NOT done: there is no result yet. ") +
            "Say ONE short holding line (a few words, like \"On it.\") and nothing more. Never say done, uploaded, sent, confirmed, finished or any outcome, and never guess one. " +
            "The agent's own reply arrives as a message starting \"${com.past9.phoneaos.voice.SpokenResult.HEAD}\": read it out as written. If they ask meanwhile, say there is no result yet."

    /** Told to the voice once when the agent is late. */
    fun lateLine(): String =
        "[The agent has not replied yet. Say once, in a few words, that it is still working and you will read it out the moment it arrives. There is no result yet: do not state or guess any outcome. Then keep listening.]"

    /** Told to the voice when it claimed an outcome the agent never reported. */
    fun correctionLine(said: String): String =
        "[Correction: you just said \"${said.trim().take(120)}\" but the agent has NOT reported any result. Say in one short line that you spoke too soon and that there is no result yet. State no outcome.]"

    /** The agent's rows after [after] that this call has not read out (it ended, or the row came in between): said at the start of the next call. */
    fun missed(rows: List<ChatItem>, after: Long, told: Set<Long>): List<ChatItem> =
        rows.filter { it.kind == "agent" && it.id > after && it.id !in told && it.text.isNotBlank() && !isCall(it) }

    private fun isCall(i: ChatItem) = runCatching { JSONObject(i.meta) }.getOrNull()?.let { it.optBoolean("call") || it.optBoolean("live") } ?: false

    /** What the next call opens with: the replies that came while nobody was on the line, in order, each framed to be read as written. */
    fun whileAway(rows: List<ChatItem>): List<String> =
        rows.map { "[While the call was closed the agent replied. This is its real answer; read it as written, once.]\n" + com.past9.phoneaos.voice.SpokenResult.frame(it.text) }

    /** How many lines of the last call the next call is given, and how many characters at most. */
    const val PREVIOUS_LINES = 30
    const val PREVIOUS_CHARS = 3_500

    /**
     * The previous call as background for the next one, newest line first and bounded. Empty when there was none. It is
     * context only: the voice does not read it out and never treats an old job as still pending unless the agent says so.
     */
    fun previousCall(lines: List<LiveLine>): String {
        if (lines.isEmpty()) return ""
        val out = StringBuilder()
        for (l in lines.asReversed().take(PREVIOUS_LINES)) {
            val row = (if (l.who == "in") "User: " else "You: ") + l.text.replace(Regex("\\s+"), " ").trim().take(400) + "\n"
            if (out.length + row.length > PREVIOUS_CHARS) break
            out.append(row)
        }
        return "\n\nTheir previous live call with you, newest line first (background only: do not read it out; use it when they refer to it, and never treat an old job in it as done or still running unless the agent told you):\n" + out.toString().trimEnd()
    }
}
