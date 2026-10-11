package com.past9.phoneaos.agent

import org.json.JSONObject

/**
 * One trivial tool call per AI the agent runs on, made after the app starts or updates. A provider that can't
 * run tools at all (Codex once couldn't start its tool host) is found here, not by the user halfway through a job.
 */
class SelfCheckPing : Tool {
    val nonce = java.util.UUID.randomUUID().toString().take(8)
    @Volatile var calls = 0
    override val spec = ToolSpec("selfcheck_ping", "Connectivity check. Call it once, with no arguments; it returns a word.", JSONObject().put("type", "object").put("properties", JSONObject()))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String { calls++; return nonce }
}

/** [power] is what the user sees ("ChatGPT", "Anthropic"); [detail] is why it failed, when it did. */
data class SelfCheckResult(val ok: Boolean, val power: String, val detail: String = "")

object SelfCheck {
    const val PROMPT = "Call the selfcheck_ping tool once, then reply with the word it returns and nothing else."
    const val SYSTEM = "You are a connectivity check inside a phone app. Use only the selfcheck_ping tool."

    /** The notice the user sees when tools don't work on their AI. Never asks them to send anything anywhere. */
    fun notice(r: SelfCheckResult) =
        "Tools don't work with ${r.power} right now, so I can't do jobs on it. This is a fault in the app. You did nothing wrong, and there is nothing for you to send anyone. " +
            "I'll test again the next time the app starts. What failed: ${r.detail}"
}
