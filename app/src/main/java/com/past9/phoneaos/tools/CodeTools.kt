package com.past9.phoneaos.tools

import android.content.Context
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.data.SubKind
import com.past9.phoneaos.runtime.SubscriptionRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The agent's own little computer: Node 24 bundled in the app, a private workspace folder, no
 * access outside the app. It can calculate, transform data, build files (CSV, HTML, SVG, PNG via
 * canvas-free tricks) and fetch from the web. Images it makes are shown to the user and to itself.
 */
class RunCodeTool(private val context: Context) : Tool {
    private val rt = SubscriptionRuntime(context, SubKind.CLAUDE)
    val workspace: File get() = File(context.filesDir, "workspace").apply { mkdirs() }

    override val spec = ToolSpec("run_code", "Run JavaScript (Node 24, ES modules) in your private workspace on the phone. Use it to calculate, parse or transform data, call web APIs with fetch, and create files (CSV, JSON, HTML, SVG, Markdown). Files you write into the current folder are kept; images (.png/.jpg/.svg) are shown to the user and to you. console.log what you want back. No npm installs.",
        schema(listOf("code", "purpose"), "code" to str("The JavaScript to run"), "purpose" to str("One line: what this does, shown to the user")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        if (!rt.nodeAvailable) return@withContext "Code isn't available in this build."
        val id = ctx.activity("Running code: ${input.optString("purpose").take(60)}", JSONObject().put("tool", "code"))
        val before = workspace.listFiles().orEmpty().associate { it.name to it.lastModified() }
        val script = File(workspace, ".run-${System.currentTimeMillis()}.mjs").apply { writeText(input.optString("code")) }
        rt.linkBins()
        val p = ProcessBuilder(File(rt.bin, "node").path, script.path).directory(workspace).redirectErrorStream(true)
            .apply { environment().putAll(rt.env()); environment()["HOME"] = workspace.path }.start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { if (out.length < 20_000) out.appendLine(it) } } }.apply { start() }
        val finished = p.waitFor(90, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(2000); script.delete()
        val changed = workspace.listFiles().orEmpty().filter { !it.name.startsWith(".") && before[it.name] != it.lastModified() }
        val images = changed.filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") }
        images.forEach { ctx.activity(it.name, JSONObject().put("tool", "code").put("image", it.path)) }
        ctx.updateActivity(id, (if (!finished) "Code timed out: " else if (p.exitValue() == 0) "Ran code: " else "Code failed: ") + input.optString("purpose").take(60))
        buildString {
            append(if (!finished) "Timed out after 90 s.\n" else "Exit ${p.exitValue()}\n")
            append(out.toString().ifBlank { "(no output)" })
            if (changed.isNotEmpty()) append("\nFiles written: " + changed.joinToString { "${it.name} (${it.length()} bytes)" })
            images.forEach { append("\n[[image:${it.path}]]") }
        }
    }
}
