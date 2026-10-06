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
    override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
        synchronized(seen) { seen += system to messages.toList() }
        if (system.contains("You are a HELPER")) {
            synchronized(helperTools) { helperTools += tools.map { it.name } }
            delay(helperDelay); return Completion(helperQueue.poll() ?: helperReply(messages.first().text), "end_turn")
        }
        return Completion(queue.poll() ?: Msg(Role.ASSISTANT, listOf(Block.Text("(no more script)"))), "end_turn")
    }
}

fun call(name: String, input: JSONObject, id: String = "c" + System.nanoTime()) = Msg(Role.ASSISTANT, listOf(Block.ToolCall(id, name, input)))
fun say(text: String) = Msg(Role.ASSISTANT, listOf(Block.Text(text)))
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

    @Test fun theMainAgentOnlyOrchestrates() {
        val rt = runtime(ScriptedProvider())
        val main = rt.tools().map { it.spec.name }.toSet()
        val worker = rt.tools(forHelper = true).map { it.spec.name }.toSet()
        // The main agent understands and plans; it has nothing that acts on the world.
        assertTrue(main.containsAll(listOf("delegate", "helpers_status", "helper_stop", "memory_save", "memory_update", "goal_create", "task_add", "ask_user")))
        listOf("web_fetch", "run_code", "browser_open", "routine_create", "routine_delete", "request_approval", "phone_files", "location_get", "watcher_save")
            .forEach { assertFalse("main agent must not have $it", it in main) }
        // Helpers do the work, and can't hand it on again.
        assertTrue(worker.containsAll(listOf("web_fetch", "run_code", "browser_open", "browser_handoff", "routine_create", "request_approval", "ask_user")))
        assertFalse("delegate" in worker)
    }

    @Test fun mainAgentPromptSaysDelegateNeverDo() = runBlocking {
        val rt = runtime(ScriptedProvider())
        val sys = rt.systemPrompt("book me an uber")
        assertTrue(sys.contains("You NEVER do the work yourself"))
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
