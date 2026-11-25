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
    @Test fun disposalStopsAndAbandonsFocus() {
        val note = prepared()
        note.play()
        note.close()
        assertFalse(note.playing)
        assertNotNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAbandonedAudioFocusRequest)
    }
}
