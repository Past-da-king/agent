package com.past9.phoneaos

import androidx.compose.runtime.mutableStateOf
import com.past9.phoneaos.ui.ComposerBox
import com.past9.phoneaos.ui.ComposerTools
import com.past9.phoneaos.ui.composerActionsFor
import com.past9.phoneaos.voice.DictationHub
import com.past9.phoneaos.voice.DictationView
import com.past9.phoneaos.voice.DictationView.Phase
import com.past9.phoneaos.voice.Settled
import com.past9.phoneaos.voice.settleDictation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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

/**
 * 18 Nov 2026: a 188 s dictation went to the chat as the Android live caption ("constration head ... the the the") while
 * the whole-take text, written down from the saved audio a moment later, was clean. The message left the box while the
 * whole-take pass was still running. A send now waits for that text and sends THAT.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DictationSendTest {
    private val caption = "for example constration head but some other companies the the the start agentility"
    private val whole = "for example, the letterhead? Because Stratage has its own letterhead"

    private fun box(t: String) = ComposerBox(mutableStateOf(t))
    private class Tools(val settle: suspend (ComposerBox) -> Settled) : ComposerTools {
        override fun pickPhotos(box: ComposerBox) {}
        override fun pickDocs(box: ComposerBox) {}
        override fun camera(box: ComposerBox, origin: androidx.compose.ui.geometry.Rect) {}
        override fun mic(box: ComposerBox) {}
        override fun listening(box: ComposerBox) = false
        override fun recordings(box: ComposerBox) {}
        override suspend fun settleDictation(box: ComposerBox) = settle(box)
    }

    @Test fun aSendWhileTheWholeTakePassRunsWaitsAndSendsTheWholeTakeNotTheCaption() = runTest {
        val b = box(caption)
        val gate = CompletableDeferred<Unit>()
        val sent = ArrayList<String>()
        val tools = Tools { gate.await(); b.draft = whole; Settled.FINAL }
        val acts = composerActionsFor(b, tools, false, backgroundScope, send = { t, _, _ -> sent += t })
        acts.onSend(b.draft); runCurrent()
        assertTrue("nothing leaves while the pass runs", sent.isEmpty())
        assertTrue("the Send button shows it is finishing", b.finishing)
        assertEquals("the caption stays in the box meanwhile", caption, b.draft)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(whole), sent)
        assertFalse(b.finishing)
        assertEquals("", b.draft)
    }

    @Test fun whenTheWholeTakeNeverComesNothingIsSentAndRetryIsOffered() = runTest {
        val b = box(caption)
        var result = Settled.NO_FINAL
        val sent = ArrayList<String>()
        var told = ""
        val tools = Tools { if (result == Settled.FINAL) b.draft = whole; result }
        val acts = composerActionsFor(b, tools, false, backgroundScope, send = { t, _, _ -> sent += t }, onFailed = { told = it })
        acts.onSend(b.draft); runCurrent()
        assertTrue("the caption is not sent", sent.isEmpty())
        assertEquals("his words stay in the box", caption, b.draft)
        assertNotNull(b.failed); assertTrue(told.isNotBlank()); assertNotNull(b.retry)
        assertFalse(b.finishing); assertFalse(b.sending)
        result = Settled.FINAL
        b.retry!!.invoke(); runCurrent()
        assertEquals(listOf(whole), sent)
    }

    @Test fun aSendThatFailsAfterTheWholeTakeCameRetriesWithTheWholeTakeNotTheCaption() = runTest {
        val b = box(caption)
        val tools = Tools { b.draft = whole; Settled.FINAL }
        val sent = ArrayList<String>(); var fail = true
        val acts = composerActionsFor(b, tools, false, backgroundScope, send = { t, _, _ -> if (fail) throw java.io.IOException("Not connected"); sent += t })
        acts.onSend(b.draft); runCurrent()
        assertEquals(whole, b.draft)
        fail = false
        b.retry!!.invoke(); runCurrent()
        assertEquals(listOf(whole), sent)
    }

    @Test fun wordsThatAreHisOwnGoAsTheyAre() = runTest {
        val b = box("typed by hand")
        val sent = ArrayList<String>()
        val acts = composerActionsFor(b, Tools { Settled.NOT_DICTATING }, false, backgroundScope, send = { t, _, _ -> sent += t })
        acts.onSend(b.draft); runCurrent()
        assertEquals(listOf("typed by hand"), sent)
    }

    @Test fun aSuggestionChipIsNeverReplacedByTheBoxWords() = runTest {
        val b = box(caption)
        val sent = ArrayList<String>()
        val acts = composerActionsFor(b, Tools { b.draft = whole; Settled.FINAL }, false, backgroundScope, send = { t, _, _ -> sent += t })
        acts.onSend("What can you do?"); runCurrent()
        assertEquals(listOf("What can you do?"), sent)
    }

    // ---- settleDictation ----
    private fun ui(owner: Any?, p: Phase, text: String = "") = DictationHub.Ui(owner, DictationView(text, p, final = p == Phase.DONE))

    @Test fun settleStopsAListeningDictationAndWaitsForDone() = runTest {
        val f = MutableStateFlow<DictationHub.Ui?>(ui("main", Phase.LISTENING, caption))
        var stops = 0; var out: Settled? = null
        backgroundScope.launch2 { out = settleDictation(f, "main", stop = { stops++; f.value = ui("main", Phase.FINISHING, caption) }, retry = {}) }
        runCurrent()
        assertEquals(1, stops); assertNull("still waiting", out)
        f.value = ui("main", Phase.DONE, whole); runCurrent()
        assertEquals(Settled.FINAL, out)
    }

    @Test fun settleWaitsOutAFinishingDictationWithoutStoppingItAgain() = runTest {
        val f = MutableStateFlow<DictationHub.Ui?>(ui("main", Phase.FINISHING, caption))
        var stops = 0; var out: Settled? = null
        backgroundScope.launch2 { out = settleDictation(f, "main", stop = { stops++ }, retry = {}) }
        runCurrent(); assertEquals(0, stops); assertNull(out)
        f.value = ui("main", Phase.DONE, whole); runCurrent()
        assertEquals(Settled.FINAL, out)
    }

    @Test fun aKeptTakeIsRetriedAndOneThatStaysKeptIsNoFinal() = runTest {
        val f = MutableStateFlow<DictationHub.Ui?>(ui("main", Phase.KEPT, caption))
        var retries = 0; var out: Settled? = null
        backgroundScope.launch2 { out = settleDictation(f, "main", stop = {}, retry = { retries++; f.value = ui("main", Phase.FINISHING, caption) }) }
        runCurrent(); assertEquals(1, retries); assertNull(out)
        f.value = ui("main", Phase.KEPT, caption); runCurrent()
        assertEquals(Settled.NO_FINAL, out)
    }

    @Test fun settleGivesUpAtTheLimitAsNoFinal() = runTest {
        val f = MutableStateFlow<DictationHub.Ui?>(ui("main", Phase.FINISHING, caption))
        var out: Settled? = null
        backgroundScope.launch2 { out = settleDictation(f, "main", stop = {}, retry = {}, limitMs = 5_000) }
        runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(Settled.NO_FINAL, out)
    }

    @Test fun anotherBoxesDictationOrNoneMeansNotDictating() = runTest {
        assertEquals(Settled.NOT_DICTATING, settleDictation(MutableStateFlow(null), "main", {}, {}))
        assertEquals(Settled.NOT_DICTATING, settleDictation(MutableStateFlow(ui("other", Phase.LISTENING)), "main", {}, {}))
        assertEquals(Settled.NOT_DICTATING, settleDictation(MutableStateFlow(ui("main", Phase.CANCELLED)), "main", {}, {}))
    }

    private fun kotlinx.coroutines.CoroutineScope.launch2(block: suspend () -> Unit) { this.launch { block() } }
}
