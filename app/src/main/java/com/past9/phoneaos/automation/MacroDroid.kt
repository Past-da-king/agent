package com.past9.phoneaos.automation

import android.content.Context
import android.content.Intent
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.enumOf
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The other direction of issue #18: the agent tells MacroDroid (or Tasker) to DO something, by running a macro.
 *
 * Local first. The agent sends an Android broadcast on this phone, and MacroDroid's "Intent Received" trigger catches
 * it. Nothing leaves the phone. MacroDroid's trigger matches the action exactly (case-sensitive) and fires only when
 * EVERY extra it was set up with is present and matches, so every extra below is always sent, as a string, even when
 * it is empty. A macro set up with "v1 = *" still fires when the agent had nothing to put in v1.
 *
 *   action  com.past9.phoneaos.MACRO
 *   macro   the macro's name, e.g. "Wifi off"         match it exactly in the trigger
 *   v1..v3  short values, "" when unused             match with * and save into variables
 *   params  a JSON object as text, "{}" when unused  for anything more
 *   from    "agent"
 *
 * Optional, off by default: MacroDroid's own webhook (trigger.macrodroid.com), for when the agent runs on another
 * device. That call goes through MacroDroid's server, and the sheet says so.
 *
 * Pure parts ([MacroCall], [MacroRules]) are plain Kotlin so they test without a phone.
 */
data class MacroCall(
    val macro: String,
    val v1: String = "",
    val v2: String = "",
    val v3: String = "",
    val params: JSONObject = JSONObject(),
) {
    /** The extras as they go on the intent: always all of them, always strings. */
    fun extras(): LinkedHashMap<String, String> = linkedMapOf(
        MacroRules.EXTRA_MACRO to macro,
        "v1" to v1, "v2" to v2, "v3" to v3,
        MacroRules.EXTRA_PARAMS to params.toString(),
        MacroRules.EXTRA_FROM to MacroRules.FROM_AGENT,
    )

    /** The broadcast for this call. Implicit on purpose: MacroDroid and Tasker both register for the action. */
    fun intent(): Intent = Intent(MacroRules.ACTION).apply {
        extras().forEach { (k, v) -> putExtra(k, v) }
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    }

    /** MacroDroid's webhook address for this call: the identifier is the macro name, the values travel as query. */
    fun webhookUrl(deviceId: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val q = extras().filterKeys { it != MacroRules.EXTRA_MACRO }.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return "${MacroRules.WEBHOOK_BASE}/${enc(deviceId.trim())}/${enc(MacroRules.webhookId(macro))}?$q"
    }
}

object MacroRules {
    const val ACTION = "com.past9.phoneaos.MACRO"
    const val EXTRA_MACRO = "macro"
    const val EXTRA_PARAMS = "params"
    const val EXTRA_FROM = "from"
    const val FROM_AGENT = "agent"
    const val WEBHOOK_BASE = "https://trigger.macrodroid.com"
    /** What the Test button runs. */
    const val TEST_MACRO = "Agent test"
    private const val MAX_NAME = 80
    private const val MAX_VALUE = 2_000

    /** The allowlist as the user typed it: one macro per line or comma, trimmed, blanks and repeats dropped. */
    fun parseAllowlist(text: String?): List<String> = text.orEmpty().split('\n', ',')
        .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    /** Whether this macro runs without asking: it is on the list, by name, ignoring case and outer spaces. */
    fun allowed(macro: String, allowlist: List<String>): Boolean =
        macro.trim().isNotEmpty() && allowlist.any { it.trim().equals(macro.trim(), ignoreCase = true) }

    /** Why this call is refused before anything is sent, or null when it is fine. */
    fun problem(call: MacroCall): String? = when {
        call.macro.isBlank() -> "No macro named."
        call.macro.length > MAX_NAME -> "The macro name is too long (at most $MAX_NAME characters)."
        call.macro.any { it.isISOControl() } -> "The macro name has a line break or a control character in it."
        listOf(call.v1, call.v2, call.v3, call.params.toString()).any { it.length > MAX_VALUE } -> "A value is too long (at most $MAX_VALUE characters)."
        else -> null
    }

    /** The webhook identifier MacroDroid is set up with: the macro name, lower case, words joined with dashes. */
    fun webhookId(macro: String): String = macro.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** What the approval card shows: the macro and exactly what it receives. */
    fun approvalDetails(call: MacroCall, via: String): String = buildString {
        append("Macro: ").append(call.macro)
        listOf("v1" to call.v1, "v2" to call.v2, "v3" to call.v3).filter { it.second.isNotEmpty() }.forEach { (k, v) -> append("\n").append(k).append(": ").append(v) }
        if (call.params.length() > 0) append("\nparams: ").append(call.params.toString())
        append("\nVia: ").append(if (via == VIA_WEBHOOK) "MacroDroid's webhook server" else "this phone only")
        append("\nAdd it to Settings > Automation > Allowed macros to stop being asked.")
    }

    const val VIA_INTENT = "intent"
    const val VIA_WEBHOOK = "webhook"

    sealed interface Gate {
        data object Go : Gate
        data class Stop(val reason: String) : Gate
    }

    /**
     * The safety rule: a macro on the allowlist runs. Anything else does something on the phone, so the user says yes
     * first; no answer (a background run, nobody there) and a decline both stop it.
     */
    suspend fun gate(call: MacroCall, allowlist: List<String>, via: String, ask: suspend (String) -> String?): Gate {
        if (allowed(call.macro, allowlist)) return Gate.Go
        return when (ask("APPROVAL|Run the macro \"${call.macro}\"|${approvalDetails(call, via)}")) {
            "Approve" -> Gate.Go
            null -> Gate.Stop("failed: needs the user's yes and nobody answered. Not sent. To run it without asking, they add \"${call.macro}\" to Settings > Automation > Allowed macros.")
            else -> Gate.Stop("failed: the user declined. Not sent.")
        }
    }

    /** The recipe the sheet copies for one macro. */
    fun recipe(macro: String = "Wifi off"): String = """
        MacroDroid: Agent to MacroDroid
        1. New macro. Add trigger: search for Intent Received.
        2. Action: $ACTION (exactly, it is case-sensitive).
        3. Extras: macro = $macro
           Optional: v1 = * (save to a variable), v2 = *, v3 = *, params = * (JSON text).
        4. Add the actions you want (Wifi off, open an app, anything).
        5. In the Agent app: Settings > Automation > Allowed macros, add "$macro" so it runs without asking.
        Tasker: Profile > Event > Intent Received, Action $ACTION; the extras arrive as %macro, %v1, %v2, %v3, %params.
        Optional webhook (goes through MacroDroid's server): trigger Webhook (Url) with identifier ${webhookId(macro)}.
    """.trimIndent()
}

/** Sends a [MacroCall]: the broadcast on this phone, or MacroDroid's webhook when the user switched it on. */
class MacroSender(private val context: Context) {
    fun broadcast(call: MacroCall): String = runCatching { context.sendBroadcast(call.intent()); "sent" }
        .getOrElse { "failed: ${it.message ?: it::class.java.simpleName}" }

    suspend fun webhook(call: MacroCall, deviceId: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(call.webhookUrl(deviceId)).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            try {
                val code = c.responseCode
                if (code in 200..299) "sent" else "failed: MacroDroid's webhook answered $code"
            } finally { c.disconnect() }
        }.getOrElse { "failed: ${it.message ?: it::class.java.simpleName}" }
    }
}

/** What Settings holds for this: the allowlist and the optional webhook. */
interface MacroSettings {
    fun macroAllowlist(): List<String>
    fun macroWebhookOn(): Boolean
    fun macroWebhookId(): String
}

/**
 * macrodroid_run: run a MacroDroid (or Tasker) macro on this phone. Returns "sent" or "failed: why". Lent to linked
 * computers too (Settings > Linked devices), so the agent on the PC runs macros on the phone through the same gate.
 */
class MacroDroidRunTool(
    private val settings: MacroSettings,
    private val send: suspend (MacroCall, String, String) -> String,
) : Tool {
    constructor(context: Context, settings: MacroSettings) : this(settings, { call, via, id ->
        val s = MacroSender(context)
        if (via == MacroRules.VIA_WEBHOOK) s.webhook(call, id) else s.broadcast(call)
    })

    override val spec = ToolSpec("macrodroid_run",
        "Run one of the user's MacroDroid (or Tasker) macros on this phone: toggle a setting, open an app, anything their macro does. " +
            "Sends an Android broadcast that the macro's 'Intent Received' trigger catches; nothing leaves the phone. " +
            "Macros on the user's allowlist run at once; any other asks the user first. Returns 'sent' (MacroDroid got it; it can't report back) or 'failed: why'.",
        schema(listOf("macro"),
            "macro" to str("The macro's name exactly as the user set it in the trigger, e.g. 'Wifi off'"),
            "v1" to str("Optional value the macro reads from v1"), "v2" to str("Optional value for v2"), "v3" to str("Optional value for v3"),
            "params" to JSONObject().put("type", "object").put("description", "Optional extra values, sent as JSON text in 'params'"),
            "via" to enumOf("'intent' (default, this phone only) or 'webhook' (MacroDroid's server; only if the user switched it on)", MacroRules.VIA_INTENT, MacroRules.VIA_WEBHOOK)))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val call = MacroCall(
            macro = input.optString("macro").trim(),
            v1 = input.optString("v1"), v2 = input.optString("v2"), v3 = input.optString("v3"),
            params = input.optJSONObject("params") ?: runCatching { JSONObject(input.optString("params")) }.getOrDefault(JSONObject()),
        )
        MacroRules.problem(call)?.let { return "failed: $it" }
        val via = if (input.optString("via") == MacroRules.VIA_WEBHOOK) MacroRules.VIA_WEBHOOK else MacroRules.VIA_INTENT
        if (via == MacroRules.VIA_WEBHOOK && (!settings.macroWebhookOn() || settings.macroWebhookId().isBlank()))
            return "failed: the MacroDroid webhook is off. The user switches it on in Settings > Automation and pastes their webhook device id. Use via 'intent' on this phone."
        when (val g = MacroRules.gate(call, settings.macroAllowlist(), via) { q -> ctx.ask(q, listOf("Approve", "Decline")) }) {
            is MacroRules.Gate.Stop -> return g.reason
            MacroRules.Gate.Go -> Unit
        }
        val out = send(call, via, settings.macroWebhookId())
        runCatching { ctx.activity("Macro: ${call.macro} ($out)", JSONObject().put("tool", "macrodroid").put("macro", call.macro).put("result", out)) }
        return out
    }
}
