package com.past9.phoneaos.agent

/**
 * Whatever an agent asks of the user, there is always a No: "I don't want to do that myself". The agent's own
 * buttons come first, and No is the last one. A question that already has a way to refuse (No, Not now, Decline)
 * is left as it was.
 */
object AskOptions {
    const val NO = "No"

    private val REFUSALS = setOf("no", "no thanks", "not now", "decline", "cancel", "never mind")

    private fun refuses(option: String) = option.trim().trimEnd('.', '!').lowercase() in REFUSALS

    /** True when the agent already offered its own way to say no. */
    fun hasRefusal(options: List<String>) = options.any(::refuses)

    fun withNo(options: List<String>): List<String> = if (hasRefusal(options)) options else options + NO

    /** At most [max] buttons for a place that can't fit more (a notification), with No always kept. */
    fun fit(options: List<String>, max: Int): List<String> {
        if (options.size <= max) return options
        val no = options.lastOrNull(::refuses) ?: NO
        return options.filter { it != no }.take(max - 1) + no
    }

    /** What the agent reads when the user turned down a request that had no refusal of its own. */
    const val TURNED_DOWN = "The user said No: they don't want to do this themselves. Don't ask again. Find another way to get it done, or tell them plainly what you can't do."
}
