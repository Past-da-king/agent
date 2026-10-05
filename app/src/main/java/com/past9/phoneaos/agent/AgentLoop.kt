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
    private val maxSteps: Int = 16,
) {
    private val byName = tools.associateBy { it.spec.name }

    suspend fun run(
        system: String,
        history: MutableList<Msg>,
        ctx: ToolContext,
        onEvent: suspend (AgentEvent) -> Unit = {},
        onAppend: suspend (Msg) -> Unit = {},
    ): String {
        var lastText = ""
        for (step in 1..maxSteps) {
            onEvent(AgentEvent.Thinking(step))
            val completion = provider.complete(system, history, tools.map { it.spec }, model)
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
