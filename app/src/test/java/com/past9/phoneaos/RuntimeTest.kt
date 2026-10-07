package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.*
import com.past9.phoneaos.data.AppDb
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
import java.util.concurrent.ConcurrentLinkedQueue

/** A scripted model: each call pops the next reply; it records what it was sent. */
class ScriptedProvider(vararg replies: Msg) : LlmProvider {
    override val name = "scripted"
    val queue = ConcurrentLinkedQueue(replies.toList())
    val seen = mutableListOf<Pair<String, List<Msg>>>()
    var helperReply: (String) -> Msg = { Msg(Role.ASSISTANT, listOf(Block.Text("helper found: $it"))) }
    /** Scripted steps for a single helper (popped in order); empty = helperReply. */
    val helperQueue = ConcurrentLinkedQueue<Msg>()
    @Volatile var helperDelay = 150L
    val helperTools = mutableListOf<List<String>>()
    val helperModels = java.util.Collections.synchronizedList(mutableListOf<String>())
    override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
        synchronized(seen) { seen += system to messages.toList() }
        if (system.contains("You are a HELPER")) {
            synchronized(helperTools) { helperTools += tools.map { it.name } }
            helperModels += model
            delay(helperDelay); return Completion(helperQueue.poll() ?: helperReply(messages.first().text), "end_turn")
        }
        return Completion(queue.poll() ?: Msg(Role.ASSISTANT, listOf(Block.Text("(no more script)"))), "end_turn")
    }
}

fun call(name: String, input: JSONObject, id: String = "c" + System.nanoTime()) = Msg(Role.ASSISTANT, listOf(Block.ToolCall(id, name, input)))
fun say(text: String) = Msg(Role.ASSISTANT, listOf(Block.Text(text)))
val quiet = object : ToolContext {
    override val agentLabel = "main"
    override suspend fun activity(text: String, meta: JSONObject) = 0L
    override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
    override suspend fun ask(question: String, options: List<String>): String? = null
    override suspend fun notify(title: String, body: String) {}
}
fun delegate(vararg briefs: String) = call("delegate", JSONObject().put("tasks", JSONArray(briefs.toList())))
private fun List<Pair<String, List<Msg>>>.main() = filter { !it.first.contains("You are a HELPER") }
private fun List<Pair<String, List<Msg>>>.helper() = filter { it.first.contains("You are a HELPER") }

class FakePhone : PhoneBridge {
    val notes = mutableListOf<String>(); val links = mutableListOf<String>()
    override fun notify(title: String, body: String, questionItemId: Long?, options: List<String>) { notes += "$title|$body" }
    override fun openLink(url: String) { links += url }
    override fun workStarted() {}
    override fun workFinished() {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeTest {
    private lateinit var db: AppDb
    private lateinit var settings: SettingsStore
    private val phone = FakePhone()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDb.inMemory(ctx); settings = SettingsStore(ctx)
        settings.setMode(PowerMode.API_KEY); settings.setProvider(Provider.ANTHROPIC); settings.setApiKey(Provider.ANTHROPIC, "sk-test"); settings.setUserName("Sam")
    }

    private fun runtime(p: ScriptedProvider) = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { p })

    private suspend fun waitIdle(rt: AgentRuntime) { withTimeout(15_000) { rt.awaitIdle() } }

    @Test fun remembersAndAnswers() = runBlocking {
        val p = ScriptedProvider(
            call("memory_save", JSONObject().put("title", "Seat preference").put("body", "Sam prefers window seats").put("kind", "preference")),
            say("Got it, window seats from now on."))
        val rt = runtime(p)
        rt.send("Remember I like window seats")
        waitIdle(rt)
        val mem = db.memory().list()
        assertEquals("Seat preference", mem.single().title)
        val kinds = db.chat().all().first().map { it.kind }
        assertEquals(listOf("user", "activity", "agent"), kinds)
        // The second call saw the tool result, and the system prompt knows his name and the time.
        val (system, msgs) = p.seen[1]
        assertTrue(system.contains("The user's name is Sam"))
        assertTrue(msgs.last().blocks.single() is Block.ToolResult)

        // Next turn: the saved memory is recalled into the system prompt by relevance.
        p.queue.add(say("Window seat it is."))
        rt.send("Book me a flight seat please")
        waitIdle(rt)
        assertTrue(p.seen.last().first.contains("prefers window seats"))
        // And the transcript carried over (provider sees the earlier exchange).
        assertTrue(p.seen.last().second.size >= 4)
    }

    @Test fun theMainAgentOrchestratesAndOnlyDoesSmallJobs() {
        val rt = runtime(ScriptedProvider())
        val main = rt.tools().map { it.spec.name }.toSet()
        val worker = rt.tools(forHelper = true).map { it.spec.name }.toSet()
        assertTrue(main.containsAll(listOf("delegate", "helper_steer", "helper_push", "helpers_status", "helper_stop", "memory_save", "memory_update", "goal_create", "task_add", "ask_user")))
        // Small jobs it may do itself (budgeted); routines, watchers and skills stay with helpers.
        assertTrue(main.containsAll(listOf("browser_open", "web_fetch")))
        listOf("routine_create", "routine_delete", "watcher_save", "skill_save").forEach { assertFalse("main agent must not have $it", it in main) }
        assertTrue(worker.containsAll(listOf("web_fetch", "run_code", "browser_open", "browser_handoff", "routine_create", "request_approval", "ask_user", "skill_save", "skill_find")))
        assertFalse("delegate" in worker)
    }

    @Test fun theMainAgentGetsFiveActionStepsAThenMustDelegate() = runBlocking {
        val now = { call("phone_files", JSONObject().put("query", "*.pdf")) }
        val p = ScriptedProvider(now(), now(), now(), now(), now(), now(), say("Handing it over."))
        val rt = runtime(p); rt.send("where am I, over and over"); waitIdle(rt)
        val results = p.seen.main().drop(1).map { (it.second.last().blocks.single() as Block.ToolResult).content }
        assertEquals(6, results.size)
        assertTrue(results.take(5).none { it.startsWith("Step limit") })
        assertTrue(results[5].startsWith("Step limit"))
        // The next turn gets a fresh budget.
        p.queue.add(now()); p.queue.add(say("ok")); rt.send("again"); waitIdle(rt)
        assertFalse((p.seen.main().last().second.last().blocks.single() as Block.ToolResult).content.startsWith("Step limit"))
    }

    @Test fun aWorkingHelperCanBeSteeredWithoutStopping() = runBlocking {
        val p = ScriptedProvider(delegate("Book the movie at 9pm"), say("On it."), say("Booked the 7pm."))
        p.helperDelay = 700
        p.helperQueue += call("now", JSONObject()); p.helperQueue += say("Booked the 7pm show.")
        val rt = runtime(p)
        rt.send("book the movie")
        withTimeout(3000) { while (rt.running.value.isEmpty()) delay(20) }
        val id = rt.running.value.single().id
        val steer = rt.tools().first { it.spec.name == "helper_steer" }
        val out = steer.run(JSONObject().put("id", id).put("message", "The user means the 7pm show, not 9pm."), quiet)
        assertTrue(out.contains("keeps going"))
        waitIdle(rt)
        val h = p.seen.helper()
        assertEquals(2, h.size) // never restarted
        // It reaches the helper before whichever step comes next (the first one, if it lands that early).
        assertTrue(h.last().second.any { it.text.contains("[The main agent, while you work] The user means the 7pm show") })
        assertEquals("done", JSONObject(db.chat().get(id)!!.meta).getString("state"))
    }

    @Test fun aHelperGoingTheWrongWayCanBeRestartedWithNewInformation() = runBlocking {
        val p = ScriptedProvider(delegate("Find flights to Cape Town"), say("On it."), say("Here you go."))
        p.helperDelay = 3000
        val rt = runtime(p)
        rt.send("flights")
        withTimeout(3000) { while (rt.running.value.isEmpty()) delay(20) }
        val id = rt.running.value.single().id
        p.helperDelay = 50
        val out = rt.tools().first { it.spec.name == "helper_steer" }.run(JSONObject().put("id", id).put("message", "Actually Durban, not Cape Town.").put("restart", true), quiet)
        assertTrue(out.contains("Stopped helper"))
        waitIdle(rt)
        val last = p.seen.helper().last().second
        assertEquals("Find flights to Cape Town", last.first().text)
        assertTrue(last.last().text.contains("Actually Durban"))
        val meta = JSONObject(db.chat().get(id)!!.meta)
        assertEquals("done", meta.getString("state")); assertEquals(1, meta.getInt("pushes"))
    }

    @Test fun helpersLeaveSkillsForTheNextHelper() = runBlocking {
        val p = ScriptedProvider(delegate("Get the Takealot price of a Kindle"), say("On it."), say("R 2,499."))
        p.helperQueue += call("skill_save", JSONObject().put("name", "Takealot prices").put("when", "Reading a price on takealot.com")
            .put("steps", "1. Open the product page in the browser, not web_fetch (prices load by script).\n2. Read the price under the title."))
        p.helperQueue += say("R 2,499 (takealot.com).")
        val rt = runtime(p); rt.send("kindle price?"); waitIdle(rt)
        assertEquals("skill", db.memory().list().single { it.title == "Takealot prices" }.kind)
        // The next helper on a Takealot job gets it in its instructions; the main agent's memory recall leaves skills out.
        assertTrue(rt.systemPrompt("check a takealot price", role = PromptRole.HELPER).contains("SKILLS EARLIER HELPERS LEFT"))
        assertFalse(rt.systemPrompt("check a takealot price").contains("SKILLS EARLIER HELPERS LEFT"))
    }

    @Test fun mainAgentPromptSaysDelegateNeverDo() = runBlocking {
        val rt = runtime(ScriptedProvider())
        val sys = rt.systemPrompt("book me an uber")
        assertTrue(sys.contains("hard limit of 5 action steps"))
        assertTrue(sys.contains("ASSUME YOU ALREADY KNOW"))
        assertFalse(sys.contains("You are a HELPER"))
        assertTrue(rt.systemPrompt("x", role = PromptRole.HELPER).contains("You are a HELPER"))
    }

    @Test fun approvalInAHelperBlocksUntilTheUserAnswers() = runBlocking {
        val p = ScriptedProvider(delegate("Email Lerato: see you at 6 on Friday"), say("Sent a helper."), say("Done, Lerato has it."))
        p.helperQueue += call("request_approval", JSONObject().put("action", "Send email to Lerato").put("details", "Subject: Friday\nSee you at 6."))
        p.helperQueue += say("Email sent to Lerato.")
        val rt = runtime(p)
        rt.send("Email Lerato that I'll see her at 6")
        withTimeout(5000) { while (db.chat().all().first().none { it.kind == "question" }) delay(50) }
        withTimeout(2000) { while (phone.notes.none { it.startsWith("Approve?|Send email to Lerato") }) delay(20) }
        val q = db.chat().all().first().first { it.kind == "question" }
        assertTrue(q.text.startsWith("APPROVAL|Send email to Lerato|"))
        assertTrue("the question belongs to the helper", JSONObject(q.meta).has("helper"))
        assertEquals(1, p.seen.helper().size) // the helper has NOT been called again while it waits
        rt.answer(q.id, "Approve")
        waitIdle(rt)
        val result = p.seen.helper().last().second.last().blocks.single() as Block.ToolResult
        assertTrue(result.content.startsWith("APPROVED"))
        // The helper's result went back to the main agent, which told the user.
        assertTrue(p.seen.main().last().second.last().text.contains("[Helper #"))
        assertEquals("Done, Lerato has it.", db.chat().all().first().last { it.kind == "agent" }.text)
    }

    @Test fun typingWhileAHelperAsksTalksToTheMainAgent() = runBlocking {
        val p = ScriptedProvider(delegate("Book a table"), say("On it."), say("Hello!"))
        p.helperQueue += call("ask_user", JSONObject().put("question", "Which time?").put("options", JSONArray(listOf("7pm", "8pm"))))
        p.helperQueue += say("Booked for 7pm.")
        val rt = runtime(p)
        rt.send("Book dinner")
        withTimeout(5000) { while (db.chat().all().first().none { it.kind == "question" }) delay(50) }
        rt.send("hi there")
        withTimeout(5000) { while (db.chat().all().first().none { it.kind == "agent" && it.text == "Hello!" }) delay(50) }
        val q = db.chat().all().first().first { it.kind == "question" }
        assertFalse("typing must not answer a helper's question", JSONObject(db.chat().get(q.id)!!.meta).has("answer"))
        rt.answer(q.id, "7pm"); waitIdle(rt)
    }

    @Test fun helpersRunInTheBackgroundAndReportBack() = runBlocking {
        val p = ScriptedProvider(delegate("price of A", "price of B", "price of C"), say("Three helpers are on it."))
        val rt = runtime(p)
        val t0 = System.currentTimeMillis()
        rt.send("Compare A, B and C")
        waitIdle(rt)
        val elapsed = System.currentTimeMillis() - t0
        val helpers = db.chat().all().first().filter { it.kind == "helper" }
        assertEquals(3, helpers.size)
        assertTrue(helpers.all { JSONObject(it.meta).getString("state") == "done" })
        // The delegate call returned at once (no results in it)...
        val delegateResult = (p.seen.main()[1].second.last().blocks.single() as Block.ToolResult).content
        assertTrue(delegateResult.startsWith("Started:"))
        assertFalse(delegateResult.contains("helper found"))
        // ...and every result came back to the main agent as a message of its own.
        val back = p.seen.main().flatMap { it.second }.filter { it.role == Role.USER }.joinToString("\n") { it.text }
        assertTrue(back.contains("helper found: price of A") && back.contains("helper found: price of B") && back.contains("helper found: price of C"))
        assertTrue("helpers should overlap, took ${elapsed}ms", elapsed < 2500)
        assertEquals(0, rt.status.value.helpers)
    }

    @Test fun theUserCanTalkWhileHelpersWork() = runBlocking {
        val p = ScriptedProvider(delegate("Research flights"), say("A helper is on it."), say("Sure, noted."))
        p.helperDelay = 2500
        val rt = runtime(p)
        rt.send("Find me flights")
        withTimeout(3000) { while (rt.status.value.helpers == 0) delay(20) }
        withTimeout(3000) { while (db.chat().all().first().none { it.text == "A helper is on it." }) delay(20) }
        val t0 = System.currentTimeMillis()
        rt.send("Also I prefer aisle seats")
        withTimeout(2000) { while (db.chat().all().first().none { it.text == "Sure, noted." }) delay(20) }
        assertTrue("answered while the helper was still working", System.currentTimeMillis() - t0 < 2000)
        assertEquals("working", JSONObject(db.chat().all().first().first { it.kind == "helper" }.meta).getString("state"))
        waitIdle(rt)
    }

    @Test fun aStoppedHelperDoesNotReportBack() = runBlocking {
        val p = ScriptedProvider(delegate("Slow job"), say("On it."))
        p.helperDelay = 10_000
        val rt = runtime(p)
        rt.send("do the slow job")
        withTimeout(3000) { while (rt.running.value.isEmpty()) delay(20) }
        val id = rt.running.value.single().id
        assertTrue(rt.stopHelper(id))
        waitIdle(rt)
        assertEquals("stopped", JSONObject(db.chat().get(id)!!.meta).getString("state"))
        assertTrue(p.seen.main().none { m -> m.second.any { it.text.contains("[Helper #") } })
        assertEquals(0, rt.status.value.helpers)
    }

    @Test fun briefsGoToTheTaggedHelperModel() = runBlocking {
        settings.setHelperRoster(listOf(com.past9.phoneaos.data.HelperModel("big-model", "Big", listOf("Hard problems")),
            com.past9.phoneaos.data.HelperModel("small-model", "Small", listOf("Quick lookups", "Web research"))))
        val p = ScriptedProvider(call("delegate", JSONObject().put("tasks", JSONArray(listOf("Weather in Durban", "Plan the move"))).put("models", JSONArray(listOf("Small", "big-model")))), say("Two helpers on it."))
        val rt = runtime(p)
        val sys = rt.systemPrompt("weather")
        assertTrue(sys.contains("YOUR HELPERS") && sys.contains("Small (id: small-model): good for Quick lookups, Web research"))
        rt.send("weather and the move"); waitIdle(rt)
        assertEquals(setOf("small-model", "big-model"), p.helperModels.toSet())
        // No model named: the first on the roster.
        p.queue.add(delegate("Anything")); p.queue.add(say("ok"))
        p.helperModels.clear(); rt.send("one more"); waitIdle(rt)
        assertEquals(listOf("big-model"), p.helperModels.toList())
    }

    @Test fun aHelperThatGivesUpIsPushedBackAndKeepsItsWork() = runBlocking {
        val p = ScriptedProvider(delegate("Find the opening hours of the Durban library"), say("On it."), say("Sending it back."), say("Open 8 to 5."))
        p.helperQueue += say("I can't access that site.")
        p.helperQueue += say("Found it: open 8:00 to 17:00 weekdays (durban.gov.za).")
        val rt = runtime(p)
        rt.send("library hours?"); waitIdle(rt)
        val back = p.seen.main().last().second.last().text
        assertTrue(back.contains("helper_push") && back.contains("believe it can do it"))
        val id = db.chat().all().first().first { it.kind == "helper" }.id
        val out = rt.tools().first { it.spec.name == "helper_push" }.run(JSONObject().put("id", id).put("message", "Try the city's own site. You can do this."), object : ToolContext {
            override val agentLabel = "main"
            override suspend fun activity(text: String, meta: JSONObject) = 0L
            override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
            override suspend fun ask(question: String, options: List<String>): String? = null
            override suspend fun notify(title: String, body: String) {}
        })
        assertTrue(out.contains("back on it"))
        waitIdle(rt)
        // It carried on in its own conversation: the first brief, its "can't", then the push.
        val h = p.seen.helper().last().second
        assertEquals("Find the opening hours of the Durban library", h.first().text)
        assertTrue(h.last().text.contains("Try the city's own site"))
        val meta = JSONObject(db.chat().get(id)!!.meta)
        assertEquals(1, meta.getInt("pushes")); assertEquals("done", meta.getString("state"))
        assertTrue(p.seen.main().last().second.last().text.contains("after 1 push"))
    }

    @Test fun aGoodResultThatMentionsAProblemIsNotPushedBack() = runBlocking {
        val p = ScriptedProvider(delegate("Find the Durban library hours"), say("On it."), say("Open 8 to 5."))
        p.helperQueue += say("Open 08:00 to 17:00 weekdays (durban.gov.za). The first site I tried was blocked, and I couldn't load the old page, so I used the city's own site.")
        val rt = runtime(p); rt.send("hours?"); waitIdle(rt)
        assertFalse(p.seen.main().last().second.last().text.contains("helper_push"))
    }

    @Test fun aHelperResultDoesNotRefillTheMainAgentsStepBudget() = runBlocking {
        val act = { call("phone_files", JSONObject().put("query", "*.pdf")) }
        // 4 steps on the user's message, then a helper result arrives: only 1 step is left for that turn.
        val p = ScriptedProvider(delegate("x"), act(), act(), act(), act(), say("Working on it."), act(), act(), say("Done."))
        val rt = runtime(p); rt.send("go"); waitIdle(rt)
        val results = p.seen.main().map { it.second.last().blocks.lastOrNull() }.filterIsInstance<Block.ToolResult>().filter { !it.content.startsWith("Started:") }.map { it.content }
        assertEquals(6, results.size)
        assertTrue(results.take(5).none { it.startsWith("Step limit") })
        assertTrue(results[5].startsWith("Step limit"))
    }

    @Test fun goalsAndTasksAutoAchieve() = runBlocking {
        val p = ScriptedProvider(
            call("goal_create", JSONObject().put("title", "Plan Durban trip").put("tasks", JSONArray(listOf("Book flights", "Book hotel")))),
            say("Planned."))
        val rt = runtime(p); rt.send("Plan my Durban trip"); waitIdle(rt)
        val tasks = db.tasks().openTasks(); assertEquals(2, tasks.size)
        p.queue.add(call("task_update", JSONObject().put("id", tasks[0].id).put("status", "done")))
        p.queue.add(call("task_update", JSONObject().put("id", tasks[1].id).put("status", "done")))
        p.queue.add(say("All done."))
        rt.send("Both booked"); waitIdle(rt)
        assertEquals("achieved", db.tasks().goal(tasks[0].goalId!!)!!.status)
    }

    @Test fun aHelperCreatesRoutinesAndTheyValidate() = runBlocking {
        val p = ScriptedProvider(delegate("Create a daily routine at 07:00 named Morning brief that runs: Brief me"), say("Setting it up."), say("Every morning at 7."))
        p.helperQueue += call("routine_create", JSONObject().put("name", "Bad").put("kind", "daily").put("spec", "7am").put("prompt", "x"))
        p.helperQueue += call("routine_create", JSONObject().put("name", "Morning brief").put("kind", "daily").put("spec", "07:00").put("prompt", "Brief me"))
        p.helperQueue += say("Created Morning brief, daily 07:00.")
        val scheduled = mutableListOf<String>()
        val rt = runtime(p).apply { onRoutineChanged = { scheduled += it!!.name } }
        rt.send("Brief me every morning at 7"); waitIdle(rt)
        val firstResult = (p.seen.helper()[1].second.last().blocks.single() as Block.ToolResult).content
        assertTrue(firstResult.startsWith("Not created"))
        assertEquals(listOf("Morning brief"), db.triggers().list().map { it.name })
        assertEquals(listOf("Morning brief"), scheduled)
    }

    @Test fun backgroundRunPostsSummaryAndNeverWaits() = runBlocking {
        val p = ScriptedProvider(call("ask_user", JSONObject().put("question", "Which?").put("options", JSONArray(listOf("A", "B")))), say("Nothing new today."))
        val rt = runtime(p)
        val out = rt.runBackground("Morning brief", "Brief me")
        assertEquals("Nothing new today.", out)
        val res = (p.seen.last().second.last().blocks.single() as Block.ToolResult).content
        assertTrue(res.contains("background run"))
        assertEquals("Morning brief", JSONObject(db.chat().all().first().last().meta).getString("routine"))
        assertTrue(phone.notes.any { it.startsWith("Morning brief|Nothing new today.") })
    }

    @Test fun badKeyShowsAFriendlyError() = runBlocking {
        val failing = object : LlmProvider {
            override val name = "x"
            override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion = throw ProviderException("HTTP 401", 401)
        }
        val rt = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { failing })
        rt.send("hi"); waitIdle(rt)
        assertEquals("Your API key was rejected. Check it in Settings.", db.chat().all().first().last().text)
    }
}
