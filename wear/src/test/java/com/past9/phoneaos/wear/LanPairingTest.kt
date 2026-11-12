package com.past9.phoneaos.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.wearproto.PairLan
import com.past9.phoneaos.wearproto.PairProof
import com.past9.phoneaos.wearproto.PairReason
import com.past9.phoneaos.wearproto.PairReply
import com.past9.phoneaos.wearproto.PairWire
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket

/** The watch's second road for pairing: the same checks as the Data Layer road, over a socket on the local network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WatchApp::class)
class LanPairingTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun fresh() { WatchPairingState.resetForTest(ctx) }
    @After fun stop() { WatchLanPair.stop() }

    private fun offer() = WatchPairingState.host(ctx).newOffer("watch-1", System.currentTimeMillis())!!
    private fun lan(proof: PairProof, phoneNode: String = "phone-A") = runBlocking { WatchPairingState.onLanRequest(ctx, PairWire.request(proof, "Pixel", phoneNode)) }

    @Test fun theRightCodeOverTheLanPairsThePhoneAndTheAnswerNamesTheWatchNode() {
        val o = offer()
        val r = lan(PairProof(code = o.code))
        assertTrue(r is PairReply.Accepted); assertEquals("watch-1", (r as PairReply.Accepted).node)
        assertEquals("phone-A", WatchPairingState.phone(ctx)); assertTrue(WatchPairingState.paired.value)
    }

    @Test fun theQrSecretOverTheLanPairsToo() {
        val o = offer()
        assertTrue(lan(PairProof(secret = o.secret)) is PairReply.Accepted)
    }

    @Test fun aWrongCodeIsRefusedAndNothingIsBound() {
        offer()
        assertEquals(PairReply.Refused(PairReason.WRONG), lan(PairProof(code = "AAAA-AAAA")))
        assertFalse(WatchPairingState.paired.value)
    }

    @Test fun aPhoneThatDoesNotSayWhoItIsIsRefusedBecauseTheDataLayerCouldNeverReachItLater() {
        val o = offer()
        assertEquals(PairReply.Refused(PairReason.NO_NODE), lan(PairProof(code = o.code), phoneNode = ""))
        assertFalse(WatchPairingState.paired.value)
    }

    @Test fun tooManyWrongCodesLockTheOfferOnThisRoadAsOnTheOther() {
        val o = offer()
        repeat(5) { lan(PairProof(code = "AAAA-AAAA")) }
        assertEquals(PairReply.Refused(PairReason.LOCKED), lan(PairProof(code = o.code)))
    }

    @Test fun aRealSocketAnswersOneLineWithOneLine() {
        val o = offer()
        WatchLanPair.start(ctx)
        val port = serverPort()
        val reply = Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 8_000
            s.getOutputStream().apply { write(PairLan.line(PairWire.request(PairProof(code = o.code), "Pixel", "phone-A"))); flush() }
            val line = ArrayList<Byte>(); val i = s.getInputStream()
            while (true) { val b = i.read(); if (b < 0 || b == '\n'.code) break; line += b.toByte() }
            PairWire.parseResult(line.toByteArray())
        }
        assertTrue(reply is PairReply.Accepted); assertEquals("phone-A", WatchPairingState.phone(ctx))
    }

    @Test fun aGarbageLineIsRefusedNotCrashed() {
        offer()
        WatchLanPair.start(ctx)
        val port = serverPort()
        val reply = Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 8_000
            s.getOutputStream().apply { write("not json\n".toByteArray()); flush() }
            val line = ArrayList<Byte>(); val i = s.getInputStream()
            while (true) { val b = i.read(); if (b < 0 || b == '\n'.code) break; line += b.toByte() }
            PairWire.parseResult(line.toByteArray())
        }
        assertTrue(reply is PairReply.Refused); assertFalse(WatchPairingState.paired.value)
    }

    @Test fun theListenerStopsWhenThePairingScreenGoes() {
        WatchLanPair.start(ctx); val port = serverPort()
        WatchLanPair.stop()
        val refused = runCatching { Socket("127.0.0.1", port).close() }.isFailure
        assertTrue(refused)
    }

    private fun serverPort(): Int {
        val f = WatchLanPair::class.java.getDeclaredField("server").apply { isAccessible = true }
        return (f.get(WatchLanPair) as java.net.ServerSocket).localPort
    }
}
