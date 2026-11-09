package com.past9.phoneaos

import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.ui.screens.Need
import com.past9.phoneaos.ui.screens.NeedStore
import com.past9.phoneaos.ui.screens.NeedTarget
import com.past9.phoneaos.ui.screens.Needs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 9 Nov 2026: Home's Needs you cards and to-dos took him nowhere useful and could not be cleared. Each card now
 * opens its real target by kind, says so when the target is gone, can be cleared or snoozed (kept across restarts),
 * and leaves by itself when its row is answered or done.
 */
class HomeNeedsTest {
    private val now = 1_800_000_000_000L
    private fun q(id: Long, text: String, meta: JSONObject = JSONObject()) = ChatItem(id, "question", text, meta.toString(), now)
    private fun task(id: Long, title: String, owner: String = "user", status: String = "todo", blocker: String = "") =
        TaskRow(id = id, title = title, owner = owner, status = status, blocker = blocker, createdAt = now)

    private val approval = q(1, "APPROVAL|Send the quote to the client|To: client@example.com", JSONObject().put("remoteKind", "approval"))
    private val question = q(2, "Which colour for the cover?", JSONObject().put("options", JSONArray(listOf("Blue", "Green"))))
    private val helperAsk = q(3, "Plug your phone in?", JSONObject().put("helper", "h1").put("by", "Ship the fixes"))
    private val projectAsk = q(4, "Use the staging database?", JSONObject().put("chat", "pc648").put("helper", "h2"))
    private val blocked = task(10, "Book the venue", owner = "agent", status = "blocked", blocker = "The booking site wants your ID number")
    private val todo = task(11, "Renew the car licence")
    private val items = listOf(ChatItem(90, "agent", "Hello", "{}", now), approval, question, helperAsk, projectAsk)
    private val tasks = listOf(blocked, todo, task(12, "Old one", status = "done"))

    private fun need(key: String): Need = (Needs.needs(items, tasks) + Needs.todos(tasks)).first { it.key == key }

    @Test
    fun everyKindOpensItsRealTarget() {
        val all = Needs.needs(items, tasks)
        assertEquals(listOf("approval", "question", "question", "question", "blocked"), all.map { it.kind })
        assertEquals("Send the quote to the client", need("q1").title)
        // An approval and a question open the chat at that very card.
        assertEquals(NeedTarget.ChatAt(1), Needs.target(need("q1"), items, tasks))
        assertEquals(NeedTarget.ChatAt(2), Needs.target(need("q2"), items, tasks))
        // A helper's card lives in the chat too; the card knows which helper asks.
        assertEquals("h1", need("q3").helper)
        assertEquals("A helper asks", Needs.kindLabel(need("q3")))
        assertEquals(NeedTarget.ChatAt(3), Needs.target(need("q3"), items, tasks))
        // A card asked in a project chat can open that project chat; a main chat card cannot.
        assertEquals(NeedTarget.ProjectChat("pc648"), Needs.projectTarget(need("q4")))
        assertNull(Needs.projectTarget(need("q2")))
        assertTrue(Needs.choices(need("q4")).any { it.id == "project" })
        assertFalse(Needs.choices(need("q2")).any { it.id == "project" })
        // The agent's blocked step opens that task on the agent's tab; his own to-do on his list.
        assertEquals(NeedTarget.Task(10, agentTab = true), Needs.target(need("b10"), items, tasks))
        assertEquals(NeedTarget.Task(11, agentTab = false), Needs.target(need("t11"), items, tasks))
        // Done is offered for tasks only; a card that asks is answered, not ticked.
        assertTrue(Needs.choices(need("t11")).any { it.id == "done" })
        assertFalse(Needs.choices(need("q1")).any { it.id == "done" })
        assertEquals(listOf("t11"), Needs.todos(tasks).map { it.key })
    }

    @Test
    fun aGoneTargetSaysSoInsteadOfOpeningNothing() {
        val q1 = need("q1"); val t11 = need("t11"); val b10 = need("b10")
        // Deleted rows.
        assertEquals(NeedTarget.Gone(Needs.MISSING), Needs.target(q1, emptyList(), tasks))
        assertEquals(NeedTarget.Gone(Needs.MISSING), Needs.target(t11, items, emptyList()))
        // Decided elsewhere, ticked elsewhere, unblocked elsewhere.
        val decided = items.map { if (it.id == 1L) it.copy(meta = JSONObject(it.meta).put("answer", "Approve").toString()) else it }
        assertEquals(NeedTarget.Gone(Needs.HANDLED), Needs.target(q1, decided, tasks))
        assertEquals(NeedTarget.Gone(Needs.HANDLED), Needs.target(t11, items, tasks.map { if (it.id == 11L) it.copy(status = "done") else it }))
        assertEquals(NeedTarget.Gone(Needs.HANDLED), Needs.target(b10, items, tasks.map { if (it.id == 10L) it.copy(status = "doing", blocker = "") else it }))
    }

    @Test
    fun aTaskClosedByASyncAndADecidedApprovalLeaveByThemselves() {
        assertTrue(Needs.todos(tasks).any { it.key == "t11" })
        assertFalse(Needs.todos(tasks.map { if (it.id == 11L) it.copy(status = "done") else it }).any { it.key == "t11" })
        assertTrue(Needs.needs(items, tasks).any { it.key == "q1" })
        val decided = items.map { if (it.id == 1L) it.copy(meta = JSONObject(it.meta).put("answer", "Decline").toString()) else it }
        assertFalse(Needs.needs(decided, tasks).any { it.key == "q1" })
        assertFalse(Needs.needs(items, tasks.map { if (it.id == 10L) it.copy(status = "doing") else it }).any { it.key == "b10" })
        assertFalse(Needs.needs(items.filter { it.id != 2L }, tasks).any { it.key == "q2" })
    }

    @Test
    fun dismissAndSnoozeHideTheCardAndSurviveARestart() {
        var disk: String? = null
        val store = NeedStore({ disk }, { disk = it })
        fun shown(s: NeedStore, at: Long = now) = (Needs.visible(Needs.needs(items, tasks), s.hidden.value, at) + Needs.visible(Needs.todos(tasks), s.hidden.value, at)).map { it.key }
        assertEquals(listOf("q1", "q2", "q3", "q4", "b10", "t11"), shown(store))
        store.dismiss(need("q2")); store.dismiss(need("b10"))
        store.snooze(need("t11"), now + 3_600_000L)
        assertEquals(listOf("q1", "q3", "q4"), shown(store))
        // A restart reads the same thing back, and a fresh sync of the same rows brings nothing back.
        val again = NeedStore({ disk }, {})
        again.prune(items, tasks, now)
        assertEquals(listOf("q1", "q3", "q4"), shown(again))
        // Rows not loaded yet (start-up) must not forget what he cleared.
        again.prune(emptyList(), emptyList(), now)
        assertEquals(listOf("q1", "q3", "q4"), shown(again))
        // The snooze runs out: the to-do is back, the cleared cards stay cleared.
        assertEquals(listOf("q1", "q3", "q4", "t11"), shown(again, now + 3_600_001L))
    }

    @Test
    fun aNewNeedForTheSameThingShowsAgain() {
        val store = NeedStore()
        store.dismiss(need("b10")); store.dismiss(need("t11"))
        fun keys(ts: List<TaskRow>) = (Needs.visible(Needs.needs(items, ts), store.hidden.value, now) + Needs.visible(Needs.todos(ts), store.hidden.value, now)).map { it.key }
        assertFalse(keys(tasks).contains("b10")); assertFalse(keys(tasks).contains("t11"))
        // The same step blocked on something new needs him again.
        assertTrue(keys(tasks.map { if (it.id == 10L) it.copy(blocker = "Now it wants a deposit") else it }).contains("b10"))
        // A to-do ticked off and later opened again is a new need: the sync that closed it forgets the old clearing.
        val closed = tasks.map { if (it.id == 11L) it.copy(status = "done") else it }
        store.prune(items, closed, now)
        assertFalse(keys(closed).contains("t11"))
        assertTrue(keys(tasks).contains("t11"))
    }

    @Test
    fun snoozeTimesArePlain() {
        val zone = ZoneId.of("Africa/Johannesburg")
        val noon = ZonedDateTime.of(2026, 11, 9, 12, 0, 0, 0, zone)
        assertEquals(noon.plusHours(1).toInstant().toEpochMilli(), Needs.snoozeUntil("hour", noon))
        assertEquals(noon.withHour(18).toInstant().toEpochMilli(), Needs.snoozeUntil("evening", noon))
        assertEquals(noon.plusDays(1).withHour(8).toInstant().toEpochMilli(), Needs.snoozeUntil("morning", noon))
        // Late at night there is no "this evening" left to offer.
        val late = noon.withHour(22)
        assertNull(Needs.snoozeUntil("evening", late))
        assertFalse(Needs.choices(need("t11"), late).any { it.id == "evening" })
        assertEquals(listOf("open", "done", "hour", "evening", "morning", "drop"), Needs.choices(need("t11"), noon).map { it.id })
    }
}
