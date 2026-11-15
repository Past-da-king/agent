package com.past9.phoneaos.wearproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private var n = 0
    private fun host(valid: Long = 600_000, wrong: Int = 5) = PairHost({ ByteArray(16) { (n++ * 7 + 3).toByte() } }, valid, wrong)
    private val t0 = 1_000L

    @Test fun theOfferCarriesTheNodeAndASecretAndTheCodeIsItsFirstEightCharacters() {
        val o = host().newOffer("watch-1", t0)!!
        assertTrue(o.qr().startsWith("agentwatch://pair?n=watch-1&s="))
        assertEquals(o.secret.take(8), o.code.replace("-", ""))
        assertEquals(ScannedOffer("watch-1", o.secret), PairCode.parseQr(o.qr()))
        assertNull(PairCode.parseQr("https://example.com")); assertNull(PairCode.parseQr("agentwatch://pair?n=x&s=short"))
    }

    @Test fun eachOfferIsDifferent() {
        val h = host(); assertNotEquals(h.newOffer("w", t0)!!.secret, h.newOffer("w", t0)!!.secret)
    }

    @Test fun theQrSecretPairsAndBindsThatPhone() {
        val h = host(); val o = h.newOffer("w", t0)!!
        val r = h.request("phone-A", PairProof(secret = o.secret), "Pixel", "Watch", t0 + 1)
        assertEquals(PairReply.Accepted("Watch"), r)
        assertEquals("phone-A", h.phone); assertTrue(h.paired)
    }

    @Test fun theTypedCodePairsWhateverTheCaseDashesAndLookAlikeLetters() {
        val h = host(); val o = h.newOffer("w", t0)!!
        val sloppy = o.code.lowercase().replace('0', 'o').replace('1', 'l').replace("-", " ")
        assertEquals(PairReply.Accepted("W"), h.request("phone-A", PairProof(code = sloppy), "P", "W", t0 + 1))
    }

    @Test fun aWrongCodeIsRefusedAndDoesNotPair() {
        val h = host(); h.newOffer("w", t0)
        assertEquals(PairReply.Refused(PairReason.WRONG), h.request("phone-A", PairProof(code = "ZZZZ-ZZZZ"), "P", "W", t0 + 1))
        assertEquals(PairReply.Refused(PairReason.WRONG), h.request("phone-A", PairProof(secret = "x".repeat(26)), "P", "W", t0 + 2))
        assertFalse(h.paired)
    }

    @Test fun anExpiredOfferIsRefusedEvenWithTheRightSecretAndCannotBeRevived() {
        val h = host(valid = 1_000); val o = h.newOffer("w", t0)!!
        assertEquals(PairReply.Refused(PairReason.EXPIRED), h.request("p", PairProof(secret = o.secret), "P", "W", t0 + 1_000))
        assertEquals(PairReply.Refused(PairReason.NO_OFFER), h.request("p", PairProof(secret = o.secret), "P", "W", t0 + 1_001))
        assertNull(h.current(t0 + 2_000)); assertFalse(h.paired)
        assertNotNull("a new one can be made", h.newOffer("w", t0 + 3_000))
    }

    @Test fun noOfferAtAllIsRefused() {
        assertEquals(PairReply.Refused(PairReason.NO_OFFER), host().request("p", PairProof(code = "ABCD-EFGH"), "P", "W", t0))
    }

    @Test fun afterTooManyWrongTriesEvenTheRightCodeIsRefusedUntilANewOffer() {
        val h = host(wrong = 3); val o = h.newOffer("w", t0)!!
        repeat(3) { h.request("p", PairProof(code = "ZZZZ-ZZZZ"), "P", "W", t0) }
        assertEquals(PairReply.Refused(PairReason.LOCKED), h.request("p", PairProof(secret = o.secret), "P", "W", t0))
        val fresh = h.newOffer("w", t0)!!
        assertTrue(h.request("p", PairProof(secret = fresh.secret), "P", "W", t0) is PairReply.Accepted)
    }

    @Test fun theSecretWorksOnceAndASecondPhoneIsRefusedEvenWithTheRightCode() {
        val h = host(); val o = h.newOffer("w", t0)!!
        assertTrue(h.request("phone-A", PairProof(secret = o.secret), "A", "W", t0) is PairReply.Accepted)
        assertEquals(PairReply.Refused(PairReason.PAIRED_OTHER), h.request("phone-B", PairProof(secret = o.secret), "B", "W", t0 + 1))
        assertEquals(PairReply.Refused(PairReason.PAIRED_OTHER), h.request("phone-B", PairProof(code = o.code), "B", "W", t0 + 1))
        assertEquals("still phone A", "phone-A", h.phone)
        assertNull("no new offer while paired", h.newOffer("w", t0 + 2))
    }

    @Test fun theSamePhoneAskingAgainIsFineAndChangesNothing() {
        val h = host(); val o = h.newOffer("w", t0)!!
        h.request("phone-A", PairProof(secret = o.secret), "A", "W", t0)
        assertTrue(h.request("phone-A", PairProof(code = "nonsense"), "A", "W", t0 + 5) is PairReply.Accepted)
    }

    @Test fun unpairingFromTheWatchOrFromThePairedPhoneFreesIt_andAnotherPhoneCannotUnpair() {
        val h = host(); val o = h.newOffer("w", t0)!!
        h.request("phone-A", PairProof(secret = o.secret), "A", "W", t0)
        assertFalse("a stranger cannot unpair it", h.unpair("phone-B")); assertTrue(h.paired)
        assertTrue(h.unpair("phone-A")); assertFalse(h.paired)
        val again = h.newOffer("w", t0 + 10)!!
        assertTrue(h.request("phone-B", PairProof(secret = again.secret), "B", "W", t0 + 11) is PairReply.Accepted)
        assertTrue("from the watch itself", h.unpair(null)); assertFalse(h.paired)
        assertFalse("nothing to unpair", h.unpair(null))
    }

    @Test fun aWatchThatWasPairedKeepsItsPhoneAfterARestart() {
        val h = PairHost({ ByteArray(16) }, boundPhone = "phone-A", boundName = "A")
        assertTrue(h.paired); assertEquals(PairReply.Refused(PairReason.PAIRED_OTHER), h.request("phone-B", PairProof(code = "X"), "B", "W", 0))
    }

    @Test fun thePhoneBindsOnlyOnAcceptedAndServesOnlyThatWatch() {
        val b = PhoneBinding()
        assertFalse(b.onResult("w1", PairReply.Refused(PairReason.WRONG))); assertNull(b.watch)
        assertTrue(b.onResult("w1", PairReply.Accepted("Watch 1")))
        assertTrue(b.serves("w1")); assertFalse(b.serves("w2"))
        assertFalse(b.unpair("w2")); assertTrue(b.serves("w1"))
        assertTrue(b.unpair("w1")); assertFalse(b.serves("w1"))
    }

    @Test fun twoWatchesNearOnePhone_onlyTheOneItPairedWithIsServed() {
        val b = PhoneBinding(); b.onResult("w1", PairReply.Accepted("A"))
        assertFalse(b.serves("w2"))
    }

    @Test fun pairingMessagesRoundTripAndGarbageIsRefused() {
        assertEquals(PairProof(code = "ABCD-EFGH") to "Pixel", PairWire.parseRequest(PairWire.request(PairProof(code = "ABCD-EFGH"), "Pixel")))
        assertNull(PairWire.parseRequest("{}".toByteArray())); assertNull(PairWire.parseRequest(null))
        assertEquals(PairReply.Accepted("W"), PairWire.parseResult(PairWire.result(PairReply.Accepted("W"))))
        assertEquals(PairReply.Refused(PairReason.EXPIRED), PairWire.parseResult(PairWire.result(PairReply.Refused(PairReason.EXPIRED))))
        assertTrue(PairReason.text(PairReason.PAIRED_OTHER).contains("another phone"))
    }
}

class CustomisationTest {
    private val c = Customisation("Hina", "scout", "iris", "dark", "en-ZA", "gemini", "Kore")

    @Test fun itRoundTripsAndAnUnknownThemeFallsBackToTheSystemOne() {
        assertEquals(c.copy(rev = 5), CustomisationWire.parse(CustomisationWire.encode(c.copy(rev = 5))))
        assertEquals(Customisation.THEME_SYSTEM, CustomisationWire.parse("""{"v":1,"theme":"neon"}""".toByteArray())?.theme)
        assertNull(CustomisationWire.parse("""{"agent":"x"}""".toByteArray())); assertNull(CustomisationWire.parse(null))
    }

    @Test fun everyMascotThePhoneHasSurvivesTheTripAndAnUnknownOneIsDropped() {
        assertEquals(listOf("scout", "ghost", "clover", "bloom", "puff", "gem", "pebble", "heart", "bun"), Mascots.IDS)
        for (id in Mascots.IDS) assertEquals(id, CustomisationWire.parse(CustomisationWire.encode(c.copy(mascot = id, rev = 1)))?.mascot)
        assertEquals("", CustomisationWire.parse(CustomisationWire.encode(c.copy(mascot = "dragon", rev = 1)))?.mascot)
    }

    @Test fun nothingIsPublishedBeforeAWatchIsPaired_andWhatIsPublishedIsOnlyWhatChanged() {
        val p = CustomisationPublisher()
        assertNull(p.next(c, bound = false, at = 10))
        val first = p.next(c, true, 20)!!
        assertEquals(20, first.rev)
        assertNull("same content again says nothing", p.next(c, true, 30))
        val renamed = p.next(c.copy(agentName = "Nova"), true, 40)!!
        assertEquals("Nova", renamed.agentName); assertTrue(renamed.rev > first.rev)
    }

    @Test fun theRevisionAlwaysGoesUpEvenIfTheClockDoesNot() {
        val p = CustomisationPublisher()
        val a = p.next(c, true, 100)!!; val b = p.next(c.copy(accent = "red"), true, 50)!!
        assertTrue(b.rev > a.rev)
    }

    @Test fun aWatchThatJustPairedGetsTheItemAgain() {
        val p = CustomisationPublisher(); p.next(c, true, 1); p.forget()
        assertNotNull(p.next(c, true, 2))
    }

    @Test fun theWatchKeepsTheNewestAndIgnoresOldOnesAndStrangers() {
        val s = CustomisationStore()
        assertTrue(s.apply(c.copy(rev = 10), true)); assertEquals("Hina", s.current.agentName)
        assertFalse("older", s.apply(c.copy(agentName = "Old", rev = 9), true))
        assertFalse("same", s.apply(c.copy(agentName = "Same", rev = 10), true))
        assertFalse("not from the paired phone", s.apply(c.copy(agentName = "Evil", rev = 99), false))
        assertEquals("Hina", s.current.agentName)
        assertTrue(s.apply(c.copy(agentName = "Nova", rev = 11), true)); assertEquals("Nova", s.current.agentName)
    }

    @Test fun unpairingForgetsWhatTheOldPhoneHeld() {
        val s = CustomisationStore(); s.apply(c.copy(rev = 10), true); s.clear()
        assertEquals(Customisation(), s.current)
        assertTrue("a new phone starts from zero", s.apply(c.copy(rev = 1), true))
    }
}
