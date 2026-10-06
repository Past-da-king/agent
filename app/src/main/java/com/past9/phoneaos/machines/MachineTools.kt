package com.past9.phoneaos.machines

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import org.json.JSONObject
import java.io.File

/** Opens sessions to the user's machines: resolves secrets and gateways, pins the host key on first use. */
class MachineService(val store: MachineStore) {
    fun creds(m: Machine) = Creds(store.password(m.id), store.privateKey(m.id), store.passphrase(m.id))

    fun open(m: Machine): Ssh.Session {
        val via = m.via?.let { store.get(it) }?.let { it to creds(it) }
        val s = Ssh.open(m, creds(m), via)
        if (m.hostKey == null && s.hostKey.isNotBlank()) store.update(m.id) { it.copy(hostKey = s.hostKey) }
        return s
    }

    /** Connect, sign in and find out what the machine is. Null when it worked, otherwise why not. */
    suspend fun test(m: Machine): String? = runCatching {
        open(m).use { s ->
            val r = Ssh.run(s, "uname -sr 2>/dev/null || ver; nproc 2>/dev/null || echo %NUMBER_OF_PROCESSORS%", timeoutSec = 20)
            val lines = r.output.lines().map { it.trim() }.filter { it.isNotBlank() }
            val about = listOfNotNull(lines.getOrNull(0), lines.getOrNull(1)?.toIntOrNull()?.let { "$it cores" }).joinToString(" · ")
            store.update(m.id) { it.copy(about = about) }
        }
    }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

    fun need(ref: String): Machine = store.find(ref) ?: throw SshException("No machine called \"$ref\". Machines: ${store.machines.value.joinToString { it.name }.ifBlank { "none yet (the user adds them in Connections)" }}.")

    suspend fun <T> with(ref: String, block: suspend (Machine, Ssh.Session) -> T): T { val m = need(ref); return open(m).use { block(m, it) } }
}

/**
 * Commands that only look are run without asking; anything that could change the machine needs the
 * user's yes (unless they trusted that machine). Deliberately conservative: unknown means ask.
 */
object CommandSafety {
    private val readOnly = setOf("ls", "cat", "head", "tail", "less", "wc", "df", "du", "free", "uptime", "uname", "whoami", "id", "pwd", "ps", "nproc",
        "which", "type", "echo", "grep", "egrep", "rg", "find", "stat", "file", "hostname", "date", "lscpu", "lsblk", "nvidia-smi", "top", "env", "printenv",
        "tree", "md5sum", "sha256sum", "diff", "realpath", "dirname", "basename", "groups", "w", "who", "last", "journalctl", "systemctl", "docker", "git", "sort", "uniq", "cut", "awk", "jq", "column", "true")
    private val gitRead = setOf("status", "log", "diff", "show", "branch", "remote", "rev-parse", "describe", "ls-files", "blame", "tag")
    private val systemctlRead = setOf("status", "is-active", "is-enabled", "list-units", "list-timers", "show", "cat")
    private val dockerRead = setOf("ps", "images", "logs", "inspect", "stats", "version", "info")

    fun isReadOnly(command: String): Boolean {
        val c = command.trim()
        if (c.isEmpty() || Regex("""[>`]|\$\(|\bsudo\b|\bxargs\b|-exec|-delete|\bsed\s+-i|\bawk\b.*system\(""").containsMatchIn(c)) return false
        return c.split(Regex("""\|\||&&|[;|&\n]""")).map { it.trim() }.filter { it.isNotEmpty() }.all { seg ->
            val words = seg.split(Regex("\\s+")).filter { !it.contains('=') || it.startsWith("-") }
            val first = words.firstOrNull()?.substringAfterLast('/') ?: return@all false
            val sub = words.drop(1).firstOrNull { !it.startsWith("-") }
            first in readOnly && when (first) {
                "git" -> sub in gitRead
                "systemctl" -> sub == null || sub in systemctlRead
                "docker" -> sub in dockerRead
                "top" -> words.contains("-b")
                else -> true
            }
        }
    }
}

private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
private fun dir(cwd: String?) = cwd?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it == "~") "\"\$HOME\"" else if (it.startsWith("~/")) "\"\$HOME\"/" + quote(it.removePrefix("~/")) else quote(it) }

private suspend fun approve(ctx: ToolContext, m: Machine, what: String, details: String, readOnly: Boolean): String? {
    if (m.trusted || readOnly) return null
    val a = ctx.ask("APPROVAL|$what on ${m.name}|$details", listOf("Approve", "Decline"))
    return when (a) { "Approve" -> null; null -> "Needs the user's approval and nobody is around. Not done."; else -> "The user declined. Not done." }
}

class MachineListTool(private val svc: MachineService) : Tool {
    override val spec = ToolSpec("machine_list", "List the computers and servers the user connected (Machines in Connections): name, address, what they are, and whether commands need approval.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = svc.store.machines.value.ifEmpty { return "No machines yet. The user can add any server or computer they have SSH access to in Connections > Machines." }
        .joinToString("\n") { "${it.name}: ${it.address}${if (it.about.isNotBlank()) " · ${it.about}" else ""}${it.via?.let { v -> " · through ${svc.store.get(v)?.name ?: v}" } ?: ""} · ${if (it.trusted) "trusted (no approval needed)" else "changes need approval"}" }
}

class MachineRunTool(private val svc: MachineService) : Tool {
    override val spec = ToolSpec("machine_run", "Run a shell command on one of the user's machines over SSH and get its output and exit code. For anything over ~2 minutes (builds, installs, training, big downloads) use machine_job_start instead. Commands that change things ask the user first unless the machine is trusted.",
        schema(listOf("machine", "command"), "machine" to str("Machine name from machine_list"), "command" to str("Shell command"), "cwd" to str("Directory to run in, e.g. ~/projects/site"), "timeout_sec" to int("Default 120, max 600")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val cmd = input.optString("command").trim().ifEmpty { return "No command." }
        val full = dir(input.optString("cwd").takeIf { it.isNotBlank() })?.let { "cd $it && $cmd" } ?: cmd
        val m = svc.need(input.optString("machine"))
        approve(ctx, m, "Run", full, CommandSafety.isReadOnly(cmd))?.let { return it }
        val id = ctx.activity("${m.name}: ${cmd.take(80)}", JSONObject().put("tool", "machine").put("machine", m.name).put("command", full))
        val r = svc.with(m.id) { _, s -> Ssh.run(s, full, input.optLong("timeout_sec", 120).coerceIn(5, 600)) }
        ctx.updateActivity(id, "${m.name}: ${cmd.take(80)}", JSONObject().put("exit", r.exit ?: -1).put("result", r.output.takeLast(4000)))
        return (if (r.timedOut) "Timed out (still running when cut off). Use machine_job_start for long work.\n" else "exit ${r.exit}\n") + r.output.ifBlank { "(no output)" }
    }
}

class MachineJobStartTool(private val svc: MachineService) : Tool {
    override val spec = ToolSpec("machine_job_start", "Start long or heavy work on a machine (build a website, compile, install, run a script for an hour) detached in the background, so it keeps running after this call returns. Output goes to a log; check it with machine_job_status. Needs bash on the machine (Linux, macOS, WSL).",
        schema(listOf("machine", "command", "label"), "machine" to str("Machine name"), "command" to str("Shell commands to run (can be several lines)"), "label" to str("Short name for the job, e.g. build-site"), "cwd" to str("Directory to run in")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val cmd = input.optString("command").trim().ifEmpty { return "No command." }
        val label = input.optString("label").lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(40).ifEmpty { "job" }
        val m = svc.need(input.optString("machine"))
        approve(ctx, m, "Start job \"$label\"", (input.optString("cwd").takeIf { it.isNotBlank() }?.let { "cd $it\n" } ?: "") + cmd, false)?.let { return it }
        val script = buildString {
            appendLine("set -e; J=\"\$HOME/.agent-jobs\"; mkdir -p \"\$J\"")
            appendLine("cat > \"\$J/$label.sh\" <<'AGENT_JOB_EOF'")
            dir(input.optString("cwd").takeIf { it.isNotBlank() })?.let { appendLine("cd $it || exit 1") }
            appendLine(cmd); appendLine("AGENT_JOB_EOF")
            appendLine("S=; command -v setsid >/dev/null 2>&1 && S=setsid")
            appendLine("\$S nohup bash -c 'bash -l \"\$0\"; echo \"[exit \$?]\"' \"\$J/$label.sh\" > \"\$J/$label.log\" 2>&1 < /dev/null &")
            appendLine("echo \$! > \"\$J/$label.pid\"; echo \"started pid \$(cat \"\$J/$label.pid\")\"")
        }
        val r = svc.with(m.id) { _, s -> Ssh.run(s, "bash -s", 30, stdin = script) }
        ctx.activity("${m.name}: started $label", JSONObject().put("tool", "machine").put("machine", m.name).put("command", cmd))
        return if (r.exit == 0) "Started \"$label\" on ${m.name} (${r.output.trim()}). Log: ~/.agent-jobs/$label.log. Check it with machine_job_status; don't wait in a loop, schedule a check if it will take long."
        else "Couldn't start it (exit ${r.exit}): ${r.output}"
    }
}

class MachineJobStatusTool(private val svc: MachineService) : Tool {
    override val spec = ToolSpec("machine_job_status", "Check a job started with machine_job_start: RUNNING or FINISHED (with its exit code) and the end of its log.",
        schema(listOf("machine", "label"), "machine" to str("Machine name"), "label" to str("The job's label"), "lines" to int("Log lines to show, default 40")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val label = input.optString("label").lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
        val n = input.optInt("lines", 40).coerceIn(5, 400)
        val script = "J=\"\$HOME/.agent-jobs/$label\"; [ -f \"\$J.pid\" ] || { echo NO_SUCH_JOB; ls \"\$HOME/.agent-jobs\" 2>/dev/null | sed -n 's/\\.pid\$//p'; exit 0; }\n" +
            "if kill -0 \$(cat \"\$J.pid\") 2>/dev/null; then echo RUNNING; else echo FINISHED; fi\ntail -n $n \"\$J.log\"\n"
        val r = svc.with(input.optString("machine")) { _, s -> Ssh.run(s, "bash -s", 30, stdin = script) }
        return r.output.ifBlank { "(no output)" }
    }
}

class MachineUploadTool(private val svc: MachineService) : Tool {
    override val spec = ToolSpec("machine_upload", "Copy a file from the phone (a path you have, e.g. from files_pick or a download) to a machine.",
        schema(listOf("machine", "local_path", "remote_path"), "machine" to str("Machine name"), "local_path" to str("File on the phone"), "remote_path" to str("Destination path on the machine")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val f = File(input.optString("local_path")); if (!f.isFile) return "No such file on the phone: ${f.path}"
        val m = svc.need(input.optString("machine"))
        val remote = input.optString("remote_path")
        approve(ctx, m, "Upload a file", "${f.name} (${f.length() / 1024} KB) to $remote", false)?.let { return it }
        svc.with(m.id) { _, s -> Ssh.upload(s, f, remote) }
        ctx.activity("${m.name}: uploaded ${f.name}", JSONObject().put("tool", "machine"))
        return "Uploaded ${f.name} to ${m.name}:$remote"
    }
}

class MachineDownloadTool(private val svc: MachineService, private val dir: File) : Tool {
    override val spec = ToolSpec("machine_download", "Copy a file from a machine to the phone (up to 100 MB), e.g. a build artifact or report, so you can send it to the user.",
        schema(listOf("machine", "remote_path"), "machine" to str("Machine name"), "remote_path" to str("File path on the machine")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val remote = input.optString("remote_path")
        val out = File(dir, remote.substringAfterLast('/').ifBlank { "file" }.replace(Regex("[^A-Za-z0-9._-]"), "_")).also { dir.mkdirs() }
        val m = svc.with(input.optString("machine")) { m, s -> Ssh.download(s, remote, out); m }
        ctx.activity("${m.name}: downloaded ${out.name}", JSONObject().put("tool", "machine"))
        return "Saved to ${out.absolutePath} (${out.length() / 1024} KB)"
    }
}

fun machineTools(svc: MachineService, downloads: File): List<Tool> = listOf(MachineListTool(svc), MachineRunTool(svc), MachineJobStartTool(svc), MachineJobStatusTool(svc), MachineUploadTool(svc), MachineDownloadTool(svc, downloads))
