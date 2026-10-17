package com.past9.phoneaos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.MemoryTransfer
import com.past9.phoneaos.tools.Recall
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The real refreshed memory zip (set MEMORY_ZIP to its path): it imports whole into an empty app and the agent can find things in it. Skipped when the file isn't there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryRealPackTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val zip = System.getenv("MEMORY_ZIP")?.let(::File)?.takeIf { it.isFile }

    @Test fun theRealPackImportsWholeAndIsSearchable() = runBlocking {
        assumeTrue("MEMORY_ZIP not set", zip != null)
        val dao = AppDb.inMemory(ctx).memory()
        val s = zip!!.inputStream().use { MemoryTransfer.importFrom(zip.name, it, dao) }
        println("REAL PACK: ${s.message()} own=${s.own} ignored=${s.ignored} pinned=${dao.pinned().size}")
        val pages = java.util.zip.ZipFile(zip).use { z -> JSONObject(z.getInputStream(z.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)).getInt("count") }
        assertTrue(s.own); assertEquals(pages, s.result.added); assertEquals(0, s.ignored); assertEquals(0, s.result.unsorted)
        assertTrue(dao.pinned().size in 1..MemoryTransfer.MAX_PINNED)
        val all = dao.list()
        for (q in listOf("agent app version", "who is the owner", "the server", "the main project", "slack")) println("  '$q' -> " + Recall.rank(q, all, 3).joinToString { it.title })
        assertTrue(Recall.rank("agent app", all, 5).isNotEmpty())
        // Export what was imported and import it back into a fresh app: nothing lost.
        val out = java.io.ByteArrayOutputStream(); assertEquals(pages, MemoryTransfer.export(dao, out))
        val fresh = AppDb.inMemory(ctx).memory()
        val back = MemoryTransfer.importFrom("again.zip", java.io.ByteArrayInputStream(out.toByteArray()), fresh)
        assertEquals(pages, back.result.added); assertEquals(dao.pinned().size, fresh.pinned().size)
        assertEquals(all.sumOf { it.body.length }, fresh.list().sumOf { it.body.length })
    }
}
