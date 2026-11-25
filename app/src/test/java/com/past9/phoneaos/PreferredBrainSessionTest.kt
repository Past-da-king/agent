package com.past9.phoneaos

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.remote.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/** Real RemoteSession entry points and real loopback RPC/health traffic. No chooser stub. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferredBrainSessionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: AppDb
    private lateinit var session: RemoteSession
    private lateinit var pc: Brain
    private lateinit var server: Brain

    private class Brain(val id: String, val rank: Int) {
        val http = MockWebServer()
        @Volatile var ready = true
        @Volatile var probes = 0
        @Volatile var probeGate: java.util.concurrent.CountDownLatch? = null
        @Volatile var probeGate: java.util.concurrent.CountDownLatch? = null
        val messages = CopyOnWriteArrayList<String>()
        val waiting = ConcurrentHashMap<String, Pair<WebSocket, JSONObject>>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        init {
            http.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/v1/health") {
                        probes++
                        probeGate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        return MockResponse().setBody(JSONObject().put("id", id).put("ready", ready).put("meshId", "mesh").toString())
                    }
                    if (request.path == "/v1/devices") return MockResponse().setBody("{}")
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = JSONObject(text)
                            if (frame.optString("type") != "call") return
                            val method = frame.getString("method")
                            if (method == "device.ask" || method == "voice.noteAudio" || method == "device.ack") {
                                waiting[method] = webSocket to frame
                                return
                            }
                            if (method == "chat.sendOnce") {
                                val words = frame.getJSONArray("args").getJSONObject(0).getString("text")
                                messages += words
                                if (words == "held") { waiting[words] = webSocket to frame; return }
                            }
                            answer(webSocket, frame, when (method) {
                                "chat.sendOnce" -> JSONObject().put("accepted", true)
                                "chat.state" -> JSONObject().put("running", false)
                                "data.page" -> JSONObject().put("rows", JSONArray()).put("seq", 0)
                                "data.list", "helpers.cards", "devices.list", "data.where" -> JSONArray()
                                else -> JSONObject()
                            })
                        }
                    })
                }
            }
            http.start()
        }
        fun ref() = BrainRef(id, id, listOf(http.url("/").toString().trimEnd('/')), "", rank)
        fun answer(socket: WebSocket, frame: JSONObject, value: Any) {
            socket.send(JSONObject().put("type", "result").put("id", frame.getString("id")).put("ok", true).put("value", value).toString())
        }
        fun release(key: String, value: Any) { val (socket, frame) = waiting.remove(key)!!; answer(socket, frame, value) }
        fun close() { sockets.forEach { it.cancel() }; http.shutdown() }
    }

    private fun field(name: String) = RemoteSession::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) { field(name).set(session, value) }
    private fun event(name: String, payload: JSONObject = JSONObject()) {
        RemoteSession::class.java.getDeclaredMethod("onEvent", String::class.java, Any::class.java).apply { isAccessible = true }
            .invoke(session, name, payload)
    }
    private suspend fun until(message: String, predicate: () -> Boolean) {
        withTimeout(5_000) { while (!predicate()) delay(10) }
        assertTrue(message, predicate())
    }

    @Before fun setup() = runBlocking {
        pc = Brain("pc", 0); server = Brain("server", 1)
        db = AppDb.inMemory(app)
        session = RemoteSession(app, Link("mesh", "phone", "test-pass", "server", listOf(pc.ref(), server.ref()), 1), db)
        set("metered", true)
        val rpc = RpcClient(OkHttpClient(), server.ref().urls.first(), "test-pass", { JSONObject() }, object : RpcClient.Listener {
            override fun onOpen(rpc: RpcClient) {}
            override fun onEvent(rpc: RpcClient, name: String, payload: Any?) {}
            override fun onClosed(rpc: RpcClient, why: String, opened: Boolean, problem: LinkProblem) {}
        })
        set("rpc", rpc); set("current", Pick(server.ref(), server.ref().urls.first()))
        rpc.connect()
        until("stand-in socket opened") { rpc.isOpen }
    }
    @After fun cleanup() = runBlocking {
        session.close()
        val scope = field("scope").get(session) as CoroutineScope
        scope.coroutineContext[Job]?.join()
        pc.close(); server.close()
        db.close()
    }

    @Test fun mainSendAcceptedRechecksAndHandsBackBeforeMarkingTheTurnBusy() = runBlocking {
        session.sendAccepted("accepted main", emptyList())
        assertTrue("preferred health was probed", pc.probes > 0)
        assertEquals(listOf("accepted main"), pc.messages.toList())
        assertTrue(server.messages.isEmpty())
    }

    @Test fun mainSendRechecksAndHandsBackBeforeMarkingTheTurnBusy() = runBlocking {
        session.send("async main", emptyList(), false)
        until("main send reached a brain") { pc.messages.isNotEmpty() || server.messages.isNotEmpty() }
        assertEquals(listOf("async main"), pc.messages.toList())
        assertTrue(server.messages.isEmpty())
    }

    @Test fun aReadyBrainThatDroppedAMessageKeepsItsThirtySecondCooldown() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val blocked = field("notAnswering").get(session) as MutableMap<String, Long>
        val until = System.currentTimeMillis() + SendFlow.SKIP_MS
        blocked[pc.id] = until
        session.sendTo("project", "use stand-in")
        assertEquals(listOf("use stand-in"), server.messages.toList())
        assertEquals("a health check cannot erase message failure evidence", until, blocked[pc.id])
        assertEquals(0, pc.probes)
    }

    @Test fun oneUnacknowledgedSendDoesNotBlockOtherChats() = runBlocking {
        set("turnRunning", true)
        val first = async(Dispatchers.IO) { session.sendTo("one", "held") }
        try {
            until("first send pending") { server.waiting.containsKey("held") }
            withTimeout(2_000) { session.replyTo("two", "independent") }
            assertTrue(server.messages.contains("independent"))
        } finally {
            server.release("held", JSONObject().put("accepted", true))
            first.await()
        }
    }

    @Test fun aLentCommandBlocksHandbackFromSendEventsAndLoop() = runBlocking {
        val lease = session.jobStarted()
        try {
            session.start()
            event(RemoteSession.EVT_DEVICES)
            session.sendTo("project", "during command")
            assertEquals(listOf("during command"), server.messages.toList())
            assertEquals(0, pc.probes)
            assertTrue((field("rpc").get(session) as RpcClient).isOpen)
        } finally { session.jobEnded(lease) }
    }

    @Test fun dispatchedCommandStaysBusyThroughItsResultAcknowledgement() = runBlocking {
        event(RemoteSession.EVT_COMMAND, JSONObject().put("id", "command-1").put("kind", "unknown"))
        until("command result waiting for acknowledgement") { server.waiting.containsKey("device.ack") }
        val origin = field("rpc").get(session)
        session.start()
        event(RemoteSession.EVT_DEVICES)
        session.sendTo("project", "while result waits")
        assertSame(origin, field("rpc").get(session))
        assertEquals(0, pc.probes)
        server.release("device.ack", JSONObject())
    }

    @Test fun commandArrivingDuringTheHealthProbePreventsSocketReplacement() = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        pc.probeGate = gate
        val sending = async(Dispatchers.IO) { session.sendTo("project", "probe race") }
        try {
            until("health probe started") { pc.probes > 0 }
            event(RemoteSession.EVT_COMMAND, JSONObject().put("id", "command-race").put("kind", "unknown"))
            until("command result is in flight") { server.waiting.containsKey("device.ack") }
            gate.countDown()
            sending.await()
            assertTrue(pc.messages.isEmpty())
            assertEquals(listOf("probe race"), server.messages.toList())
        } finally {
            gate.countDown()
            if (server.waiting.containsKey("device.ack")) server.release("device.ack", JSONObject())
            sending.cancelAndJoin()
        }
    }

    @Test fun commandArrivingDuringTheHealthProbePreventsSocketReplacement() = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        pc.probeGate = gate
        val sending = async(Dispatchers.IO) { session.sendTo("project", "probe race") }
        try {
            until("health probe started") { pc.probes > 0 }
            event(RemoteSession.EVT_COMMAND, JSONObject().put("id", "command-race").put("kind", "unknown"))
            until("command result is in flight") { server.waiting.containsKey("device.ack") }
            gate.countDown()
            sending.await()
            assertTrue(pc.messages.isEmpty())
            assertEquals(listOf("probe race"), server.messages.toList())
        } finally {
            gate.countDown()
            if (server.waiting.containsKey("device.ack")) server.release("device.ack", JSONObject())
            sending.cancelAndJoin()
        }
    }

    @Test fun pendingDeviceAskKeepsItsOriginSocketUntilTheAnswer() = pendingRpc("device.ask")
    @Test fun pendingVoiceAudioKeepsItsOriginSocketUntilTheBytesArrive() = pendingRpc("voice.noteAudio")
    private fun pendingRpc(method: String) = runBlocking {
        val origin = field("rpc").get(session) as RpcClient
        val asking = async(Dispatchers.IO) { session.call(method, emptyList(), 10_000) }
        try {
            until("RPC awaiting origin") { server.waiting.containsKey(method) }
            session.start()
            event(RemoteSession.EVT_DEVICES)
            session.sendTo("project", "while RPC waits")
            assertSame(origin, field("rpc").get(session))
            assertTrue(origin.isOpen)
            assertEquals(0, pc.probes)
        } finally {
            server.release(method, JSONObject().put("answer", "Approve"))
        }
        assertEquals("Approve", (asking.await() as JSONObject).getString("answer"))
    }

    @Test fun mobileDeviceEventsProbeOnlyPreferredAndAreThrottled() = runBlocking {
        pc.ready = false
        session.sendTo("one", "first check")
        assertEquals(1, pc.probes)
        repeat(8) { event(RemoteSession.EVT_DEVICES) }
        session.sendTo("two", "same minute")
        delay(250)
        assertEquals("one preferred check per minute", 1, pc.probes)
        assertEquals("do not probe the already open stand-in", 0, server.probes)
    }
}
