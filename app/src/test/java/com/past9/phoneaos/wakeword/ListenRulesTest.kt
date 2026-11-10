package com.past9.phoneaos.wakeword

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the listener listens, when it stands aside, and what it tells the person: every case, without a phone. */
class ListenRulesTest {
    private val on = WakeWordSettings(enabled = true)
    private val fine = WakeWorld(batteryPercent = 80)
    private fun paused(s: WakeWordSettings, w: WakeWorld) = (ListenRules.decide(s, w) as? WakeState.Paused)?.why

    @After fun tidy() { MicUse.onHold = null; MicUse.held.value.forEach { MicUse.release(it) } }

    @Test fun itIsOffUntilSwitchedOn() {
        assertEquals(WakeState.Off, ListenRules.decide(WakeWordSettings(), fine))
        assertFalse("off by default", WakeWordSettings().enabled)
        assertEquals(WakeState.Listening, ListenRules.decide(on, fine))
    }

    @Test fun eachReasonPausesItAndItListensAgainWhenTheReasonIsGone() {
        val cases = mapOf(
            WakePause.NO_MIC_PERMISSION to (on to fine.copy(micAllowed = false)),
            WakePause.NO_PHRASE to (on to fine.copy(phraseOk = false)),
            WakePause.ENGINE to (on to fine.copy(engineOk = false)),
            WakePause.USER to (on.copy(userPaused = true) to fine),
            WakePause.APP_MIC to (on to fine.copy(appMic = setOf("call"))),
            WakePause.PHONE_CALL to (on to fine.copy(inCall = true)),
            WakePause.ONLY_CHARGING to (on.copy(onlyCharging = true) to fine.copy(charging = false)),
            WakePause.BATTERY_SAVER to (on to fine.copy(batterySaver = true)),
            WakePause.LOW_BATTERY to (on.copy(pauseLowBattery = true) to fine.copy(batteryPercent = 19)),
            WakePause.MIC_TAKEN to (on to fine.copy(otherRecorder = true)),
            WakePause.MIC_SILENT to (on to fine.copy(micSilent = true)),
        )
        assertEquals("a case for every reason", WakePause.entries.toSet(), cases.keys)
        for ((why, c) in cases) assertEquals(why, paused(c.first, c.second))
        // The reason gone: listening again, with no step in between.
        assertEquals(WakeState.Listening, ListenRules.decide(on.copy(onlyCharging = true), fine.copy(charging = true)))
        assertEquals(WakeState.Listening, ListenRules.decide(on.copy(pauseLowBattery = true), fine.copy(batteryPercent = 20)))
    }

    @Test fun theBatteryOptionsOnlyApplyWhenChosen() {
        // Battery saver pauses by default; the other two are choices.
        assertTrue(WakeWordSettings().pauseOnSaver); assertFalse(WakeWordSettings().pauseLowBattery); assertFalse(WakeWordSettings().onlyCharging)
        assertEquals(WakeState.Listening, ListenRules.decide(on.copy(pauseOnSaver = false), fine.copy(batterySaver = true)))
        assertEquals(WakeState.Listening, ListenRules.decide(on, fine.copy(batteryPercent = 5)))
        // On the charger a low number does not pause it, and an unknown level never does.
        assertEquals(WakeState.Listening, ListenRules.decide(on.copy(pauseLowBattery = true), fine.copy(batteryPercent = 5, charging = true)))
        assertEquals(WakeState.Listening, ListenRules.decide(on.copy(pauseLowBattery = true), fine.copy(batteryPercent = -1)))
    }

    @Test fun theMostPressingReasonIsTheOneShown() {
        val everything = fine.copy(micAllowed = false, appMic = setOf("call"), inCall = true, batterySaver = true, otherRecorder = true)
        assertEquals(WakePause.NO_MIC_PERMISSION, paused(on.copy(userPaused = true), everything))
        assertEquals(WakePause.USER, paused(on.copy(userPaused = true), everything.copy(micAllowed = true)))
        assertEquals(WakePause.APP_MIC, paused(on, everything.copy(micAllowed = true)))
        assertEquals(WakePause.PHONE_CALL, paused(on, everything.copy(micAllowed = true, appMic = emptySet())))
        assertEquals(WakePause.BATTERY_SAVER, paused(on, everything.copy(micAllowed = true, appMic = emptySet(), inCall = false)))
    }

    @Test fun everyPauseLetsGoOfTheMicrophone() {
        assertTrue(WakePause.entries.all { it.releasesMic })
        assertTrue(WakePause.entries.all { it.line.isNotBlank() && it.line.endsWith(".") })
    }

    @Test fun theNoticeNamesThePersonsOwnPhrase() {
        for (phrase in listOf("Hey Nova", "Hey Thandi", "Okay Yuki", "Hey Амира")) {
            val (title, text) = ListenRules.notice(WakeState.Listening, phrase)
            assertEquals("Listening for \"$phrase\"", title)
            assertTrue(text.contains("on this phone only"))
            val (t2, x2) = ListenRules.notice(WakeState.Paused(WakePause.PHONE_CALL), phrase)
            assertEquals("Not listening for \"$phrase\"", t2); assertEquals(WakePause.PHONE_CALL.line, x2)
        }
        assertEquals("Pause", ListenRules.action(WakeState.Listening))
        assertEquals("Pause", ListenRules.action(WakeState.Paused(WakePause.BATTERY_SAVER)))
        assertEquals("Resume", ListenRules.action(WakeState.Paused(WakePause.USER)))
        assertNull(ListenRules.action(WakeState.Off))
    }

    @Test fun aVoiceFeatureTakingTheMicrophoneIsToldToTheListenerBeforeHoldReturns() {
        var dropped = 0
        MicUse.onHold = { dropped++ }
        MicUse.hold("call")
        assertEquals("the listener must have let go by the time hold() returns", 1, dropped)
        assertEquals(setOf("call"), MicUse.held.value)
        MicUse.hold("dictation"); MicUse.release("call")
        assertEquals("still held by dictation", WakePause.APP_MIC, paused(on, fine.copy(appMic = MicUse.held.value)))
        MicUse.release("dictation"); MicUse.release("dictation")
        assertEquals(WakeState.Listening, ListenRules.decide(on, fine.copy(appMic = MicUse.held.value)))
        // A listener that throws must not break the feature that asked.
        MicUse.onHold = { error("boom") }
        MicUse.hold("call"); assertEquals(setOf("call"), MicUse.held.value)
    }
}
