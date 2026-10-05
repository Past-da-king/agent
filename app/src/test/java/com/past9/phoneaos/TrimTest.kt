package com.past9.phoneaos

import com.past9.phoneaos.agent.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrimTest {
    @Test fun longRunStaysInBudgetAndKeepsWords() {
        val h = mutableListOf(Msg.user("Find me a monitor"))
        repeat(200) { i ->
            h += Msg(Role.ASSISTANT, listOf(Block.Text("step $i"), Block.ToolCall("c$i", "browser_read", JSONObject())))
            h += Msg(Role.USER, listOf(Block.ToolResult("c$i", "x".repeat(20_000))))
        }
        Trim.fit(h)
        assertTrue(h.sumOf { Trim.size(it) } <= 350_000)
        assertEquals("Find me a monitor", (h.first().blocks.first() as Block.Text).text)
        assertEquals(20_000, (h.last().blocks.first() as Block.ToolResult).content.length) // newest untouched
        assertEquals(401, h.size)
    }
}
