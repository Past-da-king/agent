package com.past9.phoneaos.agent

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Something the agent can do. */
interface Tool {
    val spec: ToolSpec
    suspend fun run(input: JSONObject, ctx: ToolContext): String
}

/** What a running tool can reach: the user, and who it is running for. */
interface ToolContext {
    /** "main" for the user-facing agent; a helper's label otherwise. */
    val agentLabel: String
    /** Show a short line of activity in the chat ("Searched memory"). Returns an id to update. */
    suspend fun activity(text: String, meta: JSONObject = JSONObject()): Long
    suspend fun updateActivity(id: Long, text: String, meta: JSONObject = JSONObject())
    /** Ask the user and wait for the answer. Null when nobody can answer (a background run). */
    suspend fun ask(question: String, options: List<String>): String?
    /** Post a phone notification. */
    suspend fun notify(title: String, body: String)
    /** Same, callable from non-suspending callbacks. */
    fun notifyBlocking(title: String, body: String) = kotlinx.coroutines.runBlocking { notify(title, body) }
}

sealed interface AgentEvent {
    data class Thinking(val step: Int) : AgentEvent
    data class Said(val text: String) : AgentEvent
    data class ToolStarted(val call: Block.ToolCall) : AgentEvent
    data class ToolFinished(val call: Block.ToolCall, val result: String, val isError: Boolean) : AgentEvent
}

/**
 * The provider-neutral agent loop: ask the model, run any tools it calls, feed the results
 * back, repeat until it answers in plain text (or hits the step cap).
 *
 * `history` is mutated in place: every assistant message and tool-result message is appended,
 * so the caller can persist exactly what the model saw.
 */
val imageMarker = Regex("""\[\[image:([^\]]+)]]""")

class AgentLoop(
    private val provider: LlmProvider,
    private val model: String,
    private val tools: List<Tool>,
    /** No cap by default: the agent is long-running and stops when the work is done or the user stops it. */
    private val maxSteps: Int = Int.MAX_VALUE,
) {
    private val byName = tools.associateBy { it.spec.name }

    suspend fun run(
        system: String,
        history: MutableList<Msg>,
        ctx: ToolContext,
        onEvent: suspend (AgentEvent) -> Unit = {},
        onAppend: suspend (Msg) -> Unit = {},
        /** Messages sent in while it works (the main agent steering a helper). Read before every step. */
        incoming: suspend () -> List<String> = { emptyList() },
    ): String {
        var lastText = ""
        for (step in 1..maxSteps) {
            incoming().takeIf { it.isNotEmpty() }?.let { notes ->
                val text = Block.Text(notes.joinToString("\n\n") { "[The main agent, while you work] $it" })
                // Ride along in the last user message (after tool results) so the turn order stays valid everywhere.
                val last = history.lastOrNull()
                if (last != null && last.role == Role.USER) history[history.lastIndex] = Msg(Role.USER, last.blocks + text) else history += Msg(Role.USER, listOf(text))
            }
            onEvent(AgentEvent.Thinking(step))
            Trim.fit(history)
            val completion = try { provider.complete(system, history, tools.map { it.spec }, model) } catch (e: ProviderException) {
                // A history the provider calls malformed would block every message from now on. Rebuild it
                // without the earlier tool exchanges and try once more instead of making the user clear their data.
                if (e.status != 400 || !Regex("tool", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())) throw e
                val fixed = AgentRuntime.Repair.flatten(AgentRuntime.Repair.repair(history))
                history.clear(); history.addAll(fixed)
                provider.complete(system, history, tools.map { it.spec }, model)
            }
            val reply = completion.message
            history += reply; onAppend(reply)
            if (reply.text.isNotBlank()) { lastText = reply.text; onEvent(AgentEvent.Said(reply.text)) }
            val calls = reply.toolCalls
            if (calls.isEmpty()) return lastText

            val results = calls.map { call ->
                onEvent(AgentEvent.ToolStarted(call))
                val tool = byName[call.name]
                val (out, err) = if (tool == null) "Unknown tool ${call.name}" to true else try {
                    tool.run(call.input, ctx) to false
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    "Error: ${e.message ?: e::class.simpleName}" to true
                }
                val clipped = if (out.length > 24_000) out.take(24_000) + "\n…(cut, ${out.length} chars total)" else out
                onEvent(AgentEvent.ToolFinished(call, clipped, err))
                Block.ToolResult(call.id, clipped, err)
            }
            // Tools can hand the model pictures by writing [[image:/path]] lines (e.g. photos from the gallery).
            val images = results.flatMap { r -> imageMarker.findAll(r.content).map { Block.Image(it.groupValues[1]) }.toList() }.filter { java.io.File(it.path).exists() }
            val resultMsg = Msg(Role.USER, results + images)
            history += resultMsg; onAppend(resultMsg)
        }
        return lastText.ifBlank { "I stopped after $maxSteps steps without finishing. Tell me to carry on and I will pick it up." }
    }
}

/**
 * A long run piles up tool output (pages, screenshots, code results) until the model's context is full.
 * Before each step, old tool results beyond the budget are cut down to a short stub, newest kept whole,
 * so the agent can keep going for hundreds of steps. Its own words and the user's are never cut.
 */
object Trim {
    private const val BUDGET = 350_000 // characters, comfortably inside a 128k-token window
    private const val KEEP_RECENT = 8   // messages always left untouched

    fun size(m: Msg): Int = m.blocks.sumOf { b -> when (b) { is Block.Text -> b.text.length; is Block.ToolResult -> b.content.length; is Block.ToolCall -> b.input.toString().length; is Block.Image -> 6_000; else -> 0 } }

    fun fit(history: MutableList<Msg>) {
        var total = history.sumOf { size(it) }
        if (total <= BUDGET) return
        for (i in 0 until (history.size - KEEP_RECENT).coerceAtLeast(0)) {
            if (total <= BUDGET) return
            val m = history[i]
            if (m.blocks.none { it is Block.ToolResult || it is Block.Image }) continue
            val slim = Msg(m.role, m.blocks.mapNotNull { b -> when {
                b is Block.ToolResult && b.content.length > 400 -> b.copy(content = b.content.take(300) + "\n[...older output trimmed to save room]")
                b is Block.Image -> null
                else -> b
            } })
            if (slim.blocks.isEmpty()) continue
            total += size(slim) - size(m); history[i] = slim
        }
    }
}
