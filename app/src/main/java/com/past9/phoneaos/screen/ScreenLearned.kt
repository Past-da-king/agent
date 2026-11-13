package com.past9.phoneaos.screen

import android.content.Context
import com.past9.phoneaos.App
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.connections.LearnedApp
import com.past9.phoneaos.connections.LearnedStore
import com.past9.phoneaos.connections.LearnedTool
import com.past9.phoneaos.connections.WebApps
import org.json.JSONArray
import org.json.JSONObject

/**
 * Learned apps for apps on the phone itself. The loop: the agent (or the person, by hand) does a job once on the
 * background screen; the steps of that run are saved as a tool of a learned app, with the changing parts as inputs;
 * from then on every agent calls the tool. A run that works tightens the steps; a run that stops hands the screen
 * back to the agent at that point, and saving again repairs the tool. Earlier steps are kept, so a repair can be undone.
 *
 * They live in the same store as learned web apps (connections/Learned.kt), as apps of kind "screen": the person
 * sees, renames and removes them in the same place.
 */
object ScreenLearned {
    const val MAX_VERSIONS = 5

    /** [tool] with [steps] as its steps, the ones it had kept as an earlier version. */
    fun withSteps(tool: LearnedTool, steps: JSONArray, why: String, at: Long): LearnedTool {
        val kept = JSONArray()
        val all = (0 until tool.versions.length()).map { tool.versions.getJSONObject(it) } + JSONObject().put("steps", tool.steps).put("at", at).put("why", why)
        all.takeLast(MAX_VERSIONS).forEach { kept.put(it) }
        return tool.copy(steps = steps, versions = kept)
    }

    /** [tool] with the steps it had before its last change, or null when there is nothing to go back to. */
    fun undone(tool: LearnedTool): LearnedTool? {
        val n = tool.versions.length(); if (n == 0) return null
        val kept = JSONArray(); for (i in 0 until n - 1) kept.put(tool.versions.getJSONObject(i))
        return tool.copy(steps = tool.versions.getJSONObject(n - 1).optJSONArray("steps") ?: return null, versions = kept)
    }

    fun stats(tool: LearnedTool, ok: Boolean, ms: Long): JSONObject = JSONObject(tool.stats.toString()).apply {
        put("runs", optInt("runs") + 1)
        if (ok) { put("ok", optInt("ok") + 1); put("lastMs", ms); if (!has("bestMs") || ms < optLong("bestMs")) put("bestMs", ms); if (!has("firstMs")) put("firstMs", ms) }
        else put("fails", optInt("fails") + 1)
    }

    private fun secs(ms: Long) = "%.1f s".format(ms / 1000.0)

    /** The id a phone app's learned app gets: its name, kept apart from a learned web app of the same name. */
    fun idFor(store: LearnedStore, label: String, pkg: String): String {
        store.apps.value.firstOrNull { it.isScreen && it.pkg == pkg }?.let { return it.id }
        val base = LearnedApp.slug(label).ifBlank { LearnedApp.slug(pkg.substringAfterLast('.')) }.ifBlank { "app" }
        return if (store.apps.value.none { it.id == base }) base else (base.take(17) + "_phone")
    }

    /** Run a learned tool of an app on the phone. */
    suspend fun run(context: Context, app: LearnedApp, tool: LearnedTool, args: JSONObject, ctx: ToolContext): String =
        run(Screens.session(context), App.graph(context).runtime.learned, InstalledApps(context), Screens.config(context).blocklist, app, tool, args, ctx)

    suspend fun run(session: ScreenSession, store: LearnedStore, apps: ScreenApps, blocklist: ScreenBlocklist, app: LearnedApp, tool: LearnedTool, args: JSONObject, ctx: ToolContext): String {
        val ref = apps.find(app.pkg) ?: return "Error: ${app.name} is not installed on this phone any more."
        // A learned app can never be a way into a banking or money app, whatever was saved.
        if (blocklist.blocked(ref.pkg, ref.label) != null) return ScreenBlocklist.reason(ref.label)
        val missing = ScreenSkills.inputs(tool.steps).filter { !args.has(it) || args.isNull(it) }
        if (missing.isNotEmpty()) return "Missing input: ${missing.joinToString()}."
        for (k in ScreenSkills.inputs(tool.steps)) ScreenSkills.badInput(tool.params.optJSONObject(k)?.optString("kind").orEmpty(), args.opt(k).toString())?.let { return "Not run: the input $k $it." }
        val line = "${app.name}: ${tool.name.replace('_', ' ')}"
        val id = runCatching { ctx.activity(line, JSONObject().put("tool", "screen").put("app", ref.pkg)) }.getOrDefault(0L)
        val r = session.replay(ctx, ref, tool.steps, args)
        val current = store.find(app.id)?.tools?.firstOrNull { it.name == tool.name } ?: tool
        if (r.ok) {
            val tighter = ScreenSkills.refine(current.steps, r.stepMs, r.skipped)
            val next = (if (tighter != null) withSteps(current, tighter, "A run that worked: ${if (r.skipped.isNotEmpty()) "${r.skipped.size} step(s) were not needed" else "waits fitted to how long each step took"}", System.currentTimeMillis()) else current)
                .copy(stats = stats(current, true, r.tookMs))
            store.replaceTool(app.id, tool.name, next)
            runCatching { ctx.updateActivity(id, "$line (${secs(r.tookMs)})", JSONObject().put("tool", "screen").put("app", ref.pkg)) }
            // The job is over: the screen is free for the next one, and the app stays warm so that one starts fast.
            runCatching { session.release() }
            val live = current.stats.optLong("liveMs")
            val names = (0 until current.steps.length()).map { current.steps.getJSONObject(it).optString("do") }
            val times = r.stepMs.mapIndexed { i, ms -> "${i + 1} ${names.getOrElse(i) { "step" }} ${if (i in r.skipped) "skipped" else "$ms ms"}" }.joinToString(", ")
            return "Done the learned way in ${secs(r.tookMs)}" + (if (r.approvalMs > 0) ", of which ${secs(r.approvalMs)} was waiting for the user's approval" else "") + ". Steps: $times" + (if (live > 0) " (working it out by hand the first time took ${secs(live)})" else "") +
                (if (r.skipped.isNotEmpty()) "; ${r.skipped.size} step(s) were no longer needed and were dropped" else "") +
                (if (r.usedPicture) "; one step was found by reading the picture, because the app's list had changed" else "") +
                ". The app is kept ready on the background screen for a few minutes and closes by itself (no screen_close needed).\n" +
                (if (r.values.length() > 0) "RESULT: ${r.values}\n" else "") + r.text
        }
        store.replaceTool(app.id, tool.name, current.copy(stats = stats(current, false, r.tookMs)))
        runCatching { ctx.updateActivity(id, "$line (stopped)", JSONObject().put("tool", "screen").put("app", ref.pkg)) }
        if (r.failedAt < 0) return r.text
        return "The learned way stopped part of the way. ${r.text}\n\nThe app is still open at that point. Carry on by hand with the screen tools from here. " +
            "When the job is done, call screen_skill_save with name \"${tool.name}\" again to repair the learned tool: the old steps are kept and can be brought back."
    }

    /**
     * Save what was done on the background screen in this run as a tool of a learned app.
     * [inputs]: input name to the value it had in this run. The person approves the steps.
     */
    suspend fun save(session: ScreenSession, store: LearnedStore, apps: ScreenApps, blocklist: ScreenBlocklist, ctx: ToolContext,
                     name: String, description: String, inputs: Map<String, String>, readOnly: Boolean, by: String = "agent", expect: String = "",
                     returns: Map<String, String> = emptyMap(), types: Map<String, String> = emptyMap(),
                     /** A read-only job the agent's own tool just proved works (play_media's play job): saved without the approval card. A job that is not read-only is never saved this way. */
                     autoApprove: Boolean = false): String {
        val run = session.runSteps; val pkg = session.runPackage
        if (pkg.isBlank() || run.none { it.kind in ScreenSkills.DOING && it.kind != "open" }) return "Not saved: nothing has been done on the background screen in this run yet. Do the job first, then save it."
        val label = apps.label(pkg)
        if (blocklist.blocked(pkg, label) != null) return ScreenBlocklist.reason(label)
        if (!Regex("^[a-z][a-z0-9_]{1,29}$").matches(name)) return "Not saved: the name must be lower-case letters, digits and underscores, 2 to 30 characters, starting with a letter (e.g. search)."
        if (description.isBlank()) return "Not saved: add a description saying what it does, what it returns and when to use it."
        inputs.keys.firstOrNull { !Regex("^[a-zA-Z][a-zA-Z0-9_]{0,29}$").matches(it) }?.let { return "Not saved: the input name \"$it\" must be plain letters, digits and underscores." }
        val (steps, notes) = ScreenSkills.fromRun(run, inputs)
        // What the final screen must show for the job to count as done: what the caller said, else an input that ended up on the screen (the sent message, the question asked).
        val words = session.screenWords()
        val end = expect.trim().ifBlank { inputs.values.firstOrNull { v -> v.length >= 3 && words.any { it.contains(v, true) } }.orEmpty() }
        if (end.isNotBlank()) steps.put(JSONObject().put("do", "check").put("text", ScreenSkills.templatize(end, inputs)))
        for ((n, v) in returns) steps.put(ScreenSkills.readStep(n, v, session.screenElements(), inputs)
            ?: return "Not saved: the value to return as \"$n\" (\"${v.take(40)}\") is not on the screen now. Give its exact text as the screen shows it (screen_look).")
        val used = ScreenSkills.inputs(steps)
        inputs.entries.firstOrNull { it.key !in used }?.let { return "Not saved: the value of the input \"${it.key}\" (\"${it.value.take(40)}\") was not typed or pressed in this run, so it cannot be an input. Give the exact text that was used." }
        if (steps.length() > 60) return "Not saved: ${steps.length()} steps is too many for one tool. Close the screen, do the job again the short way, and save that."
        val id = idFor(store, label, pkg)
        val existing = store.find(id)?.takeIf { it.isScreen }
        if (existing == null && store.apps.value.size >= LearnedStore.MAX_APPS) return "Not saved: too many learned apps (${LearnedStore.MAX_APPS}). Remove one first."
        val old = existing?.tools?.firstOrNull { it.name == name }
        if (old == null && (existing?.tools?.size ?: 0) >= LearnedStore.MAX_TOOLS) return "Not saved: ${existing!!.name} already has ${LearnedStore.MAX_TOOLS} tools."
        val params = JSONObject()
        for (k in used) params.put(k, JSONObject().put("type", "string").put("kind", types[k].orEmpty().trim().lowercase().let { if (it.startsWith("choice:")) "choice:" + types[k]!!.trim().substringAfter(':') else it }).put("description",
            if (k.startsWith("secret")) "A hidden text, typed where the run typed one. It is never stored." else ScreenSkills.inputHelp(types[k].orEmpty().trim()) + "What changes each time. When this was learned it was \"${inputs[k].orEmpty().take(60)}\"."))
        val liveMs = (run.last().at - run.first().at).coerceAtLeast(0)
        val fresh = LearnedTool(name, description.trim(), params, used, "", readOnly, steps, JSONArray(), JSONObject().put("liveMs", liveMs).put("by", by))
        val tool = if (old == null) fresh else withSteps(old, steps, "Saved again after it was done by hand", System.currentTimeMillis())
            .copy(description = description.trim(), params = params, required = used, readOnly = readOnly, stats = JSONObject(old.stats.toString()).put("repairs", old.stats.optInt("repairs") + 1))
        val details = buildString {
            appendLine(description.trim())
            appendLine("It does these steps in the real $label app, on the background screen:")
            appendLine(ScreenSkills.describe(steps))
            if (used.isNotEmpty()) appendLine("Changes each time: ${used.joinToString()}.")
            notes.distinct().forEach { appendLine(it) }
            append("A step that sends, pays or deletes will still ask you every time.")
        }
        if (autoApprove && readOnly) Unit
        else when (kotlinx.coroutines.withTimeoutOrNull(100_000) { ctx.ask("APPROVAL|${if (old != null) "Update" else "Save"} a learned way to use $label: ${name.replace('_', ' ')}|$details", listOf("Approve", "Decline")) }) {
            "Approve" -> {}
            null -> return "No answer on the phone in time (the card is in the chat and in a notification). Not saved yet: call screen_skill_save again when the user is at the phone."
            else -> return "DECLINED. Not saved. Ask what the user wants changed."
        }
        val app = (existing ?: LearnedApp(id, label, "app://$pkg", "The $label app on this phone, used on the background screen.", kind = LearnedApp.SCREEN, pkg = pkg))
        store.save(app.copy(tools = app.tools.filter { it.name != name } + tool, kind = LearnedApp.SCREEN, pkg = pkg))
        return "Saved. ${WebApps.toolName(true, id, name)} is now a tool for you, your helpers and a linked computer" +
            (if (used.isNotEmpty()) ", with the input${if (used.size == 1) "" else "s"} ${used.joinToString()}" else "") + ". It has ${steps.length()} steps" +
            (if (old != null) "; the earlier steps are kept, so this can be undone (screen_skill_undo)." else ".")
    }

    fun list(store: LearnedStore): String {
        val apps = store.apps.value.filter { it.isScreen }
        if (apps.isEmpty()) return "No learned phone apps yet. Do a job on the background screen with the screen tools, then save it with screen_skill_save."
        return apps.joinToString("\n\n") { a ->
            "${a.name} (${a.pkg})\n" + a.tools.joinToString("\n") { t ->
                val s = t.stats
                "- ${WebApps.toolName(a, t)}(${ScreenSkills.inputs(t.steps).joinToString()}): ${t.description} [${t.steps.length()} steps, run ${s.optInt("runs")} times" +
                    (if (s.has("lastMs")) ", last ${secs(s.optLong("lastMs"))}" else "") + (if (s.optInt("fails") > 0) ", stopped ${s.optInt("fails")} times" else "") +
                    (if (t.versions.length() > 0) ", ${t.versions.length()} earlier version(s) kept" else "") + "]"
            }
        }
    }
}
