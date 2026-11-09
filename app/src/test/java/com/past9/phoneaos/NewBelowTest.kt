package com.past9.phoneaos

import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.NewBelow
import com.past9.phoneaos.ui.screens.firstUnread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "N new messages" pill (9 Nov 2026: "it is always wrong and shows an outrageous number"). The computer sends no
 * count; the phone gets rows, and the same row arrives again on every write. The pill counts distinct MESSAGE rows
 * that were not in the chat when he left the bottom, and nothing else.
 */
class NewBelowTest {
    private var next = 100L
    /** The chat as the phone holds it (one row per id, a later write replaces the row), and every write in order. */
    private val chat = LinkedHashMap<Long, ChatItem>()
    private val writes = mutableListOf<ChatItem>()
    private fun write(id: Long, kind: String, text: String, meta: JSONObject = JSONObject()): Long {
        val row = ChatItem(id = id, kind = kind, text = text, meta = meta.toString(), createdAt = id)
        chat[id] = row; writes += row; return id
    }
    private fun add(kind: String, text: String, meta: JSONObject = JSONObject()) = write(next++, kind, text, meta)
    private fun items() = chat.values.toList()

    /** What the pill counted before this fix, with seenId the newest id when he left the bottom. */
    private fun oldRule(items: List<ChatItem>, seenId: Long) = items.count { it.id > seenId && it.kind != "user" && it.kind != "activity" && !JSONObject(it.meta).has("helper") }

    /** A busy stretch while he reads older messages: lots of work, three real messages. */
    private fun busyStretch() {
        add("notice", "New chat", JSONObject().put("divider", true))
        // 40 tool steps, each written twice: running, then done.
        repeat(40) { i ->
            val id = add("activity", "Read file $i", JSONObject().put("tool", "files_read").put("status", "running"))
            write(id, "activity", "Read file $i", JSONObject().put("tool", "files_read").put("status", "done"))
        }
        add("activity", "Thinking about the plan", JSONObject().put("line", true))
        // A helper card with 60 steps and several state updates.
        val helper = add("helper", "Research the venue", JSONObject().put("state", "working").put("steps", 0))
        repeat(60) { i ->
            val id = add("activity", "Step $i", JSONObject().put("helper", helper).put("status", "running"))
            write(id, "activity", "Step $i", JSONObject().put("helper", helper).put("status", "done"))
            if (i % 10 == 0) write(helper, "helper", "Research the venue", JSONObject().put("state", "working").put("steps", i))
        }
        // What the helper itself says stays in its card.
        add("agent", "Found three venues", JSONObject().put("helper", helper))
        write(helper, "helper", "Research the venue", JSONObject().put("state", "done").put("endedAt", 5L))
        add("notice", "Switched to the faster model")
        add("notice", "Couldn't reach the printer", JSONObject().put("error", true))
        // A question and an approval, each written again when answered and decided.
        val q = add("question", "Which venue?", JSONObject().put("options", org.json.JSONArray(listOf("A", "B"))))
        write(q, "question", "Which venue?", JSONObject().put("answer", "A"))
        val a = add("question", "APPROVAL|Book venue A|Fri 20 Nov, 40 people", JSONObject().put("remoteKind", "approval"))
        write(a, "question", "APPROVAL|Book venue A|Fri 20 Nov, 40 people", JSONObject().put("remoteKind", "approval").put("answer", "Approve"))
        add("approval", "Send the invite", JSONObject().put("summary", "Send the invite").put("decision", ""))
        add("call", "Voice call, 2 min")
        add("branch", "Side question")
        // An agent row with nothing in it (it only carried tool calls).
        add("agent", "")
        // Three real messages. The first is written many times while it streams in.
        val reply = add("agent", "Venue")
        repeat(12) { i -> write(reply, "agent", "Venue A is booked" + ".".repeat(i)) }
        add("report", "Venue comparison", JSONObject().put("path", "/reports/venues.md"))
        add("voice", "Quick summary", JSONObject().put("audio", "note.m4a"))
    }

    @Test fun aBusyStretchWithThreeRealMessagesSaysThree() {
        (1L..5L).forEach { write(it, if (it % 2 == 0L) "agent" else "user", "Earlier $it") }
        // He scrolls up: this is what the chat held.
        val seen = items().mapTo(HashSet()) { it.id }
        writes.clear()
        busyStretch()
        assertTrue("the stretch really is busy", writes.size > 220)
        assertEquals(3, NewBelow.countNewMessages(items(), seen))
        // Counting the writes as they arrive (the same id again and again) gives the same three.
        assertEquals(3, NewBelow.countNewMessages(writes, seen))
        assertEquals("3 new messages", NewBelow.label(NewBelow.countNewMessages(items(), seen)))
        // The rule it replaces got this wrong.
        assertNotEquals(3, oldRule(items(), 5))
        assertNotEquals(3, oldRule(writes, 5))
        // He reaches the bottom (or taps the pill): everything there is seen.
        val atBottom = items().mapTo(HashSet()) { it.id }
        assertEquals(0, NewBelow.countNewMessages(items(), atBottom))
        assertEquals("Jump to latest", NewBelow.label(0))
        // The same message written again later is still not new.
        write(chat.keys.last(), "voice", "Quick summary", JSONObject().put("audio", "note.m4a").put("played", true))
        assertEquals(0, NewBelow.countNewMessages(items(), atBottom))
    }

    @Test fun theWordsAreExact() {
        assertEquals("Jump to latest", NewBelow.label(0))
        assertEquals("1 new message", NewBelow.label(1))
        assertEquals("2 new messages", NewBelow.label(2))
        assertEquals("40 new messages", NewBelow.label(40))
    }

    @Test fun whatIsAMessage() {
        fun m(kind: String, text: String = "hello", meta: String = "{}") = NewBelow.isMessageRow(kind, text, meta)
        listOf("user", "agent", "report", "voice_note", "voice", "media").forEach { assertTrue(it, m(it)) }
        listOf("activity", "helper", "question", "approval", "notice", "call", "branch", "card", "").forEach { assertFalse(it, m(it)) }
        assertFalse("a helper's own message", m("agent", meta = """{"helper":"h1"}"""))
        assertFalse("an empty agent row", m("agent", text = ""))
        assertFalse("an agent row of spaces", m("agent", text = "  \n"))
        assertTrue("an agent row that is only a drawing", m("agent", text = "", meta = """{"visual":"Table"}"""))
        assertTrue("meta that is not JSON does not hide a message", m("agent", meta = ""))
    }

    @Test fun idsNeedNoOrder() {
        // The computer's rows get phone ids in the order they arrive, not the order they were written.
        val seen = setOf(900L, 901L)
        val rows = listOf(ChatItem(900, "user", "hi"), ChatItem(901, "agent", "hello"), ChatItem(12, "agent", "a new one with a small id"), ChatItem(7, "activity", "step"))
        assertEquals(1, NewBelow.countNewMessages(rows, seen))
    }

    @Test fun earlierHistoryLoadedWhileHeReadsIsNotNew() {
        val seen = setOf(50L, 51L)
        val older = (1L..20L).map { ChatItem(it, "agent", "old $it") }
        val rows = older + listOf(ChatItem(50, "user", "hi"), ChatItem(51, "agent", "hello"), ChatItem(60, "agent", "new"))
        assertEquals(1, NewBelow.countNewMessages(rows, seen))
    }

    @Test fun aProjectChatsRowsFollowTheSameRule() {
        fun row(id: String, kind: String, text: String, meta: JSONObject = JSONObject()) = JSONObject().put("id", id).put("kind", kind).put("text", text).put("meta", meta.toString())
        val seen = setOf("a", "b")
        val rows = listOf(row("a", "user", "start"), row("b", "agent", "on it"),
            row("s1", "activity", "step"), row("s1", "activity", "step", JSONObject().put("status", "done")),
            row("h", "helper", "helper"), row("hm", "agent", "helper says", JSONObject().put("helper", "h")),
            row("ap", "approval", "Send it", JSONObject().put("summary", "Send it")), row("q", "question", "Which?"),
            row("n", "notice", "New chat", JSONObject().put("divider", true)), row("e", "agent", ""),
            row("m1", "agent", "Done"), row("m1", "agent", "Done, and tested"), row("m2", "voice_note", "A note"), row("m3", "media", "clip.mp4"))
        assertEquals(3, NewBelow.countNewRows(rows, seen))
        assertEquals(0, NewBelow.countNewRows(rows, rows.mapTo(HashSet()) { it.optString("id") }))
    }

    @Test fun theDividerAChatOpensAtUsesTheSameRule() {
        (1L..5L).forEach { write(it, if (it % 2 == 0L) "agent" else "user", "Earlier $it") }
        busyStretch()
        val unread = NewBelow.unreadAfter(items(), 5)
        assertEquals(3, unread.size)
        // The divider sits above the first real message, not above a tool step, a notice or a card.
        assertEquals(unread.first().id, firstUnread(items(), 5)?.id)
        assertEquals("agent", firstUnread(items(), 5)?.kind)
    }
}
