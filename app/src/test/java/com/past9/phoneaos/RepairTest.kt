package com.past9.phoneaos

import com.past9.phoneaos.agent.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A run killed mid-tool must never poison the conversation (the 03:17 "tool_calls must be followed" bug). */
class RepairTest {
    @Test fun orphanedToolCallsGetInterruptedResults() {
        val msgs = listOf(
            Msg.user("compare contracts"),
            Msg(Role.ASSISTANT, listOf(Block.ToolCall("a", "delegate", JSONObject()), Block.ToolCall("b", "web_fetch", JSONObject()))),
            Msg.user("hello"),
        )
        val fixed = AgentRuntime.Repair.repair(msgs)
        assertEquals(4, fixed.size)
        val results = fixed[2].blocks.filterIsInstance<Block.ToolResult>().map { it.callId }
        assertEquals(listOf("a", "b"), results)
        assertEquals("hello", fixed[3].text)
    }

    @Test fun partiallyAnsweredCallsAreCompleted() {
        val msgs = listOf(Msg.user("x"), Msg(Role.ASSISTANT, listOf(Block.ToolCall("a", "t", JSONObject()), Block.ToolCall("b", "t", JSONObject()))),
            Msg(Role.USER, listOf(Block.ToolResult("a", "ok"))))
        val fixed = AgentRuntime.Repair.repair(msgs)
        assertEquals(3, fixed.size)
        assertEquals(setOf("a", "b"), fixed[2].blocks.filterIsInstance<Block.ToolResult>().map { it.callId }.toSet())
    }

    @Test fun healthyHistoryIsUntouched() {
        val msgs = listOf(Msg.user("x"), Msg(Role.ASSISTANT, listOf(Block.ToolCall("a", "t", JSONObject()))), Msg(Role.USER, listOf(Block.ToolResult("a", "ok"))), Msg(Role.ASSISTANT, listOf(Block.Text("done"))))
        assertEquals(msgs, AgentRuntime.Repair.repair(msgs))
    }

    /** The 6 Oct 11:04 bug: a message written mid-tool pushed the results away from their call. */
    @Test fun resultsSeparatedFromTheirCallAreMovedBack() {
        val msgs = listOf(Msg.user("check mail"), Msg(Role.ASSISTANT, listOf(Block.ToolCall("a", "watcher_save", JSONObject()))),
            Msg.user("[Routine] morning brief"), Msg(Role.USER, listOf(Block.ToolResult("a", "saved"))), Msg(Role.ASSISTANT, listOf(Block.Text("done"))), Msg.user("thanks"))
        val fixed = AgentRuntime.Repair.repair(msgs)
        assertEquals(listOf("a"), fixed[2].blocks.filterIsInstance<Block.ToolResult>().map { it.callId })
        assertEquals("saved", (fixed[2].blocks[0] as Block.ToolResult).content)
        assertEquals("[Routine] morning brief", fixed[3].text)
        assertValid(fixed)
    }

    @Test fun orphanResultsAreDropped() {
        val msgs = listOf(Msg.user("x"), Msg(Role.USER, listOf(Block.ToolResult("ghost", "?"))), Msg(Role.ASSISTANT, listOf(Block.Text("hi"))), Msg.user("y"))
        val fixed = AgentRuntime.Repair.repair(msgs)
        assertTrue(fixed.none { m -> m.blocks.any { it is Block.ToolResult } }); assertValid(fixed)
    }

    @Test fun flattenKeepsTheWordsAndTheCurrentTurn() {
        val msgs = listOf(Msg.user("old"), Msg(Role.ASSISTANT, listOf(Block.Text("looking"), Block.ToolCall("a", "t", JSONObject()))), Msg(Role.USER, listOf(Block.ToolResult("a", "r"))),
            Msg(Role.ASSISTANT, listOf(Block.Text("found it"))), Msg.user("new"), Msg(Role.ASSISTANT, listOf(Block.ToolCall("b", "t", JSONObject()))), Msg(Role.USER, listOf(Block.ToolResult("b", "r2"))))
        val flat = AgentRuntime.Repair.flatten(msgs)
        assertEquals(listOf("old", "looking", "found it", "new"), flat.filter { it.text.isNotBlank() }.map { it.text })
        assertEquals(1, flat.count { m -> m.toolCalls.isNotEmpty() }); assertValid(flat)
    }

    /** The provider-side rule: every result answers a call in the message right before it, and every call is answered. */
    private fun assertValid(msgs: List<Msg>) {
        msgs.forEachIndexed { i, m ->
            val results = m.blocks.filterIsInstance<Block.ToolResult>().map { it.callId }
            if (results.isNotEmpty()) assertEquals(msgs[i - 1].toolCalls.map { it.id }.toSet(), results.toSet())
            if (m.toolCalls.isNotEmpty()) assertEquals(m.toolCalls.map { it.id }.toSet(), msgs[i + 1].blocks.filterIsInstance<Block.ToolResult>().map { it.callId }.toSet())
        }
    }

    @Test fun a400AboutToolsHealsItselfOnce() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val p = object : LlmProvider {
            override val name = "fake"
            override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
                calls++
                if (messages.any { m -> m.blocks.any { it is Block.ToolResult } }) throw ProviderException("HTTP 400: Messages with role 'tool' must be a response to a preceding message with 'tool_calls'", 400)
                return Completion(Msg(Role.ASSISTANT, listOf(Block.Text("back on track"))), "stop", Usage(0, 0))
            }
        }
        val history = mutableListOf(Msg.user("a"), Msg(Role.ASSISTANT, listOf(Block.ToolCall("x", "t", JSONObject()))), Msg(Role.USER, listOf(Block.ToolResult("x", "r"))), Msg(Role.ASSISTANT, listOf(Block.Text("ok"))), Msg.user("thanks"))
        val ctx = object : ToolContext { override val agentLabel = "main"; override suspend fun activity(text: String, meta: JSONObject) = 0L
            override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}; override suspend fun ask(question: String, options: List<String>): String? = null; override suspend fun notify(title: String, body: String) {} }
        assertEquals("back on track", AgentLoop(p, "m", emptyList()).run("s", history, ctx))
        assertEquals(2, calls)
    }
}
