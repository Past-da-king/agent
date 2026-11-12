package com.past9.phoneaos.voice

/**
 * How a finished answer from the agent reaches the voice, so the voice SAYS it instead of re-summarising it.
 *
 * Before: the voice was told "say the part that matters in one or two short sentences ... do not read it out word for
 * word", and the answer was cut at 1,500 characters. The agent had already written a summary; the voice summarised that
 * again and dropped the detail (and never read a whole paragraph). Measured on gemini-3.1-flash-live-preview with a
 * 110-word answer holding 17 facts: 2 of 17 facts survived.
 *
 * Now the answer is framed with a marker the voice's instructions explain ([HEAD] ... [END]): read it as written. The
 * agent is also told, in the hand-off, that its reply is read aloud, so it writes the spoken version itself. Google's Live
 * API has no output-length or verbatim setting; the supported levers are the system instruction and the content that is
 * sent. A tool response is the documented place for a result (https://aistudio.google.com/docs/live-api/tools), but
 * gemini-3.1-flash-live-preview only supports synchronous calls and the answer here arrives long after hand_to_agent
 * returned, so the answer is sent as a client turn.
 */
object SpokenResult {
    const val HEAD = "[Agent result, speak as written]"
    const val END = "[End of agent result]"
    /** About four minutes of speech. Longer than this is cut at a sentence and the rest stays in the chat. */
    const val MAX_CHARS = 4000
    const val REST = "(The rest is in the chat.)"

    /** What the voice's instructions say about a message that starts with [HEAD]. */
    const val RULE = "When a message starts with \"$HEAD\", the text up to \"$END\" is the agent's answer and it is ALREADY written to be heard. " +
        "Read it out loud as written, all of it, in order, in the first person as your own words. " +
        "Do NOT shorten it, summarise it, reorder it, merge items or leave details out. Never summarise a summary. " +
        "Never say the two marker lines aloud. You may add one short closing offer, nothing more. " +
        "Say numbers, names and times naturally and skip symbols. If it ends with \"$REST\", say that too."

    /** The same rule for a voice call, where the voice IS the agent and speaks of its own work in the first person. */
    val RULE_CALL: String get() = RULE.replace("is the agent's answer", "is the result of your own work")

    /** What the agent is told in a hand-off: its reply is read aloud, word for word. */
    const val AGENT_NOTE = "Your reply will be read aloud word for word by the voice, so write it as speech: plain sentences, the main point first, " +
        "no markdown, tables, code or long links, and every detail the user needs. The voice will not shorten it."

    /** The answer as the voice gets it: marked, speakable, bounded. */
    fun frame(answer: String): String {
        val spoken = cut(speakable(answer))
        return "$HEAD\n$spoken\n$END"
    }

    /** Markdown and symbols out, so reading it as written sounds like a person. */
    fun speakable(text: String): String {
        var t = text.replace("\r", "")
        t = t.replace(Regex("""```[a-zA-Z0-9]*\n?"""), "")
        t = t.replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
        t = t.replace(Regex("""\[([^\]]+)]\((https?://[^)]+)\)"""), "$1")
        t = t.replace(Regex("""https?://\S+"""), "a link, which is in the chat")
        t = t.replace(Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]*"""), "")
        t = t.replace(Regex("""(?m)^[ \t]*[-*+][ \t]+"""), "")
        t = t.replace(Regex("""(?m)^[ \t]*(\d+)[.)][ \t]+"""), "$1. ")
        t = t.replace(Regex("""(?m)^[ \t]*\|?[ \t]*:?-{2,}:?[ \t]*(\|[ \t]*:?-{2,}:?[ \t]*)*\|?[ \t]*$"""), "")
        t = t.lines().joinToString("\n") { line -> if (line.contains('|')) line.split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") else line }
        t = t.replace(Regex("""(\*\*|__|\*|`)"""), "")
        t = t.replace(Regex("""[ \t]+"""), " ").replace(Regex("""\n{3,}"""), "\n\n")
        return t.trim()
    }

    /** Cuts at the last sentence end before [MAX_CHARS] and says where the rest is. */
    fun cut(text: String): String {
        if (text.length <= MAX_CHARS) return text
        val head = text.take(MAX_CHARS)
        val end = maxOf(head.lastIndexOf(". "), head.lastIndexOf(".\n"), head.lastIndexOf("? "), head.lastIndexOf("! "))
        val body = if (end > MAX_CHARS / 2) head.substring(0, end + 1) else head
        return body.trim() + "\n" + REST
    }
}
