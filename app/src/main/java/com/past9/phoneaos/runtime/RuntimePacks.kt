package com.past9.phoneaos.runtime

import android.content.Context
import com.past9.phoneaos.agent.sharedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * One downloadable runtime pack. [sha256] is the hash of the zip and lives INSIDE this APK
 * (assets/runtime/native-manifest.json), never on the server: a changed or hijacked release can
 * make a download fail, but it can never make the app run a different program. [check] is a file
 * the pack must leave behind, relative to filesDir/runtime.
 */
data class RuntimePack(val id: String, val url: String, val sha256: String, val bytes: Long, val unpackedBytes: Long, val check: String)

/**
 * Node, Claude Code, Codex and Antigravity are too big for the APK, so they are downloaded once, checked
 * against the hash baked into this APK, and unpacked into filesDir/runtime. Running them from there works
 * because the app targets SDK 28 (see build.gradle.kts): Android 10+ refuses to execute files an app
 * wrote itself, but only for apps that target 29 or higher.
 */
object RuntimePacks {
    private const val CHUNK = 64 * 1024
    private const val MB = 1024L * 1024L

    private val lock = Mutex()
    @Volatile private var cache: Map<String, RuntimePack>? = null

    private val http by lazy {
        sharedHttp.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    fun root(context: Context): File = File(context.filesDir, "runtime")
    fun bin(context: Context, name: String): File = File(root(context), "bin/$name")

    /** The packs this APK trusts. Empty when the asset is missing, which makes nothing available. */
    fun manifest(context: Context): Map<String, RuntimePack> = cache ?: synchronized(this) {
        cache ?: runCatching {
            val packs = JSONObject(context.assets.open("runtime/native-manifest.json").bufferedReader().use { it.readText() }).getJSONObject("packs")
            packs.keys().asSequence().associateWith { id ->
                val o = packs.getJSONObject(id)
                RuntimePack(id, o.getString("url"), o.getString("sha256").lowercase(), o.getLong("bytes"), o.optLong("unpackedBytes", 0L), o.getString("check"))
            }
        }.getOrDefault(emptyMap()).also { cache = it }
    }

    fun pack(context: Context, id: String): RuntimePack? = manifest(context)[id]

    /** For tests: swap the trusted list. */
    internal fun setManifestForTest(m: Map<String, RuntimePack>?) { cache = m }

    /** This phone can run the packs (arm64) and the APK knows every one asked for. */
    fun supported(context: Context, ids: List<String>): Boolean =
        android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") && ids.all { pack(context, it) != null }

    private fun marker(context: Context, id: String) = File(root(context), ".pack-$id")

    /** Unpacked from the exact zip this APK expects, and its main file is still there. */
    fun installed(context: Context, id: String): Boolean {
        val p = pack(context, id) ?: return false
        return installed(context, p)
    }
    fun installed(context: Context, p: RuntimePack): Boolean =
        runCatching { marker(context, p.id).readText().trim() == p.sha256 }.getOrDefault(false) && File(root(context), p.check).isFile

    /** Megabytes still to fetch for these packs. */
    fun pendingMb(context: Context, ids: List<String>): Long =
        ids.filterNot { installed(context, it) }.sumOf { pack(context, it)?.bytes ?: 0L } / MB

    /** Download and unpack whichever of [ids] is missing or out of date. One at a time; safe to call from anywhere. */
    suspend fun ensure(context: Context, ids: List<String>, progress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        lock.withLock {
            for (id in ids.distinct()) {
                val p = pack(context, id) ?: throw IllegalStateException("No $id runtime pack is published yet.")
                if (!installed(context, p)) install(context, p, progress)
            }
        }
    }

    /** Download (resuming a half file), verify the hash, unpack only safe names, then write the marker last. */
    fun install(context: Context, pack: RuntimePack, progress: (String) -> Unit) {
        val root = root(context)
        if (!root.exists() && !root.mkdirs()) throw IOException("Cannot create ${root.path}")
        val dl = File(root, ".dl").apply { mkdirs() }
        val part = File(dl, "${pack.id}.zip.part")
        val need = pack.bytes + (if (pack.unpackedBytes > 0) pack.unpackedBytes else pack.bytes * 2) + 50 * MB
        check(root.usableSpace > need) { "Not enough free space for ${label(pack.id)}: it needs about ${need / MB} MB." }
        marker(context, pack.id).delete()
        download(pack, part, progress)
        progress("Checking ${label(pack.id)}")
        if (!sha256(part).equals(pack.sha256, ignoreCase = true)) {
            part.delete()
            throw IllegalStateException("The ${label(pack.id)} download was damaged (checksum mismatch). Try again.")
        }
        progress("Unpacking ${label(pack.id)}")
        // A new Node pack brings a new set of JS packages: drop the old ones so no stale file lingers.
        if (pack.id == "node") File(root, "node_modules").deleteRecursively()
        unzip(part, root)
        check(File(root, pack.check).isFile) { "The ${label(pack.id)} download was missing files. Try again." }
        marker(context, pack.id).writeText(pack.sha256)
        part.delete()
        progress("${label(pack.id)} ready")
    }

    private fun download(pack: RuntimePack, part: File, progress: (String) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        if (have > pack.bytes) { part.delete(); have = 0L }
        if (have == pack.bytes) return
        val req = Request.Builder().url(pack.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(req).execute().use { res ->
            check(res.code == 200 || res.code == 206) { "Download failed for ${label(pack.id)}: HTTP ${res.code}" }
            // 200 means the server ignored Range and is sending the whole file: start over.
            if (res.code == 200 && have > 0) { part.delete(); have = 0L }
            val body = res.body ?: throw IllegalStateException("Download failed for ${label(pack.id)}: empty response")
            var done = have; var lastPct = -1
            RandomAccessFile(part, "rw").use { out ->
                out.seek(have)
                body.byteStream().use { input ->
                    val buf = ByteArray(CHUNK)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val pct = (done * 100 / pack.bytes).toInt().coerceAtMost(100)
                        if (pct / 5 != lastPct / 5) { lastPct = pct; progress("Downloading ${label(pack.id)} $pct% (${done / MB} of ${pack.bytes / MB} MB)") }
                    }
                }
            }
        }
    }

    fun label(id: String): String = when (id.lowercase()) {
        "node" -> "Node.js"
        "claude" -> "Claude Code"
        "codex" -> "Codex"
        "antigravity" -> "Antigravity"
        else -> id.replaceFirstChar { it.uppercase() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(256 * 1024)
        f.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Each file goes to a temp name, then is renamed, so a crash never leaves a half file that looks finished. Names that escape [root] are refused. */
    private fun unzip(zip: File, root: File) {
        val rootPath = root.canonicalFile.path
        val prefix = rootPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (entry.isDirectory || entry.name.endsWith("/")) { stream.closeEntry(); continue }
                val target = File(root, entry.name)
                val canonical = target.canonicalFile.path
                if (!canonical.startsWith(prefix)) throw IllegalStateException("Unsafe path in the download: ${entry.name}")
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, ".${target.name}.new")
                tmp.outputStream().use { stream.copyTo(it) }
                tmp.setExecutable(true, false); tmp.setReadable(true, false)
                check(tmp.renameTo(target)) { "Couldn't place ${entry.name}" }
                stream.closeEntry()
            }
        }
    }
}
