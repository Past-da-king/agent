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
        "Paste a Claude sign-in token (from claude setup-token). It stays on this phone and is only given to Claude Code."),
    SubKind.CODEX to SubSpec(SubKind.CODEX, listOf("@openai/codex-sdk"), "bridge-codex.mjs", listOf("codex"), "CODEX_AUTH_JSON",
        "Sign in with ChatGPT: paste the contents of your Codex auth.json (from codex login). It stays on this phone."),
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
    val signedIn: Boolean get() = token() != null

    fun token(): String? = tokenFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length > 20 }
    fun setToken(t: String?) { if (t == null) tokenFile.delete() else { dir.mkdirs(); tokenFile.writeText(t.trim()); tokenFile.setReadable(false, false); tokenFile.setReadable(true, true) } }

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

    /** Unpack npm, then npm-install this subscription's SDK. Reports progress lines. */
    suspend fun install(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        check(available) { "This build has no ${kind.label} runtime pack" }
        dir.mkdirs()
        context.assets.list("runtime")?.forEach { name -> context.assets.open("runtime/$name").use { i -> File(dir, name).outputStream().use { i.copyTo(it) } } }
        File(dir, "package.json").takeIf { !it.exists() }?.writeText("""{"name":"phone-runtime","private":true,"type":"module"}""")
        val npmDir = File(dir, "npm")
        if (!File(npmDir, "bin/npm-cli.js").exists()) {
            progress("Downloading npm")
            val meta = JSONObject(get("https://registry.npmjs.org/npm/latest").use { it.readBytes().decodeToString() })
            get(meta.getJSONObject("dist").getString("tarball")).use { untarGz(it, npmDir, stripFirst = true) }
        }
        progress("Installing ${kind.label}")
        val code = exec(listOf(node.path, File(npmDir, "bin/npm-cli.js").path, "install", "--no-audit", "--no-fund", "--ignore-scripts") + spec.packages, progress)
        check(code == 0 && installed) { "npm install failed (exit $code)" }
        progress("Installed")
    }

    private fun env() = mapOf(
        "HOME" to home.path, "TMPDIR" to context.cacheDir.path, "npm_config_cache" to File(dir, ".npm").path,
        "PATH" to "${libDir.path}:/system/bin", "NODE_PATH" to File(dir, "node_modules").path,
        // The SDKs look for their CLI on PATH or at these paths; ours live in nativeLibraryDir.
        "CODEX_BIN" to File(libDir, "libcodex.so").path, "OPENCODE_BIN" to File(libDir, "libopencode.so").path,
    )

    private fun exec(cmd: List<String>, progress: (String) -> Unit): Int {
        val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).apply { environment().putAll(env()) }.start()
        p.inputStream.bufferedReader().forEachLine { progress(it.take(160)) }
        return p.waitFor()
    }

    /** Run one turn. Emits the bridge's JSON events: session, text, tool, done, error. Cancelling kills the process. */
    fun turn(prompt: String, system: String, resume: String?, model: String?, mcpUrl: String, mcpToken: String): Flow<JSONObject> = callbackFlow {
        val pb = ProcessBuilder(node.path, File(dir, spec.bridge).path).directory(dir).redirectErrorStream(false)
        pb.environment().putAll(env())
        pb.environment()[spec.tokenEnv] = token() ?: error("Not signed in to ${kind.label}")
        pb.environment()["PHONE_MCP_URL"] = mcpUrl
        pb.environment()["PHONE_MCP_TOKEN"] = mcpToken
        val p = pb.start()
        p.outputStream.bufferedWriter().use { it.write(JSONObject().put("prompt", prompt).put("system", system).put("resume", resume ?: "").put("model", model ?: "").toString() + "\n") }
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
