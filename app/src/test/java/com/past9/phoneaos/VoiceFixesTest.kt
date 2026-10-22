package com.past9.phoneaos

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import android.speech.RecognizerIntent
import com.past9.phoneaos.remote.RemoteIds
import com.past9.phoneaos.remote.RemoteRows
import com.past9.phoneaos.voice.SystemDictation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** 0.14.3: the computer's voice notes play on the phone, and the mic uses the phone's own recogniser. */
@RunWith(RobolectricTestRunner::class)
class VoiceFixesTest {
    private val dir: File = Files.createTempDirectory("notes").toFile()
    private val failed = HashMap<String, String>()
    private val notes = object : RemoteRows.NoteAudio {
        override fun local(name: String) = File(dir, name)
        override fun failed(name: String): String? = failed[name]
    }

    private fun desktopNote(file: String) = JSONObject()
        .put("id", "c_note1").put("kind", "voice_note").put("text", "Nice, that's the whole thing working.")
        .put("meta", JSONObject().put("file", file).put("durationMs", 4200).put("style", "").toString()).put("createdAt", 1_792_648_000_000L)

    @Test fun voiceNoteBecomesTheVoiceRowNotANotice() {
        val item = RemoteRows.chat(desktopNote("1792648-abcd1234.wav"), RemoteIds(), notes)
        assertEquals("voice", item.kind)
        assertEquals("Nice, that's the whole thing working.", item.text)
        val m = JSONObject(item.meta)
        assertEquals("1792648-abcd1234.wav", m.getString("remoteNote"))
        assertEquals("loading", m.getString("audio"))
        assertEquals("", m.getString("path"))
    }

    @Test fun readyOnceTheAudioIsHere() {
        File(dir, "n-1.wav").writeBytes(ByteArray(2000))
        val m = JSONObject(RemoteRows.chat(desktopNote("n-1.wav"), RemoteIds(), notes).meta)
        assertEquals("ready", m.getString("audio"))
        assertEquals(File(dir, "n-1.wav").absolutePath, m.getString("path"))
    }

    @Test fun unavailableSaysWhyAndNeverTurnsIntoText() {
        failed["n-2.wav"] = "The computer no longer has this voice note."
        val item = RemoteRows.chat(desktopNote("n-2.wav"), RemoteIds(), notes)
        val m = JSONObject(item.meta)
        assertEquals("voice", item.kind)
        assertEquals("unavailable", m.getString("audio"))
        assertEquals("The computer no longer has this voice note.", m.getString("audioError"))
    }

    @Test fun onlyPlainNoteNamesAreAsked() {
        assertEquals("", RemoteRows.noteName(JSONObject().put("file", "../secrets.wav")))
        assertEquals("", RemoteRows.noteName(JSONObject().put("file", "C:\\Users\\x\\a.wav")))
        assertEquals("", RemoteRows.noteName(JSONObject().put("file", "a.mp3")))
        assertEquals("1-ab_c.wav", RemoteRows.noteName(JSONObject().put("file", "1-ab_c.wav")))
        assertEquals("unavailable", JSONObject(RemoteRows.chat(desktopNote("../x.wav"), RemoteIds(), notes).meta).getString("audio"))
    }

    @Test fun noteBytesReadTheWireForm() {
        val bytes = byteArrayOf(82, 73, 70, 70, 1, 2, 3)
        val wire = JSONObject().put("\$b64", java.util.Base64.getEncoder().encodeToString(bytes))
        assertEquals(bytes.toList(), RemoteRows.noteBytes(wire)!!.toList())
        assertNull(RemoteRows.noteBytes(null))
    }

    @Test fun otherRowsAreUnchanged() {
        val o = JSONObject().put("id", "c_a").put("kind", "agent").put("text", "hi").put("meta", "{\"x\":1}").put("createdAt", 5L)
        val item = RemoteRows.chat(o, RemoteIds(), notes)
        assertEquals("agent", item.kind); assertEquals("{\"x\":1}", item.meta)
    }

    @Test fun recogniserPrefersGoogle() {
        val googleTts = ComponentName("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService")
        val aiai = ComponentName("com.google.android.as", "com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService")
        val samsung = ComponentName("com.samsung.android.bixby.agent", "com.samsung.Recognizer")
        val gsa = ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
        assertEquals(googleTts, SystemDictation.pick(googleTts, listOf(aiai, googleTts)))
        assertEquals(googleTts, SystemDictation.pick(samsung, listOf(samsung, aiai, googleTts)))
        assertEquals(gsa, SystemDictation.pick(samsung, listOf(samsung, gsa)))
        assertEquals(samsung, SystemDictation.pick(samsung, listOf(samsung)))
        assertNull(SystemDictation.pick(null, emptyList()))
    }

    @Test fun intentUsesThePhonesLanguageLiveResultsAndTheOnlineModel() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val i = SystemDictation.intent(ctx, Locale.forLanguageTag("zu-ZA"))
        assertEquals("zu-ZA", i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertEquals(true, i.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertFalse(i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
        assertEquals(ctx.packageName, i.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE))
    }
}
