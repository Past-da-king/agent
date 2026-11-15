package com.past9.phoneaos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.voice.CallState
import com.past9.phoneaos.wakeup.Wake
import com.past9.phoneaos.wakeup.WakeHub
import com.past9.phoneaos.wakeup.WakeSnapshot
import com.past9.phoneaos.wear.WatchCallBridge
import com.past9.phoneaos.wear.WatchRingRelay
import com.past9.phoneaos.wear.WatchStateBuilder
import com.past9.phoneaos.wearproto.CallPlan
import com.past9.phoneaos.wearproto.CallView
import com.past9.phoneaos.wearproto.PhoneCallGate
import com.past9.phoneaos.wearproto.RingKind
import com.past9.phoneaos.wearproto.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone's half of the watch's call, ring and watch-face features (docs/wear.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchEngineTest {
    private fun task(id: Long, title: String, owner: String = "user", status: String = "todo", due: Long? = null, created: Long = id) =
        TaskRow(id = id, title = title, owner = owner, status = status, dueAt = due, createdAt = created)

    // ---- the next task for the watch face ----------------------------------------------------------------------------

    @Test fun theNextTaskIsHisOwnOpenTaskDueSoonestElseTheOldest() {
        val tasks = listOf(
            task(1, "agent step", owner = "agent", due = 1),
            task(2, "done already", status = "done", due = 1),
            task(3, "blocked one", status = "blocked", due = 1),
            task(4, "later", due = 9_000),
            task(5, "sooner", due = 5_000),
            task(6, "no date, oldest", due = null, created = 1),
        )
        assertEquals("sooner", WatchStateBuilder.nextTask(tasks)?.title)
        assertEquals("no date, oldest", WatchStateBuilder.nextTask(listOf(tasks[5], tasks[0]))?.title)
        assertNull(WatchStateBuilder.nextTask(listOf(task(1, "x", owner = "agent"), task(2, "y", status = "done"))))
        assertNull(WatchStateBuilder.nextTask(emptyList()))
    }

    @Test fun theStateTheWatchGetsCarriesTheTaskAndTheLiveCall() {
        val s = WatchStateBuilder.build(emptyList(), false, null, "Hina", "local", com.past9.phoneaos.wearproto.TaskView(2, "Pay rent"), callLive = true)
        assertEquals("Pay rent", s.nextTask?.title); assertTrue(s.callLive)
        val plain = WatchStateBuilder.build(emptyList(), false, null, "Hina", "local")
        assertNull(plain.nextTask); assertFalse(plain.callLive)
    }

    // ---- the call picture the watch draws ----------------------------------------------------------------------------

    private val plan = CallPlan("c1", Side.WATCH, Side.PHONE, "")
    private fun st(phase: String, active: Boolean = true, lines: List<Pair<String, String>> = emptyList(), muted: Boolean = false, error: String = "") =
        CallState(active = active, phase = phase, lines = lines, muted = muted, error = error)

    @Test fun theWatchSeesThePhonesCallPhaseTheLastLineAndWhoSaidIt() {
        val v = WatchCallBridge.view(null, plan, st("speaking", lines = listOf("in" to "hello", "out" to "Hi, how can I help")), PhoneCallGate.Verdict.KEEP, agent = "Hina")
        assertEquals(CallView.SPEAKING, v.phase); assertEquals("Hi, how can I help", v.line); assertEquals("out", v.who)
        assertEquals(Side.WATCH, v.mic); assertEquals(Side.PHONE, v.spk); assertEquals("Hina", v.agent)
    }

    @Test fun aDropOutOfTheWatchShowsAsReconnectingWhileTheCallIsOnButNotOnceItHasEnded() {
        assertEquals(CallView.RECONNECTING, WatchCallBridge.view(null, plan, st("listening"), PhoneCallGate.Verdict.DROP_OUT).phase)
        assertEquals(CallView.ENDED, WatchCallBridge.view(null, plan, st("ended", active = false, error = "cut off"), PhoneCallGate.Verdict.DROP_OUT).phase)
        assertEquals("cut off", WatchCallBridge.view(null, plan, st("ended", active = false, error = "cut off"), PhoneCallGate.Verdict.KEEP).error)
    }

    @Test fun beforeTheCallHasAPhaseTheWatchIsToldItIsConnecting() {
        assertEquals(CallView.CONNECTING, WatchCallBridge.view(null, plan, st(""), PhoneCallGate.Verdict.KEEP).phase)
    }

    // ---- what rings the watch -----------------------------------------------------------------------------------------

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private fun wake(id: String, sev: String = "urgent") = Wake(id, sev, "Standup", "Join the call", "https://meet.example/x", "meeting", 1L, false, emptyList(), emptySet(), "agent")

    @Test fun theWatchIsToldWhatRingsOnThePhoneWithItsSoundFlag() {
        val c = ctx()
        WakeHub.apply(c, WakeSnapshot(listOf(wake("w1", "critical")), 5, "", "agent"))
        val items = WatchRingRelay.items(WakeHub.ringing.value)
        assertEquals(1, items.size)
        assertEquals("w1", items[0].id); assertEquals(RingKind.WAKE, items[0].kind); assertEquals("critical", items[0].severity); assertTrue(items[0].sound)
        // Clear for the next test.
        WakeHub.apply(c, WakeSnapshot(emptyList(), 5, "", "agent"))
    }

    @Test fun anAnswerFromTheWatchStopsTheRingOnThePhoneThroughItsOwnPath() {
        val c = ctx()
        WakeHub.apply(c, WakeSnapshot(listOf(wake("w2")), 5, "", "agent"))
        assertEquals(listOf("w2"), WakeHub.ringing.value.map { it.wake.id })
        val ok = WatchRingRelay.answer(c, "w2", "ack")
        assertTrue(ok.ok)
        assertTrue("it no longer rings on the phone", WakeHub.ringing.value.isEmpty())
        assertFalse("answering it again is refused", WatchRingRelay.answer(c, "w2", "ack").ok)
        WakeHub.apply(c, WakeSnapshot(emptyList(), 5, "", "agent"))
    }

    @Test fun aSnoozeFromTheWatchStopsItNowAndRingsAgainLater() {
        val c = ctx()
        WakeHub.apply(c, WakeSnapshot(listOf(wake("w3")), 5, "", "agent"))
        assertTrue(WatchRingRelay.answer(c, "w3", "snooze").ok)
        assertTrue(WakeHub.ringing.value.isEmpty())
        // The phone booked the next ring a snooze length ahead.
        val next = WakeHub.snapshot.value.wakes.single { it.id == "w3" }.ringAt
        assertTrue(next > System.currentTimeMillis())
        WakeHub.apply(c, WakeSnapshot(emptyList(), 5, "", "agent"))
    }

    @Test fun theWatchCannotAnswerWithAnythingButAckOrSnoozeNorForSomethingThatDoesNotRing() {
        val c = ctx()
        WakeHub.apply(c, WakeSnapshot(listOf(wake("w4")), 5, "", "agent"))
        assertFalse(WatchRingRelay.answer(c, "w4", "delete").ok)
        assertFalse(WatchRingRelay.answer(c, "nope", "ack").ok)
        assertEquals(listOf("w4"), WakeHub.ringing.value.map { it.wake.id })
        WakeHub.apply(c, WakeSnapshot(emptyList(), 5, "", "agent"))
    }
}
