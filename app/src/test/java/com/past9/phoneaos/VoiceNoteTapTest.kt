package com.past9.phoneaos

import com.past9.phoneaos.remote.RemoteIds
import com.past9.phoneaos.remote.RemoteRows
import com.past9.phoneaos.remote.RemoteRows.NoteState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * 29 Oct 2026, "vn not working": on mobile data the computer's voice notes were never fetched (media on tap, 0.14.11)
 * and the bubble spun "Getting the audio" with a disabled play button and no tap. A note must play with one tap on
 * Wi-Fi and on mobile data, and a failure must say why with Try again.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceNoteTapTest {
    private val dir: File = Files.createTempDirectory("notes").toFile()
    private val failed = HashMap<String, String>()
    private val inFlight = HashSet<String>()
    private var metered = true
    private val notes = object : RemoteRows.NoteAudio {
        override fun local(name: String) = File(dir, name)
        override fun failed(name: String): String? = failed[name]
        override fun fetching(name: String) = name in inFlight
        override val onTap: Boolean get() = metered
    }

    private fun desktopNote(file: String, ms: Long = 30_000) = JSONObject()
        .put("id", "c_note9").put("kind", "voice_note").put("text", "The deploy is done.")
        .put("meta", JSONObject().put("file", file).put("durationMs", ms).toString()).put("createdAt", 1_793_274_226_113L)

    private fun meta(file: String) = JSONObject(RemoteRows.chat(desktopNote(file), RemoteIds(), notes).meta)

    @Test fun onMobileDataANoteWaitsOnTheComputerReadyToTapNotLoading() {
        val m = meta("1793274226113-760878e3.wav")
        assertEquals("remote", m.getString("audio"))
        assertEquals(NoteState.OnComputer, RemoteRows.noteState(m, notes))
    }

    @Test fun onWifiItIsFetchedAtOnce() {
        metered = false
        assertEquals("loading", meta("n-wifi.wav").getString("audio"))
    }

    @Test fun aRowStoredLoadingWithNothingInFlightCanBeTapped() {
        // What his phone held: rows saved "loading" by 0.14.11 on mobile data, never fetched.
        val stale = JSONObject().put("file", "n-old.wav").put("remoteNote", "n-old.wav").put("path", "").put("audio", "loading")
        assertEquals(NoteState.OnComputer, RemoteRows.noteState(stale, notes))
        inFlight += "n-old.wav"
        assertEquals(NoteState.Fetching, RemoteRows.noteState(stale, notes))
    }

    @Test fun readyAsSoonAsTheCopyIsHereWhateverTheRowSays() {
        val stale = JSONObject().put("file", "n-here.wav").put("remoteNote", "n-here.wav").put("path", "").put("audio", "loading")
        File(dir, "n-here.wav").writeBytes(ByteArray(4000))
        val s = RemoteRows.noteState(stale, notes)
        assertTrue(s is NoteState.Ready)
        assertEquals(File(dir, "n-here.wav").path, (s as NoteState.Ready).file.path)
    }

    @Test fun aFailureSaysWhyAndCanBeTriedAgain() {
        failed["n-bad.wav"] = "Couldn't get the audio from your computer: timeout"
        assertEquals(NoteState.Failed("Couldn't get the audio from your computer: timeout"), RemoteRows.noteState(meta("n-bad.wav"), notes))
    }

    @Test fun theVoiceRowKeepsTheComputersIdForAfterARestart() {
        assertEquals("c_note9", meta("n-rid.wav").getString("rid"))
    }

    @Test fun noLinkAndNoCopySaysSo() {
        val m = JSONObject().put("file", "n-x.wav").put("remoteNote", "n-x.wav").put("path", "")
        assertTrue(RemoteRows.noteState(m, null) is NoteState.NoAudio)
        assertTrue(RemoteRows.noteState(JSONObject().put("file", "../x.wav"), notes) is NoteState.NoAudio)
    }

    @Test fun theTapSaysAboutHowMuchDataItCosts() {
        assertEquals("1.9 MB", RemoteRows.noteSizeLabel(JSONObject().put("durationMs", 30_000)))
        assertEquals("", RemoteRows.noteSizeLabel(JSONObject()))
    }
}
