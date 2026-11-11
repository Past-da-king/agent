package com.past9.phoneaos

import android.media.AudioManager
import com.past9.phoneaos.assist.BubbleMath
import com.past9.phoneaos.assist.FreshOnce
import com.past9.phoneaos.assist.LiveAssistCore
import com.past9.phoneaos.voice.Earcon
import com.past9.phoneaos.voice.SendGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fast start (speech kept until connected, a token asked for ahead, a soft earcon) and the bubble's geometry. */
class LiveFastTest {
    @Test fun speechBeforeTheConnectionIsKeptAndSentInOrderWhenItOpens() {
        val gate = SendGate(10); val sent = ArrayList<String>()
        gate.submit("a") { sent += it }; gate.submit("b") { sent += it }
        assertTrue(sent.isEmpty()); assertEquals(2, gate.waiting)
        gate.open { sent += it }
        assertEquals(listOf("a", "b"), sent); assertEquals(0, gate.waiting)
        gate.submit("c") { sent += it }
        assertEquals(listOf("a", "b", "c"), sent)
    }

    @Test fun onlyTheNewestPiecesAreKeptWhenItWaitsTooLong() {
        val gate = SendGate(3); val sent = ArrayList<String>()
        (1..6).forEach { gate.submit("m$it") { s -> sent += s } }
        gate.open { sent += it }
        assertEquals(listOf("m4", "m5", "m6"), sent)
    }

    @Test fun aReconnectKeepsSpeechAgainUntilItIsReady() {
        val gate = SendGate(10); val sent = ArrayList<String>()
        gate.open { sent += it }
        gate.submit("live") { sent += it }
        gate.close()
        gate.submit("during") { sent += it }
        assertEquals(listOf("live"), sent)
        gate.open { sent += it }
        assertEquals(listOf("live", "during"), sent)
    }

    @Test fun aPreparedTokenIsUsedOnceAndOnlyWhileFresh() {
        var now = 0L
        val once = FreshOnce<String>(40_000) { now }
        assertFalse(once.fresh()); assertNull(once.take())
        once.put("t1"); assertTrue(once.fresh())
        now = 39_999; assertEquals("t1", once.take())
        assertNull(once.take())
        once.put("t2"); now += 40_000
        assertFalse(once.fresh()); assertNull(once.take())
    }

    @Test fun theEarconIsShortSoftAndAClickFreeTwoNotes() {
        val s = Earcon.samples()
        assertTrue("about 140 ms at 24 kHz", s.size in 3000..3800)
        assertTrue("soft", s.maxOf { kotlin.math.abs(it.toInt()) } < Short.MAX_VALUE * 0.3)
        assertTrue("starts and ends at silence", kotlin.math.abs(s.first().toInt()) < 50 && kotlin.math.abs(s.last().toInt()) < 50)
    }

    @Test fun theEarconRespectsSilentAndVibrate() {
        assertTrue(Earcon.shouldPlay(AudioManager.RINGER_MODE_NORMAL))
        assertFalse(Earcon.shouldPlay(AudioManager.RINGER_MODE_SILENT))
        assertFalse(Earcon.shouldPlay(AudioManager.RINGER_MODE_VIBRATE))
    }

    @Test fun theBubbleIsOverTheDismissCircleOnlyInsideItsRadius() {
        assertTrue(BubbleMath.overTarget(540f, 2200f, 540f, 2250f, 200f))
        assertFalse(BubbleMath.overTarget(540f, 1500f, 540f, 2250f, 200f))
        assertTrue(BubbleMath.overTarget(640f, 2250f, 540f, 2250f, 100f))
        assertFalse(BubbleMath.overTarget(641f, 2250f, 540f, 2250f, 100f))
    }

    @Test fun theBubbleStaysFullyOnScreen() {
        assertEquals(0, BubbleMath.clamp(-50, 200, 1080))
        assertEquals(880, BubbleMath.clamp(2000, 200, 1080))
        assertEquals(300, BubbleMath.clamp(300, 200, 1080))
        assertEquals(0, BubbleMath.clamp(10, 2000, 1080))
    }

    @Test fun aMinimisedSessionWaitsLongerBeforeLettingGo() {
        var now = 1_000L
        val c = LiveAssistCore { now }; c.connected()
        now += LiveAssistCore.IDLE_MS + 1
        assertTrue(c.idle(minimised = false)); assertFalse(c.idle(minimised = true))
        now += LiveAssistCore.IDLE_MINIMISED_MS
        assertTrue(c.idle(minimised = true))
    }
}
