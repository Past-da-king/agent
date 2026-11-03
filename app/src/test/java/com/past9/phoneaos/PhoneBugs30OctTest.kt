package com.past9.phoneaos

import com.past9.phoneaos.data.DraftFiles
import com.past9.phoneaos.docs.PdfLayout
import com.past9.phoneaos.remote.BrainRef
import com.past9.phoneaos.remote.LiveCard
import com.past9.phoneaos.remote.LiveCards
import com.past9.phoneaos.remote.RpcException
import com.past9.phoneaos.remote.SendFlow
import com.past9.phoneaos.share.ShareKind
import com.past9.phoneaos.share.ShareLogic
import com.past9.phoneaos.share.SharedFile
import com.past9.phoneaos.ui.ComposerBox
import com.past9.phoneaos.ui.composerActionsFor
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * 30 Oct 2026, the six things he reported on his phone, each with the rule it must keep:
 * 1 the words leave the box when the message is accepted, even on a computer too old for chat.sendOnce;
 * 2 a message and its pictures are not held up by a slow upload or by the turn that follows;
 * 3 a shared file reaches the computer as a file, not as a path it cannot open;
 * 4 he can always send: the Send button is never a spinner that blocks him while the agent works;
 * 5 the working list and the Home count are the computer's own list, never rows whose end never arrived;
 * 6 a PDF page keeps its shape and a tall page is sliced, and the counts are said out loud.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhoneBugs30OctTest {

    // ---- a send is done when a brain HAS it (bugs 1, 2 and 4 on a live 0.2.114 computer) ----

    private fun flow(
        attempt: suspend (Boolean) -> JSONObject?,
        legacy: () -> Boolean = { true },
        socketOpen: () -> Boolean = { true },
        prepare: suspend () -> Unit = {},
        leave: () -> Unit = {},
    ) = SendFlow(
        linkUp = { true }, prepare = prepare, attempt = attempt, socketOpen = socketOpen, leave = leave, legacy = legacy,
        ackWaitMs = 200, prepareWaitMs = 5_000, linkWaitMs = 50, giveUpMs = 5_000, pauseMs = 5,
    )

    @Test fun anOldComputerIsHandedTheMessageAndTheSendFinishesAtOnce() = runBlocking {
        // 0.2.114 has no chat.sendOnce and answers the old chat.send only when the whole turn has ended: minutes.
        val t0 = System.currentTimeMillis()
        flow(attempt = { throw RpcException("not available on this device") }).run()
        val took = System.currentTimeMillis() - t0
        assertTrue("the send waited for the turn: ${took} ms", took < 1_000)
    }

    @Test fun aHandoverWithNoSocketIsNotCountedAsSent() = runBlocking {
        var asked = 0
        val e = org.junit.Assert.assertThrows(RpcException::class.java) {
            runBlocking {
                flow(
                    attempt = { asked++; throw RpcException("not available on this device") },
                    legacy = { false },
                    socketOpen = { false },
                ).run()
            }
        }
        assertEquals(SendFlow.NO_BRAIN, e.message)
        assertTrue("it tried again", asked > 1)
    }

    @Test fun picturesGetLongEnoughToGoUpBeforeTheBrainIsBlamed() {
        // Several pictures or a PDF on mobile data take longer than half a minute to upload.
        assertTrue("prepare wait ${SendFlow.PREPARE_WAIT_MS} ms", SendFlow.PREPARE_WAIT_MS >= 60_000)
        assertTrue("a frozen brain is still left quickly", SendFlow.ACK_WAIT_MS <= 10_000)
    }

    @Test fun aBrainThatNeverAnswersIsLeftAndTheNextOneTakesIt() = runBlocking {
        var left = 0
        var second = false
        flow(
            attempt = { if (!second) { awaitCancellation() } else JSONObject().put("accepted", true) },
            leave = { left++; second = true },
        ).run()
        assertEquals(1, left)
    }

    // ---- he can always send (bug 4) ----

    private fun box(text: String) = ComposerBox(mutableStateOf(text))

    @Test fun aNewMessageGoesWhileTheLastOneIsStillOnItsWay() = runTest {
        val b = box("first question")
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val acts = composerActionsFor(b, null, false, backgroundScope, send = { t, _, _ -> sent += t; gate.await() })
        acts.onSend(b.draft); runCurrent()
        b.draft = "and this too"
        acts.onSend(b.draft); runCurrent()
        assertEquals(listOf("first question", "and this too"), sent)
        gate.complete(Unit); runCurrent()
        assertEquals("", b.draft)
    }

    @Test fun theSameMessageTwiceIsStillSentOnce() = runTest {
        val b = box("once")
        var sends = 0
        val gate = CompletableDeferred<Unit>()
        val acts = composerActionsFor(b, null, false, backgroundScope, send = { _, _, _ -> sends++; gate.await() })
        acts.onSend(b.draft); runCurrent(); acts.onSend(b.draft); runCurrent()
        assertEquals(1, sends)
        gate.complete(Unit); runCurrent()
    }

    @Test fun aSendThatDoesNotGetThroughSaysSoAndRetrySendsTheSameWords() = runTest {
        val b = box("keep these words")
        var fail = true
        val sent = mutableListOf<String>()
        val acts = composerActionsFor(b, null, false, backgroundScope, send = { t, _, _ ->
            if (fail) throw java.io.IOException("The link to your computer dropped") else sent += t
        })
        acts.onSend(b.draft); runCurrent()
        assertEquals("keep these words", b.draft)
        assertNotNull("he is told", b.failed)
        assertNotNull("one tap tries again", b.retry)
        fail = false
        b.retry!!.invoke(); runCurrent()
        assertEquals(listOf("keep these words"), sent)
        assertEquals("", b.draft)
        assertNull(b.failed)
        assertFalse(b.sending)
    }

    @Test fun theBoxOnDiskIsNotBroughtBackByASaveThatWasAlreadyWaiting() {
        val dir: File = Files.createTempDirectory("drafts30").toFile()
        val a = DraftFiles(dir)
        a.cell("main").value = "words that were sent"
        // Accepted inside the saver's own delay: the clear must win, whichever save was waiting.
        a.cell("main").value = ""
        Thread.sleep(450)
        assertEquals("", DraftFiles(dir).cell("main").value)
        assertTrue("nothing left on disk", dir.listFiles()!!.isEmpty())
    }

    // ---- a shared file is a FILE on the computer (bug 3) ----

    @Test fun aSharedVoiceNoteIsSaidToBeAttachedWhenThePhoneIsLinked() {
        val note = SharedFile("PTT-1.opus", "/data/user/0/com.past9.phoneaos/files/shared/1-PTT-1.opus", "audio/ogg", 120_000, ShareKind.AUDIO, 42_000)
        val linked = ShareLogic.compose(null, null, null, listOf(note), emptyList(), true, attached = true)
        assertTrue(linked.text, linked.text.contains("Attached to this message"))
        assertFalse("the computer is never told to open a path on the phone", linked.text.contains("Path on the phone"))
        assertTrue(linked.text.contains("media_transcribe"))
        assertTrue("it is still named under the bubble", linked.fileNames.contains("PTT-1.opus"))
        val alone = ShareLogic.compose(null, null, null, listOf(note), emptyList(), false)
        assertTrue("a phone on its own tells the agent where the file is", alone.text.contains("Path on the phone"))
    }

    @Test fun aSharedPdfAndImageBothSayWhatTheyAre() {
        val pdf = SharedFile("statement.pdf", "/x/1-statement.pdf", "application/pdf", 4_000_000, ShareKind.PDF)
        val m = ShareLogic.compose(null, null, null, listOf(pdf), listOf("/x/a.jpg"), false, attached = true)
        assertTrue(m.text.contains("PDF: statement.pdf"))
        assertTrue(m.text.contains("1 photo is attached to this message"))
        assertEquals(listOf("statement.pdf"), m.fileNames)
    }

    // ---- the working list is the computer's own list (bug 5) ----

    private fun card(id: String, state: String, started: Long = 1_000L) =
        LiveCards.card(JSONObject().put("id", id).put("row", id).put("chat", "main").put("label", id).put("state", state).put("startedAt", started))!!

    @Test fun theNewestListReplacesWhateverThePhoneHeld() {
        // 14 ghosts, all "working" for 6 h 10 min, from a computer that restarted at 13:17.
        val ghosts = (1..14).map { card("ghost$it", "working", started = 1L) }
        val truth = listOf(card("payroll", "working"), card("inventory", "done"))
        val next = LiveCards.reconcile(ghosts, truth, brainAlive = true)
        assertEquals(truth, next)
        assertEquals(1, LiveCards.workingCount(next!!))
    }

    @Test fun withNoComputerAnsweringNothingIsWorking() {
        val ghosts = (1..8).map { card("g$it", "working") }
        assertEquals(0, LiveCards.workingCount(ghosts, brainAlive = false))
        assertFalse(LiveCards.anyWorking(ghosts, brainAlive = false))
        assertEquals(emptyList<LiveCard>(), LiveCards.reconcile(ghosts, null, brainAlive = false))
    }

    @Test fun theHeaderAndTheListTakeTheSameNumber() {
        val cards = listOf(card("a", "working"), card("b", "working"), card("c", "done"), card("d", "working"))
        assertEquals(3, LiveCards.workingCount(cards))
        assertEquals(true, LiveCards.anyWorking(cards))
        assertEquals(0, LiveCards.workingCount(listOf(card("c", "done"))))
    }

    @Test fun aComputerTooOldToPublishCardsKeepsWhatIsHereWhileItAnswers() {
        val cards = listOf(card("a", "working"))
        assertEquals(cards, LiveCards.reconcile(cards, null, brainAlive = true))
    }

    // ---- a PDF page keeps its shape, and a tall page is sliced (bug 6) ----

    @Test fun aTallPageIsSlicedAndNotSquashed() {
        val plan = PdfLayout.plan(1081, 4600)
        assertEquals("the page's own shape", 1081.0 / 4600.0, plan.width.toDouble() / plan.height.toDouble(), 0.01)
        assertTrue("sliced, not shrunk to fit", plan.slices.size > 1)
        assertTrue(plan.slices.all { it.height <= PdfLayout.MAX_SLICE_HEIGHT })
        assertEquals("the slices are the whole page", plan.height, plan.slices.sumOf { it.height })
        assertEquals(0, plan.slices.first().top)
    }

    @Test fun slicesCoverThePageWithNoSliverAndInOrder() {
        val plan = PdfLayout.plan(800, 5000)
        var expected = 0
        for (s in plan.slices) { assertEquals(expected, s.top); expected += s.height }
        assertEquals(plan.height, expected)
        assertTrue("no picture is a sliver", plan.slices.all { it.height >= plan.height / plan.slices.size - 2 })
    }

    @Test fun anOrdinaryPageIsOneReadablePicture() {
        val plan = PdfLayout.plan(595, 842)
        assertEquals(1, plan.slices.size)
        assertEquals(PdfLayout.TARGET_WIDTH.toFloat(), plan.width.toFloat(), 2f)
        assertEquals(595.0 / 842.0, plan.width.toDouble() / plan.height.toDouble(), 0.01)
    }

    @Test fun aPageTooBigForMemoryIsScaledDownAndStillKeepsItsShape() {
        val plan = PdfLayout.plan(800, 30_000)
        assertTrue("memory is bounded", plan.height <= PdfLayout.MAX_PAGE_HEIGHT)
        assertEquals(800.0 / 30_000.0, plan.width.toDouble() / plan.height.toDouble(), 0.01)
    }

    @Test fun aPageWithNoSizeMakesNoPicture() {
        assertTrue(PdfLayout.plan(0, 0).slices.isEmpty())
    }

    @Test fun howManyPagesCameThroughIsSaidOutLoud() {
        assertTrue(PdfLayout.summary(6, 6, 9).startsWith("PDF: 6 pages"))
        val cut = PdfLayout.summary(34, 12, 18)
        assertTrue(cut, cut.contains("34 pages"))
        assertTrue(cut, cut.contains("the first 12"))
        assertTrue(PdfLayout.summary(6, 6, 0).contains("no readable page pictures"))
    }
}
