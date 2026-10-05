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
}
