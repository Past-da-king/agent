package com.past9.phoneaos

import androidx.compose.runtime.mutableStateOf
import com.past9.phoneaos.ui.ComposerBox
import com.past9.phoneaos.ui.ComposerTools
import com.past9.phoneaos.ui.composerActionsFor
import com.past9.phoneaos.voice.TakeContext
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

/**
 * Instant send (19 Nov 2026, replaces the 0.15.74 wait): a dictated message goes into the chat AT ONCE with its live
 * caption, carrying the id of its take; the whole take is written down behind it and replaces the message in place.
 * 18 Nov: a send used to go out as the caption while the clean text arrived after it; 0.15.74 made Send wait; now he never waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DictationSendTest {
    private val caption = "for example constration head but some other companies the the the start agentility"

    private fun box(t: String) = ComposerBox(mutableStateOf(t))
    private class Tools(val take: (ComposerBox) -> String?) : ComposerTools {
        override fun pickPhotos(box: ComposerBox) {}
        override fun pickDocs(box: ComposerBox) {}
        override fun camera(box: ComposerBox, origin: androidx.compose.ui.geometry.Rect) {}
        override fun mic(box: ComposerBox) {}
        override fun listening(box: ComposerBox) = false
        override fun recordings(box: ComposerBox) {}
        override fun instantTake(box: ComposerBox) = take(box)
    }

    @Test fun aDictatedMessageGoesAtOnceWithItsCaptionAndItsTakeId() = runTest {
        val b = box(caption)
        val sent = ArrayList<String>(); val ids = ArrayList<String?>()
        val acts = composerActionsFor(b, Tools { "20261119-212407" }, false, backgroundScope, send = { t, _, _ -> sent += t; ids += kotlin.coroutines.coroutineContext[TakeContext]?.id })
        acts.onSend(b.draft); runCurrent()
        assertEquals("the caption goes now, nothing waits for the pass", listOf(caption), sent)
        assertEquals(listOf<String?>("20261119-212407"), ids)
        assertFalse(b.finishing); assertEquals("", b.draft); assertNull(b.failed)
    }

    @Test fun aSendThatFailsRetriesWithTheSameTake() = runTest {
        val b = box(caption)
        var asked = 0
        val ids = ArrayList<String?>(); var fail = true
        val acts = composerActionsFor(b, Tools { asked++; "t1" }, false, backgroundScope, send = { _, _, _ -> ids += kotlin.coroutines.coroutineContext[TakeContext]?.id; if (fail) throw java.io.IOException("Not connected") })
        acts.onSend(b.draft); runCurrent()
        assertNotNull(b.retry); assertEquals(caption, b.draft)
        fail = false
        b.retry!!.invoke(); runCurrent()
        assertEquals("the take is handed off once, the retry reuses it", 1, asked)
        assertEquals(listOf<String?>("t1", "t1"), ids)
        assertEquals("", b.draft)
    }

    @Test fun wordsThatAreHisOwnGoAsTheyAreWithNoTake() = runTest {
        val b = box("typed by hand")
        val ids = ArrayList<String?>(); val sent = ArrayList<String>()
        val acts = composerActionsFor(b, Tools { null }, false, backgroundScope, send = { t, _, _ -> sent += t; ids += kotlin.coroutines.coroutineContext[TakeContext]?.id })
        acts.onSend(b.draft); runCurrent()
        assertEquals(listOf("typed by hand"), sent); assertEquals(listOf<String?>(null), ids)
    }

    @Test fun aSuggestionChipNeverGetsATake() = runTest {
        val b = box(caption)
        var asked = 0
        val sent = ArrayList<String>()
        val acts = composerActionsFor(b, Tools { asked++; "t1" }, false, backgroundScope, send = { t, _, _ -> sent += t })
        acts.onSend("What can you do?"); runCurrent()
        assertEquals(listOf("What can you do?"), sent); assertEquals(0, asked)
        assertTrue(b.draft.isNotEmpty())
    }
}
