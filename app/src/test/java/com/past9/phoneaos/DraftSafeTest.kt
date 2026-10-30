package com.past9.phoneaos

import com.past9.phoneaos.data.DraftFiles
import com.past9.phoneaos.data.DraftStore
import com.past9.phoneaos.data.QuietSaver
import com.past9.phoneaos.ui.ComposerBox
import com.past9.phoneaos.ui.ComposerBoxes
import com.past9.phoneaos.ui.ReplyDrafts
import com.past9.phoneaos.ui.composerActionsFor
import com.past9.phoneaos.voice.BoxWriter
import com.past9.phoneaos.voice.DictationFolder
import com.past9.phoneaos.voice.DictationTape
import com.past9.phoneaos.voice.Dictations
import com.past9.phoneaos.voice.Words
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * 30 Oct 2026, the number one rule: what he said or typed is never lost. "My whole thing just disappeared."
 * The box lives on disk per chat, a send only empties it once the computer took it, and a result never leaves less.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DraftSafeTest {
    private val dir: File = Files.createTempDirectory("drafts").toFile()

    // ---- on disk, per chat ----

    @Test fun textSurvivesProcessDeathWithinHalfASecond() {
        val a = DraftFiles(dir)
        a.cell("main").value = "a long dictation about the whole platform"
        Thread.sleep(480)
        // No flush, no goodbye: the process is gone, a new one reads the folder.
        val b = DraftFiles(dir)
        assertEquals("a long dictation about the whole platform", b.cell("main").value)
        assertFalse("no half-written file is left", dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun everyChatHasItsOwnBox() {
        val a = DraftFiles(dir)
        a.cell("main").value = "for the main chat"
        a.cell("chat:p1").value = "for the project"
        a.cell("reply:42").value = "for the card"
        a.flush()
        val b = DraftFiles(dir)
        assertEquals("for the main chat", b.cell("main").value)
        assertEquals("for the project", b.cell("chat:p1").value)
        assertEquals("for the card", b.cell("reply:42").value)
        assertEquals("", b.cell("chat:other").value)
    }

    @Test fun anEmptiedBoxLeavesNoFileBehind() {
        val a = DraftFiles(dir)
        a.cell("main").value = "words"; a.flush()
        a.cell("main").value = ""; a.flush()
        assertEquals("", DraftFiles(dir).cell("main").value)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun aRecreatedScreenFindsTheSameState() {
        // A rotation, a reload, a reconnect or Android dropping the screen: whoever asks gets the same words, and the
        // dictation still writing into the old one is seen by the new screen.
        DraftStore.openAt(dir)
        val before = ComposerBoxes.box("main")
        before.draft = "still talking"
        val after = DraftStore.cell("main")
        before.draft = "still talking and talking"
        assertEquals("still talking and talking", after.value)
        ComposerBoxes.forget("main")
    }

    @Test fun anAnswerCardsReplyComesBackOpenAfterARestart() {
        DraftStore.openAt(dir)
        ReplyDrafts.box("77").draft = "half a reply"
        DraftStore.flush()
        DraftStore.openAt(dir)
        ComposerBoxes.dropForTest()
        assertEquals("half a reply", ReplyDrafts.box("77").draft)
        assertTrue(ReplyDrafts.isOpen("77"))
        ReplyDrafts.sent("77")
        assertFalse(ReplyDrafts.isOpen("77"))
        DraftStore.flush()
        DraftStore.openAt(dir)
        assertEquals("", DraftStore.cell("reply:77").value)
    }

    // ---- a send empties it only when accepted ----

    private fun box(text: String) = ComposerBox(mutableStateOf(text))

    @Test fun theBoxKeepsItsWordsUntilTheSendIsAccepted() = runTest {
        val b = box("the whole message")
        val gate = CompletableDeferred<Unit>()
        val acts = composerActionsFor(b, null, false, backgroundScope, send = { _, _, _ -> gate.await() })
        acts.onSend(b.draft)
        runCurrent()
        assertEquals("still there while it goes", "the whole message", b.draft)
        assertTrue(b.sending)
        acts.onSend(b.draft)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("", b.draft)
        assertFalse(b.sending)
    }

    @Test fun aFailedSendKeepsEverything() = runTest {
        val b = box("the whole message"); b.attachments.add("/p.jpg")
        var told = ""
        val acts = composerActionsFor(b, null, false, backgroundScope, send = { _, _, _ -> throw java.io.IOException("Not connected") }, onFailed = { told = it })
        acts.onSend(b.draft)
        runCurrent()
        assertEquals("the whole message", b.draft)
        assertEquals(listOf("/p.jpg"), b.attachments.toList())
        assertFalse("he can press Send again", b.sending)
        assertEquals("Not connected", told)
    }

    @Test fun aSecondTapWhileSendingDoesNotSendTwice() = runTest {
        val b = box("once")
        var sends = 0
        val gate = CompletableDeferred<Unit>()
        val acts = composerActionsFor(b, null, false, backgroundScope, send = { _, _, _ -> sends++; gate.await() })
        acts.onSend(b.draft); runCurrent(); acts.onSend(b.draft); runCurrent()
        assertEquals(1, sends)
    }

    @Test fun wordsThatArriveWhileItIsSendingStay() = runTest {
        val b = box("first part")
        val gate = CompletableDeferred<Unit>()
        val acts = composerActionsFor(b, null, false, backgroundScope, send = { _, _, _ -> gate.await() })
        acts.onSend(b.draft); runCurrent()
        b.draft = "first part and more"
        gate.complete(Unit); runCurrent()
        assertEquals("and more", b.draft)
    }

    // ---- a result only ever adds ----

    @Test fun aShorterResultIsIgnored() {
        val w = BoxWriter("")
        val long = (1..30).joinToString(" ") { "word$it" }
        assertEquals(long, w.next("", long))
        assertNull("the box stays as it is", w.next(long, "word30"))
        assertNull(w.next(long, ""))
        assertEquals(long + " word31", w.next(long, "$long word31"))
    }

    @Test fun whatWasInTheBoxBeforeStaysWhateverHappens() {
        val w = BoxWriter("typed first")
        assertEquals("typed first hello", w.next("typed first", "hello"))
        assertNull(w.next("typed first hello", ""))
    }

    @Test fun aSmallCorrectionStillGoesIn() {
        val w = BoxWriter("")
        val a = "their is a sentence here today"
        val b = "there is a sentence here"
        assertEquals(a, w.next("", a))
        assertEquals(b, w.next(a, b))
    }

    @Test fun aMessageAlreadySentNeverComesBack() {
        val w = BoxWriter("")
        val first = "first thing I said"
        w.next("", first)
        // He pressed Send; the box was emptied.
        assertNull(w.next("", first))
        assertEquals("then this", w.next("", "$first then this"))
    }

    @Test fun whatHeTypesDuringADictationStays() {
        val w = BoxWriter("")
        w.next("", "dictated words")
        assertEquals("dictated words typed by hand more", w.next("dictated words typed by hand", "dictated words more"))
    }

    @Test fun aNewUtteranceIsNotACorrection() {
        val long = "one two three four five six seven eight"
        assertTrue(Words.startedOver(long, "next"))
        assertFalse(Words.startedOver(long, "one two three four five six seven"))
        assertFalse("a corrected first word", Words.startedOver("hi there", "hello there"))
    }

    // ---- saved beside the audio ----

    private fun speech(seconds: Int): ByteArray {
        val b = ByteBuffer.allocate(seconds * DictationTape.BYTES_PER_SECOND).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until b.capacity() / 2) b.putShort(if (i % 2 == 0) 3000 else -3000)
        return b.array()
    }

    /** A tape as a killed app leaves it: its header is behind, and no notes. */
    private fun bareTape(id: String, seconds: Int): File {
        val f = File(dir, "$id.wav")
        f.writeBytes(DictationTape.header(0) + speech(seconds))
        return f
    }

    @Test fun theWordsAreSavedBesideTheAudioAsTheyArrive() {
        val f = DictationFolder(dir, QuietSaver(delayMs = 10))
        f.note("20261030-105600", 1000, 2000, "hello", "recording", "main")
        f.note("20261030-105600", 1000, 4000, "hello there", "recording", "main", flush = true)
        val again = DictationFolder(dir)
        assertEquals("hello there", again.get("20261030-105600")!!.text)
        assertEquals(4000, again.get("20261030-105600")!!.durationMs)
    }

    @Test fun theSavedWordsNeverShrink() {
        val f = DictationFolder(dir, QuietSaver(delayMs = 10))
        val long = (1..30).joinToString(" ") { "w$it" }
        f.note("a", 1, 1, long, "recording", "main")
        f.note("a", 1, 2, "w30", "done", "main", flush = true)
        assertEquals(long, DictationFolder(dir).get("a")!!.text)
    }

    @Test fun aTapeFromBeforeWordsWereSavedIsListed() {
        bareTape("20261030-105630", 150)
        val d = DictationFolder(dir).list().single()
        assertEquals("audio", d.state)
        assertEquals(150_000, d.durationMs)
        assertNotNull(d.audio)
        assertTrue("it dates from its name", d.startedAt > 0)
    }

    @Test fun aKilledDictationIsMarkedCutOffAndKeepsItsWords() {
        val f = DictationFolder(dir, QuietSaver(delayMs = 10))
        f.note("k", 1, 1, "words so far", "recording", "main", flush = true)
        val next = DictationFolder(dir)
        assertEquals(listOf("k"), next.closeInterrupted().map { it.id })
        assertEquals("interrupted", next.get("k")!!.state)
        assertEquals("words so far", next.get("k")!!.text)
        assertTrue("the computer gets it", next.get("k")!!.needsSync)
    }

    @Test fun transcribeAgainWritesDownALostTape() = runTest {
        bareTape("20261030-105630", 70)
        val f = DictationFolder(dir, QuietSaver(delayMs = 10))
        var calls = 0
        val text = f.transcribeAgain("20261030-105630", { pcm -> calls++; assertTrue(pcm.size <= 30 * DictationTape.BYTES_PER_SECOND); "part$calls" })
        assertTrue("70 s goes in pieces of at most 30 s", calls >= 3)
        assertTrue(text.startsWith("part1 part2 part3"))
        assertEquals(text, DictationFolder(dir).get("20261030-105630")!!.text)
        assertEquals("done", DictationFolder(dir).get("20261030-105630")!!.state)
    }

    @Test fun transcribeAgainThatHearsLessChangesNothing() = runTest {
        bareTape("t", 5)
        val f = DictationFolder(dir, QuietSaver(delayMs = 10))
        f.note("t", 1, 5000, "the words he saw on the screen", "done", "main", flush = true)
        assertEquals("the words he saw on the screen", f.transcribeAgain("t", { "" }))
        assertEquals("the words he saw on the screen", f.transcribeAgain("t", { "thanks" }))
    }

    @Test fun pruningDropsOldAudioButKeepsTheWords() {
        val f = DictationFolder(dir, QuietSaver(delayMs = 10))
        for (i in 10..15) { bareTape("old$i", 1); f.note("old$i", i.toLong(), 1000, "words $i", "done", "main") }
        bareTape("nowords", 1); File(dir, "nowords.wav").setLastModified(1)
        f.flush()
        f.prune(keepAudio = 2)
        val after = DictationFolder(dir).list()
        assertTrue("all the words are still here", (10..15).all { i -> after.any { it.text == "words $i" } })
        assertEquals("newest two, and the one that has no words yet", 3, after.count { it.audio != null })
        assertNotNull(after.first { it.id == "nowords" }.audio)
    }

    @Test fun onlyWhatTheComputerLacksIsSent() = runTest {
        val root = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = Dictations.of(root)
        f.list().forEach { f.forget(it.id) }
        f.note("20261030-100000", 1, 1000, "first", "done", "main", flush = true)
        val sent = ArrayList<String>()
        Dictations.push(root) { sent.add(it.getString("id")); assertFalse("never the audio", it.has("audio")) }
        Dictations.push(root) { sent.add("again") }
        assertEquals(listOf("20261030-100000"), sent)
        f.note("20261030-100000", 1, 1000, "first and more", "done", "main", flush = true)
        Dictations.push(root) { sent.add("changed") }
        assertEquals(listOf("20261030-100000", "changed"), sent)
        f.forget("20261030-100000")
    }

    @Test fun aComputerThatCannotTakeItIsTriedAgainLater() = runTest {
        val root = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = Dictations.of(root)
        f.list().forEach { f.forget(it.id) }
        f.note("20261030-110000", 1, 1000, "kept", "done", "main", flush = true)
        Dictations.push(root) { throw java.io.IOException("not available") }
        assertTrue(f.get("20261030-110000")!!.needsSync)
        f.forget("20261030-110000")
    }
}
