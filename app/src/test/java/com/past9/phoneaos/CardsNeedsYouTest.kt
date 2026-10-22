package com.past9.phoneaos

import com.past9.phoneaos.remote.RemoteIds
import com.past9.phoneaos.remote.RemoteRows
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 22 Oct 2026: the computer counted two helper cards as "Waiting for you" that nothing drew. The phone must show every
 * card the computer counts, in its own shape, so Home's Needs you and the chat draw it and an answer goes back.
 */
class CardsNeedsYouTest {
    private fun row(id: String, kind: String, text: String, meta: JSONObject) =
        JSONObject().put("id", id).put("kind", kind).put("text", text).put("meta", meta.toString()).put("createdAt", 1_792_676_929_746L)

    /** The phone's own rule for a card still waiting (ui/screens/Home.kt). */
    private fun pending(kind: String, meta: String) = kind == "question" && !JSONObject(meta).has("answer")

    @Test
    fun aComputerQuestionWithAnEmptyAnswerIsPendingOnThePhone() {
        val q = row("q1", "question", "Plug your phone in?", JSONObject().put("options", org.json.JSONArray(listOf("Plugged in", "Not now")))
            .put("answer", "").put("helper", "h1").put("by", "Ship phone fixes now").put("chat", "pc648"))
        assertTrue(RemoteRows.isOpenCard(q))
        val item = RemoteRows.chat(q, RemoteIds())
        assertTrue(pending(item.kind, item.meta))
        val meta = JSONObject(item.meta)
        assertEquals("question", meta.getString("remoteKind"))
        assertEquals("Ship phone fixes now", meta.getString("whyBy"))
    }

    @Test
    fun aComputerApprovalBecomesThePhonesApprovalCard() {
        val a = row("a1", "approval", "Change the worker: Strat Edge Slack", JSONObject().put("summary", "Change the worker: Strat Edge Slack")
            .put("details", "Runs: node main.mjs").put("decision", "").put("scope", "computer").put("helper", "h2").put("by", "/Another thing I need"))
        assertTrue(RemoteRows.isOpenCard(a))
        val item = RemoteRows.chat(a, RemoteIds())
        assertTrue(pending(item.kind, item.meta))
        assertTrue(item.text.startsWith("APPROVAL|Change the worker: Strat Edge Slack|"))
        assertEquals("approval", JSONObject(item.meta).getString("remoteKind"))
    }

    @Test
    fun answeredOrDecidedCardsAreNotPending() {
        val q = row("q2", "question", "Which?", JSONObject().put("answer", "Blue"))
        assertFalse(RemoteRows.isOpenCard(q))
        assertFalse(RemoteRows.chat(q, RemoteIds()).let { pending(it.kind, it.meta) })
        val a = row("a2", "approval", "Send it", JSONObject().put("summary", "Send it").put("decision", "decline"))
        assertFalse(RemoteRows.isOpenCard(a))
        val item = RemoteRows.chat(a, RemoteIds())
        assertFalse(pending(item.kind, item.meta))
        assertEquals("Decline", JSONObject(item.meta).getString("answer"))
        assertFalse(RemoteRows.isOpenCard(row("n", "agent", "hi", JSONObject())))
    }
}
