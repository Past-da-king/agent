package com.past9.phoneaos

import com.past9.phoneaos.assist.LiveAssistCore
import com.past9.phoneaos.assist.LivePhase
import com.past9.phoneaos.assist.LiveTruth
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.voice.SpokenResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 17 Nov 2026 call: Live invented outcomes, said a canned Done, and dropped the agent's later replies. */
class LiveTruthTest {
    private fun row(id: Long, kind: String, text: String, meta: JSONObject = JSONObject()) = ChatItem(id = id, kind = kind, text = text, meta = meta.toString())
    private var now = 1_000L
    private fun core() = LiveAssistCore { now }.also { it.connected() }

    // 1. RETURN PATH: the main agent says it started a helper, the helper's result comes minutes later in the same chat.
    @Test fun aLaterHelperResultRelayedByTheMainAgentIsSpokenAsWritten() {
        val c = core(); c.handOff("Did we upload the PDF to NSFAS?", 5)
        val first = c.rows(listOf(row(5, "user", "x"), row(6, "agent", "I have started a helper to check the NSFAS upload.")), false)
        assertEquals(1, first.size); assertTrue(first[0].contains("started a helper"))
        // Minutes later: the helper works (activity rows, agent working), then the main agent relays its result.
        assertTrue(c.rows(listOf(row(6, "agent", "I have started a helper to check the NSFAS upload."), row(9, "helper", "x")), true).isEmpty())
        val later = c.rows(listOf(row(6, "agent", "I have started a helper to check the NSFAS upload."), row(9, "helper", "x"), row(12, "agent", "Done. Uploaded, status Verification.")), false)
        assertEquals(1, later.size)
        assertTrue(later[0].startsWith(SpokenResult.HEAD)); assertTrue(later[0].contains("Uploaded, status Verification."))
        // and once only
        assertTrue(c.rows(listOf(row(12, "agent", "Done. Uploaded, status Verification.")), false).isEmpty())
    }

    // 4a. A second request while the first is out: both replies are read, not just the first.
    @Test fun aSecondHandOffWhileTheFirstIsOutGetsItsOwnReplyToo() {
        val c = core(); c.handOff("first", 5); c.handOff("upload the form", 5)
        val a = c.rows(listOf(row(6, "agent", "First is done.")), false)
        val b = c.rows(listOf(row(6, "agent", "First is done."), row(8, "agent", "The form is uploaded.")), false)
        assertEquals(1, a.size); assertEquals(1, b.size); assertTrue(b[0].contains("The form is uploaded."))
    }

    // 2 and 4b. The sheet's canned "Done." / "On it. The rest is in the chat." (agent stopped without a word) is NOT an agent result.
    @Test fun theSheetsCannedDoneIsNeverReadAsTheAgentsResult() {
        val c = core(); c.handOff("upload the form", 5)
        c.rows(listOf(row(5, "user", "x"), row(6, "activity", "Uploading")), true)
        val told = c.rows(listOf(row(5, "user", "x"), row(6, "activity", "Uploading")), false)
        assertTrue(told.joinToString(), told.none { it.contains("Done.") || it.contains("The rest is in the chat") || it.startsWith(SpokenResult.HEAD) })
        assertTrue("the job is still out", c.handOffOpen)
        assertEquals(LivePhase.WORKING, c.current.phase)
    }
    @Test fun aSilentFinishWithNoActivityIsNotADoneEither() {
        val c = core(); c.handOff("x", 5)
        c.rows(listOf(row(5, "user", "x")), true)
        val told = c.rows(listOf(row(5, "user", "x")), false)
        assertTrue(told.joinToString(), told.none { it.contains("Done.") || it.startsWith(SpokenResult.HEAD) })
    }

    // 3. Live must not claim an outcome of its own while no agent reply backs it.
    @Test fun liveClaimingUploadedBeforeAnyReplyIsCaught() {
        val c = core(); c.handOff("upload the PDF", 5)
        assertTrue(c.outstanding)
        assertTrue(c.claimWithoutResult("The PDF has been successfully uploaded to the NSF system."))
        assertTrue(c.claimWithoutResult("On it. Done."))
        assertFalse(c.claimWithoutResult("On it."))
        assertFalse(c.claimWithoutResult("It is still working, I will read it out when it arrives."))
    }
    @Test fun aClaimIsFineOnceTheAgentHasReplied() {
        val c = core(); c.handOff("upload the PDF", 5)
        c.rows(listOf(row(6, "agent", "Uploaded, status Verification.")), false)
        assertFalse(c.claimWithoutResult("Uploaded, status Verification."))
    }
    @Test fun theToolAnswerTellsTheModelTheJobIsNotDone() {
        val n = LiveTruth.ack(true, "")
        assertTrue(n, n.contains("NOT done")); assertTrue(n, n.contains("holding line"))
        assertTrue(LiveTruth.ack(false, "the payroll helper").contains("NOT done"))
    }

    // 4c. No silent gap.
    @Test fun aLateReplyIsAnnouncedOnceAndNeverMute() {
        val c = core(); c.handOff("upload the PDF", 5)
        now += 5_000; assertFalse(c.lateDue())
        now += LiveTruth.LATE_MS; assertTrue(c.lateDue())
        assertFalse("only once", c.lateDue())
        assertTrue(LiveTruth.lateLine().contains("no result"))
    }
    @Test fun theSessionDoesNotEndWhileAJobIsOut() {
        val c = core(); c.handOff("x", 5); now += 5 * 60_000L
        assertFalse(c.idle())
        now += 6 * 60_000L
        assertTrue("a job nobody answers for ten minutes no longer holds the line", c.idle())
    }

    // Ended call: what the agent said while the call was over is spoken at the start of the next one.
    @Test fun whatArrivedAfterTheCallEndedIsSpokenAtTheNextCall() {
        val rows = listOf(row(6, "agent", "Started a helper."), row(12, "agent", "Done. Uploaded, status Verification."), row(13, "activity", "x"))
        val m = LiveTruth.missed(rows, after = 6, told = setOf(6))
        assertEquals(listOf(12L), m.map { it.id })
    }

    // Every turn goes to the main chat as it finalises; a re-send only updates.
    @Test fun turnsAreHandedOnAsTheyFinaliseAndOnlyWhenTheyChange() {
        val c = core()
        c.caption("in", "Did we upload "); c.caption("in", "Thuli's PDF?")
        assertTrue("the line still growing is not final", c.drainFinal(false).isEmpty())
        c.caption("out", "On it.")
        val a = c.drainFinal(false)
        assertEquals(listOf(0), a.map { it.first }); assertEquals("Did we upload Thuli's PDF?", a[0].second.text)
        assertTrue(c.drainFinal(false).isEmpty())
        val b = c.drainFinal(true)
        assertEquals(listOf(1), b.map { it.first })
        c.caption("out", " One moment.")
        assertEquals(listOf(1), c.drainFinal(true).map { it.first })
    }
    @Test fun theNextCallOpensWithThePreviousCallsWordsNewestFirstAndBounded() {
        val lines = (1..100).map { com.past9.phoneaos.assist.LiveLine(if (it % 2 == 0) "out" else "in", "line number $it " + "x".repeat(300), it.toLong()) }
        val p = LiveTruth.previousCall(lines)
        assertTrue(p.indexOf("line number 100") in 1 until p.indexOf("line number 99"))
        assertTrue(p.length < LiveTruth.PREVIOUS_CHARS + 600); assertFalse(p.contains("line number 1 "))
        assertEquals("", LiveTruth.previousCall(emptyList()))
        val sys = LiveAssistCore.systemPrompt("Hina", "Ayanda", true, null, LiveTruth.previousCall(lines.take(2)))
        assertTrue(sys.contains("previous live call")); assertTrue(sys.contains("line number 2"))
    }
    @Test fun theHandOffCarriesHisExactWordsAtTopLevelAndInTheNote() {
        val m = LiveAssistCore.handoffMeta("Did we upload the toolseller's PDF to NSFs?", "Yo, my guy, did we upload Thuli's PDF to NSFAS?", false, "live-abc")
        assertEquals("Yo, my guy, did we upload Thuli's PDF to NSFAS?", m.getString("spoken")); assertEquals("live-abc", m.getString("session")); assertEquals("live_voice", m.getString("origin"))
        val note = LiveAssistCore.liveNote("Yo, my guy, did we upload Thuli's PDF to NSFAS?")
        assertTrue(note.contains("Thuli's PDF to NSFAS"))
        // His words survive even when no finished line of his exists yet (the transcript was still growing).
        val c = core(); c.caption("in", "did we upload Thuli's PDF")
        assertEquals("did we upload Thuli's PDF", c.spokenNow())
        assertTrue(LiveAssistCore.systemPrompt("Hina", "A", true).contains("never turn a word you are unsure of into a name"))
    }
}
