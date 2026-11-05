package com.past9.phoneaos

import com.past9.phoneaos.remote.RemoteRows
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Starting a new chat does not delete anything on the computer: it writes a "New chat" divider and the next turn
 * only reads what came after it (memory/service.ts compressChat, runtime/runner.ts loadHistory). The phone mirrors
 * those rows, so it has to hide what the divider closed or a cleared chat reappears (issue #14, 4 Nov 2026).
 */
class NewChatDividerTest {
    private fun row(id: String, at: Long, kind: String = "user", chat: String? = null) = JSONObject()
        .put("id", id).put("createdAt", at).put("kind", kind).put("text", "t$id")
        .put("meta", JSONObject().apply { if (chat != null) put("chat", chat) }.toString())

    private fun divider(at: Long, chat: String? = null) = row("d$at", at, "notice", chat).apply {
        put("meta", JSONObject().apply { put("divider", true); if (chat != null) put("chat", chat) }.toString())
    }

    private val chat = listOf(
        row("a", 100), row("b", 200), divider(300), row("c", 400), row("d", 500),
    )

    @Test fun newestDividerIsFound() = assertEquals(300L, RemoteRows.dividerAt(chat))

    @Test fun noDividerMeansNothingClosed() {
        val plain = listOf(row("a", 100), row("b", 200))
        assertEquals(0L, RemoteRows.dividerAt(plain))
        assertFalse(RemoteRows.closed(plain[0], RemoteRows.dividerAt(plain)))
        assertEquals(plain, RemoteRows.openMain(plain))
    }

    @Test fun whatCameBeforeTheDividerIsNotShown() {
        val shown = RemoteRows.openMain(chat).map { it.optString("id") }
        assertEquals(listOf("d300", "c", "d"), shown)
    }

    @Test fun theDividerItselfStays() {
        assertTrue(RemoteRows.openMain(chat).any { RemoteRows.isDivider(it) })
        assertFalse(RemoteRows.closed(divider(300), 300))
    }

    @Test fun onlyTheNewestDividerCounts() {
        val twice = listOf(divider(100), row("old", 200), divider(300), row("new", 400))
        assertEquals(listOf("d300", "new"), RemoteRows.openMain(twice).map { it.optString("id") })
    }

    @Test fun aProjectsDividerLeavesTheMainChatAlone() {
        val mixed = listOf(row("a", 100), divider(300, chat = "p-abc"), row("b", 400))
        assertEquals(0L, RemoteRows.dividerAt(mixed))
        // The project's own divider belongs to that project's chat, not the main one.
        assertEquals(listOf("a", "b"), RemoteRows.openMain(mixed).map { it.optString("id") })
    }

    @Test fun aCardStillWaitingOnThePersonSurvivesTheClear() {
        val question = JSONObject().put("id", "q").put("createdAt", 150).put("kind", "question").put("text", "Which one?")
            .put("meta", JSONObject().put("options", org.json.JSONArray().put("A").put("B")).toString())
        val rows = listOf(row("a", 100), question, divider(300), row("b", 400))
        val shown = RemoteRows.openMain(rows).map { it.optString("id") }
        assertEquals(listOf("q", "d300", "b"), shown)
    }

    @Test fun aClosedRowIsNotPutBackByALaterChange() {
        val divider = 300L
        assertTrue(RemoteRows.closed(row("old", 299), divider))
        assertFalse(RemoteRows.closed(row("new", 301), divider))
        assertFalse("no divider, nothing closed", RemoteRows.closed(row("old", 100), 0L))
    }
}
