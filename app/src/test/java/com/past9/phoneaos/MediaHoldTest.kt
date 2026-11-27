package com.past9.phoneaos

import android.media.session.PlaybackState
import android.os.Bundle
import com.past9.phoneaos.media.MediaSessionView
import com.past9.phoneaos.voice.AudioRoute
import com.past9.phoneaos.voice.MediaHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A call (or the assistant listening) started while a song plays: the song must step aside at once and come back
 * after, and nothing else may be touched. 27 Nov 2026: a song kept playing into the call's microphone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaHoldTest {
    private class Player(override val pkg: String, override var state: Int, val obeysFocus: Boolean = false) : MediaSessionView {
        val calls = mutableListOf<String>()
        override val label get() = pkg
        override val title = ""; override val artist = ""; override val album = ""; override val position = 0L; override val duration = 0L
        override fun play() { calls += "play"; state = PlaybackState.STATE_PLAYING }
        override fun pause() { calls += "pause"; state = PlaybackState.STATE_PAUSED }
        override fun next() {}
        override fun previous() {}
        override fun seekTo(ms: Long) {}
        override fun playFromSearch(query: String, extras: Bundle) {}
    }

    private class Phone(vararg p: Player) : MediaHold.Port {
        val players = p.toMutableList()
        var grant = true
        var access = true
        val log = mutableListOf<String>()
        override val ownPackage = "com.example.agent"
        override fun requestFocus(): Boolean {
            log += "focus"
            // A well-behaved player pauses itself on a transient focus loss; the others keep going.
            players.filter { it.obeysFocus && it.state == PlaybackState.STATE_PLAYING }.forEach { it.state = PlaybackState.STATE_PAUSED; it.calls += "self-pause" }
            return grant
        }
        override fun abandonFocus() {
            log += "abandon"
            players.filter { it.obeysFocus && "self-pause" in it.calls && it.state == PlaybackState.STATE_PAUSED }.forEach { it.state = PlaybackState.STATE_PLAYING; it.calls += "self-resume" }
        }
        override fun musicActive() = players.any { it.state == PlaybackState.STATE_PLAYING }
        override fun sessions(): List<MediaSessionView>? = if (access) players.toList() else null
    }

    @Test fun aSongThatIgnoresFocusIsPausedAndPlaysAgainAfterTheCall() {
        val song = Player("com.example.music", PlaybackState.STATE_PLAYING)
        val phone = Phone(song)
        val hold = MediaHold(phone)
        assertTrue(hold.take())
        val o = hold.enforce()
        assertEquals(listOf("com.example.music"), o.paused)
        assertEquals(PlaybackState.STATE_PAUSED, song.state)
        assertFalse("nothing plays into the microphone", phone.musicActive())
        hold.release()
        assertEquals(listOf("pause", "play"), song.calls)
        assertEquals(PlaybackState.STATE_PLAYING, song.state)
        assertEquals(listOf("focus", "abandon"), phone.log)
    }

    @Test fun aPlayerThatPausesItselfIsLeftToResumeByItself() {
        val song = Player("com.example.music", PlaybackState.STATE_PLAYING, obeysFocus = true)
        val hold = MediaHold(Phone(song))
        hold.take()
        assertTrue(hold.enforce().paused.isEmpty())
        hold.release()
        assertEquals(listOf("self-pause", "self-resume"), song.calls)
    }

    @Test fun focusIsAskedForOnceAndReleasedOnce() {
        val phone = Phone(Player("com.example.music", PlaybackState.STATE_PLAYING))
        val hold = MediaHold(phone)
        hold.take(); hold.take(); hold.enforce(); hold.enforce()
        hold.release(); hold.release()
        assertEquals(listOf("focus", "abandon"), phone.log)
        assertFalse(hold.holding)
    }

    @Test fun refusedFocusStillPausesTheSong() {
        val song = Player("com.example.music", PlaybackState.STATE_PLAYING)
        val phone = Phone(song).apply { grant = false }
        val hold = MediaHold(phone)
        assertFalse(hold.take())
        val o = hold.enforce()
        assertFalse(o.focus)
        assertEquals(listOf("com.example.music"), o.paused)
        hold.release()
        assertEquals("focus was never held, so it is not abandoned", listOf("focus"), phone.log)
        assertEquals(PlaybackState.STATE_PLAYING, song.state)
    }

    @Test fun pausedAndOwnSessionsAreNeverTouched() {
        val paused = Player("com.example.podcast", PlaybackState.STATE_PAUSED)
        val own = Player("com.example.agent", PlaybackState.STATE_PLAYING)
        val hold = MediaHold(Phone(paused, own))
        hold.take(); hold.enforce(); hold.release()
        assertTrue(paused.calls.isEmpty())
        assertTrue(own.calls.isEmpty())
    }

    @Test fun nothingIsResumedOverWhatHeStartedDuringTheCall() {
        val song = Player("com.example.music", PlaybackState.STATE_PLAYING)
        val phone = Phone(song)
        val hold = MediaHold(phone)
        hold.take(); hold.enforce()
        // He asked the agent to play something else on the call.
        val video = Player("com.example.video", PlaybackState.STATE_PLAYING); phone.players += video
        hold.release()
        assertEquals(listOf("pause"), song.calls)
        assertEquals(PlaybackState.STATE_PAUSED, song.state)
    }

    @Test fun aSongHePausedHimselfDuringTheCallIsNotResumedTwice() {
        val song = Player("com.example.music", PlaybackState.STATE_PLAYING)
        val hold = MediaHold(Phone(song))
        hold.take(); hold.enforce()
        song.state = PlaybackState.STATE_STOPPED // he stopped it from the notification
        hold.release()
        assertEquals(listOf("pause"), song.calls)
    }

    @Test fun withoutAccessToSessionsTheHoldSaysTheSongMayStillPlay() {
        val phone = Phone(Player("com.example.music", PlaybackState.STATE_PLAYING)).apply { access = false }
        val hold = MediaHold(phone)
        hold.take()
        val o = hold.enforce()
        assertFalse(o.canSeeSessions)
        assertTrue(o.stillPlaying)
        hold.release()
    }

    @Test fun enforceAfterReleaseDoesNothing() {
        val song = Player("com.example.music", PlaybackState.STATE_PLAYING)
        val hold = MediaHold(Phone(song))
        hold.take(); hold.release()
        hold.enforce()
        assertTrue(song.calls.isEmpty())
    }

    @Test fun aBluetoothHeadsetThatRefusesTheVoiceLinkIsTriedAgainNotDroppedForTheSpeaker() {
        assertTrue(AudioRoute.retryHeadset(AudioRoute.Kind.BLUETOOTH, 0))
        assertTrue(AudioRoute.retryHeadset(AudioRoute.Kind.WIRED, AudioRoute.HEADSET_RETRIES - 1))
        assertFalse(AudioRoute.retryHeadset(AudioRoute.Kind.BLUETOOTH, AudioRoute.HEADSET_RETRIES))
        assertFalse(AudioRoute.retryHeadset(AudioRoute.Kind.SPEAKER, 0))
        assertFalse(AudioRoute.retryHeadset(null, 0))
    }
}
