package com.past9.phoneaos.automation

import android.content.Context
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.agent.PromptRole
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.data.TriggerRow
import com.past9.phoneaos.triggers.Routines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the endpoint does with a request (GitHub issue #18): it reads the phone's own routines, fires one through the
 * very worker the Routines screen uses, and sends the agent an instruction with nobody watching.
 *
 * Nobody watching is the whole safety story: a run fired from outside is non-interactive, so any tool that would send,
 * spend or delete asks for an approval it cannot get, refuses, and says so. Nothing here can approve anything.
 */
class PhoneAutomation(
    private val settings: SettingsStore,
    private val runtime: AgentRuntime,
    private val scope: CoroutineScope,
    private val triggers: suspend () -> List<TriggerRow>,
) {
    fun handlers(): AutomationServer.Handlers = AutomationServer.Handlers(
        allowed = settings::automationAllowed,
        routines = { routinesNow() },
        fire = { routine, note ->
            // By id: the name was resolved against this same list a moment ago.
            Routines.runTraced(routine.id.toString(), note.ifBlank { "automation" })
        },
        instruct = { text ->
            val runId = AutomationRuns.start("instruction")
            scope.launch {
                val out = runCatching { runtime.runBackground(INSTRUCTION_NAME, text, role = PromptRole.MAIN) }
                out.fold(
                    { AutomationRuns.finish(runId, it) },
                    { AutomationRuns.fail(runId, "It stopped: ${it.message ?: it::class.java.simpleName}") },
                )
            }
            runId
        },
        run = { id -> AutomationRuns.get(id) },
    )

    private fun routinesNow(): List<AutomationRoutine> = runCatching {
        kotlinx.coroutines.runBlocking { triggers() }.map {
            AutomationRoutine(id = it.id, name = it.name, enabled = it.enabled, schedule = scheduleOf(it), lastResult = it.lastResult)
        }
    }.getOrDefault(emptyList())

    /** When a routine runs, in the same words the Routines screen uses. */
    private fun scheduleOf(t: TriggerRow): String = runCatching {
        when (t.kind) {
            "at" -> "Once, at " + java.time.Instant.ofEpochMilli(t.spec.toLong())
                .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm"))
            "daily" -> "Every day at ${t.spec}"
            "weekly" -> Routines.weeklyLabel(t.spec)
            "interval" -> "Every ${t.spec} minutes"
            "email" -> "When an email matches \"${t.spec}\""
            "notification" -> "When a notification arrives from ${t.spec}"
            else -> "${t.kind.replaceFirstChar { it.uppercase() }} ${t.spec}".trim()
        }
    }.getOrDefault(t.kind)

    companion object {
        /** The name an outside instruction runs under: it is in the chat and the notification as this. */
        const val INSTRUCTION_NAME = "From your automation"
    }
}
