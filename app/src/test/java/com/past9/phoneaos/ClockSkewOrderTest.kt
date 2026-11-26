package com.past9.phoneaos

import com.past9.phoneaos.remote.RemoteIds
import com.past9.phoneaos.remote.RemoteRows
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reply must land under the message it answers whatever the sender's clock says (26 Nov 2026: the PC's clock was 35
 * minutes slow and every reply it sent landed 35 minutes up the chat, out of view). The chat is shown in id order.
 */
class ClockSkewOrderTest {
    private val min = 60_000L
    private val now = 1_795_000_000_000L

    private fun row(id: String, at: Long, kind: String = "agent") = JSONObject()
        .put("id", id).put("createdAt", at).put("kind", kind).put("text", "t$id").put("meta", "{}")

    /** What RemoteSession does for a live main-chat row: the floor from what is held, then the row. */
    private fun live(ids: RemoteIds, held: List<Pair<Long, Long>>, o: JSONObject): Long {
        val floor = RemoteRows.liveFloor(ids.localOrNull(o.getString("id")) != null, o.getLong("createdAt"), held.maxOfOrNull { it.first }, held.maxOfOrNull { it.second })
        return RemoteRows.chat(o, ids, null, floor).id
    }

    @Test fun aReplyFromAClock35MinutesSlowLandsUnderTheQuestion() {
        val ids = RemoteIds()
        val question = RemoteRows.chat(row("send-1", now, "user"), ids).id
        val reply = live(ids, listOf(question to now), row("r1", now - 35 * min))
        assertTrue("reply $reply must come after question $question", reply > question)
        // And the next reply from the same slow clock comes after the first one.
        val next = live(ids, listOf(question to now, reply to now - 35 * min), row("r2", now - 35 * min + 2_000))
        assertTrue(next > reply)
    }

    @Test fun aReplyAfterARowFromAClock35MinutesFastStillLandsLast() {
        val ids = RemoteIds()
        val fast = RemoteRows.chat(row("pc-fast", now + 35 * min), ids).id
        val reply = live(ids, listOf(fast to now + 35 * min), row("server-reply", now + 1_000))
        assertTrue("reply $reply must come after $fast", reply > fast)
    }

    @Test fun aKnownRowKeepsItsPlace() {
        val ids = RemoteIds()
        val first = RemoteRows.chat(row("r1", now), ids).id
        val later = RemoteRows.chat(row("r2", now + 1_000), ids).id
        assertEquals(first, live(ids, listOf(first to now, later to now + 1_000), row("r1", now)))
    }

    @Test fun historyMoreThanSixHoursOldIsPlacedByItsOwnTime() {
        assertNull(RemoteRows.liveFloor(false, now - 7 * 60 * min, 99L, now))
        assertEquals(99L, RemoteRows.liveFloor(false, now - 35 * min, 99L, now))
        assertNull(RemoteRows.liveFloor(true, now, 99L, now))
        assertNull(RemoteRows.liveFloor(false, now, null, null))
    }

    @Test fun aFullReadKeepsSenderOrder() {
        val ids = RemoteIds()
        val a = RemoteRows.chat(row("a", now - 2_000), ids).id
        val b = RemoteRows.chat(row("b", now - 1_000), ids).id
        assertTrue(b > a)
    }
}
