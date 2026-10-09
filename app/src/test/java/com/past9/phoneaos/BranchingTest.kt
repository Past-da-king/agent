package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.*
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.data.TurnRow
import com.past9.phoneaos.ui.screens.quotedFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A model that records every request and answers through a handler, which can hold a request open (a gate) to keep a turn running. */
class BranchProvider : LlmProvider {
    override val name = "branching"
    val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, List<Msg>>>())
    var handler: suspend (system: String, msgs: List<Msg>) -> Msg = { _, _ -> say("ok") }
    override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
        calls += system to messages.toList()
        return Completion(handler(system, messages.toList()), "end_turn")
    }
    fun forked() = calls.filter { it.second.last().text.startsWith("[You were forked") }
    fun last(m: List<Msg>) = m.last().text
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BranchingTest {
    private lateinit var db: AppDb
    private lateinit var settings: SettingsStore
    private val phone = FakePhone()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDb.inMemory(ctx); settings = SettingsStore(ctx)
        settings.setMode(PowerMode.API_KEY); settings.setProvider(Provider.ANTHROPIC); settings.setApiKey(Provider.ANTHROPIC, "sk-test"); settings.setUserName("Sam")
    }

    private fun runtime(p: LlmProvider, d: AppDb = db) = AgentRuntime(ApplicationProvider.getApplicationContext(), d, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { p })
    private suspend fun idle(rt: AgentRuntime) { withTimeout(15_000) { rt.awaitIdle() } }
    private suspend fun until(what: String, ms: Long = 8_000, cond: suspend () -> Boolean) {
        withTimeout(ms) { while (!cond()) delay(15) }
    }
    private suspend fun chat() = db.chat().all().first()
    private suspend fun branchRows() = chat().filter { it.kind == "branch" }
    private suspend fun mainTurns() = db.chat().recentTurns("main", 200).reversed().map { Msg.fromJson(JSONObject(it.json)) }
    private fun meta(i: ChatItem) = JSONObject(i.meta)

    /** Main line: step 1 calls a tool, then holds at `gate`; then says "main done". Anything forked answers at once unless gated. */
    private fun scenario(gate: CompletableDeferred<Unit>, gates: Map<String, CompletableDeferred<Unit>> = emptyMap()): BranchProvider {
        val p = BranchProvider()
        p.handler = { system, msgs ->
            val last = msgs.last()
            val text = last.text
            when {
                system.contains("You are a HELPER") -> { delay(60); say("helper found: ${msgs.first().text.take(40)}") }
                text.startsWith("[You were forked") -> {
                    gates.entries.firstOrNull { text.substringAfterLast("\n").contains(it.key) }?.value?.await()
                    when {
                        text.contains("boom") -> throw ProviderException("model exploded", 500)
                        text.contains("get me the news") && msgs.none { it.toolCalls.any { c -> c.name == "delegate" } && it !== msgs.last() && msgs.indexOf(it) > msgs.indexOfLast { m -> m.text.startsWith("[You were forked") } - 1 } ->
                            delegate("find today's news")
                        else -> say("branch answer to: ${text.substringAfterLast("\n").take(40)}")
                    }
                }
                last.blocks.any { it is Block.ToolResult } && msgs.any { it.text.startsWith("[You were forked") } -> {
                    // A branch's own tool result coming back.
                    say("On it, the news is being fetched.")
                }
                text.startsWith("[Helper #") && msgs.any { it.text.startsWith("[You were forked") } -> say("news summary from branch")
                text == "research X" || text == "book a table at 8" || text == "a" -> call("now", JSONObject())
                last.blocks.any { it is Block.ToolResult } && msgs.count { m -> m.blocks.any { it is Block.ToolResult } } == 1 -> { gate.await(); call("now", JSONObject()) }
                last.blocks.any { it is Block.ToolResult } -> say("main done")
                text.startsWith("[Branch merge") -> say("noted")
                else -> say("plain reply to: ${text.take(40)}")
            }
        }
        return p
    }

    // ---- the policy ------------------------------------------------------------------------

    @Test fun thePolicyTellsAcksCorrectionsAdditionsAndNewRequestsApart() {
        val c = { t: String -> HeuristicPolicy.classify(t, "book a table") }
        listOf("ok thanks", "Thanks!", "👍", "cool", "yes", "ok", "thank you").forEach { assertEquals(it, Relation.ACK, c(it)) }
        listOf("wait, I meant 9pm", "Actually make it Friday", "no wait, the other one", "scrap that", "never mind", "I meant the 7pm show", "Hold on - change of plan").forEach { assertEquals(it, Relation.SUPERSEDE, c(it)) }
        listOf("also book a taxi", "and tell Sam I'm late", "plus add dessert", "one more thing: vegetarian", "while you're at it check parking").forEach { assertEquals(it, Relation.RELATED, c(it)) }
        listOf("what's the weather in Cape Town", "remind me to call mum at five", "find me a plumber").forEach { assertEquals(it, Relation.PARALLEL, c(it)) }
    }

    // ---- fork at state ---------------------------------------------------------------------

    @Test fun aMessageSentMidWorkForksFromTheWorkingAgentsCurrentStateNotColdAndBothAnswer() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val p = scenario(gate); val rt = runtime(p)
        rt.send("research X")
        until("main in its second step") { p.calls.size >= 2 }
        rt.send("what's the weather in Cape Town")
        until("the branch answered") { chat().any { it.kind == "agent" && it.text.startsWith("branch answer") } }
        // The branch's very first request already holds what the main line had seen and done: the user's message, its tool call and the result.
        val fork = p.forked().single().second
        assertEquals("research X", fork.first().text)
        assertTrue("tool call carried over", fork.any { m -> m.toolCalls.any { it.name == "now" } })
        assertTrue("tool result carried over", fork.any { m -> m.blocks.any { it is Block.ToolResult } })
        assertTrue(fork.last().text.contains("SEPARATE request"))
        gate.complete(Unit); idle(rt)

        val items = chat()
        val users = items.filter { it.kind == "user" }
        val mainReply = items.single { it.kind == "agent" && it.text == "main done" }
        val branchReply = items.single { it.kind == "agent" && it.text.startsWith("branch answer") }
        // Each reply quotes the message it answers; the branch's is marked.
        assertEquals(users[0].id, meta(mainReply).optLong("replyTo"))
        assertEquals(users[1].id, meta(branchReply).optLong("replyTo"))
        assertTrue(meta(branchReply).has("branch"))
        assertTrue("replies quote what they answer", quotedFor(branchReply, items, items.associateBy { it.id }) != null)
        val row = branchRows().single()
        assertEquals("parallel", meta(row).getString("relation")); assertEquals("done", meta(row).getString("state")); assertTrue(meta(row).getBoolean("merged"))
    }

    // ---- supersede -------------------------------------------------------------------------

    @Test fun aCorrectionClosesTheOldAgentAndTheNewBranchCarriesOnWithItsContext() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val p = scenario(gate); val rt = runtime(p)
        rt.send("book a table at 8")
        until("main waiting") { p.calls.size >= 2 }
        rt.send("wait, I meant 9pm")
        until("branch answered") { chat().any { it.kind == "agent" && it.text.startsWith("branch answer") } }
        idle(rt)
        val items = chat()
        assertTrue("the old agent never finished", items.none { it.text == "main done" })
        assertTrue("closing it is not 'Stopped'", items.none { it.kind == "notice" && it.text == "Stopped." })
        val fork = p.forked().single().second
        assertTrue("the branch carries the old agent's context", fork.first().text == "book a table at 8" && fork.any { m -> m.toolCalls.any { it.name == "now" } })
        assertTrue(fork.last().text.contains("REPLACES or corrects"))
        val row = branchRows().single()
        assertEquals("supersede", meta(row).getString("relation")); assertTrue(meta(row).getBoolean("merged"))
        // The main transcript now carries the branch's work, labelled.
        assertTrue(mainTurns().any { it.text.startsWith("[Branch merge") && it.text.contains("wait, I meant 9pm") })
    }

    // ---- ack: no fork ----------------------------------------------------------------------

    @Test fun okThanksNeverForksAnAgentButTheRunningOneHearsIt() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val p = scenario(gate); val rt = runtime(p)
        rt.send("research X")
        until("main waiting") { p.calls.size >= 2 }
        rt.send("ok thanks")
        until("bubble in") { chat().count { it.kind == "user" } == 2 }
        gate.complete(Unit); idle(rt)
        assertTrue("no branch for a pleasantry", branchRows().isEmpty())
        assertTrue(p.forked().isEmpty())
        assertTrue("the running agent was told before its next step", p.calls.last().second.any { it.text.contains("ok thanks") })
    }

    // ---- related ---------------------------------------------------------------------------

    @Test fun relatedWorkBuildsOnTheRunningAgentAndTheRunningAgentIsToldSoTheyDontDoItTwice() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val p = scenario(gate); val rt = runtime(p)
        rt.send("research X")
        until("main waiting") { p.calls.size >= 2 }
        rt.send("also include the price history")
        until("branch answered") { chat().any { it.kind == "agent" && it.text.startsWith("branch answer") } }
        gate.complete(Unit); idle(rt)
        assertTrue(p.forked().single().second.last().text.contains("ADDS to that same work"))
        assertTrue("the working agent got a heads-up", p.calls.any { (sys, m) -> !sys.contains("You are a HELPER") && m.any { it.text.contains("Heads-up: the user just added") } })
        assertEquals("related", meta(branchRows().single()).getString("relation"))
    }

    // ---- merge ordering --------------------------------------------------------------------

    @Test fun branchesMergeBackInTheOrderTheUserSentThemEvenWhenTheyFinishOutOfOrder() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val slow = CompletableDeferred<Unit>()
        val p = scenario(gate, mapOf("plan the trip" to slow)); val rt = runtime(p)
        rt.send("research X")
        until("main waiting") { p.calls.size >= 2 }
        rt.send("plan the trip to Durban")           // branch 1, held open
        until("branch 1 started") { branchRows().size == 1 }
        rt.send("what is two plus two")              // branch 2, answers at once
        until("branch 2 answered") { chat().any { it.kind == "agent" && it.text.contains("two plus two") } }
        gate.complete(Unit)
        until("main finished") { chat().any { it.text == "main done" } }
        // Branch 2 is done and the main line is idle, but branch 1 (sent earlier) is still working: nothing merges yet, so the order can't jump.
        delay(300)
        assertTrue(mainTurns().none { it.text.startsWith("[Branch merge") })
        slow.complete(Unit); idle(rt)
        val notes = mainTurns().filter { it.text.startsWith("[Branch merge") }.map { it.text }
        assertEquals(2, notes.size)
        assertTrue("sent first, merged first", notes[0].contains("plan the trip") && notes[1].contains("two plus two"))
        assertTrue(branchRows().all { meta(it).getBoolean("merged") })
        // The merged block says what the branch did and that it's done, so nothing is redone.
        assertTrue(notes[0].contains("is DONE: don't redo it") && notes[0].contains("you said: branch answer"))
    }

    // ---- three rapid messages --------------------------------------------------------------

    @Test fun threeRapidMessagesChainAgainstTheNewestLineOfWork() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val held = CompletableDeferred<Unit>()
        val p = scenario(gate, mapOf("b please" to held)); val rt = runtime(p)
        rt.send("a")
        until("main waiting") { p.calls.size >= 2 }
        rt.send("wait, b please"); rt.send("also c")
        until("both branches exist") { branchRows().size == 2 }
        val rows = branchRows()
        // "wait, b" corrected the main line; "also c" adds to b (the newest line), not to the closed one.
        assertEquals("supersede", meta(rows[0]).getString("relation")); assertEquals("main", meta(rows[0]).getString("parent"))
        assertEquals("related", meta(rows[1]).getString("relation")); assertEquals("b${rows[0].id}", meta(rows[1]).getString("parent"))
        held.complete(Unit); idle(rt)
        assertTrue(branchRows().all { meta(it).getBoolean("merged") })
        assertEquals(listOf("a", "wait, b please", "also c"), chat().filter { it.kind == "user" }.map { it.text })
    }

    // ---- failure ---------------------------------------------------------------------------

    @Test fun aBranchThatFailsSaysSoAndTheOthersCarryOn() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val p = scenario(gate); val rt = runtime(p)
        rt.send("research X")
        until("main waiting") { p.calls.size >= 2 }
        rt.send("boom now")
        until("failed notice") { chat().any { it.kind == "notice" && it.text.contains("side thread") } }
        gate.complete(Unit); idle(rt)
        assertTrue("the main line finished anyway", chat().any { it.text == "main done" })
        val row = branchRows().single()
        assertEquals("failed", meta(row).getString("state")); assertTrue(meta(row).getBoolean("merged"))
        assertTrue("the main line is told it failed", mainTurns().any { it.text.startsWith("[Branch merge") && it.text.contains("failed") })
    }

    // ---- helpers stay on their branch -------------------------------------------------------

    @Test fun aHelperABranchStartsReportsBackToThatBranchNotTheMainLine() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val p = scenario(gate); val rt = runtime(p)
        rt.send("research X")
        until("main waiting") { p.calls.size >= 2 }
        rt.send("get me the news")
        until("the branch's helper came back to it") { chat().any { it.kind == "agent" && it.text == "news summary from branch" } }
        gate.complete(Unit); idle(rt)
        val helperRow = chat().single { it.kind == "helper" }
        val branchId = branchRows().single().id
        assertEquals(branchId, meta(helperRow).getLong("branch"))
        assertTrue("the main line never saw the helper's result", p.calls.filter { !it.first.contains("You are a HELPER") && it.second.none { m -> m.text.startsWith("[You were forked") } }.none { c -> c.second.any { it.text.startsWith("[Helper #") } })
        assertTrue(meta(chat().single { it.text == "news summary from branch" }).getLong("branch") == branchId)
        assertTrue(meta(branchRows().single()).getBoolean("merged"))
    }

    // ---- budget ----------------------------------------------------------------------------

    @Test fun eachLineHasItsOwnFiveStepBudget() = runBlocking {
        val rt = runtime(BranchProvider())
        val branchTurn = Turn(Line("b9", 99))
        val branchTools = rt.tools(turn = branchTurn).first { it.spec.name == "phone_files" }
        val mainTools = rt.tools().first { it.spec.name == "phone_files" }
        val q = JSONObject().put("query", "*.pdf")
        // (The tool itself can't run under Robolectric; what matters is whether the budget lets it through.)
        suspend fun out(t: Tool) = runCatching { t.run(q, quiet) }.getOrElse { "ran" }
        repeat(5) { assertFalse(out(branchTools).startsWith("Step limit")) }
        assertTrue("the branch is out of steps", out(branchTools).startsWith("Step limit"))
        assertFalse("the main line still has all five", out(mainTools).startsWith("Step limit"))
    }

    // ---- restart ---------------------------------------------------------------------------

    @Test fun aBranchCutOffByARestartIsMergedCleanlyWithWhatItDid() = runBlocking {
        val id = db.chat().insert(ChatItem(kind = "branch", text = "find me a plumber", meta = JSONObject().put("state", "running").put("relation", "parallel").put("parent", "main").put("origin", 5).toString()))
        db.chat().insertTurn(TurnRow(thread = "branch-$id", json = Msg.user("[You were forked...]\n\nfind me a plumber").toJson().toString()))
        db.chat().insertTurn(TurnRow(thread = "branch-$id", json = call("web_fetch", JSONObject().put("url", "https://plumbers.example"), "c1").toJson().toString()))
        db.chat().insertTurn(TurnRow(thread = "branch-$id", json = Msg(Role.USER, listOf(Block.ToolResult("c1", "Pipe Dreams, 021 555 0100"))).toJson().toString()))
        val rt = runtime(BranchProvider())      // a fresh process on the same database
        idle(rt)
        val row = db.chat().get(id)!!
        assertEquals("interrupted", meta(row).getString("state")); assertTrue(meta(row).getBoolean("merged"))
        assertTrue(chat().any { it.kind == "notice" && it.text.contains("find me a plumber") })
        val note = mainTurns().single { it.text.startsWith("[Branch merge") }.text
        assertTrue(note.contains("interrupted") && note.contains("Pipe Dreams") && note.contains("web_fetch"))
        assertTrue("its working transcript is cleared once merged", db.chat().recentTurns("branch-$id", 10).isEmpty())
    }

    // ---- subscription mode: Claude forks the running session --------------------------------

    private class FakeEngine(val canForkIt: Boolean) : SubscriptionEngine {
        override val ready = true
        override val canFork = canForkIt
        val prompts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val forkedFrom = java.util.Collections.synchronizedList(mutableListOf<String>())
        val release = CompletableDeferred<Unit>()
        override fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String, images: List<String>, model: String?, role: String): Flow<JSONObject> = flow {
            prompts += prompt
            emit(JSONObject().put("type", "session").put("id", resume ?: "s-main"))
            if (prompt.startsWith("slow")) release.await()
            emit(JSONObject().put("type", "text").put("text", "reply: " + prompt.substringAfterLast("\n").take(30)))
            emit(JSONObject().put("type", "done").put("ok", true))
        }
        override fun forkTurn(prompt: String, system: String, parent: String, mcpUrl: String, mcpToken: String, images: List<String>, model: String?, role: String): Flow<JSONObject> = flow {
            forkedFrom += parent; prompts += prompt
            emit(JSONObject().put("type", "session").put("id", "s-fork"))
            emit(JSONObject().put("type", "text").put("text", "fork reply"))
            emit(JSONObject().put("type", "done").put("ok", true))
        }
    }

    private fun subRuntime(engine: FakeEngine): AgentRuntime {
        settings.setMode(PowerMode.SUBSCRIPTION)
        return AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { null }).also { it.subscription = engine }
    }

    @Test fun onClaudeTheBranchForksTheRunningSessionAndTheMergeRidesOnTheNextPrompt() = runBlocking {
        val e = FakeEngine(true); val rt = subRuntime(e)
        rt.send("slow research")
        until("main running") { e.prompts.size >= 1 }
        rt.send("what's the weather in Cape Town")
        until("fork reply") { chat().any { it.text == "fork reply" } }
        assertEquals(listOf("s-main"), e.forkedFrom)
        e.release.complete(Unit); idle(rt)
        assertEquals("the main session is untouched by the fork", "s-main", settings.extra("claude_session"))
        rt.send("and now?"); idle(rt)
        val next = e.prompts.last()
        assertTrue("the next turn is handed the branch's work, labelled", next.startsWith("[Branch merge") && next.contains("weather in Cape Town") && next.contains("fork reply") && next.endsWith("and now?"))
    }

    @Test fun aCorrectionOnClaudeBecomesTheMainSession() = runBlocking {
        val e = FakeEngine(true); val rt = subRuntime(e)
        rt.send("slow research")
        until("main running") { e.prompts.size >= 1 }
        rt.send("wait, I meant something else")
        until("fork reply") { chat().any { it.text == "fork reply" } }
        idle(rt)
        assertEquals("s-fork", settings.extra("claude_session"))
        assertTrue("nothing left to carry: the session already holds it", rt.let { settings.extra("branch_pending") == null })
    }

    @Test fun anEngineThatCannotForkStartsTheBranchFreshWithAHandoff() = runBlocking {
        val e = FakeEngine(false); val rt = subRuntime(e)
        rt.send("slow research")
        until("main running") { e.prompts.size >= 1 }
        rt.send("what's the weather in Cape Town")
        until("branch replied") { chat().any { it.kind == "agent" && it.text.startsWith("reply: ") && meta(it).has("branch") } }
        e.release.complete(Unit); idle(rt)
        assertTrue(e.forkedFrom.isEmpty())
        assertTrue(e.prompts.any { it.startsWith("[Context handed over") && it.contains("slow research") })
    }
}
