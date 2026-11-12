package com.past9.phoneaos

import com.past9.phoneaos.assist.AskRefs
import com.past9.phoneaos.assist.AssistContext
import com.past9.phoneaos.assist.AssistContextBuilder
import com.past9.phoneaos.assist.CtxAgent
import com.past9.phoneaos.assist.CtxAsk
import com.past9.phoneaos.assist.LiveAssistCore
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.LiveCards
import com.past9.phoneaos.voice.LiveSession
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Live voice ALWAYS knows what is going on (13 Nov 2026): his agents, what each does and waits on, what waits on him, the
 * last of the main chat. Never silently empty, never dropped on a timeout, kept current while the session is open, and the
 * instruction of a reconnect is built from what is true then. Proven here against a fake socket and fake computer data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveContextAlwaysTest {
    private val now = 1_800_000_000_000L
    private lateinit var server: MockWebServer
    private val setups = LinkedBlockingQueue<JSONObject>()
    private val frames = LinkedBlockingQueue<JSONObject>()

    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { runCatching { server.shutdown() } }

    private fun url() = server.url("/live").toString().replaceFirst("http", "ws")

    private fun socket() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = JSONObject(text)
            if (m.has("setup")) { setups.add(m.getJSONObject("setup")); webSocket.send("""{"setupComplete":{}}""") } else frames.add(m)
        }
    })

    private fun card(id: String, label: String, state: String, line: String, brief: String = "", steps: List<String> = emptyList()) = LiveCards.card(JSONObject()
        .put("id", id).put("label", label).put("state", state).put("line", line).put("brief", brief).put("startedAt", now - 60_000)
        .put("steps", JSONArray(steps.mapIndexed { i, t -> JSONObject().put("text", t).put("status", "done").put("at", now - 50_000 + i * 1000L) }))
        .put("owner", JSONObject().put("kind", "plain")))!!

    private fun rows() = listOf(
        ChatItem(1, "user", "Pair my watch", JSONObject().toString(), now - 5_000),
        ChatItem(2, "agent", "Waiting for the watch to come back on adb.", JSONObject().toString(), now - 4_000),
        ChatItem(3, "question", "What is the phone pairing code?", JSONObject().put("whyBy", "Watch helper").put("options", JSONArray()).toString(), now - 3_000),
    )

    private fun ctx(cards: List<com.past9.phoneaos.remote.LiveCard>? = listOf(card("h1", "Watch helper", "working", "Waiting for the watch to come back on adb", "Install wear 0.15.62", listOf("Checked adb devices", "Watch is offline"))),
                    extra: List<CtxAgent>? = null, known: Boolean = true) =
        AssistContextBuilder.build(rows(), cards, emptyList(), false, "", now, AskRefs(), "Nova", extra, known)

    // ---- the setup carries the agents and the asks ---------------------------------------------------

    @Test fun theLiveSetupCarriesTheAgentsTheirLatestStepsAndWhatWaitsOnTheUser() {
        server.enqueue(socket())
        val c = ctx()
        val s = LiveSession(url(), "m", "Kore", "base", tools = null) {}
        s.systemFrom { LiveAssistCore.systemPrompt("Nova", "Ayanda", true, c) }
        s.start()
        val text = setups.poll(5, TimeUnit.SECONDS)!!.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(text, text.contains("Watch helper (helper), working: Waiting for the watch to come back on adb"))
        assertTrue(text, text.contains("Started for: Install wear 0.15.62"))
        assertTrue(text, text.contains("Latest steps: Watch is offline; Checked adb devices"))
        assertTrue(text, text.contains("[A1] Question, from Watch helper: What is the phone pairing code?"))
        assertTrue(text, text.contains("Waiting for the watch to come back on adb.")) // the main chat tail
        s.stop()
    }

    @Test fun aReconnectsFreshSetupIsBuiltFromWhatIsTrueThenNotWhatWasTrueAtStart() {
        server.enqueue(socket())
        var current = ctx()
        val s = LiveSession(url(), "m", "Kore", "base", tools = null) {}
        s.systemFrom { LiveAssistCore.systemPrompt("Nova", "Ayanda", true, current) }
        current = ctx(cards = listOf(card("h2", "Cosmi helper", "working", "Rendering video 09")))
        s.start()
        val text = setups.poll(5, TimeUnit.SECONDS)!!.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(text, text.contains("Cosmi helper"))
        assertFalse(text, text.contains("Watch helper (helper)"))
        s.stop()
    }

    @Test fun withNoProviderTheInstructionItWasBuiltWithIsUsed() {
        server.enqueue(socket())
        val s = LiveSession(url(), "m", "Kore", "plain base", tools = null) {}
        s.start()
        val text = setups.poll(5, TimeUnit.SECONDS)!!.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("plain base", text)
        s.stop()
    }

    // ---- mid-session refresh is a quiet context turn ---------------------------------------------------

    @Test fun aMidSessionRefreshIsAClientContentTurnThatAsksForNoAnswer() {
        server.enqueue(socket())
        val s = LiveSession(url(), "m", "Kore", "base", tools = null) {}
        s.start()
        setups.poll(5, TimeUnit.SECONDS)!!
        // The line is up a moment after setupComplete.
        var sent = false
        for (i in 0 until 20) { if (s.note("[Context update]\nAgents: none")) { sent = true; break }; Thread.sleep(100) }
        assertTrue("the note went out once the line was ready", sent)
        val m = frames.poll(3, TimeUnit.SECONDS)
        assertNotNull(m)
        val cc = m!!.getJSONObject("clientContent")
        assertFalse("no answer is asked for", cc.getBoolean("turnComplete"))
        assertTrue(cc.getJSONArray("turns").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text").contains("Context update"))
        s.stop()
    }

    @Test fun aNoteBeforeTheLineIsUpIsNotSentAndSaysSo() {
        val s = LiveSession(url(), "m", "Kore", "base", tools = null) {}
        assertFalse(s.note("early"))
    }

    // ---- the context itself ----------------------------------------------------------------------------

    @Test fun anEmptyButKnownContextSaysNoneAreWorkingNotNothing() {
        val c = AssistContextBuilder.build(emptyList(), emptyList(), emptyList(), false, "", now, AskRefs(), "Nova")
        assertTrue(c.prompt(), c.prompt().contains("Agents: none are working right now."))
    }

    @Test fun agentsThatCouldNotBeReadAreNeverReportedAsNone() {
        val c = AssistContextBuilder.build(emptyList(), null, emptyList(), false, "", now, AskRefs(), "Nova", null, agentsKnown = false)
        val p = c.prompt()
        assertTrue(p, p.contains("you could not read them just now"))
        assertFalse(p, p.contains("none are working"))
    }

    @Test fun aContextThatCouldNotBeReadAtAllTellsTheVoiceSoInsteadOfLeavingItBlank() {
        val block = LiveAssistCore.contextBlock(null, true)
        assertTrue(block, block.contains("could not read it just now"))
        assertTrue(block, block.contains("Never say that nothing is running"))
    }

    @Test fun theComputersListOfWhoIsAtWorkFillsInWhenItsCardsAreNotThereYet() {
        val targets = JSONArray()
            .put(JSONObject().put("kind", "main").put("running", true).put("doing", "Chatting"))
            .put(JSONObject().put("kind", "project").put("name", "Payroll").put("running", false).put("chats", JSONArray().put(JSONObject().put("title", "Run").put("running", true))))
            .put(JSONObject().put("kind", "project").put("name", "Idle one").put("running", false).put("chats", JSONArray()))
            .put(JSONObject().put("kind", "helper").put("name", "Watch helper").put("running", true).put("doing", "Waiting for the watch on adb"))
            .put(JSONObject().put("kind", "helper").put("name", "Done helper").put("running", false).put("doing", "finished"))
        val agents = AssistContextBuilder.fromTargets(targets)
        assertEquals(listOf("project Payroll", "Watch helper"), agents.map { it.name })
        val c = ctx(cards = null, extra = agents)
        assertTrue(c.prompt(), c.prompt().contains("Watch helper (helper), working: Waiting for the watch on adb"))
        assertTrue(c.agentsKnown)
    }

    @Test fun theAgentsAndTheAsksComeFirstAndSurviveTheCapWhileOldChatGoes() {
        val many = (1..8).map { card("h$it", "Helper number $it", "working", "Doing a long step number $it " + "x".repeat(100), "A long task " + "y".repeat(120), listOf("s" + "a".repeat(80), "t" + "b".repeat(80))) }
        val long = (1..40).map { ChatItem(100L + it, if (it % 2 == 0) "agent" else "user", "line $it " + "z".repeat(170), JSONObject().toString(), now - 60_000 + it) } + rows()
        val c = AssistContextBuilder.build(long, many, emptyList(), false, "", now, AskRefs(), "Nova")
        val p = c.prompt()
        assertTrue(p.length.toString(), p.length <= AssistContext.MAX_CHARS)
        assertTrue(p, p.indexOf("Waiting on the user") in 0 until p.indexOf("Agents"))
        assertTrue(p, p.contains("Helper number 1") && p.contains("[A1] Question"))
        assertTrue(p, p.indexOf("Agents") < p.indexOf("The last of the main chat").let { if (it < 0) Int.MAX_VALUE else it })
    }

    @Test fun theVoiceIsToldTheListStaysCurrentAndWhereTheNewestIs() {
        val block = LiveAssistCore.contextBlock(ctx(), true)
        assertTrue(block, block.contains("[Context update]"))
        assertTrue(block, block.contains("answer_ask"))
    }
}
