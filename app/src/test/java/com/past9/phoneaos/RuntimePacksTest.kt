package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.runtime.RuntimePack
import com.past9.phoneaos.runtime.RuntimePacks
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimePacksTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer

    @Before fun up() { server = MockWebServer().apply { start() }; File(context.filesDir, "runtime").deleteRecursively() }
    @After fun down() { server.shutdown(); RuntimePacks.setManifestForTest(null) }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return b.toByteArray()
    }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun pack(id: String, bytes: ByteArray, check: String = "bin/$id", sha: String = sha(bytes)) =
        RuntimePack(id, server.url("/$id.zip").toString(), sha, bytes.size.toLong(), 0L, check)

    /** Serves [bytes] and honours Range like GitHub does. */
    private fun serve(bytes: ByteArray, honourRange: Boolean = true) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (honourRange && range != null) {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    return MockResponse().setResponseCode(206).setBody(Buffer().write(bytes, from, bytes.size - from))
                }
                return MockResponse().setBody(Buffer().write(bytes))
            }
        }
    }

    @Test fun installsAndMarksAPack() {
        val z = zip("bin/node" to "node-binary", "node_modules/x/package.json" to "{}")
        serve(z); val p = pack("node", z); RuntimePacks.setManifestForTest(mapOf("node" to p))
        assertFalse(RuntimePacks.installed(context, p))
        RuntimePacks.install(context, p) {}
        assertTrue(RuntimePacks.installed(context, p))
        val bin = File(context.filesDir, "runtime/bin/node")
        assertEquals("node-binary", bin.readText()); assertTrue(bin.canExecute())
        assertTrue(File(context.filesDir, "runtime/node_modules/x/package.json").exists())
        assertFalse("the zip is deleted after unpacking", File(context.filesDir, "runtime/.dl/node.zip.part").exists())
    }

    @Test fun aWrongHashIsRejectedAndNothingIsInstalled() {
        val z = zip("bin/node" to "evil")
        serve(z); val p = pack("node", z, sha = "0".repeat(64))
        val e = runCatching { RuntimePacks.install(context, p) {} }.exceptionOrNull()
        assertNotNull(e); assertTrue(e!!.message!!.contains("damaged"))
        assertFalse(File(context.filesDir, "runtime/bin/node").exists())
        assertFalse(RuntimePacks.installed(context, p))
        assertFalse("a bad download is deleted so the retry starts clean", File(context.filesDir, "runtime/.dl/node.zip.part").exists())
    }

    @Test fun anEntryThatEscapesTheRuntimeFolderIsRefused() {
        val z = zip("bin/node" to "ok", "../../evil.so" to "x")
        serve(z); val p = pack("node", z)
        assertNotNull(runCatching { RuntimePacks.install(context, p) {} }.exceptionOrNull())
        assertFalse(File(context.filesDir.parentFile, "evil.so").exists())
        assertFalse(RuntimePacks.installed(context, p))
    }

    @Test fun aHalfDownloadResumesWithRange() {
        val z = zip("bin/claude" to "x".repeat(50_000))
        serve(z); val p = pack("claude", z)
        val part = File(context.filesDir, "runtime/.dl/claude.zip.part").apply { parentFile!!.mkdirs(); writeBytes(z.copyOfRange(0, z.size / 2)) }
        assertTrue(part.length() < z.size)
        RuntimePacks.install(context, p) {}
        assertTrue(RuntimePacks.installed(context, p))
        assertEquals(1, server.requestCount); assertTrue(server.takeRequest().getHeader("Range")!!.startsWith("bytes="))
    }

    @Test fun aServerThatIgnoresRangeStillWorks() {
        val z = zip("bin/codex" to "y".repeat(40_000))
        serve(z, honourRange = false); val p = pack("codex", z)
        File(context.filesDir, "runtime/.dl/codex.zip.part").apply { parentFile!!.mkdirs(); writeBytes(z.copyOfRange(0, z.size / 3)) }
        RuntimePacks.install(context, p) {}
        assertTrue(RuntimePacks.installed(context, p))
    }

    @Test fun aNewPackHashMakesAnOldInstallOutOfDate() {
        val z1 = zip("bin/node" to "v1"); serve(z1); val p1 = pack("node", z1); RuntimePacks.install(context, p1) {}
        assertTrue(RuntimePacks.installed(context, p1))
        val z2 = zip("bin/node" to "v2"); val p2 = pack("node", z2)
        assertFalse("an app update that brings a new pack must trigger a download", RuntimePacks.installed(context, p2))
        serve(z2); RuntimePacks.install(context, p2) {}
        assertTrue(RuntimePacks.installed(context, p2)); assertEquals("v2", File(context.filesDir, "runtime/bin/node").readText())
    }

    @Test fun aMissingCheckFileMeansNotInstalled() {
        val z = zip("bin/node" to "n"); serve(z); val p = pack("node", z); RuntimePacks.install(context, p) {}
        File(context.filesDir, "runtime/bin/node").delete()
        assertFalse(RuntimePacks.installed(context, p))
    }

    @Test fun theShippedManifestIsWellFormedAndPinned() {
        RuntimePacks.setManifestForTest(null)
        val m = RuntimePacks.manifest(context)
        assertEquals(setOf("node", "claude", "codex", "antigravity"), m.keys)
        m.values.forEach {
            assertTrue(it.id, it.url.startsWith("https://github.com/Past-da-king/agent-runtimes/releases/download/"))
            assertEquals(64, it.sha256.length); assertTrue(it.bytes > 1_000_000); assertTrue(it.check.startsWith("bin/"))
        }
    }
}
