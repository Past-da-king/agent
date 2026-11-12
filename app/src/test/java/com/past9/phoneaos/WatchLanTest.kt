package com.past9.phoneaos

import com.past9.phoneaos.remote.QrScan
import com.past9.phoneaos.wear.WatchLan
import com.past9.phoneaos.wearproto.PairLan
import com.past9.phoneaos.wearproto.PairProof
import com.past9.phoneaos.wearproto.PairReason
import com.past9.phoneaos.wearproto.PairReply
import com.past9.phoneaos.wearproto.PairWire
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The phone's end of the local-network road, against a stand-in watch on a socket, and what the scanner asks of the camera. */
class WatchLanTest {
    private fun fakeWatch(answer: PairReply, seen: MutableList<ByteArray>): ServerSocket {
        val s = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching {
                s.accept().use { c ->
                    val b = java.io.ByteArrayOutputStream(); val i = c.getInputStream()
                    while (true) { val x = i.read(); if (x < 0 || x == '\n'.code) break; b.write(x) }
                    seen += b.toByteArray()
                    c.getOutputStream().apply { write(PairLan.line(PairWire.result(answer))); flush() }
                }
            }
        }
        return s
    }

    @Test fun theRequestGoesOutAsOneLineAndTheAcceptedAnswerComesBackWithTheWatchNode() = runBlocking {
        val seen = mutableListOf<ByteArray>()
        val s = fakeWatch(PairReply.Accepted("Galaxy Watch4", "watch-node"), seen)
        val r = WatchLan.ask("127.0.0.1", s.localPort, PairWire.request(PairProof(code = "9H4T5PBS"), "SM-A356B", "phone-node"), strict = false)
        s.close()
        assertEquals(PairReply.Accepted("Galaxy Watch4", "watch-node"), r)
        assertEquals("phone-node", PairWire.parseRequestNode(seen.single())); assertEquals("9H4T5PBS", PairWire.parseRequest(seen.single())!!.first.code)
    }

    @Test fun aRefusalComesBackAsTheWatchSaidIt() = runBlocking {
        val s = fakeWatch(PairReply.Refused(PairReason.EXPIRED), mutableListOf())
        assertEquals(PairReply.Refused(PairReason.EXPIRED), WatchLan.ask("127.0.0.1", s.localPort, PairWire.request(PairProof(code = "X"), "p", "n"), strict = false))
        s.close()
    }

    @Test fun nothingListeningIsNullNotACrash() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(WatchLan.ask("127.0.0.1", port, PairWire.request(PairProof(code = "X"), "p", "n"), timeoutMs = 500, strict = false))
    }

    @Test fun aScannedQrCanNeverMakeThePhoneDialAPublicHost() = runBlocking {
        assertNull(WatchLan.ask("8.8.8.8", 5000, PairWire.request(PairProof(code = "X"), "p", "n")))
        assertNull(WatchLan.ask("127.0.0.1", 5000, PairWire.request(PairProof(code = "X"), "p", "n")))
    }

    @Test fun theScannerReadsAtAResolutionAWatchFaceCodeNeeds() {
        assertTrue("the default 640x480 never read a small code", QrScan.ANALYSIS_W * QrScan.ANALYSIS_H >= 1280 * 720)
        assertTrue(QrScan.REFOCUS_MS in 1_000..4_000)
    }
}
