package com.past9.phoneaos.wear

import com.past9.phoneaos.wearproto.Phase
import com.past9.phoneaos.wearproto.TurnView
import com.past9.phoneaos.wearproto.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicPolicyTest {
    private fun quiet(ms: Int, peak: Int = 0) = List(ms / MicPolicy.WINDOW_MS) { peak }

    @Test fun noMicHardwareOrNoInputIsReportedBeforeAnythingIsRecorded() {
        assertEquals(MicPolicy.Verdict.NO_HARDWARE, MicPolicy.preflight(hasMicFeature = false, hasInput = true, granted = true))
        assertEquals(MicPolicy.Verdict.NO_HARDWARE, MicPolicy.preflight(true, hasInput = false, granted = true))
        assertEquals(MicPolicy.Verdict.NO_PERMISSION, MicPolicy.preflight(true, true, granted = false))
        assertEquals(MicPolicy.Verdict.OK, MicPolicy.preflight(true, true, true))
    }

    @Test fun aTakeOfPureSilenceMarksTheMicDeadOnlyOnceItIsLongEnoughToTell() {
        assertTrue(MicPolicy.silentTake(quiet(2_000)))
        assertTrue(MicPolicy.silentTake(quiet(2_000, peak = MicPolicy.DEAD_PEAK)))
        assertFalse(MicPolicy.silentTake(quiet(1_000)))
        assertFalse(MicPolicy.silentTake(quiet(2_000, peak = 40)))
        assertEquals(MicPolicy.Verdict.SILENT, MicPolicy.judge(quiet(2_000)))
        assertEquals(MicPolicy.Verdict.OK, MicPolicy.judge(quiet(2_000, peak = 40)))
    }

    @Test fun speechIsHeardAboveTheThreshold() {
        assertFalse(MicPolicy.heardSpeech(quiet(2_000, 40)))
        assertTrue(MicPolicy.heardSpeech(quiet(500, 40) + quiet(300, 4_000)))
    }

    @Test fun recordingStopsWhenSpeechEndsOrNothingIsSaidOrTheCapIsReached() {
        assertFalse(MicPolicy.shouldStop(quiet(3_000, 40)))
        assertTrue(MicPolicy.shouldStop(quiet(MicPolicy.GIVE_UP_MS, 40)))
        val spoke = quiet(500, 40) + quiet(600, 5_000)
        assertFalse(MicPolicy.shouldStop(spoke + quiet(600, 40)))
        assertTrue(MicPolicy.shouldStop(spoke + quiet(MicPolicy.QUIET_END_MS, 40)))
        assertTrue(MicPolicy.shouldStop(quiet(MicPolicy.MAX_TAKE_MS, 5_000)))
    }

    @Test fun aDeadMicPutsTheFallbacksFirstForADayThenIsTriedAgain() {
        assertFalse(MicPolicy.preferFallback(0, 1_000_000))
        assertTrue(MicPolicy.preferFallback(1_000, 1_000 + MicPolicy.RETRY_AFTER_MS - 1))
        assertFalse(MicPolicy.preferFallback(1_000, 1_000 + MicPolicy.RETRY_AFTER_MS))
    }

    @Test fun levelIsClamped() {
        assertEquals(0f, MicPolicy.level(0), 0f); assertEquals(1f, MicPolicy.level(40_000), 0f)
    }
}

class TurnForTest {
    private val answered = WatchState(turn = TurnView(Phase.ANSWERING, heard = "what is on today", answer = "Two meetings."))

    @Test fun withNothingAskedTheWatchShowsThePhonesTurn() {
        assertEquals(answered.turn, WatchController.turnFor(answered, null))
        assertNull(WatchController.turnFor(null, null))
    }

    @Test fun aNewAskNeverShowsThePreviousAnswer() {
        val t = WatchController.turnFor(answered, WatchController.Asked("a", "something else"))!!
        assertEquals(Phase.SENDING, t.phase); assertEquals("something else", t.heard)
    }

    @Test fun onceThePhoneReportsTheSameWordsItsTurnIsShown() {
        assertEquals(answered.turn, WatchController.turnFor(answered, WatchController.Asked("a", "what is on today")))
    }

    @Test fun speakingOnThePhoneWaitsForItsWordsToArrive() {
        val waiting = WatchController.turnFor(answered.copy(turn = TurnView(Phase.SENDING, heard = "…")), WatchController.Asked("", "", onPhone = true))!!
        assertEquals(Phase.SENDING, waiting.phase)
        assertEquals(answered.turn, WatchController.turnFor(answered, WatchController.Asked("", "", onPhone = true)))
    }
}
