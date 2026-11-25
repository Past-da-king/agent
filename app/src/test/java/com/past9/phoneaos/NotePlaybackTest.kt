package com.past9.phoneaos

import com.past9.phoneaos.voice.*
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test

class NotePlaybackTest {
    @Test fun seekClamps() {
        assertEquals(0, noteSeek(-20f, 100f, 118000, false))
        assertEquals(118000, noteSeek(120f, 100f, 118000, false))
        assertEquals(59000, noteSeek(50f, 100f, 118000, false))
    }
    @Test fun seekMirrorsRtl() {
        assertEquals(118000, noteSeek(0f, 100f, 118000, true))
        assertEquals(29500, noteSeek(75f, 100f, 118000, true))
        assertEquals(0, noteSeek(150f, 100f, 118000, true))
    }
    @Test fun invalidGeometryIsSafe() {
        for (x in listOf(Float.NaN, Float.POSITIVE_INFINITY)) assertEquals(0, noteSeek(x, 100f, 118000, false))
        assertEquals(0, noteSeek(20f, 0f, 118000, false))
        assertEquals(0, noteSeek(20f, Float.NaN, 118000, false))
        assertEquals(0, noteSeek(20f, 100f, -1, false))
    }
    @Test fun formatsTimes() {
        assertEquals("1:58", noteTime(118000))
        assertEquals("0:42", noteTime(42000))
        assertEquals("0:00", noteTime(-10))
    }
    @Test fun startingAnotherBubblePausesTheFirst() {
        val coordinator = NotePlaybackCoordinator()
        val first = Any(); val second = Any()
        var pauses = 0
        coordinator.claim(first) { pauses++ }
        coordinator.claim(second) {}
        assertEquals(1, pauses)
    }
    @Test fun releasingOldBubbleDoesNotReleaseNewOwner() {
        val coordinator = NotePlaybackCoordinator()
        val first = Any(); val second = Any()
        var secondPauses = 0
        coordinator.claim(first) {}
        coordinator.claim(second) { secondPauses++ }
        coordinator.release(first)
        coordinator.claim(Any()) {}
        assertEquals(1, secondPauses)
    }
    @Test fun sameBubbleCanResumeWithoutPausingItself() {
        val coordinator = NotePlaybackCoordinator()
        val first = Any(); var pauses = 0
        coordinator.claim(first) { pauses++ }
        coordinator.claim(first) { pauses++ }
        assertEquals(0, pauses)
        coordinator.release(first)
        coordinator.claim(Any()) {}
        assertEquals(0, pauses)
    }
    @Test fun readableAccessibilityTimes() {
        assertEquals("42 seconds", noteSpokenTime(42000))
        assertEquals("1 minute 58 seconds", noteSpokenTime(118000))
        assertEquals("0 seconds", noteSpokenTime(0))
        assertEquals("1 second", noteSpokenTime(1000))
    }
    private class Fake : NoteFocusPort {
        var grant = true
        var abandoned = 0
        lateinit var listener: (Int) -> Unit
        override fun request(listener: (Int) -> Unit): Boolean { this.listener = listener; return grant }
        override fun abandon() { abandoned++ }
    }
    @Test fun transientLossResumesOnlyPreviouslyPlaying() {
        val fake = Fake(); var pauses = 0; var resumes = 0
        val focus = NoteFocus(fake, { pauses++ }, { resumes++ }, {})
        assertTrue(focus.play())
        fake.listener(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, pauses)
        fake.listener(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, resumes)
        fake.listener(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, resumes)
    }
    @Test fun repeatedTransientLossKeepsResumeIntent() {
        val fake = Fake(); var resumes = 0
        val focus = NoteFocus(fake, {}, { resumes++ }, {})
        focus.play()
        fake.listener(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        fake.listener(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        fake.listener(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, resumes)
    }
    @Test fun userPauseCancelsAutomaticResume() {
        val fake = Fake(); var resumes = 0
        val focus = NoteFocus(fake, {}, { resumes++ }, {})
        focus.play()
        fake.listener(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focus.stop()
        fake.listener(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, resumes)
        assertEquals(1, fake.abandoned)
    }
    @Test fun permanentLossAbandonsAndNeverResumes() {
        val fake = Fake(); var resumes = 0; var pauses = 0
        val focus = NoteFocus(fake, { pauses++ }, { resumes++ }, {})
        focus.play()
        fake.listener(AudioManager.AUDIOFOCUS_LOSS)
        fake.listener(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, pauses); assertEquals(0, resumes); assertEquals(1, fake.abandoned)
    }
    @Test fun duckAndRestoreVolume() {
        val fake = Fake(); val volume = mutableListOf<Float>()
        val focus = NoteFocus(fake, {}, {}, { volume += it })
        focus.play()
        fake.listener(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        fake.listener(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(0.2f, 1f), volume)
    }
    @Test fun refusedFocusNeverResumes() {
        val fake = Fake(); fake.grant = false; var resumes = 0
        val focus = NoteFocus(fake, {}, { resumes++ }, {})
        assertFalse(focus.play())
        fake.listener(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        fake.listener(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, resumes)
    }
}
