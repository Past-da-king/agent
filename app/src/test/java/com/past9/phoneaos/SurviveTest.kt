package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.*
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.data.TriggerRow
import com.past9.phoneaos.triggers.RunEntry
import com.past9.phoneaos.triggers.RunLedger
import com.past9.phoneaos.triggers.RunStore
import com.past9.phoneaos.triggers.Routines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

class MemStore : RunStore {
    val m = mutableMapOf<Long, RunEntry>()
    override fun get(id: Long) = m[id]
    override fun put(id: Long, e: RunEntry) { m[id] = e }
}

/** The app dying mid-job, and what happens next: checkpoints, resume, isolation, and routines that run once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SurviveTest {
    private lateinit var db: AppDb
    private lateinit var settings: SettingsStore
    private val phone = FakePhone()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDb.inMemory(ctx); settings = SettingsStore(ctx)
        settings.setMode(PowerMode.API_KEY); settings.setProvider(Provider.ANTHROPIC); settings.setApiKey(Provider.ANTHROPIC, "sk-test"); settings.setUserName("Sam")
    }

    private fun runtime(p: LlmProvider) = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { p })

    private suspend fun helperRow(brief: String, meta: JSONObject = JSONObject()) =
        db.chat().insert(ChatItem(kind = "helper", text = brief, meta = meta.put("label", "Scout").put("state", "working").put("startedAt", 1L).put("model", "").toString()))

    private suspend fun waitState(id: Long, vararg states: String) = withTimeout(15_000) { while (JSONObject(db.chat().get(id)!!.meta).optString("state") !in states) delay(50) }

    // ---- checkpoints ------------------------------------------------------------------------

    @Test fun aSavedTranscriptComesBackInOrder() = runBlocking {
        val msgs = listOf(Msg.user("brief"), call("now", JSONObject(), "c1"), Msg(Role.USER, listOf(Block.ToolResult("c1", "10:00"))), say("noted"))
        msgs.forEach { HelperCheckpoint.append(db, 7, it) }
        HelperCheckpoint.append(db, 8, Msg.user("someone else"))
        val back = HelperCheckpoint.load(db, 7)
        assertEquals(4, back.size); assertEquals("brief", back.first().text); assertEquals("noted", back.last().text)
        HelperCheckpoint.clear(db, 7)
        assertTrue(HelperCheckpoint.load(db, 7).isEmpty()); assertEquals(1, HelperCheckpoint.load(db, 8).size)
    }

    @Test fun aLastStepWithNoResultIsMarkedMayOrMayNotHaveRun() {
        val saved = listOf(Msg.user("book it"), Msg(Role.ASSISTANT, listOf(Block.Text("on it"), Block.ToolCall("c9", "machine_run", JSONObject().put("cmd", "long job")))))
        val h = HelperCheckpoint.resumeHistory(saved, "book it", "")!!
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), h.map { it.role }.take(3).let { listOf(it[0], it[1], Role.USER) })
        // The call gets a result saying it may or may not have run, and the restart note follows in the same user turn: roles still alternate.
        val last = h.last()
        assertEquals(Role.USER, last.role)
        val res = last.blocks.filterIsInstance<Block.ToolResult>().single()
        assertEquals("c9", res.callId); assertTrue(res.content.contains("may or may not"))
        assertTrue(last.text.contains("Resumed after the app restarted")); assertTrue(last.text.contains("machine_run"))
        assertTrue(last.text.contains("jobs running on machines"))
        assertTrue(h.zipWithNext().none { (a, b) -> a.role == b.role })
    }

    @Test fun nothingSavedMeansNoCheckpointToResumeFrom() {
        assertNull(HelperCheckpoint.resumeHistory(emptyList(), "x", ""))
    }

    // ---- resume after the app died ----------------------------------------------------------

    @Test fun anApiHelperPicksUpFromItsLastCheckpointWithoutRepeatingASideEffect() = runBlocking {
        val id = helperRow("Create the report task", JSONObject().put("steps", 2))
        // It had said something, then called task_add; the app died before the result was recorded.
        listOf(Msg.user("Create the report task"), Msg(Role.ASSISTANT, listOf(Block.Text("Creating it."), Block.ToolCall("t1", "task_add", JSONObject().put("title", "Report"))))).forEach { HelperCheckpoint.append(db, id, it) }
        val p = ScriptedProvider()
        val rt = runtime(p)
        waitState(id, "done")
        val sent = synchronized(p.seen) { p.seen.filter { it.first.contains("You are a HELPER") }.first().second }
        // It saw its own earlier words and call, a result saying the call may or may not have run, and the restart note.
        assertEquals("Create the report task", sent.first().text)
        assertTrue(sent.any { m -> m.blocks.any { it is Block.ToolCall && it.id == "t1" } })
        assertTrue(sent.last().blocks.filterIsInstance<Block.ToolResult>().single().content.contains("may or may not"))
        assertTrue(sent.last().text.contains("Resumed after the app restarted"))
        // The call was NOT run again by the restart.
        assertTrue(db.tasks().allTasks().isEmpty())
        // Done: nothing left to resume from, and the work is marked.
        assertTrue(HelperCheckpoint.load(db, id).isEmpty())
        assertEquals(1, JSONObject(db.chat().get(id)!!.meta).optInt("resumes"))
        rt.awaitIdle()
    }

    @Test fun theMainAgentIsToldWhichHelpersWereResumed() = runBlocking {
        val id = helperRow("Check the stock level")
        HelperCheckpoint.append(db, id, Msg.user("Check the stock level"))
        val p = ScriptedProvider(say("Noted."))
        val rt = runtime(p)
        waitState(id, "done"); rt.awaitIdle()
        val toMain = synchronized(p.seen) { p.seen.filter { !it.first.contains("You are a HELPER") }.flatMap { it.second }.joinToString("\n") { m -> m.text } }
        assertTrue(toMain, toMain.contains("The app restarted") && toMain.contains("#$id") && toMain.contains("Scout"))
        assertTrue(db.chat().all().first().any { it.kind == "notice" && it.text.contains("picked up where it left off") })
    }

    @Test fun aHelperThatKeepsMakingProgressIsNotGivenUpOn() = runBlocking {
        // Resumed twice already, but it did 5 more steps since the last pick-up: it is not crash-looping.
        val id = helperRow("Long job", JSONObject().put("resumes", AgentRuntime.MAX_RESUMES).put("stepsAtResume", 3).put("steps", 8))
        val rt = runtime(ScriptedProvider())
        waitState(id, "done", "failed")
        assertEquals("done", JSONObject(db.chat().get(id)!!.meta).optString("state"))
        rt.awaitIdle()
    }

    @Test fun aHelperThatMadeNoProgressStillStopsAtTheCap() = runBlocking {
        val id = helperRow("Loops", JSONObject().put("resumes", AgentRuntime.MAX_RESUMES).put("stepsAtResume", 8).put("steps", 8))
        val rt = runtime(ScriptedProvider())
        waitState(id, "failed")
        assertTrue(JSONObject(db.chat().get(id)!!.meta).optString("result").contains("2 tries"))
        rt.awaitIdle()
    }

    @Test fun withNoAiSetUpACutOffHelperWaitsInsteadOfFailing() = runBlocking {
        settings.setApiKey(Provider.ANTHROPIC, "")
        val id = helperRow("Wait for a key")
        val rt = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { null })
        waitState(id, "interrupted")
        // A later start (or the safety-net check) with an AI available takes it up again.
        val p = ScriptedProvider()
        val rt2 = runtime(p)
        waitState(id, "done")
        rt.awaitIdle(); rt2.awaitIdle()
    }

    // ---- isolation --------------------------------------------------------------------------

    @Test fun anErrorInsideAToolFailsThatCallNotTheLoop() = runBlocking {
        val boom = object : Tool {
            override val spec = ToolSpec("boom", "x", schema())
            override suspend fun run(input: JSONObject, ctx: ToolContext): String = throw NoSuchMethodError("gone")
        }
        val p = ScriptedProvider()
        val out = AgentLoop(object : LlmProvider {
            var n = 0
            override val name = "t"
            override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int) =
                Completion(if (n++ == 0) call("boom", JSONObject(), "b1") else say("recovered: " + messages.last().blocks.filterIsInstance<Block.ToolResult>().single().content), "end_turn")
        }, "m", listOf(boom)).run("sys", mutableListOf(Msg.user("go")), quiet)
        assertTrue(out, out.startsWith("recovered: Error: the tool crashed"))
    }

    @Test fun oneHelperDyingOfAnErrorDoesNotStopTheOthers() = runBlocking {
        val provider = object : LlmProvider {
            override val name = "t"
            val main = java.util.concurrent.ConcurrentLinkedQueue(listOf(delegate("bomb job", "fine job"), say("done")))
            override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
                if (system.contains("You are a HELPER")) {
                    delay(100)
                    if (messages.first().text.contains("bomb")) throw StackOverflowError("deep")
                    return Completion(say("finished fine"), "end_turn")
                }
                return Completion(main.poll() ?: say("ok"), "end_turn")
            }
        }
        val rt = runtime(provider)
        rt.send("do both")
        withTimeout(20_000) { rt.awaitIdle() }
        val hs = db.chat().all().first().filter { it.kind == "helper" }
        assertEquals(2, hs.size)
        val states = hs.associate { it.text to JSONObject(it.meta).optString("state") }
        assertEquals("failed", states["bomb job"]); assertEquals("done", states["fine job"])
        assertTrue(JSONObject(hs.first { it.text == "bomb job" }.meta).optString("result").contains("internal error"))
    }

    @Test fun onlyTheUiThreadIsAllowedToCrashTheApp() {
        val ui = Thread {}
        assertFalse(CrashGuard.contain(ui, ui))
        assertTrue(CrashGuard.contain(Thread {}, ui))
    }

    // ---- routines: once per slot, catch-up --------------------------------------------------

    @Test fun theSameSlotNeverRunsTwiceNoMatterWhoStartsIt() {
        val s = MemStore(); val slot = 1_000_000L
        // The queued job, the catch-up after a restart and a second catch-up all try the same slot.
        val verdicts = listOf(RunLedger.claim(s, 1, slot, now = slot + 1), RunLedger.claim(s, 1, slot, now = slot + 2), RunLedger.claim(s, 1, slot, now = slot + 3))
        assertEquals(listOf(RunLedger.Verdict.RUN, RunLedger.Verdict.SKIP, RunLedger.Verdict.SKIP), verdicts)
        RunLedger.done(s, 1, slot)
        assertEquals(RunLedger.Verdict.SKIP, RunLedger.claim(s, 1, slot, now = slot + 99 * 60_000, retried = true))
        // The next slot is a new run.
        assertEquals(RunLedger.Verdict.RUN, RunLedger.claim(s, 1, slot + 86_400_000, now = slot + 86_400_000))
        // Another routine is independent.
        assertEquals(RunLedger.Verdict.RUN, RunLedger.claim(s, 2, slot, now = slot))
    }

    @Test fun aRunTheAppDiedInTheMiddleOfIsRetriedOnceNotForever() {
        val s = MemStore(); val slot = 5_000L
        assertEquals(RunLedger.Verdict.RUN, RunLedger.claim(s, 1, slot, now = slot))
        // Seconds later the claim still looks alive (the run may be going): no second run...
        assertEquals(RunLedger.Verdict.SKIP, RunLedger.claim(s, 1, slot, now = slot + 5_000))
        // ...unless WorkManager says it is re-running a job whose process died.
        assertEquals(RunLedger.Verdict.RERUN, RunLedger.claim(s, 1, slot, now = slot + 6_000, retried = true))
        // And a second death does not loop: two attempts is the limit.
        assertEquals(RunLedger.Verdict.SKIP, RunLedger.claim(s, 1, slot, now = slot + 60 * 60_000, retried = true))
    }

    private val z = ZoneId.of("Africa/Johannesburg")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, z)
    private fun daily(spec: String, created: ZonedDateTime, lastRun: ZonedDateTime? = null) =
        TriggerRow(id = 4, name = "Morning brief", kind = "daily", spec = spec, prompt = "p", createdAt = created.toInstant().toEpochMilli(), lastRunAt = lastRun?.toInstant()?.toEpochMilli())

    @Test fun aMissedDailyRunIsMadeUpOnceNotOncePerMissedDay() {
        val s = MemStore()
        val t = daily("07:00", created = at(1, 12), lastRun = at(5, 7, 1))
        // The phone was off for 4 days. Now it is Sat 10th, 17:30: the slot due is today 07:00 (not 6th, 7th, 8th, 9th).
        val now = at(10, 17, 30)
        val due = Routines.lastDue(t, now)!!
        assertEquals(at(10, 7).toInstant().toEpochMilli(), due)
        assertTrue(Routines.missed(t, due, s, now.toInstant().toEpochMilli()))
        // Once it has run (claimed), it is not made up again.
        RunLedger.claim(s, t.id, due, now = due + 1); RunLedger.done(s, t.id, due)
        assertFalse(Routines.missed(t, due, s, now.toInstant().toEpochMilli()))
    }

    @Test fun aRunThatAlreadyHappenedOrABrandNewRoutineIsNotMadeUp() {
        val s = MemStore(); val now = at(10, 17, 30)
        val ranToday = daily("07:00", created = at(1, 12), lastRun = at(10, 7, 2))
        assertFalse(Routines.missed(ranToday, Routines.lastDue(ranToday, now)!!, s, now.toInstant().toEpochMilli()))
        val brandNew = daily("07:00", created = at(10, 12))   // made at noon for 07:00: the 07:00 before it existed is not owed
        assertFalse(Routines.missed(brandNew, Routines.lastDue(brandNew, now)!!, s, now.toInstant().toEpochMilli()))
        val off = daily("07:00", created = at(1, 12)).copy(enabled = false)
        assertFalse(Routines.missed(off, Routines.lastDue(off, now)!!, s, now.toInstant().toEpochMilli()))
    }

    @Test fun weeklyAndOneOffRoutinesCatchUpToo() {
        val now = at(10, 17, 30) // Sat
        val weekly = TriggerRow(id = 5, name = "Plan", kind = "weekly", spec = "FRI 08:00", prompt = "", createdAt = at(1, 12).toInstant().toEpochMilli())
        assertEquals(at(9, 8).toInstant().toEpochMilli(), Routines.lastDue(weekly, now))
        assertTrue(Routines.missed(weekly, Routines.lastDue(weekly, now)!!, MemStore(), now.toInstant().toEpochMilli()))
        val once = TriggerRow(id = 6, name = "Call", kind = "at", spec = "2026-10-10T09:00", prompt = "", createdAt = at(1, 12).toInstant().toEpochMilli())
        val due = Routines.lastDue(once, now)!!
        assertTrue(Routines.missed(once, due, MemStore(), now.toInstant().toEpochMilli()))
        assertFalse(Routines.missed(once.copy(lastRunAt = due + 1000), due, MemStore(), now.toInstant().toEpochMilli()))
        assertNull(Routines.lastDue(once.copy(spec = "2026-10-11T09:00"), now))
    }

    // ---- routines use the same power as the chat ---------------------------------------------

    private fun engine(isReady: Boolean, vararg events: JSONObject) = object : SubscriptionEngine {
        override val ready = isReady
        val prompts = mutableListOf<String>()
        override fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String, images: List<String>, model: String?, role: String) =
            kotlinx.coroutines.flow.flow { prompts += prompt; events.forEach { emit(it) } }
    }

    @Test fun aRoutineOnASubscriptionRunsOnTheSubscriptionNotNoProvider() = runBlocking {
        settings.setMode(PowerMode.SUBSCRIPTION)
        val rt = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { null })
        val e = engine(true, JSONObject().put("type", "text").put("text", "Posted the thread."), JSONObject().put("type", "done").put("ok", true))
        rt.subscription = e
        val out = rt.runBackground("X session", "check my X")
        assertEquals("Posted the thread.", out)
        assertTrue(e.prompts.single().contains("check my X"))
        val rows = db.chat().all().first()
        assertTrue(rows.any { it.kind == "agent" && JSONObject(it.meta).optString("routine") == "X session" })
        assertTrue(rows.none { it.text.contains("No AI provider") })
        assertTrue(phone.notes.any { it.startsWith("X session|") })
    }

    @Test fun aRoutineWithNoAiAtAllSaysSoClearly() = runBlocking {
        settings.setMode(PowerMode.NONE)
        val rt = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { null })
        val out = rt.runBackground("Morning brief", "brief me")
        assertTrue(out, out.contains("No AI is set up"))
        assertTrue(db.chat().all().first().any { it.kind == "notice" && it.text.contains("Morning brief") })
        assertTrue(phone.notes.any { it.contains("didn't run") })
    }

    // ---- start-up self-check -----------------------------------------------------------------

    /** A subscription engine that calls the check's tool through the phone's own MCP endpoint, as the real harness would. */
    private fun callingEngine(callTool: Boolean, vararg events: JSONObject) = object : SubscriptionEngine {
        override val ready = true
        lateinit var rt: AgentRuntime
        override fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String, images: List<String>, model: String?, role: String) =
            kotlinx.coroutines.flow.flow {
                if (callTool) {
                    val route = mcpUrl.substringAfterLast("/h/").toLong()
                    val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call").put("params", JSONObject().put("name", "selfcheck_ping").put("arguments", JSONObject()))
                    val u = java.net.URL(mcpUrl); val c = u.openConnection() as java.net.HttpURLConnection
                    c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Authorization", "Bearer $mcpToken"); c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                    check(c.responseCode == 200 && route > 0) { "mcp ${c.responseCode}" }
                }
                events.forEach { emit(it) }
            }
    }

    @Test fun theSelfCheckPassesWhenTheToolRunsAndIsNotRepeatedForTheSameBuild() = runBlocking {
        settings.setMode(PowerMode.SUBSCRIPTION)
        val rt = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { null })
        rt.subscription = callingEngine(true, JSONObject().put("type", "text").put("text", "ok"), JSONObject().put("type", "done").put("ok", true))
        val r = rt.selfCheck("59")!!
        assertTrue(r.detail, r.ok)
        assertNull("passed once for this build: not run again", rt.selfCheck("59"))
        assertNotNull("a new build checks again", rt.selfCheck("60"))
        assertTrue(db.chat().all().first().none { it.kind == "notice" })
    }

    @Test fun aProviderThatCannotRunToolsIsNamedInAClearNoticeAndNeverBlamesTheUser() = runBlocking {
        settings.setMode(PowerMode.SUBSCRIPTION); settings.setSubKind(com.past9.phoneaos.data.SubKind.CODEX)
        val rt = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { null })
        // The tool never reaches the phone: Codex could not start its tool host.
        rt.subscription = callingEngine(false, JSONObject().put("type", "error").put("message", "failed to spawn code-mode host /lib/arm64/codex-code-mode-host: No such file or directory (os error 2)"))
        val r = rt.selfCheck("59")!!
        assertFalse(r.ok); assertEquals("ChatGPT", r.power); assertTrue(r.detail.contains("code-mode host"))
        val notice = db.chat().all().first().single { it.kind == "notice" }.text
        assertTrue(notice, notice.contains("ChatGPT") && notice.contains("code-mode host"))
        assertFalse(notice.contains("developer", true)); assertTrue(notice.contains("nothing for you to send"))
        assertTrue(phone.notes.any { it.startsWith("Tools don't work with ChatGPT|") })
        // It is told again only if it changes, and is retried at the next start.
        rt.selfCheck("59", force = true)
        assertEquals(1, db.chat().all().first().count { it.kind == "notice" })
        // The main agent knows, and is told never to send the user to a developer.
        val sys = rt.systemPrompt("hi")
        assertTrue(sys.contains("KNOWN FAULT: ChatGPT")); assertTrue(sys.contains("never tell them to send it"))
    }

    @Test fun anApiProviderIsCheckedByOneRealToolCall() = runBlocking {
        val p = ScriptedProvider(call("selfcheck_ping", JSONObject()), say("ok"))
        val rt = runtime(p)
        val r = rt.selfCheck("59")!!
        assertTrue(r.detail, r.ok); assertEquals("Anthropic", r.power)
        val bad = runtime(ScriptedProvider(say("I cannot use tools")))
        settings.setExtra("selfcheck_ok", null)
        val f = bad.selfCheck("60")!!
        assertFalse(f.ok); assertTrue(f.detail.contains("never called"))
    }

    @Test fun noAiMeansNothingToCheck() = runBlocking {
        settings.setMode(PowerMode.NONE)
        assertNull(runtime(ScriptedProvider()).selfCheck("59"))
    }
}
