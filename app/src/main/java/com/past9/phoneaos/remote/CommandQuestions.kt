package com.past9.phoneaos.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Paired hands return questions to the app-owned context that dispatched their command. */
internal class CommandQuestions(
    private val commandId: String,
    private val origin: JSONObject,
    private val call: suspend (String, List<Any?>, Long) -> Any?,
    private val fallback: suspend (String, List<String>) -> String?,
    private val waitForAbsent: suspend () -> Unit = { delay(15_000) },
    private val verifiedFallbackRequest: (JSONObject?) -> Unit = {},
) {
    private val sequence = AtomicInteger()

    suspend fun ask(question: String, options: List<String>, actualOrigin: JSONObject = origin): String? {
        verifiedFallbackRequest(null)
        if (commandId.isBlank()) return fallback(question, options)
        val request = listOf(commandId, sequence.incrementAndGet(), question, options, actualOrigin)
        val value = try {
            call("device.ask", request, 600_000)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RpcException) {
            // A response may have been lost after a card was created. Never create a second native card on an
            // ambiguous transport failure. Only a definitely absent/older brain falls back to the person's sheet.
            val message = error.message.orEmpty()
            val absent = message.contains("Not connected to a computer", ignoreCase = true)
            val old = message.contains("Unknown RPC", ignoreCase = true) || message.contains("No such RPC", ignoreCase = true) || message == "not available on this device"
            if (!absent && !old) throw error
            if (absent) waitForAbsent()
            return fallback(question, options)
        }
        val response = value as? JSONObject ?: throw RpcException("The computer returned an invalid question response.")
        if (!response.optBoolean("handled")) {
            verifiedFallbackRequest(response.optJSONObject("request"))
            return fallback(question, options)
        }
        return if (response.isNull("answer")) null else response.optString("answer").takeIf { it.isNotBlank() }
    }
}
