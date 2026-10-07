package com.past9.phoneaos.triggers

import android.content.Context
import com.past9.phoneaos.App
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.data.SubKind
import com.past9.phoneaos.data.TriggerRow
import com.past9.phoneaos.runtime.SubscriptionRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Watchers: small scripts the agent writes for things with no API (a Takealot price, a stock level,
 * a page that changes). They run on a timer with NO model call at all; the agent is only woken when
 * the script says something happened, or when the script breaks and needs fixing.
 *
 * Contract for the script: Node ESM. Previous state arrives as JSON in process.env.WATCH_STATE.
 * Its LAST line of output must be JSON: {"fire": bool, "message": "...", "state": {...}}.
 */
object Watchers {
    const val KIND = "watch"
    private const val BREAKS_BEFORE_REPAIR = 2

    fun dir(context: Context) = File(context.filesDir, "watchers").apply { mkdirs() }
    fun scriptFile(context: Context, id: Long) = File(dir(context), "$id.mjs")

    data class Outcome(val ok: Boolean, val fire: Boolean, val message: String, val state: String, val raw: String, val json: JSONObject? = null)

    suspend fun execute(context: Context, script: String, state: String, extraEnv: Map<String, String> = emptyMap()): Outcome = withContext(Dispatchers.IO) {
        val rt = SubscriptionRuntime(context, SubKind.CLAUDE)
        if (!rt.nodeAvailable) return@withContext Outcome(false, false, "", state, "Node isn't available in this build.")
        val work = File(context.filesDir, "workspace").apply { mkdirs() }
        val f = File(work, ".watch-${System.nanoTime()}.mjs").apply { writeText(script) }
        rt.linkBins()
        val p = ProcessBuilder(File(rt.bin, "node").path, f.path).directory(work).redirectErrorStream(true)
            .apply { environment().putAll(rt.env()); environment()["HOME"] = work.path; environment()["WATCH_STATE"] = state.ifBlank { "{}" }; environment().putAll(extraEnv) }.start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { if (out.length < 20_000) out.appendLine(it) } } }.apply { start() }
        val finished = p.waitFor(90, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(2000); f.delete()
        val text = out.toString().trim()
        val json = text.lineSequence().toList().asReversed().firstNotNullOfOrNull { l -> runCatching { JSONObject(l.trim()) }.getOrNull() }
        if (!finished || p.exitValue() != 0 || json == null) return@withContext Outcome(false, false, "", state, (if (!finished) "Timed out after 90 s.\n" else "") + text.takeLast(3000))
        Outcome(true, json.optBoolean("fire"), json.optString("message"), json.optJSONObject("state")?.toString() ?: state, text.takeLast(1000), json)
    }

    /** One tick of a watcher. Returns the line shown as "Last:" on the routine. */
    suspend fun tick(context: Context, t: TriggerRow): String {
        val g = App.graph(context)
        val file = scriptFile(context, t.id)
        if (!file.exists()) return "No script yet."
        val meta = runCatching { JSONObject(t.cursor) }.getOrElse { JSONObject() }
        val o = execute(context, file.readText(), meta.optString("state", "{}"))
        if (!o.ok) {
            val breaks = meta.optInt("breaks") + 1
            meta.put("breaks", breaks)
            g.db.triggers().upsert(t.copy(cursor = meta.toString()))
            if (breaks >= BREAKS_BEFORE_REPAIR) {
                meta.put("breaks", 0); g.db.triggers().upsert(t.copy(cursor = meta.toString()))
                g.runtime.runBackground("Fix watcher: ${t.name}", "Your watcher #${t.id} \"${t.name}\" broke (the site probably changed). Output:\n${o.raw}\n\nCurrent script:\n```js\n${file.readText()}\n```\nFind out what changed (browser, run_code), fix the script, test it with run_code, then save it with watcher_save using id ${t.id}. Only tell the user if you can't fix it.")
                return "Broke twice, asked your agent to fix it"
            }
            return "Didn't work this time, will retry"
        }
        meta.put("breaks", 0).put("state", o.state)
        g.db.triggers().upsert(t.copy(cursor = meta.toString()))
        if (o.fire) {
            g.runtime.runBackground(t.name, "Your watcher \"${t.name}\" just fired: ${o.message}\n\n${t.prompt}")
            return "Fired: ${o.message}"
        }
        return o.message.ifBlank { "Checked, nothing yet" }
    }
}

class WatcherSaveTool(private val context: Context, private val onChange: suspend (TriggerRow) -> Unit) : Tool {
    override val spec = ToolSpec("watcher_save",
        "Create or update a WATCHER: a small script that checks something on a timer WITHOUT waking you, and only wakes you when it matters. Use it for anything with no API or connected app: a product price on Takealot or Amazon, stock coming back, a page changing, a result being posted. " +
            "How: first find the cheapest reliable source yourself (the site's JSON endpoint seen in the page, its HTML, an RSS feed) with the browser and run_code; write the script; TEST it with run_code; then save it here. " +
            "Script rules: Node 24 ESM, use fetch (send a normal browser User-Agent). Previous state is JSON in process.env.WATCH_STATE. Print as the LAST line one JSON object: {\"fire\": true/false, \"message\": \"what happened, for the user\", \"state\": {...anything to remember next time}}. " +
            "fire only when the user should hear about it (e.g. price at or below their target). If the script breaks twice in a row you are woken to fix it. Pass id to update an existing watcher.",
        schema(listOf("name", "every_minutes", "script", "on_fire"),
            "name" to str("Short name, e.g. 'Monitor under R 2,300'"),
            "every_minutes" to int("How often to check, at least 15. Daily = 1440"),
            "script" to str("The tested Node script"),
            "on_fire" to str("What you will do when it fires, as an instruction to yourself (e.g. 'Tell the user which monitor hit the price with the link, and offer to buy it')"),
            "id" to int("Existing watcher id to update (optional)")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val dao = App.graph(context).db.triggers()
        val minutes = input.optInt("every_minutes", 1440).coerceAtLeast(15)
        val existing = input.optLong("id", 0L).takeIf { it > 0 }?.let { dao.get(it) }
        val row = (existing ?: TriggerRow(name = "", kind = Watchers.KIND, spec = "", prompt = "")).copy(
            name = input.optString("name").ifBlank { existing?.name ?: "Watcher" }, kind = Watchers.KIND, spec = minutes.toString(),
            prompt = input.optString("on_fire"), enabled = true)
        val id = dao.upsert(row)
        Watchers.scriptFile(context, id).writeText(input.optString("script"))
        onChange(row.copy(id = id))
        ctx.activity("${if (existing == null) "New" else "Updated"} watcher: ${row.name}", JSONObject().put("tool", "routines"))
        // Run it once now so a broken script shows up straight away, not at the first tick.
        val first = Watchers.execute(context, input.optString("script"), "{}")
        if (first.ok) dao.upsert(row.copy(id = id, cursor = JSONObject().put("state", first.state).toString(), lastRunAt = System.currentTimeMillis(), lastResult = first.message.ifBlank { "Checked, nothing yet" }))
        return if (!first.ok) "Saved as watcher #$id, BUT the first run failed:\n${first.raw}\nFix the script and save again with id $id."
        else "Watcher #$id saved. Checks every $minutes min. First run: fire=${first.fire} ${first.message}"
    }
}
