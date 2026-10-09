package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.*
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.PinnedAgent
import com.past9.phoneaos.data.PinnedAgentStore
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun List<Pair<String, List<Msg>>>.main() = filter { !it.first.contains("You are a HELPER") }
private fun List<Pair<String, List<Msg>>>.helper() = filter { it.first.contains("You are a HELPER") }
private fun triage(decision: String, message: String, needs: String? = null, helper: Long? = null) =
    call("escalation_triage", JSONObject().put("decision", decision).put("message", message).apply { needs?.let { put("needs", it) }; helper?.let { put("helper", it) } })
private fun ask(question: String, vararg options: String) = call("ask_user", JSONObject().put("question", question).put("options", JSONArray(options.toList())))
/** The helper's tool results, in order. */
private fun ScriptedProvider.helperResults() = seen.helper().flatMap { it.second.last().blocks.filterIsInstance<Block.ToolResult>() }.map { it.content }.distinct()
/** What the main agent's tools returned to it. */
private fun ScriptedProvider.mainResults() = seen.main().flatMap { it.second.last().blocks.filterIsInstance<Block.ToolResult>() }.map { it.content }.distinct()

/**
 * The escalation gate (a helper's request for the user goes to the main agent first) and pinned agents
 * (helpers kept with their context and handed later jobs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GateAndPinsTest {
    private lateinit var db: AppDb
    private lateinit var settings: SettingsStore
    private val phone = FakePhone()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDb.inMemory(ctx); settings = SettingsStore(ctx)
        settings.setMode(PowerMode.API_KEY); settings.setProvider(Provider.ANTHROPIC); settings.setApiKey(Provider.ANTHROPIC, "sk-test"); settings.setUserName("Sam")
        java.io.File(ctx.filesDir, "pinned").deleteRecursively(); java.io.File(ctx.filesDir, "escalations.jsonl").delete()
    }

    private fun runtime(p: ScriptedProvider) = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { p })
    private suspend fun waitIdle(rt: AgentRuntime) {
        try { withTimeout(15_000) { rt.awaitIdle() } }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Not idle. Running: ${rt.running.value.map { it.id }}. Chat:\n" + db.chat().all().first().joinToString("\n") { "${it.id} ${it.kind}: ${it.text.take(120)} ${it.meta.take(200)}" })
        }
    }
    private suspend fun questions() = db.chat().all().first().filter { it.kind == "question" }
    private suspend fun helperRows() = db.chat().all().first().filter { it.kind == "helper" }

    // ---- the gate ------------------------------------------------------------------------------

    @Test fun aHelpersHandoffGoesToTheMainAgentWhichPushesItBack() = runBlocking {
        val p = ScriptedProvider(delegate("Create an X account"), say("On it."),
            triage("push_back", "Click the month dropdown, then each option; or focus it and type the month, or use arrow keys."), say("HOLD"),
            say("Your X account is ready."))
        // The same shape as browser_handoff's request (its options mark it as a handoff).
        p.helperQueue += ask("Set the birthday on the sign-up form. Open the browser, do it, then tap Done.", "Open browser", "Done", "Skip")
        p.helperQueue += say("Set the birthday with arrow keys. Account created.")
        val rt = runtime(p)
        rt.send("Make me an X account"); waitIdle(rt)

        assertTrue("the user never saw it", questions().isEmpty())
        assertTrue(phone.notes.isEmpty())
        val note = p.seen.main().map { it.second.last().text }.first { it.contains("wants the user") }
        assertTrue(note.contains("browser handoff") && note.contains("escalation_triage"))
        // The helper heard the main agent instead of "the user says they are done".
        val r = p.helperResults().first()
        assertTrue(r, r.startsWith("NOT PASSED TO THE USER") && r.contains("arrow keys"))
        val h = helperRows().single()
        assertEquals(1, JSONObject(h.meta).getInt("gatePushes"))
        val log = rt.escalationLog.recent().single()
        assertEquals("pushed_back", log.getString("decision")); assertEquals("handoff", log.getString("kind"))
        assertEquals("Your X account is ready.", db.chat().all().first().last { it.kind == "agent" }.text)
    }

    @Test fun aStuckRequestCantBeForwardedUntilPushedBackTwice() = runBlocking {
        val p = ScriptedProvider(delegate("Sign up"), say("On it."),
            triage("forward", "It's stuck on the form.", "other"), triage("push_back", "Use the keyboard on the field."), say("HOLD"),
            triage("push_back", "Try browser_look and click the option you see."), say("HOLD"),
            triage("forward", "It tried everything I suggested.", "other"), say("HOLD"), say("Done."))
        repeat(3) { p.helperQueue += ask("Can you fill in the date field for me?", "OK", "No") }
        p.helperQueue += say("Done.")
        val rt = runtime(p)
        rt.send("sign me up")
        withTimeout(10_000) { while (questions().isEmpty()) delay(50) }
        assertTrue(p.mainResults().any { it.startsWith("Not forwarded") && it.contains("pushed back 0 times") })
        val q = questions().single()
        assertEquals("It tried everything I suggested.", JSONObject(q.meta).getString("why"))
        assertTrue(phone.notes.single().contains("needs you|It tried everything I suggested."))
        rt.answer(q.id, "OK"); waitIdle(rt)
        assertEquals(listOf("pushed_back", "pushed_back", "forwarded"), rt.escalationLog.recent().map { it.getString("decision") })
    }

    @Test fun theMainAgentAnswersWhatItKnowsAndTheUserNeverSeesIt() = runBlocking {
        val p = ScriptedProvider(delegate("Sign up to the newsletter"), say("On it."), triage("answer", "sam@example.com"), say("HOLD"), say("Signed up."))
        p.helperQueue += ask("Which email should I use?", "Work", "Personal")
        p.helperQueue += say("Signed up with sam@example.com.")
        val rt = runtime(p)
        rt.send("newsletter please"); waitIdle(rt)
        assertTrue(questions().isEmpty())
        val r = p.helperResults().first()
        assertTrue(r, r.startsWith("The user answered: sam@example.com") && r.contains("not from the user"))
        assertEquals("self_answered", rt.escalationLog.recent().single().getString("decision"))
    }

    @Test fun approvalsAreTheUsersAloneAndArriveWithTheReason() = runBlocking {
        val p = ScriptedProvider(delegate("Email Lerato: see you at 6 on Friday"), say("Sent a helper."),
            triage("answer", "Approve"), triage("forward", "It sends an email in your name.", "in_their_name"), say("HOLD"),
            say("Done, Lerato has it."))
        p.helperQueue += call("request_approval", JSONObject().put("action", "Send email to Lerato").put("details", "Subject: Friday\nSee you at 6."))
        p.helperQueue += say("Email sent to Lerato.")
        val rt = runtime(p)
        rt.send("Email Lerato that I'll see her at 6")
        withTimeout(10_000) { while (questions().isEmpty()) delay(50) }
        assertTrue(p.mainResults().any { it.startsWith("You can't answer this for the user") && it.contains("only they can say yes") })
        val q = questions().single()
        assertTrue(q.text.startsWith("APPROVAL|Send email to Lerato|"))
        assertEquals("It sends an email in your name.", JSONObject(q.meta).getString("why"))
        assertTrue(phone.notes.single().startsWith("Approve?|Send email to Lerato"))
        rt.answer(q.id, "Approve"); waitIdle(rt)
        assertTrue(p.helperResults().any { it.startsWith("APPROVED") })
    }

    @Test fun aRequestTheMainAgentNeverDecidesStillReachesTheUser() = runBlocking {
        // It ignores the request and the reminder: the helper must not hang, so the user gets it.
        val p = ScriptedProvider(delegate("Book a table"), say("On it."), say("HOLD"), say("HOLD"), say("Booked."))
        p.helperQueue += ask("Which time?", "7pm", "8pm")
        p.helperQueue += say("Booked for 7pm.")
        val rt = runtime(p)
        rt.send("Book dinner")
        withTimeout(10_000) { while (questions().isEmpty()) delay(50) }
        assertTrue(p.seen.main().any { it.second.last().text.contains("still waiting on your decision") })
        assertFalse(JSONObject(questions().single().meta).has("why"))
        rt.answer(questions().single().id, "7pm"); waitIdle(rt)
        assertEquals("forwarded_untriaged", rt.escalationLog.recent().single().getString("decision"))
    }

    @Test fun aHelpersNotificationGoesToTheMainAgent() = runBlocking {
        val p = ScriptedProvider(delegate("Watch the price"), say("On it."), say("HOLD"), say("Price is R899."))
        p.helperQueue += call("notify_user", JSONObject().put("title", "Price").put("body", "Checking Takealot now"))
        p.helperQueue += say("R899 on Takealot.")
        val rt = runtime(p)
        rt.send("price?"); waitIdle(rt)
        assertTrue(phone.notes.isEmpty())
        assertTrue(p.seen.main().any { it.second.last().text.contains("wanted to notify the user] Price: Checking Takealot now") })
    }

    @Test fun aHelperThatHandsTheUserAChoreIsFlaggedForAPush() = runBlocking {
        val p = ScriptedProvider(delegate("Create an X account"), say("On it."), say("HOLD"))
        p.helperQueue += say("Got to the last step. Please set the birthday yourself in the browser, then it's done.")
        val rt = runtime(p)
        rt.send("X account"); waitIdle(rt)
        assertTrue(p.seen.main().last().second.last().text.contains("handing part of the job back"))
    }

    @Test fun gateRules() {
        assertEquals(EscalationGate.Kind.APPROVAL, EscalationGate.kind("APPROVAL|Pay|R10", listOf("Approve", "Decline")))
        assertEquals(EscalationGate.Kind.HANDOFF, EscalationGate.kind("Solve the captcha. Open the browser, do it, then tap Done.", listOf("Open browser", "Done", "Skip")))
        assertEquals(EscalationGate.Kind.PERMISSION, EscalationGate.kind("Can I use your location?", listOf("Allow this time", "No")))
        assertEquals(EscalationGate.Kind.CONNECT, EscalationGate.kind(com.past9.phoneaos.tools.ConnectRequest.of("gmail", "read mail").text, listOf("Connect", "Decline")))
        assertEquals(EscalationGate.Kind.QUESTION, EscalationGate.kind("Which one?", listOf("A", "B")))
        // A captcha goes straight through; "stuck" needs two push-backs; approvals can't be answered for the user.
        assertNull(EscalationGate.check(EscalationGate.Kind.HANDOFF, "forward", "A captcha.", "captcha", 0))
        assertNotNull(EscalationGate.check(EscalationGate.Kind.HANDOFF, "forward", "Stuck.", "other", 1))
        assertNull(EscalationGate.check(EscalationGate.Kind.HANDOFF, "forward", "Stuck.", "other", 2))
        assertNotNull(EscalationGate.check(EscalationGate.Kind.APPROVAL, "answer", "Approve", "", 5))
        assertNotNull(EscalationGate.check(EscalationGate.Kind.HANDOFF, "answer", "Done", "", 5))
        assertNull(EscalationGate.check(EscalationGate.Kind.APPROVAL, "forward", "It pays R10.", "", 0))
        assertNotNull(EscalationGate.check(EscalationGate.Kind.QUESTION, "push_back", "", "", 0))
    }

    // ---- pinned agents -------------------------------------------------------------------------

    @Test fun aPinnedAgentKeepsItsContextAndTakesTheNextJob() = runBlocking {
        val p = ScriptedProvider(delegate("Get the Teams transcript of the 6 Oct call"), say("On it."), say("Here it is."))
        p.helperQueue += say("Got it via the SharePoint media API: /_api/v2.1/drives/{id}/items/{id}/media/transcripts. The Stream page itself has no download.")
        val rt = runtime(p)
        rt.send("transcript of Monday's call"); waitIdle(rt)
        val first = helperRows().single()
        val pin = rt.tools().first { it.spec.name == "agent_pin" }
        // Pinning is for good reason only.
        assertTrue(pin.run(JSONObject().put("helper", first.id).put("name", "Teams transcripts").put("summary", "short"), quiet).startsWith("Not pinned"))
        val out = pin.run(JSONObject().put("helper", first.id).put("name", "Teams transcripts")
            .put("summary", "Pulls Teams meeting transcripts through the SharePoint media API (the Stream page has no download).")
            .put("good_for", JSONArray(listOf("Teams transcripts"))).put("why", "it found an undocumented route"), quiet)
        assertTrue(out, out.startsWith("Pinned \"Teams transcripts\""))
        assertTrue(rt.systemPrompt("anything").contains("YOUR PINNED AGENTS") && rt.systemPrompt("x").contains("Teams transcripts (good for Teams transcripts)"))

        // The next job goes to the same agent, which still has its earlier work.
        p.queue += call("delegate", JSONObject().put("tasks", JSONArray(listOf("The transcript of the 7 Oct call"))).put("agents", JSONArray(listOf("Teams transcripts"))))
        p.queue += say("Sent it to the transcripts agent."); p.queue += say("Done.")
        p.helperQueue += say("Same route, 7 Oct transcript fetched.")
        rt.send("and Tuesday's"); waitIdle(rt)
        val (sys, msgs) = p.seen.helper().last()
        assertTrue(sys.contains("YOU ARE A PINNED AGENT: \"Teams transcripts\""))
        assertEquals("Get the Teams transcript of the 6 Oct call", msgs.first().text)
        assertTrue(msgs.any { it.text.contains("SharePoint media API") })
        assertTrue(msgs.last().text.contains("[A new job from the main agent") && msgs.last().text.contains("7 Oct call"))
        val second = helperRows().last()
        assertEquals("Teams transcripts", JSONObject(second.meta).getString("pinnedName"))
        val kept = rt.pins.agents.value.single()
        assertEquals(second.id, kept.lastHelper); assertEquals(2, kept.jobs.size)
        assertTrue(rt.pins.loadHistory(kept.id).any { it.text.contains("7 Oct transcript fetched") })
    }

    @Test fun anUnknownPinnedAgentIsRefusedAndUnpinningLetsItGo() = runBlocking {
        val rt = runtime(ScriptedProvider())
        rt.pins.put(PinnedAgent("a1", "X sign-ups", "Signs up to X with Google; birthday dropdowns need arrow keys.", lastHelper = 999))
        val p = ScriptedProvider(call("delegate", JSONObject().put("tasks", JSONArray(listOf("Make a second X account"))).put("agents", JSONArray(listOf("Nobody")))), say("ok"))
        val rt2 = runtime(p); rt2.send("go"); waitIdle(rt2)
        assertTrue(p.mainResults().any { it.contains("No pinned agent called \"Nobody\"") })
        assertEquals("Let X sign-ups go.", rt2.tools().first { it.spec.name == "agent_unpin" }.run(JSONObject().put("name", "x sign-ups"), quiet))
        assertTrue(rt2.pins.agents.value.isEmpty())
    }

    @Test fun stalePinsExpireAndLongHistoriesAreCompacted() {
        val dir = kotlin.io.path.createTempDirectory("pins").toFile()
        val store = PinnedAgentStore(dir)
        val old = System.currentTimeMillis() - (PinnedAgentStore.EXPIRE_DAYS + 1) * 86_400_000L
        store.put(PinnedAgent("old", "Old", "Stale know-how", pinnedAt = old, lastUsedAt = old))
        store.put(PinnedAgent("new", "New", "Fresh know-how"))
        assertEquals(listOf("Old"), store.prune().map { it.name })
        assertEquals(listOf("New"), PinnedAgentStore(dir).agents.value.map { it.name })

        val big = "x".repeat(20_000)
        val h = mutableListOf(Msg.user("First brief"))
        repeat(30) { i ->
            h += Msg(Role.ASSISTANT, listOf(Block.Text("step $i"), Block.ToolCall("c$i", "web_fetch", JSONObject().put("url", "u"))))
            h += Msg(Role.USER, listOf(Block.ToolResult("c$i", big)))
            if (i % 10 == 9) { h += Msg(Role.ASSISTANT, listOf(Block.Text("job done $i"))); h += Msg.user("next job $i") }
        }
        h += Msg(Role.ASSISTANT, listOf(Block.Text("all done")))
        val c = PinnedAgentStore.compact(h)
        assertTrue(c.sumOf { Trim.size(it) } <= PinnedAgentStore.BUDGET)
        assertEquals(Role.USER, c.first().role)
        assertFalse("never starts on orphaned tool results", c.first().blocks.any { it is Block.ToolResult })
        assertTrue(c.first().text.contains("[Your earlier work, condensed") && c.first().text.contains("First brief"))
        assertEquals(c, AgentRuntime.Repair.repair(c)) // tool calls still pair up
        assertEquals("all done", c.last().text)
        store.saveHistory("new", h)
        assertEquals(c.size, store.loadHistory("new").size)
    }
}
