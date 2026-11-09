package com.past9.phoneaos.automation

import org.json.JSONArray
import org.json.JSONObject

/**
 * The outside entry point for Android automation apps (GitHub issue #18): MacroDroid, Tasker or anything else on this
 * phone fires a routine or sends the agent an instruction over 127.0.0.1, carrying the token shown in Settings.
 *
 * Pure Kotlin and org.json only, so the wire format is testable without Android. The socket around it lives in
 * AutomationServer.kt.
 *
 * Wire shapes (every reply is JSON):
 *   GET  /v1/routines       {"routines":[{"id":12,"name":"Morning brief","enabled":true,"schedule":"Every day at 07:00","lastResult":"..."}]}
 *   POST /v1/routines/run   {"runId":"run-3","status":"running"}            then GET /v1/runs/run-3
 *   POST /v1/instruction    {"runId":"instruction-4","status":"running"}    then GET /v1/instructions/instruction-4
 *   GET  /v1/runs/{id}      {"runId":"run-3","status":"done","result":"the full text","durationMs":4210}
 *   a refusal               {"error":"unauthorized","code":401}
 * Statuses: running, done, failed.
 */

/** A routine, as an automation app sees it. */
data class AutomationRoutine(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val schedule: String,
    val lastResult: String = "",
)

/**
 * The runs and instructions this process started: id to status and the FULL result text, newest 50 kept. In memory on
 * purpose: a run belongs to the process that is doing it, and a lost one must not look like a finished one.
 */
object AutomationRuns {
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    private const val KEEP = 50

    class State(val id: String, val startedAt: Long = System.currentTimeMillis()) {
        @Volatile var status: String = RUNNING
        @Volatile var result: String = ""
        @Volatile var finishedAt: Long = 0L
    }

    private val byId = LinkedHashMap<String, State>()
    private var counter = 0L

    /** A new run id, kept in the registry as running. [kind] is "run" or "instruction". */
    @Synchronized
    fun start(kind: String = "run"): String {
        val id = "$kind-${++counter}"
        byId[id] = State(id)
        while (byId.size > KEEP) byId.remove(byId.keys.first())
        return id
    }

    @Synchronized
    fun get(id: String): State? = byId[id]

    /** The work finished: the full text is kept, not the 500 characters a routine row stores. */
    @Synchronized
    fun finish(id: String, result: String, ok: Boolean = true) {
        val s = byId[id] ?: return
        s.result = result
        s.status = if (ok) DONE else FAILED
        s.finishedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun fail(id: String, why: String) = finish(id, why, ok = false)

    /** Only for tests: forget every run. */
    @Synchronized
    fun clear() {
        byId.clear()
        counter = 0L
    }
}

/** The four replies an automation app reads, plus the error shape. */
object AutomationApi {
    private const val PREFIX = "Bearer "

    /**
     * The caller may act: the header is "Bearer <token>" for the token shown in Settings. Compared in constant time,
     * so a wrong token cannot be guessed one character at a time.
     */
    fun authorised(header: String?, token: String): Boolean {
        if (token.isBlank() || header.isNullOrBlank()) return false
        return java.security.MessageDigest.isEqual((PREFIX + token).toByteArray(), header.trim().toByteArray())
    }

    fun routines(list: List<AutomationRoutine>): String = JSONObject().put("routines", JSONArray().apply {
        list.forEach { r ->
            put(JSONObject().put("id", r.id).put("name", r.name).put("enabled", r.enabled)
                .put("schedule", r.schedule).put("lastResult", r.lastResult))
        }
    }).toString()

    /** What a fire or an instruction answers at once: the id to poll for the outcome. */
    fun runAck(runId: String): String = JSONObject().put("runId", runId).put("status", AutomationRuns.RUNNING).toString()

    /** A run as it stands now. [s] null means there is no such run in this process (an app restart forgets them). */
    fun runState(s: AutomationRuns.State?): String = if (s == null)
        error(404, "no such run in this process: the app or the run has been restarted")
    else JSONObject().put("runId", s.id).put("status", s.status).put("result", s.result)
        .put("needsApproval", needsApproval(s.result))
        .put("durationMs", (if (s.finishedAt > 0) s.finishedAt else System.currentTimeMillis()) - s.startedAt).toString()

    /**
     * True when the run stopped because only the user could approve what it wanted to do. The refusal comes from the
     * existing approval gate, which fails closed when nobody is there; an automation should be able to see that.
     */
    fun needsApproval(result: String): Boolean = result.contains("nobody is around", ignoreCase = true) ||
        result.contains("Needs the user's approval", ignoreCase = true)

    fun error(code: Int, message: String): String = JSONObject().put("error", message).put("code", code).toString()
}
