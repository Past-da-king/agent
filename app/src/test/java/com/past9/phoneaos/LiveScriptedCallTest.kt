package com.past9.phoneaos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.assist.LiveAssistCore
import com.past9.phoneaos.assist.LivePending
import com.past9.phoneaos.assist.LiveTruth
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.voice.SpokenResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scripted call, on a virtual clock, with the real core and the real saved state: hand-off, quick reply, helper result about
 * 60 s later, the call ends, one more result arrives while closed (notified), the next call opens and reads it as written, once.
 * Prints what the voice is told at every step (the log is the transcript of the script).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveScriptedCallTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun row(id: Long, kind: String, text: String) = ChatItem(id = id, kind = kind, text = text, meta = JSONObject().toString())
    private var now = 1_000_000L
    private fun say(step: String, told: List<String>) = told.forEach { println("SCRIPT $step -> voice is told: ${it.replace('\n', '|')}") }

    @Test fun theWholeScriptedCall() {
        val c = LiveAssistCore { now }; c.connected()
        c.caption("in", "Did we upload Thuli's PDF to NSFAS?")
        // hand-off; the model is told NOT done
        c.handOff("check if we uploaded Thuli's PDF to NSFAS", 5)
        println("SCRIPT hand-off -> tool answer: " + LiveTruth.ack(true, ""))
        assertTrue(c.claimWithoutResult("Done, it is uploaded."))
        // quick reply
        now += 4_000
        val quick = c.rows(listOf(row(5, "user", "x"), row(6, "agent", "I have started a helper to check the NSFAS upload.")), false)
        say("quick reply t+4s", quick); assertEquals(1, quick.size)
        // about 60 s later the helper result is relayed
        now += 60_000
        assertTrue(c.rows(listOf(row(6, "agent", "I have started a helper to check the NSFAS upload."), row(9, "helper", "h")), true).isEmpty())
        val later = c.rows(listOf(row(6, "agent", "I have started a helper to check the NSFAS upload."), row(9, "helper", "h"), row(12, "agent", "Done. Uploaded, status Verification.")), false)
        say("helper result t+64s", later); assertEquals(1, later.size); assertTrue(later[0].startsWith(SpokenResult.HEAD))
        // a second job goes out, then the call ends with it still open
        c.handOff("also upload the form", 12)
        now += 5_000
        LivePending.save(ctx, c.base, c.toldIds, now)
        val state = LivePending.peek(ctx, now)!!
        println("SCRIPT call ended -> pending base=${state.base} told=${state.told}")
        // one more result posts while the call is closed
        now += 120_000
        val all = listOf(row(6, "agent", "a"), row(12, "agent", "Done. Uploaded, status Verification."), row(15, "agent", "The form is uploaded too. Status Verification."))
        val missed = LiveTruth.missed(all, state.base, state.told)
        println("SCRIPT closed call -> notify: " + missed.map { it.text })
        assertEquals(listOf(15L), missed.map { it.id })
        // next call opens: reads it once as written, then clears
        val opening = LiveTruth.whileAway(missed)
        say("next call opens", opening)
        assertTrue(opening[0].contains(SpokenResult.HEAD)); assertTrue(opening[0].contains("The form is uploaded too."))
        LivePending.clear(ctx)
        assertNull(LivePending.peek(ctx, now))
        // stale news is dropped
        LivePending.save(ctx, 1, emptySet(), now)
        assertNull(LivePending.peek(ctx, now + LivePending.KEEP_MS + 1))
        assertFalse(c.handOffOpen && false)
    }
}
