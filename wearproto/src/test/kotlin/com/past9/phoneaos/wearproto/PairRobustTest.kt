package com.past9.phoneaos.wearproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pairing made robust and observable: the offer that survives, the phone's note, the retry schedule, the battery rules, the log. */
class PairRobustTest {
    private class MemStore : PairOfferStore {
        var held: StoredOffer? = null
        override fun load() = held
        override fun save(offer: PairOffer, wrong: Int) { held = StoredOffer(offer, wrong) }
        override fun clear() { held = null }
    }
    private var n = 0
    private fun host(store: PairOfferStore, bound: String? = null) = PairHost({ ByteArray(16) { (n++ and 0xFF).toByte() } }, store = store, boundPhone = bound)

    // ---- the offer survives the process ----------------------------------------------------------------------------------

    @Test fun theOfferOnShowIsKeptAndAProcessThatComesBackHoldsTheSameCode() {
        val store = MemStore(); val t0 = 1_000_000L
        val first = host(store).newOffer("watch-1", t0)!!
        val again = host(store)               // the process died; a new host is built from what was kept
        assertEquals(first, again.current(t0 + 5 * 60_000L))
        assertEquals("a service started cold answers with it", PairReply.Accepted("W"), again.request("phone-A", PairProof(secret = first.secret), "Pixel", "W", t0 + 60_000L))
    }

    @Test fun backingOutAndComingBackWithinTenMinutesKeepsTheSameCodeAndLaterMakesANewOne() {
        val store = MemStore(); val h = host(store); val t0 = 5_000L
        val o = h.offerOrNew("watch-1", t0)!!
        assertEquals(o, h.offerOrNew("watch-1", t0 + 9 * 60_000L))
        val fresh = h.offerOrNew("watch-1", t0 + 10 * 60_000L)!!
        assertNotEquals(o.secret, fresh.secret)
    }

    @Test fun anExpiredOfferIsNotRestored() {
        val store = MemStore(); host(store).newOffer("watch-1", 0L)
        store.held = store.held!!.copy(offer = store.held!!.offer.copy(expiresAt = 10L))
        assertNull(PairHost({ ByteArray(16) }, store = store).current(11L))
    }

    @Test fun wrongTriesSurviveToo() {
        val store = MemStore(); val h = host(store); h.newOffer("watch-1", 0L)
        repeat(3) { h.request("p", PairProof(code = "ZZZZ-ZZZZ"), "x", "W", 1L) }
        assertEquals(3, store.held!!.wrong)
        val back = host(store)
        repeat(2) { back.request("p", PairProof(code = "ZZZZ-ZZZZ"), "x", "W", 2L) }
        assertEquals(PairReply.Refused(PairReason.LOCKED), back.request("p", PairProof(code = "ZZZZ-ZZZZ"), "x", "W", 3L))
    }

    @Test fun pairingUnpairingAndExpiryClearWhatWasKept() {
        val store = MemStore(); val h = host(store); val o = h.newOffer("watch-1", 0L)!!
        h.request("phone-A", PairProof(secret = o.secret), "P", "W", 1L)
        assertNull("paired: the offer is gone from disk", store.held)
        h.unpair(null); assertNull(store.held)
        val o2 = h.newOffer("watch-1", 2L)!!; assertNotNull(store.held)
        h.request("p", PairProof(secret = o2.secret), "P", "W", 3L + 10 * 60_000L)
        assertNull("expired: cleared", store.held)
    }

    @Test fun aWatchThatIsAlreadyPairedDoesNotRestoreAnOffer() {
        val store = MemStore(); host(store).newOffer("watch-1", 0L)
        assertNull(host(store, bound = "phone-A").current(1L))
    }

    // ---- the phone's note: phone paired, watch missed the reply ---------------------------------------------------------

    @Test fun theNoteRoundTripsAndNothingElseParses() {
        val note = PairedNote("watch-1", "Galaxy A35", 123L)
        assertEquals(note, PairedWire.parse(PairedWire.encode(note)))
        assertNull(PairedWire.parse(null)); assertNull(PairedWire.parse("{}".toByteArray())); assertNull(PairedWire.parse("nope".toByteArray()))
    }

    @Test fun aWatchThatMissedTheReplyBindsToThePhoneFromItsNote() {
        val store = MemStore(); val h = host(store); h.newOffer("watch-1", 0L)
        val note = PairedNote("watch-1", "Galaxy A35", 100L)
        assertTrue(PairedRule.accepts(note, "watch-1", unpairedAt = 0L, alreadyPaired = h.paired))
        assertTrue(h.adopt("phone-A", note.phoneName))
        assertTrue(h.paired); assertEquals("phone-A", h.phone); assertEquals("Galaxy A35", h.phoneName)
        assertNull("the code is no longer needed", store.held)
    }

    @Test fun aNoteIsBelievedOnlyForThisWatchAndOnlyIfNewerThanTheLastUnpairing() {
        val note = PairedNote("watch-1", "P", 100L)
        assertFalse("another watch", PairedRule.accepts(note, "watch-2", 0L, false))
        assertFalse("he unpaired after the note was made", PairedRule.accepts(note, "watch-1", 200L, false))
        assertFalse("already paired", PairedRule.accepts(note, "watch-1", 0L, true))
        assertFalse("no note", PairedRule.accepts(null, "watch-1", 0L, false))
        assertFalse("unknown own node", PairedRule.accepts(note, "", 0L, false))
    }

    @Test fun adoptingIsRefusedWhenAlreadyBoundOrWithoutAPhone() {
        val h = host(MemStore(), bound = "phone-A")
        assertFalse(h.adopt("phone-B", "x")); assertEquals("phone-A", h.phone)
        assertFalse(host(MemStore()).adopt("", "x"))
    }

    // ---- the Data Layer send is retried over the window ---------------------------------------------------------------------

    private class Clock { var t = 0L; val naps = ArrayList<Long>() }

    private fun run(c: Clock, answeredAfter: Int = Int.MAX_VALUE, sendOk: (Int) -> Boolean): PairRetry.Result {
        var tries = 0
        return runBlocking {
            PairRetry.run(send = { a -> tries = a; sendOk(a) }, answered = { tries >= answeredAfter }, now = { c.t }, sleep = { c.t += it; c.naps += it })
        }
    }

    @Test fun aSendThatFailsIsTriedAgainSoonAndStopsWhenItIsTaken() {
        val c = Clock(); val r = run(c) { it >= 3 }
        assertEquals(PairRetry.Result(attempts = 5, accepted = 3, failed = 2), r.copy(attempts = r.attempts))
        assertEquals("two quick retries, then the long waits", listOf(PairRetry.FAILED_GAP_MS, PairRetry.FAILED_GAP_MS, PairRetry.WAIT_MS, PairRetry.WAIT_MS), c.naps.take(4))
    }

    @Test fun itStopsAsSoonAsTheWatchHasAnswered() {
        val c = Clock(); val r = run(c, answeredAfter = 1) { true }
        assertEquals(1, r.attempts); assertTrue(c.naps.size <= 1)
    }

    @Test fun itNeverUsesMoreThanTheWindowOrTheAttemptCap() {
        val c = Clock(); val r = run(c) { false }
        assertTrue(r.attempts <= PairRetry.MAX_ATTEMPTS)
        assertTrue("slept ${c.t} ms", c.t < PairRetry.WINDOW_MS)
        assertEquals(0, r.accepted)
    }

    @Test fun silenceAfterATakenSendIsRetriedAFewTimesWithinThirtySeconds() {
        val c = Clock(); val r = run(c) { true }
        assertTrue("more than one try over the window: ${r.attempts}", r.attempts in 2..PairRetry.MAX_ATTEMPTS)
        assertTrue(c.t < PairRetry.WINDOW_MS)
    }

    // ---- the battery and power saving rules --------------------------------------------------------------------------------

    @Test fun belowTenPercentThePairingScreenWarnsEvenOnACharger() {
        assertTrue(PairBattery.low(4)); assertTrue(PairBattery.low(9)); assertTrue(PairBattery.low(0))
        assertFalse(PairBattery.low(10)); assertFalse(PairBattery.low(80)); assertFalse("unknown is not low", PairBattery.low(-1))
    }

    @Test fun powerSavingAndAVeryLowBatteryAreWhatCanStopTheAppOpening() {
        assertEquals(LaunchLimit.POWER_SAVE, LaunchHealth.of(80, powerSave = true))
        assertEquals(LaunchLimit.POWER_SAVE, LaunchHealth.of(3, powerSave = true))
        assertEquals(LaunchLimit.VERY_LOW_BATTERY, LaunchHealth.of(4, powerSave = false))
        assertEquals(LaunchLimit.NONE, LaunchHealth.of(5, powerSave = false))
        assertEquals(LaunchLimit.NONE, LaunchHealth.of(-1, powerSave = false))
    }

    // ---- the log -------------------------------------------------------------------------------------------------------------

    @Test fun theLogKeepsTheLastLinesCutsNodeIdsAndNeverNeedsASecret() {
        PairLog.clear(); val seen = ArrayList<String>(); val old = PairLog.sink; PairLog.sink = { seen += it }
        try {
            repeat(250) { PairLog.i("line $it") }
            assertEquals(200, PairLog.recent().size); assertEquals("line 249", PairLog.recent().last()); assertEquals(250, seen.size)
            assertEquals("abcdefgh", PairLog.node("abcdefghijklmnop")); assertEquals("-", PairLog.node(null))
            assertEquals("AgentWatchPair", PairLog.TAG)
        } finally { PairLog.sink = old; PairLog.clear() }
    }
}
