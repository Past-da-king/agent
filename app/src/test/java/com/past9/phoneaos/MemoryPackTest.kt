package com.past9.phoneaos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.MemoryPack
import com.past9.phoneaos.data.MemoryRow
import com.past9.phoneaos.tools.Recall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryPackTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun importsPagesKeepsOwnPagesAndReimportReplaces() = runBlocking {
        val db = AppDb.inMemory(ctx); val dao = db.memory()
        dao.insert(MemoryRow(title = "Alex Meyer", body = "Ayanda told me Ben likes short updates.", source = "agent"))
        val pack = """[
          {"title":"Alex Meyer","body":"CEO of Guavas.","kind":"person","tags":"guavas,ceo","pinned":false},
          {"title":"Strat Edge","body":"Contract client, tasks in Microsoft To Do.","kind":"weird","tags":["client","strat-edge"],"pinned":true},
          {"title":"","body":"no title, skipped"}
        ]"""
        assertEquals(2, MemoryPack.import(pack, dao))
        val ben = dao.byTitle("ben van rooyen")!!
        assertTrue(ben.body.startsWith("Ayanda told me Ben likes short updates."))
        assertTrue(ben.body.contains("CEO of Guavas."))
        assertEquals("agent", ben.source)
        val se = dao.byTitle("Strat Edge")!!
        assertEquals("fact", se.kind); assertEquals("client,strat-edge", se.topics); assertTrue(se.pinned); assertEquals("import", se.source)

        // A second pack replaces imported text instead of stacking it.
        MemoryPack.import("""[{"title":"Alex Meyer","body":"CEO of Guavas and Grow Factory."},{"title":"Strat Edge","body":"Updated."}]""", dao)
        assertEquals(1, Regex("From A.O.S memory bank").findAll(dao.byTitle("Alex Meyer")!!.body).count())
        assertTrue(dao.byTitle("Alex Meyer")!!.body.endsWith("CEO of Guavas and Grow Factory."))
        assertEquals("Updated.", dao.byTitle("Strat Edge")!!.body)
        assertEquals(2, dao.list().size)

        // The agent can find it with its normal recall.
        assertEquals("Strat Edge", Recall.rank("strat edge client", dao.list(), 3).first().title)
    }

    @Test fun pendingFileIsImportedOnceThenRenamed() = runBlocking {
        val db = AppDb.inMemory(ctx)
        val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, MemoryPack.FILE)
        f.writeText("""[{"title":"Panache Services","body":"Ayanda's consultancy.","kind":"fact","tags":"panache"}]""")
        assertEquals(1, MemoryPack.pending(ctx).size)
        assertEquals(1, MemoryPack.importPending(ctx, db.memory()))
        assertTrue(MemoryPack.pending(ctx).isEmpty())
        assertEquals(0, MemoryPack.importPending(ctx, db.memory()))
        assertNotNull(db.memory().byTitle("Panache Services"))
    }

    @Test fun brokenPackIsSetAsideNotRetried() = runBlocking {
        val db = AppDb.inMemory(ctx)
        File(ctx.filesDir, MemoryPack.FILE).writeText("{not json")
        assertEquals(0, MemoryPack.importPending(ctx, db.memory()))
        assertTrue(MemoryPack.pending(ctx).isEmpty())
        assertTrue(ctx.filesDir.listFiles()!!.any { it.name.startsWith("memory-pack.json.failed") })
    }
}
