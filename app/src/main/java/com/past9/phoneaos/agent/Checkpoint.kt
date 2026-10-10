package com.past9.phoneaos.agent

import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.TurnRow
import org.json.JSONObject

/**
 * A helper's working state, saved after every step so a dead app loses nothing. The model's own transcript
 * (every assistant message, every tool call and result) goes into the `turns` table under "helper-<id>";
 * the step count and status live in the helper's chat item. After a restart the helper is rebuilt from here.
 */
object HelperCheckpoint {
    fun thread(helperId: Long) = "helper-$helperId"

    const val CALL_UNKNOWN = "No result was recorded for this call: the app closed while it ran. It may or may not have happened, so check before you do it again."

    suspend fun append(db: AppDb, helperId: Long, m: Msg) {
        db.chat().insertTurn(TurnRow(thread = thread(helperId), json = m.toJson().toString()))
    }

    /** Replace the saved transcript with this history (a helper starting, resuming, or sent back to work). */
    suspend fun replace(db: AppDb, helperId: Long, history: List<Msg>) {
        db.chat().clearTurns(thread(helperId))
        history.forEach { append(db, helperId, it) }
    }

    /** The saved transcript in order (oldest first), skipping rows that no longer parse. */
    suspend fun load(db: AppDb, helperId: Long): List<Msg> =
        db.chat().recentTurns(thread(helperId), 100_000).reversed().mapNotNull { runCatching { Msg.fromJson(JSONObject(it.json)) }.getOrNull() }

    suspend fun clear(db: AppDb, helperId: Long) = db.chat().clearTurns(thread(helperId))

    /** Tool calls the model made in its last step that have no recorded result: they may or may not have run. */
    fun unconfirmed(msgs: List<Msg>): List<Block.ToolCall> {
        val last = msgs.lastOrNull { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() } ?: return emptyList()
        val after = msgs.subList(msgs.indexOf(last) + 1, msgs.size)
        val done = after.flatMap { it.blocks.filterIsInstance<Block.ToolResult>() }.map { it.callId }.toSet()
        return last.toolCalls.filter { it.id !in done }
    }

    fun describe(c: Block.ToolCall): String = "${c.name}(${c.input.toString().take(200)})"

    /**
     * The conversation a resumed API helper starts from: what it had said and done, with calls that never got a
     * result stubbed as "may or may not have run", then one note saying the app restarted.
     * Returns null when nothing usable was saved (the caller falls back to the brief and activity trail).
     */
    fun resumeHistory(saved: List<Msg>, brief: String, trail: String): MutableList<Msg>? {
        val msgs = saved.toMutableList()
        // Start on a real user message, like any stored thread.
        while (msgs.isNotEmpty() && !(msgs.first().role == Role.USER && msgs.first().blocks.any { it is Block.Text })) msgs.removeAt(0)
        if (msgs.isEmpty()) return null
        val unknown = unconfirmed(msgs)
        val fixed = AgentRuntime.Repair.repair(msgs, missing = CALL_UNKNOWN)
        val note = Msg.user(restartNote(unknown, trail)).blocks
        val last = fixed.lastOrNull()
        // The turn order has to stay valid: ride along in the trailing user message (the tool results), or add one.
        if (last != null && last.role == Role.USER) fixed[fixed.lastIndex] = Msg(Role.USER, last.blocks + note) else fixed += Msg(Role.USER, note)
        return fixed
    }

    fun restartNote(unknown: List<Block.ToolCall>, trail: String): String = buildString {
        append("[Resumed after the app restarted. You were cut off mid-job. Check what was in progress, for example jobs running on machines, before redoing anything; don't repeat what is already done.")
        if (unknown.isNotEmpty()) append("\nThe last step's call${if (unknown.size > 1) "s" else ""} may or may not have run: ${unknown.joinToString("; ") { describe(it) }}.")
        if (trail.isNotBlank()) append("\nWhat you had done so far:\n$trail")
        append("]")
    }
}
