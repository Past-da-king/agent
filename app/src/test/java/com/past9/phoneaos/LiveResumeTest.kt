package com.past9.phoneaos

import com.past9.phoneaos.voice.LiveSession
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A live session outlives one connection: Google ends a connection after about ten minutes (goAway first) and networks
 * drop. The session keeps the latest resumption handle and reconnects invisibly with it, the same conversation, no new
 * "connecting" and no end event. Proven against a fake socket server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveResumeTest {
    private lateinit var server: MockWebServer
    private val setups = LinkedBlockingQueue<JSONObject>()
    private val events = CopyOnWriteArrayList<LiveSession.Event>()

    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { runCatching { server.shutdown() } }

    private fun url() = server.url("/live").toString().replaceFirst("http", "ws")

    /** One fake connection: answers the setup, hands out a resumption handle, then does [after] with the socket. */
    private fun connection(handle: String, after: (WebSocket) -> Unit = {}) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = JSONObject(text)
            if (!m.has("setup")) return
            setups.add(m.getJSONObject("setup"))
            webSocket.send("""{"setupComplete":{}}""")
            webSocket.send("""{"sessionResumptionUpdate":{"resumable":true,"newHandle":"$handle"}}""")
            after(webSocket)
        }
    })

    private fun session() = LiveSession(url(), "gemini-test", "Kore", "be brief", tools = null) { events += it }

    @Test fun theSetupAsksForResumptionAndForSlidingWindowCompressionSoASessionCanRunLong() {
        server.enqueue(connection("H0"))
        val s = session(); s.start()
        val setup = setups.poll(5, TimeUnit.SECONDS)!!
        assertTrue("resumption on", setup.has("sessionResumption"))
        assertFalse("no handle on the first connection", setup.getJSONObject("sessionResumption").has("handle"))
        assertTrue("sliding window compression on", setup.getJSONObject("contextWindowCompression").has("slidingWindow"))
        s.stop()
    }

    @Test fun aDroppedSocketReconnectsWithTheLatestHandleAndTheSessionNeverEnds() {
        server.enqueue(connection("H1") { ws -> Thread.sleep(150); ws.close(1011, "network blip") })
        server.enqueue(connection("H2"))
        val s = session(); s.start()
        val first = setups.poll(5, TimeUnit.SECONDS)!!
        assertFalse(first.getJSONObject("sessionResumption").has("handle"))
        val second = setups.poll(8, TimeUnit.SECONDS)
        assertNotNull("it reconnected on its own", second)
        assertEquals("H1", second!!.getJSONObject("sessionResumption").getString("handle"))
        assertTrue("the reconnect was announced so the screen can stay LIVE", events.any { it is LiveSession.Event.Reconnecting })
        assertTrue("setup completed once, not twice (the second is a resume)", events.count { it is LiveSession.Event.SetupComplete } == 1)
        assertTrue("never reported as ended or failed", events.none { it is LiveSession.Event.Closed || it is LiveSession.Event.Failed })
        s.stop()
    }

    @Test fun goAwayMovesToAFreshConnectionWithTheHandleBeforeTheOldOneIsCut() {
        server.enqueue(connection("G1") { ws -> Thread.sleep(150); ws.send("""{"goAway":{"timeLeft":"30s"}}""") })
        server.enqueue(connection("G2"))
        val s = session(); s.start()
        setups.poll(5, TimeUnit.SECONDS)!!
        val second = setups.poll(8, TimeUnit.SECONDS)
        assertNotNull(second)
        assertEquals("G1", second!!.getJSONObject("sessionResumption").getString("handle"))
        assertTrue(events.none { it is LiveSession.Event.Closed || it is LiveSession.Event.Failed })
        s.stop()
    }

    @Test fun afterAResumeTheSameSessionCarriesOnAndCanBeTalkedTo() {
        val got = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JSONObject(text).has("setup")) { setups.add(JSONObject(text).getJSONObject("setup")); webSocket.send("""{"setupComplete":{}}"""); webSocket.send("""{"sessionResumptionUpdate":{"resumable":true,"newHandle":"S1"}}"""); Thread.sleep(150); webSocket.close(1011, "blip") }
            }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = JSONObject(text)
                if (m.has("setup")) { setups.add(m.getJSONObject("setup")); webSocket.send("""{"setupComplete":{}}""") } else got.add(text)
            }
        }))
        val s = session(); s.start()
        setups.poll(5, TimeUnit.SECONDS)!!
        val second = setups.poll(8, TimeUnit.SECONDS)!!
        assertEquals("S1", second.getJSONObject("sessionResumption").getString("handle"))
        Thread.sleep(200)
        // The resumed connection is the live one: what is said to the session now reaches it.
        s.announce("hello again")
        val sent = got.poll(3, TimeUnit.SECONDS)
        assertNotNull("the resumed connection received the message", sent)
        assertTrue(sent!!.contains("hello again"))
        s.stop()
    }
}
