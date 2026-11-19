package com.past9.phoneaos

import com.past9.phoneaos.voice.Take
import com.past9.phoneaos.voice.TakeEnv
import com.past9.phoneaos.voice.TakeFlow
import com.past9.phoneaos.voice.TakeMark
import com.past9.phoneaos.voice.TakeQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Instant send, the durable half (19 Nov 2026): the take is on disk before anything is sent, and nothing runs or replaces twice. */
@OptIn(ExperimentalCoroutinesApi::class)
class TakesTest {
    private val dir: File = Files.createTempDirectory("takes").toFile()
    private val pcm = ByteArray(32_000 * 10) { 1 }

    private class Env(var linkedNow: Boolean = true, var key: Boolean = false) : TakeEnv {
        val handed = ArrayList<String>(); val written = ArrayList<Int>(); val replaced = ArrayList<Pair<String, String>>(); val retried = ArrayList<String>()
        var linkUp = true; var failures = 0; var text = "Clean text of the whole take."; var hang = false
        override fun linked() = linkedNow
        override suspend fun handOver(take: Take, pcm: ByteArray): Boolean { if (!linkUp) return false; if (failures > 0) { failures--; throw java.io.IOException("refused") }; handed += take.id; return true }
        override suspend fun writeHere(pcm: ByteArray): String? { if (!key) return null; if (hang) kotlinx.coroutines.awaitCancellation(); if (failures > 0) { failures--; throw java.io.IOException("Gemini 503") }; written += pcm.size; return text }
        override suspend fun replaceHere(take: Take, text: String) { replaced += take.id to text }
        override suspend fun retryRemote(id: String): Boolean { retried += id; return true }
    }

    private fun TestScope.flow(env: Env) = TakeFlow(TakeQueue(dir), backgroundScope, env) { testScheduler.currentTime }

    @Test fun aTakeIsOnDiskBeforeAnythingIsSentAndTwiceIsOnce() = runTest {
        val f = flow(Env())
        assertTrue(f.enqueue("t1", null, "caption words", pcm))
        assertFalse("the same recording id never queues twice", f.enqueue("t1", null, "caption words", pcm))
        val q = TakeQueue(dir)
        assertEquals("queued", q.get("t1")?.state); assertEquals(pcm.size, q.pcm("t1")?.size)
    }

    @Test fun linkedTheWholeAudioIsHandedToTheComputerOnceNotPieces() = runTest {
        val env = Env(); val f = flow(env)
        f.enqueue("t1", "chat-9", "caption", pcm)
        f.submit("t1"); f.submit("t1"); runCurrent()
        assertEquals(listOf("t1"), env.handed)
        assertEquals("handed", TakeQueue(dir).get("t1")?.state)
        f.resume(); runCurrent()
        assertEquals("a resume never hands it over again", listOf("t1"), env.handed)
    }

    @Test fun aForceStopRightAfterSendLosesNothingAndTheNextStartCarriesOn() = runTest {
        val env = Env().apply { linkUp = false }
        val first = flow(env)
        first.enqueue("t1", null, "caption", pcm); first.submit("t1"); runCurrent()
        assertEquals("no link yet: still queued", "queued", TakeQueue(dir).get("t1")?.state)
        // The process dies here. A new one starts on the same folder, with the link back.
        val second = flow(Env().also { env.linkUp = true; it.linkUp = true })
        second.resume(); runCurrent()
        assertEquals("handed", TakeQueue(dir).get("t1")?.state)
    }

    @Test fun withNoLinkAndItsOwnKeyThePhoneRunsThePassEditsItsMessageOnceAndFreesTheAgent() = runTest {
        val env = Env(linkedNow = false, key = true); val f = flow(env)
        f.enqueue("t1", null, "constration head", pcm)
        var got: com.past9.phoneaos.voice.TakeText? = null
        backgroundScope.launch2 { got = f.settled("t1") }
        f.submit("t1"); runCurrent()
        assertEquals(listOf(pcm.size), env.written)
        assertEquals(listOf("t1" to "Clean text of the whole take."), env.replaced)
        assertEquals("Clean text of the whole take.", got?.text); assertTrue(got!!.clean)
        f.resume(); f.submit("t1"); runCurrent()
        assertEquals("never replaced twice", 1, env.replaced.size); assertEquals(1, env.written.size)
    }

    @Test fun aPassThatNeverFinishesFreesTheAgentWithTheCaptionAndTheNote() = runTest {
        val env = Env(linkedNow = false, key = true).apply { hang = true }; val f = flow(env)
        f.enqueue("t1", null, "the caption", pcm)
        var got: com.past9.phoneaos.voice.TakeText? = null
        backgroundScope.launch2 { got = f.settled("t1") }
        f.submit("t1"); runCurrent()
        advanceTimeBy(TakeFlow.holdCapMs(10) + 1); runCurrent()
        assertEquals("the caption", got?.text); assertFalse(got!!.clean)
        assertEquals("dictated; clean transcript failed, this is the live caption", got!!.note)
    }

    @Test fun aFailedPassIsRetriedThenFailsWithRetryOffered() = runTest {
        val env = Env(linkedNow = false, key = true).apply { failures = 10 }; val f = flow(env)
        f.enqueue("t1", null, "caption", pcm); f.submit("t1")
        advanceTimeBy(30_000); runCurrent()
        assertEquals("failed", TakeQueue(dir).get("t1")?.state)
        assertEquals(TakeMark.Kind.FAILED, TakeMark.of("""{"dictation":{"id":"t1","state":"refining"}}""", "caption", f.states.value, 0).kind)
        env.failures = 0
        assertTrue(f.retry("t1")); advanceTimeBy(30_000); runCurrent()
        assertEquals("done", TakeQueue(dir).get("t1")?.state)
        assertEquals(1, env.replaced.size)
    }

    @Test fun retryOfATakeTheComputerHoldsAsksTheComputer() = runTest {
        val env = Env(); val f = flow(env)
        f.enqueue("t1", null, "caption", pcm); f.submit("t1"); runCurrent()
        assertTrue(f.retry("t1")); assertEquals(listOf("t1"), env.retried)
    }

    @Test fun theMarkerSaysRefiningThenGoesWhenTheWordsChange() {
        val take = Take("t1", null, "caption", "handed", 10, acceptedAt = 1_000)
        val meta = """{"dictation":{"id":"t1","state":"refining"}}"""
        assertEquals(TakeMark.Kind.REFINING, TakeMark.of(meta, "caption", mapOf("t1" to take), 2_000).kind)
        assertEquals("the message was replaced by the clean text", TakeMark.Kind.NONE, TakeMark.of(meta, "Clean text.", mapOf("t1" to take), 2_000).kind)
        assertEquals("a computer that never answers: no marker forever", TakeMark.Kind.NONE, TakeMark.of(meta, "caption", mapOf("t1" to take), 1_000 + TakeFlow.holdCapMs(10) + 16_000).kind)
        assertEquals(TakeMark.Kind.NONE, TakeMark.of("""{"dictation":{"id":"t1","state":"done"}}""", "caption", mapOf("t1" to take), 2_000).kind)
        assertEquals("a message with no dictation is left alone", TakeMark.Kind.NONE, TakeMark.of("{}", "hello", emptyMap(), 0).kind)
        assertEquals("the computer says it failed", TakeMark.Kind.FAILED, TakeMark.of("""{"dictation":{"id":"t1","state":"failed"}}""", "caption", mapOf("t1" to take), 2_000).kind)
    }

    private fun kotlinx.coroutines.CoroutineScope.launch2(block: suspend () -> Unit) { launch { block() } }
}
