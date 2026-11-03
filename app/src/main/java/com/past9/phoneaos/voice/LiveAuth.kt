package com.past9.phoneaos.voice

import org.json.JSONObject
import java.net.URLEncoder

/**
 * How a call reaches Gemini Live: with this phone's own Google key, or, while linked, with a one-use token the
 * computer mints from ITS key (voice.liveToken). The token can start one call and nothing else, and the key itself
 * never comes to the phone. A.O.S used to hand the phone the raw key over the tailnet; this is the same "no key on
 * the phone" without the key ever leaving the computer.
 */
object LiveAuth {
    const val ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private const val GOOGLE = "wss://generativelanguage.googleapis.com/"

    const val NEEDS_KEY = "Calls use the Google (Gemini) key on your computer. Add one there in Settings, Voice, then call again."
    const val OLD_COMPUTER = "Update the Agent app on your computer, then call again. This version can't start a call for your phone."

    fun keyUrl(key: String) = "$ENDPOINT?key=$key"

    /** Only Google's own Live host is accepted from the computer: a token is never sent anywhere else. */
    fun tokenUrl(base: String, token: String): String? =
        if (!base.startsWith(GOOGLE) || token.isBlank()) null else base + "?access_token=" + URLEncoder.encode(token, "UTF-8")

    sealed interface Token {
        data class Ok(val url: String, val model: String, val voice: String) : Token
        data class No(val why: String, val needsKey: Boolean) : Token
    }

    /** The computer's answer to voice.liveToken. */
    fun parse(value: Any?): Token {
        val o = value as? JSONObject ?: return Token.No("Your computer didn't answer the call request.", false)
        if (!o.optBoolean("ok")) {
            val needs = o.optBoolean("needsKey")
            return Token.No(if (needs) NEEDS_KEY else o.optString("error").ifBlank { "Your computer couldn't start the call." }, needs)
        }
        val url = tokenUrl(o.optString("url"), o.optString("token")) ?: return Token.No("Your computer sent a call token this phone can't use.", false)
        return Token.Ok(url, o.optString("model"), o.optString("voice"))
    }

    /** The computer is older than voice.liveToken: it refuses the method by name. */
    fun isOldComputer(error: String?): Boolean = error?.contains("not available on this device", ignoreCase = true) == true

    /** The call's words for voice.callSave: "in" is the person, "out" the agent. */
    fun record(startedAt: Long, endedAt: Long, lines: List<Triple<String, String, Long>>): JSONObject = JSONObject()
        .put("startedAt", startedAt)
        .put("durationMs", (endedAt - startedAt).coerceAtLeast(0))
        .put("lines", org.json.JSONArray().apply {
            lines.forEach { (who, text, at) -> put(JSONObject().put("who", if (who == "in") "you" else "agent").put("text", text).put("at", at)) }
        })
}
