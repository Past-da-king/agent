package com.past9.phoneaos

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.voice.NotePlayback
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidNotePlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notes = mutableListOf<NotePlayback>()
    private val path = "/tmp/voice-note-test.wav"
    @Before fun setup() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(path), ShadowMediaPlayer.MediaInfo(118000, 0))
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_DURATION, "118000")
        shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }
    @After fun close() { notes.forEach { it.close() } }
    private fun prepared(): NotePlayback {
        val note = NotePlayback(context)
        notes += note
        runBlocking { note.prepare(path) }
        shadowOf(Looper.getMainLooper()).idle()
        return note
    }
    private fun player(note: NotePlayback): MediaPlayer {
        val field = NotePlayback::class.java.getDeclaredField("player")
        field.isAccessible = true
        return field.get(note) as MediaPlayer
    }
    @Test fun metadataFailureCanBeRetried() {
        val note = NotePlayback(context)
        notes += note
        val broken = "/tmp/voice-note-broken.wav"
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(broken), IllegalArgumentException("Unreadable audio"))
        val result = runCatching { runBlocking { note.prepare(broken) } }
        assertTrue(result.isFailure)
        assertTrue(note.error)
        assertFalse(note.playing)
        runBlocking { note.prepare(path) }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(note.error)
        assertEquals(118000, note.duration)
        note.play()
        assertTrue(note.playing)
    }
    @Test fun durationKnownBeforePlay() {
        val note = prepared()
        assertEquals(118000, note.duration)
        assertFalse(note.playing)
        assertFalse(note.started)
    }
    @Test fun pausedSeekSurvivesResume() {
        val note = prepared()
        note.play()
        note.pause()
        note.seek(42000)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(42000, note.position)
        note.play()
        assertTrue(note.playing)
        assertEquals(42000, note.position)
    }
    @Test fun pauseDuringPendingSeekKeepsTheChosenPosition() {
        val note = prepared()
        note.play()
        shadowOf(player(note)).setSeekDelay(500)
        note.seek(59000)
        note.pause()
        assertEquals(59000, note.position)
    }
    @Test fun anotherNotePausesFirstAndKeepsItsPosition() {
        val first = prepared()
        val second = prepared()
        first.play()
        first.seek(42000)
        shadowOf(Looper.getMainLooper()).idle()
        second.play()
        assertFalse(first.playing)
        assertTrue(second.playing)
        assertEquals(42000, first.position)
    }
    @Test fun overlappingSeeksKeepTheNewestTarget() {
        val note = prepared()
        note.play()
        shadowOf(player(note)).setSeekDelay(500)
        note.seek(20000)
        note.seek(90000)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        note.pause()
        assertEquals(90000, note.position)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(90000, note.position)
        note.play()
        assertEquals(90000, note.position)
    }
    @Test fun unpluggingEarphonesPausesAndKeepsPosition() {
        val note = prepared()
        note.play(); note.seek(42000)
        shadowOf(Looper.getMainLooper()).idle()
        context.sendBroadcast(android.content.Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(note.playing)
        assertEquals(42000, note.position)
        assertNotNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAbandonedAudioFocusRequest)
    }
    @Test fun focusRefusalDoesNotLosePositionOrRequirePreparation() {
        val note = prepared()
        note.seek(42000); shadowOf(Looper.getMainLooper()).idle()
        val nativePlayer = player(note)
        val manager = shadowOf(context.getSystemService(AudioManager::class.java))
        manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        note.play()
        assertFalse(note.playing); assertFalse(note.error); assertTrue(note.focusBlocked)
        assertEquals(42000, note.position)
        manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        note.play()
        assertSame(nativePlayer, player(note))
        assertEquals(42000, note.position); assertTrue(note.playing)
    }
    @Test fun recycledBubbleKeepsItsPositionAndDuration() {
        val registry = com.past9.phoneaos.voice.NotePlayers(context)
        registry.attach(1)
        val first = registry.get(1); notes += first
        runBlocking { first.prepare(path) }; shadowOf(Looper.getMainLooper()).idle()
        first.play(); first.seek(42000); shadowOf(Looper.getMainLooper()).idle()
        registry.detach(1)
        assertFalse(first.playing); assertEquals(42000, first.position)
        registry.attach(1)
        val restored = registry.get(1)
        assertSame(first, restored)
        runBlocking { restored.prepare(path) }; shadowOf(Looper.getMainLooper()).idle()
        assertEquals(118000, restored.duration); assertEquals(42000, restored.position)
        restored.play(); assertEquals(42000, restored.position)
        registry.detach(1)
    }
    @Test fun autoplayAndBubbleShareOneNativePlayer() {
        val registry = com.past9.phoneaos.voice.NotePlayers(context)
        registry.attach(2)
        val autoplay = runBlocking { registry.autoPlay(2, path) }; notes += autoplay
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(autoplay.playing)
        val bubble = registry.get(2)
        assertSame(autoplay, bubble)
        val nativePlayer = player(autoplay)
        runBlocking { bubble.prepare(path) }
        assertSame(nativePlayer, player(bubble))
        bubble.pause(); assertFalse(autoplay.playing)
        registry.detach(2)
    }
    @Test fun stopSpeakingStopsTheBubbleBeforeRecording() {
        val note = prepared(); note.play()
        com.past9.phoneaos.App.graph(context).stopSpeaking()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(note.playing)
        assertNotNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAbandonedAudioFocusRequest)
    }
    @Test fun completionReleasesFocusAndStops() {
        val note = prepared(); note.play()
        shadowOf(player(note)).invokeCompletionListener()
        assertFalse(note.playing); assertEquals(118000, note.position)
        assertNotNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAbandonedAudioFocusRequest)
    }
    @Test fun decoderErrorReleasesAndCanRetry() {
        val note = prepared(); note.play()
        shadowOf(player(note)).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        assertFalse(note.playing); assertTrue(note.error)
        assertNotNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAbandonedAudioFocusRequest)
        runBlocking { note.prepare(path) }; shadowOf(Looper.getMainLooper()).idle()
        note.play(); assertTrue(note.playing); assertFalse(note.error)
    }
    @Test fun unknownPlayerDurationKeepsMetadataAndNeverCrashesSeek() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(path), ShadowMediaPlayer.MediaInfo(-1, 0))
        val note = prepared()
        assertEquals(118000, note.duration)
        note.seek(42000); assertEquals(42000, note.position)
    }
    @Test fun completedDetachedNoteCanReplayFromStart() {
        val note = prepared(); note.play()
        shadowOf(player(note)).invokeCompletionListener()
        note.release()
        runBlocking { note.prepare(path) }; shadowOf(Looper.getMainLooper()).idle()
        note.play(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(note.playing); assertEquals(0, note.position)
    }
    @Test fun disposalStopsAndAbandonsFocus() {
        val note = prepared()
        note.play()
        note.close()
        assertFalse(note.playing)
        assertNotNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAbandonedAudioFocusRequest)
    }
}
