package com.past9.phoneaos

import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.HelperLive
import com.past9.phoneaos.ui.HelperView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A helper row only counts as working while it is really at work (9 Nov 2026).
 *
 * His Profile page said "12 working for you right now" and listed helpers that had died days before: they were started
 * on his Windows PC, which went away mid-run, and the phone mirrored their rows and believed meta.state for ever.
 * These are the red-first tests for the one rule (ui/HelperLive.kt): a quiet window, a waiting limit, and the device
 * that owns the row.
 */
class StaleHelpersTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    @Before fun fixedClock() {
        HelperLive.forgetDevices()
        HelperLive.clock = { now }
    }

    @After fun realClock() {
        HelperLive.clock = { System.currentTimeMillis() }
        HelperLive.forgetDevices()
    }

    /** A helper row as the computer writes it, with its own steps. */
    private fun helper(id: Long, label: String, startedAt: Long, meta: JSONObject = JSONObject(), stepsAt: List<Long> = emptyList()): List<ChatItem> {
        val m = JSONObject().put("label", label).put("state", "working").put("startedAt", startedAt).apply {
            meta.keys().forEach { k -> put(k, meta.get(k)) }
        }
        // A step names its helper by the helper row's own id (Helpers.kt groups them that way).
        val rowId = id * 1000
        val row = ChatItem(rowId, "helper", "the brief for $label", m.toString(), startedAt)
        return listOf(row) + stepsAt.mapIndexed { i, at ->
            ChatItem(rowId + i + 1, "activity", "step ${i + 1} of $label",
                JSONObject().put("tool", "agent").put("helper", rowId).toString(), at)
        }
    }

    private fun views(items: List<ChatItem>) = HelperView.from(items)

    /** The eight ghosts he saw, and one that is really running. */
    private fun hisList(): List<HelperView> {
        val ghosts: List<List<ChatItem>> = (1L..6L).map { i ->
            helper(i, "Rebuild: module $i", now - 260 * hour, JSONObject().put("runOn", "desk-1").put("runsOnName", "DESKTOP-MSHPVRG"),
                listOf(now - 260 * hour + hour))
        } + listOf(helper(7, "SEI more Agent guides", now - 176 * hour, JSONObject().put("runOn", "desk-1").put("runsOnName", "DESKTOP-MSHPVRG")))
        val live = helper(8, "Fix the login screen", now - 4 * 60_000L, JSONObject().put("runOn", "server-1").put("runsOnName", "impetix"),
            listOf(now - 2 * 60_000L))
        return views(ghosts.flatten() + live)
    }

    @Test
    fun aGhostFromAComputerThatWentAwayIsNotCountedAndNotListed() {
        // The computer that started them was last heard from 260 h ago: it is gone, as far as the phone can tell.
        HelperLive.heardFrom("desk-1", "DESKTOP-MSHPVRG", now - 260 * hour)
        val ghost = views(helper(1, "Rebuild: payroll", now - 260 * hour, JSONObject().put("runOn", "desk-1")))
        assertEquals("a 260 h row is not at work", 0, HelperLive.count(ghost, now))
        assertTrue("and it is not in the list", HelperLive.running(ghost, now).isEmpty())
        assertFalse(ghost[0].working)
        assertEquals("lost", ghost[0].shownState)
        assertTrue("it says why, and how long", ghost[0].lostBecause.startsWith("Lost") && ghost[0].lostBecause.contains("260 h"))
    }

    @Test
    fun aFreshHelperOnALiveComputerIsCountedAndListed() {
        HelperLive.heardFrom("server-1", "impetix")
        val fresh = views(helper(2, "Fix the login screen", now - 4 * 60_000L, JSONObject().put("runOn", "server-1"), listOf(now - 60_000L)))
        assertEquals(1, HelperLive.count(fresh, now))
        assertEquals(listOf("Fix the login screen"), HelperLive.running(fresh, now).map { it.label })
        assertTrue(fresh[0].working)
        assertEquals("working", fresh[0].shownState)
    }

    @Test
    fun theHeaderCountIsAlwaysTheNumberOfRowsUnderIt() {
        HelperLive.heardFrom("desk-1", "DESKTOP-MSHPVRG", now - 260 * hour)
        HelperLive.heardFrom("server-1", "impetix")
        val list = hisList()
        // His morning: 9 rows say working, one of them really is. The header and the rows come from the same rule.
        assertEquals(1, HelperLive.count(list, now))
        assertEquals(HelperLive.count(list, now), HelperLive.running(list, now).size)
        assertEquals(listOf("Fix the login screen"), HelperLive.running(list, now).map { it.label })
        // Nothing live: the count is 0 and the list is empty, never "0 working" over two rows.
        val dead = list.filter { it.id != 8L * 1000L }
        assertEquals(0, HelperLive.count(dead, now))
        assertEquals(HelperLive.count(dead, now), HelperLive.running(dead, now).size)
    }

    @Test
    fun aQueuedRowPastTheLimitIsNotWorking() {
        // Queued cards have their "waiting its turn" line rewritten now and then, so the row moves while it never starts:
        // the waiting clock runs from when it was queued, not from the last move.
        val queued = views(helper(3, "Rebuild: tendering", now - 3 * hour, JSONObject().put("queued", true).put("updatedAt", now - 60_000L)))
        assertFalse(queued[0].working)
        assertNotEquals("working", queued[0].shownState)
        assertEquals(1, HelperLive.reapPlan(queued, now).size)
        assertEquals("stopped", JSONObject(HelperLive.reapPlan(queued, now)[0].meta).optString("state"))
        // Queued a moment ago: still waiting its turn, still working.
        val freshQueue = views(helper(4, "Rebuild: vault", now - 2 * 60_000L, JSONObject().put("queued", true).put("updatedAt", now - 60_000L)))
        assertTrue(freshQueue[0].working)
    }

    @Test
    fun theReaperEndsOnlyTheRowsThatStoppedWorking() {
        HelperLive.heardFrom("desk-1", "DESKTOP-MSHPVRG", now - 260 * hour)
        HelperLive.heardFrom("server-1", "impetix")
        val plan = HelperLive.reapPlan(hisList(), now)
        assertEquals("7 ghosts, the live one is left alone", 7, plan.size)
        val ghost = plan.first()
        val m = JSONObject(ghost.meta)
        assertEquals("lost", m.optString("state"))
        assertTrue("it keeps the reason", m.optString("endReason").isNotBlank())
        assertTrue("and when it was last seen", m.optLong("endedAt") > 0 && m.optLong("endedAt") <= now)
        assertFalse("a working row no longer says now", m.has("now"))
        assertFalse("the live row is not in the plan", plan.any { it.row.id == 8L * 1000L })
    }

    @Test
    fun aHelperWhoseComputerIsNotHeardFromIsNotWorkingEvenIfItJustMoved() {
        // It moved a minute ago, but the phone knows that computer was last seen two hours ago: it is not running there.
        HelperLive.heardFrom("desk-1", "DESKTOP-MSHPVRG", now - 2 * hour)
        val row = views(helper(5, "Rebuild: finance", now - 5 * hour, JSONObject().put("runOn", "desk-1"), listOf(now - 60_000L)))
        assertFalse(row[0].working)
        // The same row, with its computer answering now: it is at work.
        HelperLive.heardFrom("desk-1", "DESKTOP-MSHPVRG", now)
        assertTrue(row[0].working)
    }

    @Test
    fun aComputerThePhoneHasNoWordAboutDoesNotHideItsHelpers() {
        // The phone talks to the server and never hears the PC directly: no word is not "gone", so the steps decide.
        val row = views(helper(6, "Rebuild: crm", now - 5 * hour, JSONObject().put("runOn", "pc-somewhere"), listOf(now - 3 * 60_000L)))
        assertTrue(row[0].working)
        // And with no step for 40 minutes it is not working, whatever the state says.
        val quiet = views(helper(7, "Rebuild: crm", now - 5 * hour, JSONObject().put("runOn", "pc-somewhere"), listOf(now - 40 * 60_000L)))
        assertFalse(quiet[0].working)
    }

    @Test
    fun theComputerSayingDoneEndsTheRowThePhoneWasStuckOn() {
        // The phone's copy: 260 h old, mid-run text, saying working. The computer has had it as done since 30 Oct.
        val items = helper(1, "Rebuild: finance", now - 260 * hour,
            JSONObject().put("runOn", "desk-1").put("summary", "Exit code 2: 3 files failed").put("queued", false),
            listOf(now - 260 * hour + hour))
        val stuck = HelperView.from(items).first()
        assertEquals("working", stuck.state)
        assertFalse("the quiet window alone already refuses it", stuck.working)
        val theirs = JSONObject().put("state", "done").put("endedAt", now - 259 * hour).put("result", "Rebuilt: 12 files, all green")
        val fixed = HelperLive.reconciled(stuck, theirs, now)
        assertTrue("the row is written back", fixed != null)
        val meta = JSONObject(fixed!!.meta)
        assertEquals("done", meta.optString("state"))
        assertEquals(now - 259 * hour, meta.optLong("endedAt"))
        assertEquals("Rebuilt: 12 files, all green", meta.optString("result"))
        assertFalse("the mid-run line is gone", meta.has("now"))
        // What he sees: nothing working, nothing listed, and the row reads ended.
        val after = HelperView.from(listOf(fixed) + items.drop(1))
        assertEquals(0, HelperLive.count(after, now))
        assertTrue(HelperLive.running(after, now).isEmpty())
        assertEquals("done", after[0].shownState)
        assertFalse(after[0].working)
        // Nothing to do when the phone already agrees (a live computer is never rewritten over and over).
        assertEquals(null, HelperLive.reconciled(after[0], theirs, now))
        // A stop reads the same way.
        val stopped = HelperLive.reconciled(stuck, JSONObject().put("state", "stopped").put("endedAt", now - 259 * hour), now)!!
        assertEquals("stopped", JSONObject(stopped.meta).optString("state"))
        // The computer no longer has the row at all: lost here.
        val gone = HelperLive.reconciled(stuck, null, now)!!
        assertEquals("lost", JSONObject(gone.meta).optString("state"))
        assertTrue(JSONObject(gone.meta).optString("endReason").contains("no longer has it"))
        // And a computer that still says working (a real run) changes nothing.
        assertEquals(null, HelperLive.reconciled(stuck, JSONObject().put("state", "working"), now))
    }

    @Test
    fun aWorkingRowTheComputersLiveListDoesNotNameIsNotWorking() {
        // The case behind nine of the twelve ghosts: a helper row inside a project chat. The computer's own list of what
        // is working there names none of them, but this phone is never sent that row again (the node drops chat changes
        // outside the main chat), so its stored "working" is all it had.
        val row = views(helper(1, "Rebuild: inventory", now - 3 * hour,
            JSONObject().put("rid", "e2cca69d").put("chat", "pc66972ff24089451e").put("runOn", "desk-1").put("summary", "Exit code 2"),
            listOf(now - 2 * 60_000L))).first() // its step is fresh, so only the list can tell
        HelperLive.heardFrom("desk-1", "DESKTOP-MSHPVRG", now)
        // No list to go by: the row is judged on its own heartbeat, so it is working.
        HelperLive.cardsUnknown()
        assertTrue(row.working)
        // The computer published its list and it does not name this row: it is not working there.
        HelperLive.cardsSeen(listOf("79fce1dc", "1d957a51"), now)
        assertFalse(row.working)
        assertEquals("lost", row.shownState)
        assertEquals(0, HelperLive.count(listOf(row), now))
        // The same row, named by the list: working.
        HelperLive.cardsSeen(listOf("e2cca69d"), now)
        assertTrue(row.working)
        assertEquals(1, HelperLive.count(listOf(row), now))
    }

    @Test
    fun aRowThatStartedAfterTheListWasReadIsNotJudgedByIt() {
        HelperLive.cardsSeen(emptyList(), now - 5 * 60_000L)
        val young = views(helper(2, "Just started", now - 60_000L, JSONObject().put("rid", "new-1").put("runOn", "desk-1"), listOf(now - 30_000L))).first()
        assertTrue("the list cannot know about it yet", young.working)
        // Once a newer list has been read that leaves it out, it is not working.
        HelperLive.cardsSeen(emptyList(), now)
        assertFalse(young.working)
    }

    @Test
    fun aRowWithNoStateAtAllIsNeverWorking() {
        val noState = ChatItem(1001, "helper", "a brief", JSONObject().put("label", "Nameless").toString(), now - 60_000L)
        val v = HelperView.from(listOf(noState)).first()
        assertEquals("unknown", v.state)
        assertFalse(v.working)
        assertEquals(0, HelperLive.count(listOf(v), now))
    }

    @Test
    fun aProjectChatGhostIsEndedWithTheComputersWord() {
        // A row in a project chat (pc66972ff24089451e), stuck on working since 1 Nov, with mid-run text.
        val items = helper(3, "Rebuild: vault", now - 260 * hour,
            JSONObject().put("rid", "93afa990").put("chat", "pc66972ff24089451e").put("runOn", "desk-1").put("summary", "Queued: waiting for a free slot"),
            listOf(now - 260 * hour + hour))
        val stuck = HelperView.from(items).first()
        assertEquals("working", stuck.state)
        assertFalse(stuck.working)
        val theirs = JSONObject().put("state", "done").put("endedAt", now - 259 * hour).put("result", "Rebuilt the vault screens")
        val fixed = HelperLive.reconciled(stuck, theirs, now)!!
        assertEquals("done", JSONObject(fixed.meta).optString("state"))
        val after = HelperView.from(listOf(fixed) + items.drop(1))
        assertEquals(0, HelperLive.count(after, now))
        assertTrue(HelperLive.running(after, now).isEmpty())
    }

    @Test
    fun aCardHeSwipesAwayLeavesTheCountAndTheList() {
        HelperLive.heardFrom("server-1", "impetix")
        val items = helper(8, "Fix the login screen", now - 4 * 60_000L, JSONObject().put("runOn", "server-1").put("now", "Reading files"), listOf(now - 60_000L)) +
            helper(9, "Write the tests", now - 3 * 60_000L, JSONObject().put("runOn", "server-1"), listOf(now - 30_000L))
        assertEquals(2, HelperLive.count(views(items), now))
        val gone = HelperLive.dismissed(items.first(), now)!!
        val m = JSONObject(gone.meta)
        assertEquals("stopped", m.optString("state"))
        assertEquals(now, m.optLong("endedAt"))
        assertEquals("Dismissed on this phone", m.optString("endReason"))
        assertFalse(m.has("now"))
        val after = views(listOf(gone) + items.drop(1))
        assertEquals(1, HelperLive.count(after, now))
        assertEquals(listOf("Write the tests"), HelperLive.running(after, now).map { it.label })
        // Swiping a row that already ended, or a row that is not a helper, writes nothing.
        assertEquals(null, HelperLive.dismissed(gone, now))
        assertEquals(null, HelperLive.dismissed(ChatItem(5, "agent", "hello", "{}", now), now))
    }

    @Test
    fun aRowThatEndedIsNeverWorkingEvenIfItSaysSo() {
        val ended = views(helper(9, "Old job", now - hour, JSONObject().put("endedAt", now - 30 * 60_000L), listOf(now - 31 * 60_000L)))
        assertFalse(ended[0].working)
        assertEquals("lost", ended[0].shownState)
    }
}
