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
    override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
        synchronized(seen) { seen += system to messages.toList() }
        if (system.contains("You are a HELPER")) { delay(150); return Completion(helperReply(messages.first().text), "end_turn") }
        return Completion(queue.poll() ?: Msg(Role.ASSISTANT, listOf(Block.Text("(no more script)"))), "end_turn")
    }
}

fun call(name: String, input: JSONObject, id: String = "c" + System.nanoTime()) = Msg(Role.ASSISTANT, listOf(Block.ToolCall(id, name, input)))
fun say(text: String) = Msg(Role.ASSISTANT, listOf(Block.Text(text)))

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

    @Test fun approvalBlocksUntilTheUserAnswers() = runBlocking {
        val p = ScriptedProvider(
            call("request_approval", JSONObject().put("action", "Send email to Lerato").put("details", "Subject: Friday\nSee you at 6.")),
            say("Sent."))
        val rt = runtime(p)
        rt.send("Email Lerato that I'll see her at 6")
        withTimeout(5000) { while (db.chat().all().first().none { it.kind == "question" }) delay(50) }
        assertEquals("Waiting for you", rt.status.value.label)
        // The notification is posted just after the question row; give it a moment.
        withTimeout(2000) { while (phone.notes.none { it.startsWith("Approve?|Send email to Lerato") }) delay(20) }
        val q = db.chat().all().first().first { it.kind == "question" }
        assertTrue(q.text.startsWith("APPROVAL|Send email to Lerato|"))
        assertEquals(1, p.seen.size) // the model has NOT been called again while we wait
        rt.answer(q.id, "Approve")
        waitIdle(rt)
        val result = p.seen.last().second.last().blocks.single() as Block.ToolResult
        assertTrue(result.content.startsWith("APPROVED"))
        assertEquals("Approve", JSONObject(db.chat().get(q.id)!!.meta).getString("answer"))
    }

    @Test fun helpersRunInParallelAndReportBack() = runBlocking {
        val p = ScriptedProvider(
            call("delegate", JSONObject().put("tasks", JSONArray(listOf("price of A", "price of B", "price of C")))),
            say("Here are all three."))
        val rt = runtime(p)
        val t0 = System.currentTimeMillis()
        rt.send("Compare A, B and C")
        waitIdle(rt)
        val elapsed = System.currentTimeMillis() - t0
        val helpers = db.chat().all().first().filter { it.kind == "helper" }
        assertEquals(3, helpers.size)
        assertTrue(helpers.all { JSONObject(it.meta).getString("state") == "done" })
        val result = (p.seen.last { !it.first.contains("HELPER") }.second.last().blocks.single() as Block.ToolResult).content
        assertTrue(result.contains("helper found: price of A") && result.contains("helper found: price of C"))
        assertTrue("helpers should overlap, took ${elapsed}ms", elapsed < 1500)
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

    @Test fun routinesValidateTheirSchedule() = runBlocking {
        val p = ScriptedProvider(
            call("routine_create", JSONObject().put("name", "Bad").put("kind", "daily").put("spec", "7am").put("prompt", "x")),
            call("routine_create", JSONObject().put("name", "Morning brief").put("kind", "daily").put("spec", "07:00").put("prompt", "Brief me")),
            say("Every morning at 7."))
        val scheduled = mutableListOf<String>()
        val rt = runtime(p).apply { onRoutineChanged = { scheduled += it!!.name } }
        rt.send("Brief me every morning at 7"); waitIdle(rt)
        val firstResult = (p.seen[1].second.last().blocks.single() as Block.ToolResult).content
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
