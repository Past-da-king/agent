package com.past9.phoneaos.runtime

import android.content.Context
import com.past9.phoneaos.agent.sharedHttp
import com.past9.phoneaos.data.SubKind
import com.past9.phoneaos.ui.screens.RuntimeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/** What each subscription needs on the phone. */
data class SubSpec(
    val kind: SubKind,
    /** npm packages installed into filesDir/runtime (kept for compatibility; the node pack carries them). */
    val packages: List<String>,
    /** The bridge script in assets/runtime that runs one turn and prints JSON lines. */
    val bridge: String,
    /** Native executables this runtime needs, by name in filesDir/runtime/bin. */
    val nativeBins: List<String>,
    /** Environment variable the bridge reads the user's sign-in from. */
    val tokenEnv: String,
    val signInHelp: String,
)

val SubSpecs = mapOf(
    SubKind.CLAUDE to SubSpec(SubKind.CLAUDE, listOf("@anthropic-ai/claude-agent-sdk"), "bridge-claude.mjs", listOf("claude"), "CLAUDE_CODE_OAUTH_TOKEN",
        "Or paste a long-lived token from claude setup-token on a computer."),
    SubKind.CODEX to SubSpec(SubKind.CODEX, listOf("@openai/codex-sdk"), "bridge-codex.mjs", listOf("codex"), "CODEX_AUTH_JSON",
        "Or paste the contents of auth.json from codex login on a computer."),
    SubKind.OPENCODE to SubSpec(SubKind.OPENCODE, listOf("@opencode-ai/sdk"), "bridge-opencode.mjs", listOf("opencode"), "OPENCODE_API_KEY",
        "Paste your OpenCode Zen or Go API key (opencode.ai, Settings, API keys). It stays on this phone."),
    SubKind.ANTIGRAVITY to SubSpec(SubKind.ANTIGRAVITY, emptyList(), "bridge-agy.mjs", listOf("antigravity"), "",
        "Sign in with your Google account. Antigravity opens a Google page and shows a code; paste it here."),
)

/**
 * The subscription path, thin: Node, Claude Code, Codex and Antigravity are downloaded as
 * runtime packs on first use (never shipped in the APK). The app targets API 28, the Termux
 * route, so Android lets it execute the binaries it downloads into filesDir/runtime/bin.
 */
class SubscriptionRuntime(private val context: Context, val kind: SubKind) {
    val spec = SubSpecs.getValue(kind)
    val dir: File get() = RuntimePacks.root(context)
    val bin: File get() = File(dir, "bin")
    val home: File get() = File(dir, "home").apply { mkdirs() }
    val node: File get() = File(bin, "node")
    private val tokenFile: File get() = File(dir, ".token-${kind.name.lowercase()}")

    private val packId: String get() = when (kind) {
        SubKind.CLAUDE -> "claude"
        SubKind.CODEX -> "codex"
        SubKind.OPENCODE -> "opencode"
        SubKind.ANTIGRAVITY -> "antigravity"
    }

    /** The packs this subscription needs: Node (it carries the JS packages) plus its own executable. */
    private val packIds: List<String> get() = listOf("node", packId)

    /** This phone can run it (arm64) and this APK knows its pack. A runtime is a download away, not a build flag. */
    val available: Boolean get() = RuntimePacks.supported(context, packIds)
    /** Every pack is on disk and unpacked from the exact zip this APK expects, so a new pack in an app update triggers a fresh download. */
    val installed: Boolean get() = packIds.all { RuntimePacks.installed(context, it) }
    val upToDate: Boolean get() = installed
    val signedIn: Boolean get() = token() != null || when (kind) {
        SubKind.CLAUDE -> File(home, ".claude/.credentials.json").exists()
        SubKind.CODEX -> File(home, ".codex/auth.json").exists()
        SubKind.OPENCODE -> false
        SubKind.ANTIGRAVITY -> File(home, ".gemini/antigravity-cli/antigravity-oauth-token").exists()
    }

    fun token(): String? = tokenFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length > 20 && (kind != SubKind.CLAUDE || it.startsWith("sk-ant-")) }
    fun setToken(t: String?) {
        if (t == null) { tokenFile.delete(); return }
        dir.mkdirs()
        if (kind == SubKind.CLAUDE && !t.trim().startsWith("sk-ant-")) error(context.getString(com.past9.phoneaos.R.string.rt_not_claude_access))
        if (kind == SubKind.CODEX && t.trim().startsWith("{")) { File(home, ".codex").mkdirs(); File(home, ".codex/auth.json").writeText(t.trim()); return }
        tokenFile.writeText(t.trim()); tokenFile.setReadable(false, false); tokenFile.setReadable(true, true)
    }

    fun sizeBytes(): Long = if (dir.exists()) dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L

    fun info(): RuntimeInfo = RuntimeInfo(
        available = available, installedBytes = sizeBytes(), signedIn = signedIn,
        note = when {
            !available -> context.getString(com.past9.phoneaos.R.string.rt_note_unavailable, kind.label)
            !installed -> context.getString(com.past9.phoneaos.R.string.rt_note_downloads, RuntimePacks.pendingMb(context, packIds).toString())
            !signedIn -> context.getString(com.past9.phoneaos.R.string.rt_note_installed, kind.label)
            else -> context.getString(com.past9.phoneaos.R.string.rt_note_ready, kind.label)
        },
    )

    /** Everything downloaded or unpacked, for every subscription. */
    fun delete() { dir.deleteRecursively() }

    /** Forget this subscription's sign-in but keep the downloaded runtimes. */
    fun signOut() {
        setToken(null)
        when (kind) {
            SubKind.CLAUDE -> File(home, ".claude/.credentials.json").delete()
            SubKind.CODEX -> File(home, ".codex/auth.json").delete()
            SubKind.OPENCODE -> Unit
            SubKind.ANTIGRAVITY -> File(home, ".gemini/antigravity-cli/antigravity-oauth-token").delete()
        }
    }

    /** Node can run on its own (no sign-in needed): the agent's code tool uses it. */
    val nodeAvailable: Boolean get() = RuntimePacks.installed(context, "node")

    /** Download just Node if it isn't there yet; the code tool and routines call this. */
    suspend fun ensureNode(progress: (String) -> Unit = {}) = RuntimePacks.ensure(context, listOf("node"), progress)

    /** Download the packs this subscription needs, then check they can run. */
    suspend fun install(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs(); bin.mkdirs(); File(dir, "lib").mkdirs()
        context.assets.list("runtime")?.filter { it != "node_modules.zip" }?.forEach { name ->
            if (!name.endsWith(".mjs") && name != "cacert.pem" && name != "open-url-hook.cjs") return@forEach
            context.assets.open("runtime/$name").use { i -> File(dir, name).outputStream().use { i.copyTo(it) } }
        }
        check(available) { "${kind.label} isn't available on this phone yet." }
        RuntimePacks.ensure(context, packIds, progress)
        check(node.exists()) { "The Node runtime pack did not unpack. Try again." }
        spec.nativeBins.forEach { check(File(bin, it).exists()) { "The ${kind.label} runtime pack did not unpack. Try again." } }
        linkBins()
        if (kind == SubKind.CLAUDE) check(File(bin, "ld-musl-aarch64.so.1").let { it.isFile && it.path == CLAUDE_INTERP }) {
            "Claude Code can't run from this app's folder (${bin.path}); it expects $CLAUDE_INTERP"
        }
        // Prove Android really lets this app execute the downloaded binary (targetSdk 28). If this fails,
        // nothing else will work, so say so here instead of at the first message.
        progress(context.getString(com.past9.phoneaos.R.string.rt_checking_runtime))
        val out = StringBuilder()
        val code = runCatching {
            ProcessBuilder(node.path, "--version").directory(dir).redirectErrorStream(true).apply { environment().putAll(env()) }.start().let { p ->
                p.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
                p.waitFor()
            }
        }.getOrElse { out.appendLine(it.message); -1 }
        check(code == 0) { "Android blocked the downloaded runtime (exit $code). ${out.toString().take(300)}" }
        progress(context.getString(com.past9.phoneaos.R.string.rt_installed))
    }

    /** While a sign-in waits for the code the user copies from the browser. */
    private var loginStdin: java.io.Writer? = null
    fun submitCode(code: String) { runCatching { loginStdin?.apply { write(code.trim() + "\n"); flush() } } }

    /**
     * Sign in with the user's own account the way the CLI does on a computer. The CLI prints a
     * URL; we open it in the phone's browser. Codex gets the redirect on localhost; Claude and
     * Antigravity show a code: [needsCode] asks for it and [submitCode] types it in.
     */
    suspend fun login(onUrl: (String) -> Unit, progress: (String) -> Unit, needsCode: () -> Unit = {}): String? = withContext(Dispatchers.IO) {
        if (!installed) return@withContext context.getString(com.past9.phoneaos.R.string.rt_not_downloaded, kind.label)
        val cmd = when (kind) {
            SubKind.CLAUDE -> listOf(File(bin, "claude").path, "auth", "login", "--claudeai")
            SubKind.CODEX -> listOf(File(bin, "codex").path, "login")
            SubKind.OPENCODE -> return@withContext context.getString(com.past9.phoneaos.R.string.rt_opencode_signin_unavailable)
            SubKind.ANTIGRAVITY -> listOf(File(bin, "antigravity").path, "-p", "Signing in", "--output-format", "stream-json",
                "--dangerously-skip-permissions", "--print-timeout", "2m")
        }
        context.assets.open("runtime/open-url-hook.cjs").use { i -> File(dir, "open-url-hook.cjs").outputStream().use { i.copyTo(it) } }
        val opened = File(home, ".open-url").apply { delete() }
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).apply {
            environment().putAll(env())
            if (kind != SubKind.ANTIGRAVITY) {
                environment()["BROWSER"] = File(bin, "node").path
                environment()["NODE_OPTIONS"] = "--require ${File(dir, "open-url-hook.cjs").path}"
            }
        }
        val p = pb.start()
        loginStdin = p.outputStream.bufferedWriter()
        var sent = false
        val lines = StringBuilder()
        val watcher = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            repeat(240) {
                if (!sent && opened.exists() && opened.length() > 20) { sent = true; onUrl(opened.readText().trim()) }
                kotlinx.coroutines.delay(250)
            }
        }
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                lines.appendLine(line); progress(line.take(200))
                val url = Regex("https://\\S+").find(line)?.value?.trimEnd('.', ')')
                if (!sent && url != null && (url.contains("oauth") || url.contains("authorize") || url.contains("accounts.google.com"))) {
                    sent = true; onUrl(url); if (kind == SubKind.ANTIGRAVITY || kind == SubKind.CLAUDE) needsCode()
                }
            }
            val code = p.waitFor()
            if (code == 0 && signedIn) null else context.getString(com.past9.phoneaos.R.string.rt_signin_not_finished, lines.lines().lastOrNull { it.isNotBlank() && !it.startsWith("http") }.orEmpty().take(200))
        } finally { watcher.cancel(); loginStdin = null; runCatching { p.destroy() } }
    }

    fun cancelLogin() { runCatching { loginStdin?.close() } }

    /** Make every downloaded executable runnable. No symlinks: the packs put the real files in bin/. */
    fun linkBins() {
        bin.mkdirs()
        val names = spec.nativeBins + listOf("node", "rg", "ld-musl-aarch64.so.1")
        names.forEach { n -> File(bin, n).takeIf { it.isFile }?.setExecutable(true, false) }
    }

    fun env(): Map<String, String> {
        linkBins()
        val base = mapOf(
            "HOME" to home.path, "TMPDIR" to context.cacheDir.path, "SHELL" to "/system/bin/sh",
            "PATH" to "${bin.path}:/system/bin", "LD_LIBRARY_PATH" to File(dir, "lib").path,
            "NODE_PATH" to File(dir, "node_modules").path,
            "SSL_CERT_FILE" to File(dir, "cacert.pem").path, "NODE_EXTRA_CA_CERTS" to File(dir, "cacert.pem").path,
            "SSL_CERT_DIR" to File(dir, "certs").apply { mkdirs() }.path,
            // The node build reads OpenSSL's config from Termux's prefix, which Android refuses to another app ("Permission denied"
            // even when Termux isn't installed), and then exits. An empty config of our own avoids that path.
            "OPENSSL_CONF" to File(dir, "openssl.cnf").apply { if (!exists()) writeText("") }.path,
            "USE_BUILTIN_RIPGREP" to "0", "DISABLE_AUTOUPDATER" to "1", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
            "CODEX_HOME" to File(home, ".codex").apply { mkdirs() }.path, "CODEX_BIN" to File(bin, "codex").path,
            "CLAUDE_BIN" to File(bin, "claude").path, "ANTIGRAVITY_BIN" to File(bin, "antigravity").path,
        )
        if (kind == SubKind.OPENCODE) return base
        // musl/Go binaries read /etc/resolv.conf, which Android lacks. Our proxy does DNS for them.
        val proxy = "http://127.0.0.1:${proxy.start().port}"
        return base + mapOf("HTTPS_PROXY" to proxy, "HTTP_PROXY" to proxy, "https_proxy" to proxy, "http_proxy" to proxy,
            "NO_PROXY" to "127.0.0.1,localhost", "no_proxy" to "127.0.0.1,localhost")
    }

    /** Run one turn. Emits the bridge's JSON events: session, text, tool, done, error. Cancelling kills the process. */
    fun turn(prompt: String, system: String, resume: String?, model: String?, mcpUrl: String, mcpToken: String, images: List<String> = emptyList(), role: String = "main", fork: Boolean = false): Flow<JSONObject> = callbackFlow {
        if (!upToDate) runCatching { install {} }.onFailure { trySend(JSONObject().put("type", "error").put("message", "Couldn't download the ${kind.label} runtime: ${it.message}")); close(); return@callbackFlow }
        runCatching { context.assets.open("runtime/${spec.bridge}").use { i -> File(dir, spec.bridge).outputStream().use { i.copyTo(it) } } }
        if (!node.exists()) { trySend(JSONObject().put("type", "error").put("message", "The Node runtime isn't downloaded. Open Settings, Subscription, and install ${kind.label}.")); close(); return@callbackFlow }
        val pb = ProcessBuilder(node.path, File(dir, spec.bridge).path).directory(dir).redirectErrorStream(false)
        pb.environment().putAll(env())
        token()?.let { pb.environment()[spec.tokenEnv] = it } ?: check(signedIn) { "Not signed in to ${kind.label}" }
        pb.environment()["PHONE_MCP_URL"] = mcpUrl
        pb.environment()["PHONE_MCP_TOKEN"] = mcpToken
        val p = pb.start()
        p.outputStream.bufferedWriter().use { it.write(JSONObject().put("prompt", prompt).put("system", system).put("resume", resume ?: "").put("model", model ?: "").put("images", org.json.JSONArray(images)).put("role", role).put("fork", fork).toString() + "\n") }
        val err = StringBuilder()
        thread(isDaemon = true) { runCatching { p.errorStream.bufferedReader().forEachLine { synchronized(err) { if (err.length < 4000) err.appendLine(it) } } } }
        thread(isDaemon = true) {
            runCatching { p.inputStream.bufferedReader().forEachLine { l -> runCatching { JSONObject(l) }.getOrNull()?.let { trySend(it) } } }
            val code = p.waitFor()
            if (code != 0) trySend(JSONObject().put("type", "error").put("message", synchronized(err) { err.toString() }.takeLast(600).ifBlank { "exit $code" }))
            close()
        }
        awaitClose { p.destroy() }
    }.flowOn(Dispatchers.IO)

    private fun get(url: String): InputStream {
        val res = sharedHttp.newCall(Request.Builder().url(url).build()).execute()
        check(res.isSuccessful) { "Download failed: HTTP ${res.code}" }
        return res.body!!.byteStream()
    }

    companion object {
        private val proxy = LocalProxy()
        /** Where libclaude.so's patched ELF interpreter points: the downloaded musl loader. */
        const val CLAUDE_INTERP = "/data/user/0/com.past9.phoneaos/files/runtime/bin/ld-musl-aarch64.so.1"

        fun unzip(input: InputStream, dest: File) {
            java.util.zip.ZipInputStream(input.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name.contains("..")) continue
                    val f = File(dest, e.name)
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                }
            }
        }

        /** Minimal ustar reader: regular files and directories, GNU long names. Enough for npm tarballs. */
        fun untarGz(input: InputStream, dest: File, stripFirst: Boolean) {
            val tin = GZIPInputStream(input).buffered()
            val header = ByteArray(512); var longName: String? = null
            fun readFully(b: ByteArray, n: Int = b.size): Boolean { var r = 0; while (r < n) { val k = tin.read(b, r, n - r); if (k < 0) return false; r += k }; return true }
            while (readFully(header)) {
                if (header.all { it == 0.toByte() }) break
                fun str(off: Int, len: Int) = String(header, off, len, Charsets.UTF_8).substringBefore('\u0000')
                val size = str(124, 12).trim().ifBlank { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val prefix = str(345, 155)
                var name = longName ?: (if (prefix.isNotEmpty()) "$prefix/${str(0, 100)}" else str(0, 100))
                longName = null
                val data = ByteArray(size.toInt()); readFully(data)
                val pad = ((512 - size % 512) % 512).toInt(); if (pad > 0) readFully(ByteArray(pad))
                if (type == 'L') { longName = String(data, Charsets.UTF_8).substringBefore('\u0000'); continue }
                if (stripFirst) name = name.substringAfter('/', "")
                if (name.isBlank() || name.contains("..")) continue
                val f = File(dest, name)
                when (type) { '5' -> f.mkdirs(); '0', '\u0000' -> { f.parentFile?.mkdirs(); f.writeBytes(data) } }
            }
        }
    }
}
