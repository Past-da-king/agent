package com.past9.phoneaos.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Intents IN: any app on this phone fires a routine or sends the agent an instruction with a broadcast, alongside the
 * loopback HTTP endpoint (issue #18). Same token, same "Allow outside runs" switch, same handlers, same safety: the run
 * has nobody watching, so anything that needs the user's approval is refused.
 *
 *   action  com.past9.phoneaos.RUN_ROUTINE   extras: token, routine (its name or id), note (optional)
 *   action  com.past9.phoneaos.INSTRUCT      extras: token, text
 *   package com.past9.phoneaos               the sender MUST name the package: Android delivers a broadcast to an app
 *                                            that is not running only when it is addressed to that app
 *
 * Unlike the HTTP endpoint this works when the app is not running: Android starts it for the broadcast.
 * A sender that uses an ordered broadcast reads the answer as result data, the same JSON the endpoint returns.
 *
 * [IntentTriggers.handle] is plain Kotlin so the rules test without a phone.
 */
object IntentTriggers {
    const val RUN_ROUTINE = "com.past9.phoneaos.RUN_ROUTINE"
    const val INSTRUCT = "com.past9.phoneaos.INSTRUCT"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_ROUTINE = "routine"
    const val EXTRA_NOTE = "note"
    const val EXTRA_TEXT = "text"

    /** The code is an HTTP-style status so both doors answer alike; the body is the endpoint's own JSON. */
    class Outcome(val code: Int, val body: String) {
        val ok get() = code == 200
    }

    /** The token matches, compared in constant time. */
    fun tokenOk(given: String?, token: String): Boolean =
        token.isNotBlank() && !given.isNullOrBlank() && java.security.MessageDigest.isEqual(token.toByteArray(), given.trim().toByteArray())

    fun handle(action: String?, extras: Map<String, String?>, token: String, h: AutomationServer.Handlers): Outcome {
        // The token first: an app without it learns nothing, not even whether the switch is on.
        if (!tokenOk(extras[EXTRA_TOKEN], token)) return Outcome(401, AutomationApi.error(401, "unauthorized"))
        if (!h.allowed()) return Outcome(403, AutomationApi.error(403, "outside runs are off: turn on Allow outside runs in Settings > Automation"))
        return when (action) {
            RUN_ROUTINE -> {
                val key = extras[EXTRA_ROUTINE].orEmpty().trim()
                if (key.isEmpty()) return Outcome(400, AutomationApi.error(400, "add the extra \"routine\" with the routine's name or id"))
                val all = h.routines()
                val id = key.toLongOrNull()
                val found = (if (id != null) all.firstOrNull { it.id == id } else null)
                    ?: all.firstOrNull { it.name.equals(key, ignoreCase = true) }
                    ?: all.filter { it.name.contains(key, ignoreCase = true) }.singleOrNull()
                    ?: return Outcome(404, JSONObject(AutomationApi.error(404, "no routine called \"$key\"")).put("routines", JSONArray(all.map { it.name })).toString())
                val runId = h.fire(found, extras[EXTRA_NOTE].orEmpty()) ?: return Outcome(503, AutomationApi.error(503, "could not start \"${found.name}\""))
                Outcome(200, AutomationApi.runAck(runId))
            }
            INSTRUCT -> {
                val text = extras[EXTRA_TEXT].orEmpty().trim()
                if (text.isEmpty()) return Outcome(400, AutomationApi.error(400, "add the extra \"text\" with the instruction"))
                val runId = h.instruct(text) ?: return Outcome(503, AutomationApi.error(503, "the instruction could not be started"))
                Outcome(200, AutomationApi.runAck(runId))
            }
            else -> Outcome(404, AutomationApi.error(404, "no such action"))
        }
    }

    /** Where the receiver gets the token and the handlers: the app's own graph, replaced in tests. */
    @Volatile
    var source: (Context) -> Pair<String, AutomationServer.Handlers> = { c ->
        val g = com.past9.phoneaos.App.graph(c)
        g.settings.automationToken() to g.automationHandlers
    }

    /** The recipe the Settings sheet copies. */
    fun recipe(token: String): String = """
        Any app to Agent, by intent
        Send a BROADCAST with:
          Package: com.past9.phoneaos   (needed: without it Android does not deliver it)
          Action:  $RUN_ROUTINE
          Extras:  token = $token
                   routine = Morning brief   (the routine's name or id)
                   note = anything you want it to know (optional)
        Or send the agent an instruction:
          Action:  $INSTRUCT
          Extras:  token = $token
                   text = What is on my list today?
        MacroDroid: action Send Intent, Target Broadcast. Tasker: Send Intent, Target Broadcast Receiver.
        It works even when the Agent app is closed. Anything that needs your approval is refused.
    """.trimIndent()
}

/** The door itself. Exported, so the token is the lock; a wrong or missing token does nothing at all. */
class IntentTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = listOf(IntentTriggers.EXTRA_TOKEN, IntentTriggers.EXTRA_ROUTINE, IntentTriggers.EXTRA_NOTE, IntentTriggers.EXTRA_TEXT)
            .associateWith { k -> runCatching { intent.extras?.get(k)?.toString() }.getOrNull() }
        val action = intent.action
        val ordered = isOrderedBroadcast
        val pending = goAsync()
        // Listing routines reads the database, so the work leaves the main thread.
        Thread {
            try {
                val (token, handlers) = IntentTriggers.source(context.applicationContext)
                val out = IntentTriggers.handle(action, extras, token, handlers)
                if (ordered) runCatching { pending.setResult(out.code, out.body, null) }
            } catch (_: Throwable) {
            } finally { runCatching { pending.finish() } }
        }.start()
    }
}
