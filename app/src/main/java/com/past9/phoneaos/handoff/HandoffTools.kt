package com.past9.phoneaos.handoff

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.machines.MachineService
import com.past9.phoneaos.machines.Ssh
import com.past9.phoneaos.machines.approve
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where the Agent Host listens on a machine. The phone never holds the Host's token and never opens its port: it reaches the
 * Host by SSH, on the machine itself (127.0.0.1), the same way it already runs commands there.
 */
data class HostSpot(val port: Int = 8787, val home: String? = null, val useService: Boolean = true) {
    /** Shell that sets H (host folder), P (port) and T (token) or stops with NO_HOST. */
    fun prelude() = "H=${home?.let { quote(it) } ?: "\"\${AGENT_HOST_HOME:-\$HOME/.agent-host}\""}; P=${port}\n" +
        "[ -f \"\$H/token\" ] || { echo NO_HOST; exit 3; }\nT=\"\$(cat \"\$H/token\")\"\n"
}

private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

class MachineHandoffTool(private val svc: MachineService, private val spot: HostSpot = HostSpot()) : Tool {
    override val spec = ToolSpec("machine_handoff",
        "Hand a whole job (a routine, tracker, research or build task) to one of the user's machines so it runs there, with its own agent, even when the phone is off or dead. It is NOT a command: give it the full task in plain words, as you would brief a helper, and say what result you want back. When it finishes, the result arrives here as a message from the machine, by itself; you don't poll. The machine needs the Agent Host (machine_host_setup installs it).",
        schema(listOf("machine", "task", "title"),
            "machine" to str("Machine name from machine_list"), "task" to str("The full brief for the job, self-contained: the machine's agent has none of this conversation"),
            "title" to str("Short name shown to the user, e.g. 'Weekly stock check'"), "ref" to str("Optional: the routine, tracker or task this belongs to, so the result can be filed against it"),
            "engine" to str("Optional: claude, codex, pi or opencode. Default: whatever the machine has")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val task = input.optString("task").trim().ifEmpty { return "No task." }
        val m = svc.need(input.optString("machine"))
        val title = input.optString("title").trim().ifEmpty { task.take(60) }
        approve(ctx, m, "Hand off \"$title\"", task.take(600), false)?.let { return it }
        val body = JSONObject().put("task", task).put("title", title).put("system", HANDOFF_SYSTEM)
        input.optString("ref").takeIf { it.isNotBlank() }?.let { body.put("ref", it) }
        input.optString("engine").takeIf { it.isNotBlank() }?.let { body.put("engine", it) }
        val end = "AGENT_JOB_" + java.util.UUID.randomUUID().toString().take(8)
        val script = spot.prelude() + "curl -sS -m 20 -X POST \"http://127.0.0.1:\$P/v1/jobs\" -H \"Authorization: Bearer \$T\" -H 'Content-Type: application/json' --data-binary @- <<'$end'\n$body\n$end\n" +
            "echo; curl -sS -m 10 \"http://127.0.0.1:\$P/v1/health\" -H \"Authorization: Bearer \$T\"\n"
        val r = svc.with(m.id) { _, s -> Ssh.run(s, "bash -s", 40, stdin = script) }
        if (r.output.contains("NO_HOST")) return "${m.name} doesn't have the Agent Host yet. Call machine_host_setup for it first."
        val lines = r.output.lines().map { it.trim() }.filter { it.startsWith("{") }
        val made = lines.getOrNull(0)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val id = made?.optString("id")?.takeIf { it.isNotBlank() } ?: return "The Host on ${m.name} didn't take the job: ${made?.optString("error")?.ifBlank { null } ?: r.output.trim().take(300).ifBlank { "no answer (is it running?)" }}"
        val linked = lines.getOrNull(1)?.let { runCatching { JSONObject(it).optBoolean("linked") }.getOrNull() } ?: false
        ctx.activity("Handed \"$title\" to ${m.name}", JSONObject().put("tool", "machine").put("machine", m.name).put("job", id))
        return "Handed \"$title\" to ${m.name} (job $id). " + if (linked) "It runs there on its own. When it finishes, its result arrives here as a message from the machine, even if the phone is closed; it shows when the app is next open. Don't wait for it or poll."
            else "WARNING: that machine isn't linked to the user's sync service, so its result will NOT come back by itself. Use machine_handoff_status to check it, or run machine_host_setup again while the phone is signed in to hand-offs."
    }
}

class MachineHandoffStatusTool(private val svc: MachineService, private val spot: HostSpot = HostSpot()) : Tool {
    override val spec = ToolSpec("machine_handoff_status", "List the jobs handed to a machine and how each is going (running, done, failed), or show one job's latest progress and result.",
        schema(listOf("machine"), "machine" to str("Machine name"), "job" to str("Optional job id; omit for all jobs")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val job = input.optString("job").trim().takeIf { it.matches(Regex("[\\w-]{1,64}")) }
        val m = svc.need(input.optString("machine"))
        val script = spot.prelude() + "curl -sS -m 15 \"http://127.0.0.1:\$P/v1/jobs${job?.let { "/$it" } ?: ""}\" -H \"Authorization: Bearer \$T\"\n"
        val r = svc.with(m.id) { _, s -> Ssh.run(s, "bash -s", 30, stdin = script) }
        if (r.output.contains("NO_HOST")) return "${m.name} doesn't have the Agent Host yet."
        val o = runCatching { JSONObject(r.output.trim()) }.getOrNull() ?: return "No answer from the Host on ${m.name}: ${r.output.trim().take(200)}"
        o.optString("error").takeIf { it.isNotBlank() }?.let { return "The Host on ${m.name} says: $it" }
        fun line(j: JSONObject) = "${j.optString("id")} · ${j.optString("title").ifBlank { j.optString("task").take(60) }} · ${j.optString("state")}" + j.optString("result").takeIf { it.isNotBlank() }?.let { " · ${it.take(400)}" }.orEmpty()
        val jobs: JSONArray? = o.optJSONArray("jobs")
        return if (jobs != null) (0 until jobs.length()).map { line(jobs.getJSONObject(it)) }.takeLast(15).joinToString("\n").ifBlank { "No jobs on ${m.name} yet." } else line(o)
    }
}

/** Puts the Agent Host on a machine and links it to the user's own sync service, so results come back by themselves. */
class MachineHostSetupTool(private val svc: MachineService, private val asset: () -> File, private val link: () -> Pair<String, String>?, private val spot: HostSpot = HostSpot()) : Tool {
    override val spec = ToolSpec("machine_host_setup",
        "Install the Agent Host on one of the user's machines (Linux, macOS or WSL with Node 18+), start it as a background service there, and link it to the user's sync service so finished jobs report back to this phone. Do this once per machine before machine_handoff. Safe to run again.",
        schema(listOf("machine"), "machine" to str("Machine name")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val m = svc.need(input.optString("machine"))
        val sync = link()
        approve(ctx, m, "Install the Agent Host", "Copies a small program to ~/.agent-host and starts it as a service on ${m.name}." + if (sync == null) " The phone isn't signed in to hand-offs yet, so results won't come back by themselves." else " It links to your sync service.", false)?.let { return it }
        val node = svc.with(m.id) { _, s -> Ssh.run(s, "node --version 2>&1 | head -1", 20) }.output.trim()
        val major = Regex("""v?(\d+)""").find(node)?.groupValues?.get(1)?.toIntOrNull()
        if (major == null || major < 18) return "${m.name} needs Node.js 18 or newer for the Agent Host (found: ${node.ifBlank { "none" }}). Install Node there first."
        val root = spot.home?.let { quote(it) } ?: "\"\${AGENT_HOST_HOME:-\$HOME/.agent-host}\""
        val script = buildString {
            appendLine("set -e; H=$root; export AGENT_HOST_HOME=\"\$H\"; B=\"\$H/bin/agent-host.mjs\"; mkdir -p \"\$H/bin\"")
            appendLine("node \"\$B\" pair --port ${spot.port} >/dev/null   # creates the Host's private token")
            if (sync != null) appendLine("node \"\$B\" link --sync ${quote(sync.first)} --token ${quote(sync.second)} --device ${quote("host-" + m.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'))}")
            appendLine("up() { for i in 1 2 3 4 5 6 7 8 9 10; do curl -sf -m 3 \"http://127.0.0.1:${spot.port}/v1/health\" -H \"Authorization: Bearer \$(cat \"\$H/token\")\" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }")
            if (spot.useService) appendLine("node \"\$B\" install --port ${spot.port} >/dev/null 2>&1 || true; systemctl --user restart agent-host >/dev/null 2>&1 || true")
            appendLine("if up; then echo HOST_READY; else")
            appendLine("  S=; command -v setsid >/dev/null 2>&1 && S=setsid")
            appendLine("  \$S nohup node \"\$B\" serve --port ${spot.port} > \"\$H/host.log\" 2>&1 < /dev/null & echo \$! > \"\$H/host.pid\"")
            appendLine("  if up; then echo HOST_READY_NO_SERVICE; fi")
            appendLine("fi")
        }
        // Upload first (the script needs the file), then run the script from stdin so the sync token never shows in a process list.
        svc.with(m.id) { _, s ->
            Ssh.run(s, "H=$root; mkdir -p \"\$H/bin\"; echo \"\$H/bin\"", 20).output.trim().lines().last().let { dir -> Ssh.upload(s, asset(), if (dir.startsWith("/")) "$dir/agent-host.mjs" else ".agent-host/bin/agent-host.mjs") }
        }
        val r = svc.with(m.id) { _, s -> Ssh.run(s, "bash -s", 120, stdin = script) }
        ctx.activity("Agent Host on ${m.name}", JSONObject().put("tool", "machine").put("machine", m.name))
        val linkNote = if (sync != null) " and linked to the user's sync service" else ", but NOT linked: the phone isn't signed in to hand-offs, so results won't come back by themselves (Settings > Hand-offs)"
        return when {
            r.output.contains("HOST_READY_NO_SERVICE") -> "The Agent Host is running on ${m.name}$linkNote. This machine has no background-service support, so it was started directly: it keeps running until the machine restarts, then run machine_host_setup again. You can use machine_handoff now."
            r.output.contains("HOST_READY") -> "The Agent Host is running on ${m.name} as a service$linkNote. You can use machine_handoff now."
            else -> "The Agent Host did not start on ${m.name} (exit ${r.exit}): ${r.output.trim().takeLast(500).ifBlank { "no output; see ~/.agent-host/host.log on the machine" }}"
        }
    }
}

private const val HANDOFF_SYSTEM = "You are running unattended on the user's own computer as a handed-off job. Do the whole task yourself without asking questions. " +
    "When you finish, your last message is sent back to the user's phone as the result: make it a short, plain summary of what you did and what they need to know. If you cannot finish, say exactly why in that last message."

fun handoffTools(svc: MachineService, asset: () -> File, link: () -> Pair<String, String>?): List<Tool> =
    listOf(MachineHandoffTool(svc), MachineHandoffStatusTool(svc), MachineHostSetupTool(svc, asset, link))
