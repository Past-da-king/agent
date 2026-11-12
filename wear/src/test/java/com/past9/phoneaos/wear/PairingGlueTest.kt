package com.past9.phoneaos.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.wearproto.Customisation
import com.past9.phoneaos.wearproto.CustomisationWire
import com.past9.phoneaos.wearproto.PairProof
import com.past9.phoneaos.wearproto.PairPaths
import com.past9.phoneaos.wearproto.PairReply
import com.past9.phoneaos.wearproto.PairWire
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The watch's half of pairing and of the customisation sync, through the real stores (the Data Layer itself is not there to answer). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WatchApp::class)
class PairingGlueTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun fresh() { WatchPairingState.resetForTest(ctx) }

    private fun ask(from: String, proof: PairProof) = runBlocking { WatchPairingState.onRequest(ctx, from, PairWire.request(proof, "Pixel")) }
    private fun offer() = WatchPairingState.host(ctx).newOffer("watch-1", System.currentTimeMillis())!!
    private fun item(c: Customisation) = CustomisationWire.encode(c)
    private fun replies(to: String) = WatchPairingState.sent.filter { it.first == to && it.second == PairPaths.RESULT }.mapNotNull { PairWire.parseResult(it.third) }

    @Test fun aNewWatchIsNotPairedAndTalksToNobody() {
        assertFalse(WatchPairingState.paired.value); assertNull(WatchPairingState.phone(ctx))
        assertNull("no phone to send to", runBlocking { PhoneLink(ctx).phoneNode() })
        assertFalse("nothing is sent", runBlocking { PhoneLink(ctx).send("/agent/ask") })
    }

    @Test fun theRightSecretPairsThatPhoneAndTheBindingSurvivesARestart() {
        val o = offer()
        ask("phone-A", PairProof(secret = o.secret))
        assertTrue(WatchPairingState.paired.value); assertEquals("phone-A", WatchPairingState.phone(ctx)); assertEquals("Pixel", WatchPairingState.phoneName.value)
        assertTrue("the phone is told it worked", replies("phone-A").single() is PairReply.Accepted)
        // The process restarts: the host is rebuilt from what was saved.
        val rebuilt = com.past9.phoneaos.wearproto.PairHost({ ByteArray(16) }, boundPhone = ctx.getSharedPreferences("watch_pairing", Context.MODE_PRIVATE).getString("phone", null))
        assertTrue(rebuilt.paired); assertEquals("phone-A", rebuilt.phone)
    }

    @Test fun aWrongCodeLeavesTheWatchUnpaired() {
        offer(); ask("phone-A", PairProof(code = "ZZZZ-ZZZZ"))
        assertFalse(WatchPairingState.paired.value); assertNull(WatchPairingState.phone(ctx))
        assertEquals(PairReply.Refused("wrong_code"), replies("phone-A").single())
    }

    @Test fun anExpiredCodeDoesNotPair() {
        val h = WatchPairingState.host(ctx)
        val o = h.newOffer("watch-1", System.currentTimeMillis() - 11 * 60_000L)!!
        ask("phone-A", PairProof(secret = o.secret))
        assertFalse(WatchPairingState.paired.value); assertNull(WatchPairingState.currentOffer(ctx))
        assertEquals(PairReply.Refused("expired"), replies("phone-A").single())
    }

    @Test fun aSecondPhoneIsRefusedEvenWithTheCodeAndTheFirstStaysBound() {
        val o = offer()
        ask("phone-A", PairProof(secret = o.secret))
        ask("phone-B", PairProof(secret = o.secret)); ask("phone-B", PairProof(code = o.code))
        assertEquals("phone-A", WatchPairingState.phone(ctx))
        assertEquals(listOf<PairReply>(PairReply.Refused("paired_other"), PairReply.Refused("paired_other")), replies("phone-B"))
    }

    @Test fun onlyThePairedPhoneCanUnpairAndItBringsThePairingScreenBack() {
        val o = offer(); ask("phone-A", PairProof(secret = o.secret))
        WatchPairingState.onUnpair(ctx, "phone-B")
        assertTrue("a stranger cannot", WatchPairingState.paired.value)
        WatchPairingState.onUnpair(ctx, "phone-A")
        assertFalse(WatchPairingState.paired.value); assertNull(WatchPairingState.phone(ctx))
    }

    @Test fun unpairingOnTheWatchForgetsThePhonesPictureAndCustomisations() {
        val o = offer(); ask("phone-A", PairProof(secret = o.secret))
        WatchCustomisationState.onItem(ctx, "phone-A", item(Customisation(agentName = "Nova", rev = 5)))
        WatchStore.update(ctx, com.past9.phoneaos.wearproto.WatchState(agent = "Nova"))
        runBlocking { WatchPairingState.unpair(ctx) }
        assertTrue("the phone is told", WatchPairingState.sent.any { it.first == "phone-A" && it.second == PairPaths.UNPAIR })
        assertFalse(WatchPairingState.paired.value)
        assertEquals(Customisation(), WatchCustomisationState.state.value)
        assertNull(WatchStore.cached(ctx))
    }

    @Test fun customisationsAreTakenOnlyFromThePairedPhoneAndTheNewestWins() {
        val o = offer(); ask("phone-A", PairProof(secret = o.secret))
        assertTrue(WatchCustomisationState.onItem(ctx, "phone-A", item(Customisation("Hina", "scout", "iris", "dark", "en-ZA", "gemini", "Kore", rev = 10))))
        assertEquals("Hina", WatchCustomisationState.state.value.agentName); assertEquals("dark", WatchCustomisationState.state.value.theme)
        assertFalse("a stranger's item", WatchCustomisationState.onItem(ctx, "phone-B", item(Customisation(agentName = "Evil", rev = 99))))
        assertFalse("an old item", WatchCustomisationState.onItem(ctx, "phone-A", item(Customisation(agentName = "Old", rev = 9))))
        assertTrue(WatchCustomisationState.onItem(ctx, "phone-A", item(Customisation(agentName = "Nova", accent = "red", rev = 11))))
        assertEquals("Nova", WatchCustomisationState.state.value.agentName); assertEquals("red", WatchCustomisationState.state.value.accent)
    }

    @Test fun customisationsBeforePairingAreIgnored() {
        assertFalse(WatchCustomisationState.onItem(ctx, "phone-A", item(Customisation(agentName = "Early", rev = 1))))
        assertEquals("", WatchCustomisationState.state.value.agentName)
    }

    @Test fun theCustomisationsAreKeptOnTheWatchAcrossARestart() {
        val o = offer(); ask("phone-A", PairProof(secret = o.secret))
        WatchCustomisationState.onItem(ctx, "phone-A", item(Customisation(agentName = "Nova", rev = 7)))
        val saved = ctx.getSharedPreferences("watch_customisation", Context.MODE_PRIVATE).getString("item", null)
        assertNotNull(saved); assertEquals("Nova", CustomisationWire.parse(saved!!.toByteArray())?.agentName)
    }

    @Test fun thePairingQrDrawsAndEncodesTheOffer() {
        val o = offer()
        val img = com.past9.phoneaos.wear.ui.QrImage.make(o.qr())
        assertNotNull(img); assertEquals(240, img!!.width)
    }
}
