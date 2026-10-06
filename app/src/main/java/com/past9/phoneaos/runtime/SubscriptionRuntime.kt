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
    /** npm packages installed into filesDir/runtime. */
    val packages: List<String>,
    /** The bridge script in assets/runtime that runs one turn and prints JSON lines. */
    val bridge: String,
    /** Native executables the CLI needs, shipped as lib<name>.so in the runtime pack (Android only executes from nativeLibraryDir). */
    val nativeBins: List<String>,
    /** Environment variable the bridge reads the user's sign-in from. */
    val tokenEnv: String,
    val signInHelp: String,
)

val SubSpecs = mapOf(
    SubKind.CLAUDE to SubSpec(SubKind.CLAUDE, listOf("@anthropic-ai/claude-agent-sdk"), "bridge-claude.mjs", emptyList(), "CLAUDE_CODE_OAUTH_TOKEN",
        "Or paste a long-lived token from claude setup-token on a computer."),
    SubKind.CODEX to SubSpec(SubKind.CODEX, listOf("@openai/codex-sdk"), "bridge-codex.mjs", listOf("codex"), "CODEX_AUTH_JSON",
        "Or paste the contents of auth.json from codex login on a computer."),
    SubKind.OPENCODE to SubSpec(SubKind.OPENCODE, listOf("@opencode-ai/sdk"), "bridge-opencode.mjs", listOf("opencode"), "OPENCODE_API_KEY",
        "Paste your OpenCode Zen or Go API key (opencode.ai, Settings, API keys). It stays on this phone."),
)

/**
 * The subscription path. Node ships as a native library (lib/arm64-v8a/libnode.so) so Android
 * lets us execute it from nativeLibraryDir, and so do the Codex/OpenCode binaries. Everything
 * else (npm, the SDKs, our bridge scripts) is JavaScript unpacked into filesDir/runtime, which
 * we delete whenever the user stops needing it.
 */
class SubscriptionRuntime(private val context: Context, val kind: SubKind) {
    val spec = SubSpecs.getValue(kind)
    private val libDir get() = File(context.applicationInfo.nativeLibraryDir)
    val node: File get() = File(libDir, "libnode.so")
    val dir: File get() = File(context.filesDir, "runtime")
    val home: File get() = File(dir, "home").apply { mkdirs() }
    private val tokenFile: File get() = File(dir, ".token-${kind.name.lowercase()}")

    val available: Boolean get() = node.exists() && spec.nativeBins.all { File(libDir, "lib$it.so").exists() }
    val installed: Boolean get() = spec.packages.all { File(dir, "node_modules/$it/package.json").exists() }
    val signedIn: Boolean get() = token() != null || when (kind) {
        SubKind.CLAUDE -> File(home, ".claude/.credentials.json").exists()
        SubKind.CODEX -> File(home, ".codex/auth.json").exists()
        SubKind.OPENCODE -> false
    }

    fun token(): String? = tokenFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length > 20 && (kind != SubKind.CLAUDE || it.startsWith("sk-ant-")) }
    fun setToken(t: String?) {
        if (t == null) { tokenFile.delete(); return }
        dir.mkdirs()
        if (kind == SubKind.CLAUDE && !t.trim().startsWith("sk-ant-")) error("That isn't a Claude token. It starts with sk-ant-. Use Sign in with Claude instead.")
        if (kind == SubKind.CODEX && t.trim().startsWith("{")) { File(home, ".codex").mkdirs(); File(home, ".codex/auth.json").writeText(t.trim()); return }
        tokenFile.writeText(t.trim()); tokenFile.setReadable(false, false); tokenFile.setReadable(true, true)
    }

    fun sizeBytes(): Long = if (dir.exists()) dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L

    fun info(): RuntimeInfo = RuntimeInfo(
        available = available, installedBytes = sizeBytes(), signedIn = signedIn,
        note = when {
            !available -> "${kind.label} runtime pack not in this build"
            !installed -> "Ready to set up"
            !signedIn -> "Installed · sign in with ${kind.label}"
            else -> "Ready · signed in to ${kind.label}"
        },
    )

    /** Everything we unpacked or downloaded, for every subscription. The libs inside the APK stay. */
    fun delete() { dir.deleteRecursively() }

    val bin: File get() = File(dir, "bin")

    /** Node can run on its own (no sign-in needed): the agent's code tool uses it. */
    val nodeAvailable: Boolean get() = node.exists()

    /** Unpack the bundled JS (Agent SDK + Codex SDK) and wire up bin/ links to the native executables. */
    suspend fun install(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        check(available) { "This build has no ${kind.label} runtime pack" }
        dir.mkdirs(); bin.mkdirs()
        progress("Unpacking")
        context.assets.list("runtime")?.filter { it != "node_modules.zip" }?.forEach { name -> context.assets.open("runtime/$name").use { i -> File(dir, name).outputStream().use { i.copyTo(it) } } }
        if (!installed) context.assets.open("runtime/node_modules.zip").use { unzip(it, dir) }
        linkBins()
        progress("Checking Node")
        val out = StringBuilder()
        val code = exec(listOf(File(bin, "node").path, "-e", "console.log('node ' + process.version)")) { out.appendLine(it); progress(it) }
        check(code == 0 && installed) { "Runtime check failed (exit $code): ${out.toString().take(300)}" }
        progress("Installed")
    }

    /** While a Claude sign-in waits for the code the user copies from claude.ai. */
    private var loginStdin: java.io.Writer? = null
    fun submitCode(code: String) { runCatching { loginStdin?.apply { write(code.trim() + "\n"); flush() } } }

    /**
     * Sign in with the user's own account the way the CLI does on a computer. The CLI prints a
     * URL; we open it in the phone's browser. Codex then gets the redirect on localhost (this
     * phone). Claude shows a code on its page instead: [needsCode] tells the screen to ask for it,
     * and [submitCode] types it into the waiting CLI. Returns null on success, else the reason.
     */
    suspend fun login(onUrl: (String) -> Unit, progress: (String) -> Unit, needsCode: () -> Unit = {}): String? = withContext(Dispatchers.IO) {
        val cmd = when (kind) {
            SubKind.CLAUDE -> listOf(File(bin, "node").path, File(dir, "node_modules/@anthropic-ai/claude-agent-sdk/cli.js").path, "auth", "login", "--claudeai")
            SubKind.CODEX -> listOf(File(bin, "codex").path, "login")
            SubKind.OPENCODE -> return@withContext "OpenCode sign-in isn't available yet"
        }
        context.assets.open("runtime/open-url-hook.cjs").use { i -> File(dir, "open-url-hook.cjs").outputStream().use { i.copyTo(it) } }
        val opened = File(home, ".open-url").apply { delete() }
        val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).apply {
            environment().putAll(env())
            // Claude: the CLI "opens a browser" with $BROWSER; our hook hands us that URL. It is the
            // automatic flow, which redirects to the CLI's own server on localhost, i.e. this phone.
            environment()["BROWSER"] = File(bin, "node").path
            environment()["NODE_OPTIONS"] = "--require ${File(dir, "open-url-hook.cjs").path}"
        }.start()
        loginStdin = p.outputStream.bufferedWriter()
        var sent = false
        val lines = StringBuilder()
        val watcher = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            repeat(120) {
                if (!sent && opened.exists() && opened.length() > 20) { sent = true; onUrl(opened.readText().trim()) }
                kotlinx.coroutines.delay(250)
            }
        }
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                lines.appendLine(line); progress(line.take(200))
                val url = Regex("https://\\S+").find(line)?.value?.trimEnd('.', ')')
                // Codex prints a localhost-redirect URL itself. Claude's printed one is the manual flow,
                // which needs a TTY to paste into, so for Claude we wait for the hook's URL instead.
                if (kind == SubKind.CODEX && !sent && url != null && (url.contains("oauth") || url.contains("authorize"))) { sent = true; onUrl(url) }
            }
            val code = p.waitFor()
            if (code == 0 && signedIn) null else "Sign-in didn't finish. ${lines.lines().lastOrNull { it.isNotBlank() && !it.startsWith("http") }.orEmpty().take(200)}"
        } finally { watcher.cancel(); loginStdin = null; runCatching { p.destroy() } }
    }

    fun cancelLogin() { runCatching { loginStdin?.close() } }

    /**
     * bin/node, bin/rg, bin/codex point into nativeLibraryDir, and that path CHANGES on every app
     * update (/data/app/~~random/...). Re-point them before every run or they dangle.
     */
    fun linkBins() {
        bin.mkdirs()
        mapOf("node" to "libnode.so", "rg" to "librg.so", "codex" to "libcodex.so").forEach { (name, lib) ->
            val target = File(libDir, lib); val link = File(bin, name)
            if (target.exists() && runCatching { android.system.Os.readlink(link.path) }.getOrNull() != target.path) {
                link.delete(); runCatching { android.system.Os.symlink(target.path, link.path) }
            }
        }
    }

    fun env(): Map<String, String> {
        linkBins()
        val base = mapOf(
            "HOME" to home.path, "TMPDIR" to context.cacheDir.path, "SHELL" to "/system/bin/sh",
            "PATH" to "${bin.path}:/system/bin", "LD_LIBRARY_PATH" to libDir.path, "NODE_PATH" to File(dir, "node_modules").path,
            // Termux's OpenSSL looks for CAs under Termux's own prefix; hand it ours.
            "SSL_CERT_FILE" to File(dir, "cacert.pem").path, "NODE_EXTRA_CA_CERTS" to File(dir, "cacert.pem").path, "SSL_CERT_DIR" to File(dir, "certs").apply { mkdirs() }.path,
            "USE_BUILTIN_RIPGREP" to "0", "DISABLE_AUTOUPDATER" to "1", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
            // Codex refuses to start if its home folder doesn't exist yet.
            "CODEX_HOME" to File(home, ".codex").apply { mkdirs() }.path, "CODEX_BIN" to File(bin, "codex").path,
        )
        // Only Codex needs the proxy: it is static musl and can't resolve DNS on Android. Node (bionic)
        // resolves fine on its own, and routing Claude's sign-in through the proxy broke it.
        if (kind != SubKind.CODEX) return base
        val proxy = "http://127.0.0.1:${proxy.start().port}"
        return base + mapOf("HTTPS_PROXY" to proxy, "HTTP_PROXY" to proxy, "https_proxy" to proxy, "http_proxy" to proxy,
            "NO_PROXY" to "127.0.0.1,localhost", "no_proxy" to "127.0.0.1,localhost")
    }

    private fun exec(cmd: List<String>, progress: (String) -> Unit): Int {
        val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).apply { environment().putAll(env()) }.start()
        // Full lines: sign-in URLs run to 500+ characters and a cut one fails ("missing state").
        p.inputStream.bufferedReader().forEachLine { progress(it) }
        return p.waitFor()
    }

    /** Run one turn. Emits the bridge's JSON events: session, text, tool, done, error. Cancelling kills the process. */
    fun turn(prompt: String, system: String, resume: String?, model: String?, mcpUrl: String, mcpToken: String, images: List<String> = emptyList(), role: String = "main"): Flow<JSONObject> = callbackFlow {
        // Bridges ship in the APK; refresh them so an app update takes effect without reinstalling the runtime.
        runCatching { context.assets.open("runtime/${spec.bridge}").use { i -> File(dir, spec.bridge).outputStream().use { i.copyTo(it) } } }
        val pb = ProcessBuilder(node.path, File(dir, spec.bridge).path).directory(dir).redirectErrorStream(false)
        pb.environment().putAll(env())
        token()?.let { pb.environment()[spec.tokenEnv] = it } ?: check(signedIn) { "Not signed in to ${kind.label}" }
        pb.environment()["PHONE_MCP_URL"] = mcpUrl
        pb.environment()["PHONE_MCP_TOKEN"] = mcpToken
        val p = pb.start()
        p.outputStream.bufferedWriter().use { it.write(JSONObject().put("prompt", prompt).put("system", system).put("resume", resume ?: "").put("model", model ?: "").put("images", org.json.JSONArray(images)).put("role", role).toString() + "\n") }
        val err = StringBuilder()
        thread(isDaemon = true) { p.errorStream.bufferedReader().forEachLine { synchronized(err) { if (err.length < 4000) err.appendLine(it) } } }
        thread(isDaemon = true) {
            p.inputStream.bufferedReader().forEachLine { l -> runCatching { JSONObject(l) }.getOrNull()?.let { trySend(it) } }
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
