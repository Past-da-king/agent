package com.past9.phoneaos

import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.voice.VoiceAutoPlay
import com.past9.phoneaos.voice.VoiceAutoPlay.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Agent voice notes play by themselves (27 Nov 2026, "what happened to the auto play VN feature"). A linked computer's
 * note lands before its audio; it must play once the audio is here, and on mobile data its audio must be fetched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceAutoPlayTest {
    private val files = mutableSetOf<String>()
    private fun note(id: Long, path: String = "", audio: String = "", call: Boolean = false) = ChatItem(id = id, kind = "voice", text = "hi",
        meta = org.json.JSONObject().put("path", path).apply { if (audio.isNotBlank()) put("audio", audio); if (call) put("call", true) }.toString())
    private fun steps(items: List<ChatItem>, heard: Set<Long> = emptySet(), asked: Set<Long> = emptySet(), on: Boolean = true) =
        VoiceAutoPlay.steps(items, heard, asked, on) { it in files }

    @Test fun aComputerNoteWhoseAudioIsStillComingPlaysOnceTheAudioLands() {
        // First the row, audio on its way: nothing plays yet, and nothing is used up.
        assertTrue(steps(listOf(note(5, audio = "loading"))).isEmpty())
        // The audio arrives and the row is updated with its path: now it plays.
        files += "/data/notes/a.wav"
        assertEquals(listOf(Step.Play(5, "/data/notes/a.wav")), steps(listOf(note(5, "/data/notes/a.wav", "ready"))))
    }

    @Test fun onMobileDataTheAudioIsAskedForOnce() {
        assertEquals(listOf(Step.Fetch(7)), steps(listOf(note(7, audio = "remote"))))
        assertTrue(steps(listOf(note(7, audio = "remote")), asked = setOf(7)).isEmpty())
    }

    @Test fun aLocalNotePlaysAtOnceWithoutEarphones() {
        files += "/n/1.wav"
        assertEquals(listOf(Step.Play(1, "/n/1.wav")), steps(listOf(note(1, "/n/1.wav"))))
    }

    @Test fun historyCallRowsAndTheSwitchOffNeverPlay() {
        files += "/n/1.wav"; files += "/n/2.wav"
        assertTrue(steps(listOf(note(1, "/n/1.wav")), heard = setOf(1)).isEmpty())
        assertTrue(steps(listOf(note(2, "/n/2.wav", call = true))).isEmpty())
        assertTrue(steps(listOf(note(2, "/n/2.wav")), on = false).isEmpty())
    }

    @Test fun onlyTheNewestPlaysAndOlderOnesAreSkipped() {
        files += "/n/1.wav"; files += "/n/2.wav"
        val items = listOf(note(1, "/n/1.wav"), note(2, "/n/2.wav"))
        assertEquals(listOf(Step.Play(2, "/n/2.wav")), steps(items))
        assertEquals(listOf(1L), VoiceAutoPlay.skipped(items, emptySet(), 2))
    }
}
