package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.agent.SubscriptionEngine
import com.past9.phoneaos.data.*
import com.past9.phoneaos.tools.*
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the agent makes (tasks, goals, routines) it can also change and remove, the way the user can in the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManageWhatItMadeTest {
    private val app get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun j(s: String) = JSONObject(s)

    @Test fun tasksTakeANewDateAndAreRemovedForReal() = runBlocking {
        val dao = AppDb.inMemory(app).tasks()
        val id = dao.insertTask(TaskRow(title = "Renew the passport", owner = "user", dueAt = parseWhen("2020-01-10")))
        assertTrue(TaskListTool(dao).run(j("{}"), quiet).contains("(due 2020-01-10, overdue)"))

        // A status the list doesn't know would leave it open forever: refused, and the way out is named.
        assertTrue(TaskUpdateTool(dao).run(j("""{"id":$id,"status":"cancelled"}"""), quiet).contains("task_delete"))
        assertEquals("todo", dao.task(id)!!.status)

        assertTrue(TaskUpdateTool(dao).run(j("""{"id":$id,"due":"2099-03-01T14:30"}"""), quiet).endsWith("due 2099-03-01T14:30"))
        assertTrue(TaskListTool(dao).run(j("{}"), quiet).contains("(due 2099-03-01T14:30)"))
        assertTrue(TaskUpdateTool(dao).run(j("""{"id":$id,"due":"next week"}"""), quiet).startsWith("Not changed"))
        TaskUpdateTool(dao).run(j("""{"id":$id,"due":"none"}"""), quiet)
        assertNull(dao.task(id)!!.dueAt)

        TaskDeleteTool(dao).run(j("""{"id":$id}"""), quiet)
        assertNull(dao.task(id))
        assertEquals("No open goals or tasks.", TaskListTool(dao).run(j("{}"), quiet))
    }

    @Test fun goalsCanBeChangedAndRemovedWithTheirTasks() = runBlocking {
        val dao = AppDb.inMemory(app).tasks()
        val gid = dao.insertGoal(GoalRow(title = "Plan the trip"))
        val done = dao.insertTask(TaskRow(goalId = gid, title = "Book flights", status = "done"))
        val left = dao.insertTask(TaskRow(goalId = gid, title = "Book a hotel"))
        // Removing the last open step leaves only done ones: the goal is achieved.
        TaskDeleteTool(dao).run(j("""{"id":$left}"""), quiet)
        assertEquals("achieved", dao.goal(gid)!!.status)

        GoalUpdateTool(dao).run(j("""{"id":$gid,"status":"open","title":"Plan the summer trip"}"""), quiet)
        assertEquals("Plan the summer trip" to "open", dao.goal(gid)!!.let { it.title to it.status })
        assertTrue(GoalUpdateTool(dao).run(j("""{"id":$gid,"status":"dropped"}"""), quiet).contains("goal_delete"))

        GoalDeleteTool(dao).run(j("""{"id":$gid}"""), quiet)
        assertNull(dao.goal(gid)); assertNull(dao.task(done))
    }

    @Test fun routinesAreChangedInPlaceAndListedWhole() = runBlocking {
        val dao = AppDb.inMemory(app).triggers()
        val long = "Every morning: check the weather, then the news, then my calendar. " + "Keep each part short. ".repeat(10) + "End with the calendar."
        val id = dao.upsert(TriggerRow(name = "Morning brief", kind = "daily", spec = "07:00", prompt = long))
        assertTrue(ScheduleListTool(dao).run(j("{}"), quiet).contains("End with the calendar."))

        val changed = mutableListOf<TriggerRow>()
        val update = ScheduleUpdateTool(dao) { changed += it }
        assertTrue(update.run(j("""{"id":$id,"spec":"7am"}"""), quiet).startsWith("Not changed"))
        update.run(j("""{"id":$id,"kind":"weekly","spec":"MON,FRI 06:30"}"""), quiet)
        update.run(j("""{"id":$id,"enabled":false}"""), quiet)
        val t = dao.get(id)!!
        assertEquals(listOf("weekly", "MON,FRI 06:30", long), listOf(t.kind, t.spec, t.prompt))
        assertFalse(t.enabled)
        assertEquals(1, dao.list().size)
        assertEquals(id, changed.last().id)

        // Recreating it under the same name is refused and points to routine_update.
        val again = ScheduleCreateTool(dao) { changed += it }.run(j("""{"name":"morning brief","kind":"daily","spec":"06:15","prompt":"x"}"""), quiet)
        assertTrue(again.contains("routine_update (id $id)"))
        assertEquals(1, dao.list().size)
    }

    @Test fun anEmptyBodyNeverWipesAMemoryPage() = runBlocking {
        val dao = AppDb.inMemory(app).memory()
        MemorySaveTool(dao).run(j("""{"title":"Seat preferences","body":"Window, never the last row.","tags":"travel"}"""), quiet)
        assertTrue(MemorySaveTool(dao).run(j("""{"title":"Seat preferences","tags":"travel"}"""), quiet).startsWith("Not saved"))
        val page = dao.byTitle("Seat preferences")!!
        assertEquals("Window, never the last row.", page.body)
        assertTrue(MemoryUpdateTool(dao).run(j("""{"id":${page.id}}"""), quiet).startsWith("Not changed"))
        assertEquals("Window, never the last row.", dao.get(page.id)!!.body)
    }

    @Test fun eachAgentGetsTheRightTools() = runBlocking {
        val db = AppDb.inMemory(app); val settings = SettingsStore(app); settings.setMode(PowerMode.SUBSCRIPTION)
        val rt = AgentRuntime(app, db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone(), { null })
        val main = rt.tools().map { it.spec.name }
        val helper = rt.tools(forHelper = true).map { it.spec.name }
        assertTrue(listOf("task_delete", "goal_update", "goal_delete").all { it in main } && "routine_update" !in main)
        assertTrue("routine_update" in helper && "task_add" !in helper && "task_delete" !in helper)

        // A routine on the subscription is served its own tools: the helpers' plus adding to and clearing the list.
        var routineTools = emptyList<String>()
        rt.subscription = object : SubscriptionEngine {
            override val ready = true
            override fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String, images: List<String>, model: String?, role: String) = flow {
                val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".toRequestBody("application/json".toMediaType())
                val res = OkHttpClient().newCall(Request.Builder().url(mcpUrl).post(body).header("Authorization", "Bearer $mcpToken").build()).execute()
                val list = JSONObject(res.body!!.string()).getJSONObject("result").getJSONArray("tools")
                routineTools = (0 until list.length()).map { list.getJSONObject(it).getString("name") }
                emit(JSONObject().put("type", "text").put("text", "Done."))
                emit(JSONObject().put("type", "done").put("ok", true))
            }
        }
        rt.runBackground("Weekly tidy", "Clear what no longer applies")
        assertTrue("task_add" in routineTools && "task_delete" in routineTools && "routine_update" in routineTools)
        rt.mcp.stop()
    }
}
