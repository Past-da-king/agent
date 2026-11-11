package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.agent.LlmProvider
import com.past9.phoneaos.agent.SubscriptionEngine
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.tools.parseWhen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #5: a routine runs with the helpers' tools, and a helper's findings go through the main agent, which decides
 * what lands on the user's list. A routine has no main agent in the loop, so it adds to the list and keeps its own
 * notes itself. Helpers stay as they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoutineToolsTest {
    private lateinit var ctx: android.content.Context
    private lateinit var db: AppDb
    private lateinit var settings: SettingsStore
    private val phone = FakePhone()
    private var provider: LlmProvider? = null

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = AppDb.inMemory(ctx); settings = SettingsStore(ctx)
        settings.setMode(PowerMode.API_KEY); settings.setProvider(Provider.ANTHROPIC); settings.setApiKey(Provider.ANTHROPIC, "sk-test"); settings.setUserName("Sam")
    }

    private fun runtime() = AgentRuntime(ctx, db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { provider })

    @Test fun aRoutineAddsToTheUsersListAndKeepsItsOwnNote() = runBlocking {
        provider = ScriptedProvider(
            call("task_add", JSONObject().put("title", "Order Thursday's stock").put("due", "2026-11-12")),
            call("memory_save", JSONObject().put("title", "Stock orders")
                .put("body", "The shop app lists the week's orders on Monday; Thursday's stock is ordered by Wednesday.")
                .put("tags", "shop, orders")),
            say("Added Thursday's stock order, and how the shop app works."))
        val out = runtime().runBackground("Monday deliveries", "Add this week's deliveries from the shop app")

        val t = db.tasks().allTasks().single { it.title == "Order Thursday's stock" }
        assertEquals("user", t.owner)
        assertEquals(parseWhen("2026-11-12"), t.dueAt)
        val page = db.memory().byTitle("Stock orders")!!
        assertEquals("shop, orders", page.topics)
        assertTrue(page.body, page.body.contains("shop app"))
        assertTrue(out, out.contains("stock"))
    }

    @Test fun aRoutineThatComesRoundAgainDoesNotAddTheSameJobTwice() = runBlocking {
        val rt = runtime()
        fun addIt() {
            provider = ScriptedProvider(call("task_add", JSONObject().put("title", "Collect the till slip").put("due", "2026-11-10")), say("Added it."))
        }
        addIt(); rt.runBackground("Daily till", "add the till slip task")
        addIt(); rt.runBackground("Daily till", "add the till slip task")
        val tasks = db.tasks().allTasks().filter { it.title == "Collect the till slip" }
        assertEquals("one open copy, not one per run", 1, tasks.size)

        // Last week's is done: this week's job is a new task, not a duplicate of it.
        db.tasks().updateTask(tasks.single().copy(status = "done"))
        addIt(); rt.runBackground("Daily till", "add the till slip task")
        assertEquals(2, db.tasks().allTasks().count { it.title == "Collect the till slip" })
    }

    @Test fun routinesGetTheirOwnToolsAndNothingElse() = runBlocking {
        val rt = runtime()
        val helper = rt.tools(forHelper = true).map { it.spec.name }.toSet()
        val routine = servedRoutineTools(rt)

        assertEquals("the routine set is the helper set plus exactly these",
            setOf("task_add", "task_delete", "memory_save", "wake_schedule", "wake_list", "wake_cancel",
                // People (issue #13): a routine keeps the user's own people data; a helper does not.
                "person_add", "person_log", "person_list", "person_get", "person_date_add", "gift_add"), routine - helper)
        assertEquals("and nothing is taken away from helpers", emptySet<String>(), helper - routine)
    }

    @Test fun aHelperStillCannotTouchTheListOrMemory() = runBlocking {
        val rt = runtime()
        val helper = rt.tools(forHelper = true).map { it.spec.name }.toSet()
        assertFalse("task_add" in helper)
        assertFalse("task_delete" in helper)
        assertFalse("memory_save" in helper)
    }

    @Test fun aRoutineStaysOutOfWhatOnlyTheMainAgentShouldDo() = runBlocking {
        val rt = runtime()
        val routine = servedRoutineTools(rt)
        // Delegating, steering helpers, goals, profiles and rewriting or deleting memory pages are the main agent's.
        val mainOnly = listOf("delegate", "helper_steer", "helper_push", "helper_stop", "helpers_status", "escalation_triage",
            "agent_pin", "agent_unpin", "agents_pinned", "goal_create", "goal_update", "goal_delete", "memory_update",
            "profile_create", "report")
        assertEquals(emptyList<String>(), mainOnly.filter { it in routine })
    }

    /** The tools the app really serves a routine on a subscription: its own MCP endpoint, its own tool list. */
    private suspend fun servedRoutineTools(rt: AgentRuntime): Set<String> {
        settings.setMode(PowerMode.SUBSCRIPTION)
        var names = emptySet<String>()
        rt.subscription = object : SubscriptionEngine {
            override val ready = true
            override fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String,
                              images: List<String>, model: String?, role: String) = flow {
                val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".toRequestBody("application/json".toMediaType())
                val res = OkHttpClient().newCall(Request.Builder().url(mcpUrl).post(body).header("Authorization", "Bearer $mcpToken").build()).execute()
                val list = JSONObject(res.body!!.string()).getJSONObject("result").getJSONArray("tools")
                names = (0 until list.length()).map { list.getJSONObject(it).getString("name") }.toSet()
                emit(JSONObject().put("type", "text").put("text", "Done."))
                emit(JSONObject().put("type", "done").put("ok", true))
            }
        }
        rt.runBackground("Weekly tidy", "clear what no longer applies")
        rt.mcp.stop()
        return names
    }
}
