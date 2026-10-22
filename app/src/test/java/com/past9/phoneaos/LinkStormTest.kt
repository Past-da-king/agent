package com.past9.phoneaos

import com.past9.phoneaos.remote.DeviceFacts
import com.past9.phoneaos.remote.LinkProblem
import com.past9.phoneaos.remote.Pinning
import com.past9.phoneaos.remote.RemoteSession
import com.past9.phoneaos.remote.RpcClient
import com.past9.phoneaos.remote.backoffMs
import com.past9.phoneaos.remote.linkReason
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * 22 Oct 2026: the phone opened about 15 connections a second to the computer and never connected. These pin the
 * pieces of the fix: waits that grow to 30 s, failures named so the screen can say what to do.
 */
class LinkStormTest {
    @Test fun waitsGrowFromOneSecondToThirtyAndNoFurther() {
        assertEquals(0L, backoffMs(0, 0.5))
        assertEquals(1_000L, backoffMs(1, 0.5))
        assertEquals(2_000L, backoffMs(2, 0.5))
        assertEquals(4_000L, backoffMs(3, 0.5))
        assertEquals(16_000L, backoffMs(5, 0.5))
        assertEquals(RemoteSession.MAX_BACKOFF_MS, backoffMs(6, 0.5))
        assertEquals(RemoteSession.MAX_BACKOFF_MS, backoffMs(60, 0.5))
        // Jitter stays within a fifth and never past the cap.
        assertEquals(800L, backoffMs(1, 0.0)); assertEquals(1_200L, backoffMs(1, 1.0))
        assertEquals(RemoteSession.MAX_BACKOFF_MS, backoffMs(9, 1.0))
        assertEquals(24_000L, backoffMs(9, 0.0))
    }

    @Test fun eachFailureIsNamed() {
        assertEquals(LinkProblem.Unpaired, LinkProblem.of(java.net.ProtocolException("Expected HTTP 101"), 401))
        assertEquals(LinkProblem.Unpaired, LinkProblem.of(null, 403))
        assertEquals(LinkProblem.Unpaired, LinkProblem.ofClose(4003))
        assertEquals(LinkProblem.Dropped, LinkProblem.ofClose(1001))
        assertEquals(LinkProblem.CertChanged, LinkProblem.of(SSLHandshakeException("x").apply { initCause(CertificateException(Pinning.CERT_CHANGED)) }, null))
        assertEquals(LinkProblem.Unreachable, LinkProblem.of(java.net.ConnectException("Failed to connect"), null))
        assertEquals(LinkProblem.Unreachable, LinkProblem.of(java.io.IOException("wrapped", java.net.SocketTimeoutException("t")), null))
        assertEquals(LinkProblem.Dropped, LinkProblem.of(java.io.EOFException(), null))
    }

    @Test fun theWordsSayWhatToDo() {
        assertTrue(linkReason(LinkProblem.Unpaired, "Desk").contains("no longer knows this phone"))
        assertTrue(linkReason(LinkProblem.Unpaired, "Desk").contains("Scan its QR code again"))
        assertTrue(linkReason(LinkProblem.CertChanged, "Desk").contains("certificate changed"))
        assertTrue(linkReason(LinkProblem.Unreachable, "Desk").startsWith("Can't reach Desk"))
    }

    @Test fun aComputerThatRefusesThePassIsReportedAsUnpaired() {
        val srv = MockWebServer()
        srv.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"This device is not paired with this computer.","reason":"unknown-pass"}"""))
        srv.start()
        try {
            val done = CountDownLatch(1)
            var got: LinkProblem? = null
            var wasOpen = true
            val rpc = RpcClient(OkHttpClient(), "http://127.0.0.1:${srv.port}", "old-pass",
                { JSONObject().put("device", DeviceFacts("P", "phone", "0.14.5", 400, 800).toJson()) },
                object : RpcClient.Listener {
                    override fun onOpen(rpc: RpcClient) {}
                    override fun onEvent(rpc: RpcClient, name: String, payload: Any?) {}
                    override fun onClosed(rpc: RpcClient, why: String, opened: Boolean, problem: LinkProblem) { got = problem; wasOpen = opened; done.countDown() }
                })
            rpc.connect()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(LinkProblem.Unpaired, got)
            assertFalse(wasOpen)
            assertEquals(1, srv.requestCount)
        } finally { srv.shutdown() }
    }
}
