package com.past9.phoneaos

import com.past9.phoneaos.agent.*
import com.past9.phoneaos.data.AgentSettings
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/** HTTPS by default; plain http:// only to loopback or with the user's explicit opt-in. */
class HttpPolicyTest {
    @Test fun httpsIsAlwaysFine() {
        assertNull(HttpPolicy.blockReason("https://api.example.com/v1", false))
        assertNull(HttpPolicy.blockReason("HTTPS://api.example.com/v1", false))
    }

    @Test fun lanHttpNeedsOptIn() {
        assertEquals(HttpPolicy.NEEDS_OPT_IN, HttpPolicy.blockReason("http://192.168.1.20:8080/v1", false))
        assertEquals(HttpPolicy.NEEDS_OPT_IN, HttpPolicy.blockReason("http://strata.lan/v1", false))
        assertNull(HttpPolicy.blockReason("http://192.168.1.20:8080/v1", true))
    }

    @Test fun loopbackStaysAllowed() {
        listOf("http://127.0.0.1:4096/mcp", "http://localhost:11434/v1", "http://[::1]:8080/v1", "http://127.0.0.53/v1")
            .forEach { assertNull(it, HttpPolicy.blockReason(it, false)) }
        assertFalse(HttpPolicy.isLoopback("http://127.0.0.1.evil.com/v1"))
        assertFalse(HttpPolicy.isLoopback("http://localhost.evil.com/v1"))
    }

    @Test fun otherSchemesRejected() {
        assertNotNull(HttpPolicy.blockReason("ftp://x/v1", true))
        assertNotNull(HttpPolicy.blockReason("api.example.com/v1", true))
    }

    @Test fun optInIsOffByDefault() = assertFalse(AgentSettings().allowHttp)

    /** A self-hosted server on the LAN ("strata.lan" resolved to a local stub), reached over plain HTTP. */
    private fun lanStub(): Pair<MockWebServer, OkHttpClient> {
        val server = MockWebServer(); server.start()
        val http = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String) = if (hostname == "strata.lan") listOf(InetAddress.getLoopbackAddress()) else Dns.SYSTEM.lookup(hostname)
        }).build()
        return server to http
    }

    @Test fun providerBlocksLanHttpWithoutOptIn() = runBlocking {
        val (server, http) = lanStub()
        val p = OpenAiCompatProvider("custom", "sk-lan", "http://strata.lan:${server.port}/v1", http)
        val e = runCatching { p.complete("s", listOf(Msg.user("hi")), emptyList(), "llama") }.exceptionOrNull()
        assertTrue(e is ProviderException); assertEquals(HttpPolicy.NEEDS_OPT_IN, e!!.message)
        assertEquals("the key must never leave the phone", 0, server.requestCount)
        server.shutdown()
    }

    @Test fun providerTalksToLanHttpWithOptIn() = runBlocking {
        val (server, http) = lanStub()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"hello from strata"},"finish_reason":"stop"}]}"""))
        val p = OpenAiCompatProvider("custom", "sk-lan", "http://strata.lan:${server.port}/v1", http, allowHttp = true)
        val c = p.complete("s", listOf(Msg.user("hi")), emptyList(), "llama")
        assertEquals("hello from strata", c.message.text)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path); assertEquals("strata.lan:${server.port}", req.getHeader("Host"))
        server.shutdown()
    }

    @Test fun providerForCarriesTheOptIn() = runBlocking {
        val blocked = AgentRuntime.providerFor(com.past9.phoneaos.data.Provider.CUSTOM, "k", "http://10.0.0.5:1234/v1")
        val e = runCatching { blocked.complete("s", listOf(Msg.user("hi")), emptyList(), "m") }.exceptionOrNull()
        assertEquals(HttpPolicy.NEEDS_OPT_IN, e?.message)
    }
}
