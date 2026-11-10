package com.past9.phoneaos.tools

import android.content.Context
import com.past9.phoneaos.App
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.bool
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.connections.LearnedCallTool
import com.past9.phoneaos.connections.LearnedStore
import com.past9.phoneaos.connections.PageRunner
import com.past9.phoneaos.connections.WebAppRun
import com.past9.phoneaos.screen.InstalledApps
import com.past9.phoneaos.screen.ScreenApps
import com.past9.phoneaos.screen.ScreenBlocklist
import com.past9.phoneaos.screen.ScreenLearned
import com.past9.phoneaos.screen.ScreenSession
import com.past9.phoneaos.screen.Screens
import org.json.JSONObject

/*
 * Learning from the background screen. There are no ready-made actions for any app: the agent works a job out with
 * the plain screen tools, and what worked is saved as a tool of a learned app (the same learned apps as for web
 * sites). These tools save, list, run and undo those; the learned tools themselves appear as app_<app>_<tool>.
 */

/** What these tools need from the app. Tests hand in fakes. */
class ScreenLearning(val session: () -> ScreenSession, val store: LearnedStore, val apps: ScreenApps, val blocklist: () -> ScreenBlocklist, val runner: PageRunner)

class ScreenSkillSaveTool(private val l: ScreenLearning) : Tool {
    override val spec = ToolSpec("screen_skill_save",
        "Save the job you just did on the background screen as a LEARNED tool, so next time you (or a helper, or a linked computer) call one tool instead of working it out again. " +
            "Call it right after the job worked, before screen_close. It keeps the steps you did in this app since you opened it (each with how to find its button or field again) and what you name as inputs. " +
            "Later it runs as app_<app>_<name>, re-finds every element on the live screen, still asks before a send, a payment or a delete, and returns what the app's screen shows at the end. " +
            "If a learned tool stops part of the way, finish the job by hand and save again under the same name to repair it. The user approves what is saved.",
        schema(listOf("name", "description"),
            "name" to str("The tool's name: lower-case letters, digits, underscores, e.g. search or send_message"),
            "description" to str("What it does, what it returns and when to use it, one or two lines"),
            "inputs" to JSONObject().put("type", "object").put("description", "The parts that change each time: {input name: the exact text it had in this run}, e.g. {\"query\": \"weather in Durban\"}. Wherever that text was typed or pressed, the tool takes the input instead."),
            "read_only" to bool("true when it only looks things up; false when it sends, posts, books or changes anything (default false)")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val ins = input.optJSONObject("inputs") ?: JSONObject()
        val map = LinkedHashMap<String, String>().apply { ins.keys().forEach { k -> put(k, ins.opt(k)?.toString().orEmpty()) } }
        return ScreenLearned.save(l.session(), l.store, l.apps, l.blocklist(), ctx, input.optString("name").trim(), input.optString("description"), map, input.optBoolean("read_only", false))
    }
}

class ScreenSkillListTool(private val l: ScreenLearning) : Tool {
    override val spec = ToolSpec("screen_skill_list", "The learned tools for apps on this phone (jobs done before on the background screen): their names, inputs and how they have been going. Check here before working a job out again.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = ScreenLearned.list(l.store)
}

class ScreenSkillRunTool(private val l: ScreenLearning) : Tool {
    override val spec = ToolSpec("screen_skill_run", "Run a learned tool of an app on this phone by name (the same as calling app_<app>_<tool>; use this when that tool is not in your list yet).",
        schema(listOf("app", "tool"), "app" to str("The app's name, as screen_skill_list shows it"), "tool" to str("The tool's name"),
            "args" to JSONObject().put("type", "object").put("description", "Its inputs, e.g. {\"query\": \"...\"}")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val app = l.store.find(input.optString("app"))?.takeIf { it.isScreen } ?: l.store.apps.value.firstOrNull { it.isScreen && it.pkg == input.optString("app").trim() }
            ?: return "No learned phone app called ${input.optString("app")}. screen_skill_list shows them."
        val t = app.tools.firstOrNull { it.name == input.optString("tool").trim() } ?: return "${app.name} has no learned tool ${input.optString("tool")}: ${app.tools.joinToString { it.name }}."
        val args = input.optJSONObject("args") ?: runCatching { JSONObject(input.optString("args").ifBlank { "{}" }) }.getOrElse { return "args must be an object." }
        return WebAppRun.call(app, t, args, ctx, l.runner)
    }
}

class ScreenSkillUndoTool(private val l: ScreenLearning) : Tool {
    override val spec = ToolSpec("screen_skill_undo", "Bring back the steps a learned phone tool had before its last change (a repair or a tightening that made it worse).",
        schema(listOf("app", "tool"), "app" to str("The app's name"), "tool" to str("The tool's name")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val app = l.store.find(input.optString("app"))?.takeIf { it.isScreen } ?: return "No learned phone app called ${input.optString("app")}."
        val t = app.tools.firstOrNull { it.name == input.optString("tool").trim() } ?: return "${app.name} has no learned tool ${input.optString("tool")}."
        val back = ScreenLearned.undone(t) ?: return "${t.name} has no earlier version to go back to."
        l.store.replaceTool(app.id, t.name, back)
        return "${t.name} is back to its earlier steps (${back.steps.length()} steps)."
    }
}

fun screenSkillTools(l: ScreenLearning): List<Tool> = listOf(ScreenSkillSaveTool(l), ScreenSkillListTool(l), ScreenSkillRunTool(l), ScreenSkillUndoTool(l))

private fun learning(context: Context): ScreenLearning {
    val g = App.graph(context)
    return ScreenLearning({ Screens.session(context) }, g.runtime.learned, InstalledApps(context.applicationContext), { Screens.config(context).blocklist }, g.runtime.pageRunner)
}

fun screenSkillTools(context: Context): List<Tool> = screenSkillTools(learning(context))

/** For a linked computer: the learning tools, and every learned phone tool as its own tool (the phone's own agent gets those from the learned-app list). */
fun screenLearnedTools(context: Context): List<Tool> {
    val l = learning(context)
    return screenSkillTools(l) + l.store.apps.value.filter { it.isScreen }.flatMap { a -> a.tools.map { LearnedCallTool(a.id, it, l.store, l.runner) } }
}
