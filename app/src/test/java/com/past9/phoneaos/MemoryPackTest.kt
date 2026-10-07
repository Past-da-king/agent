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
        dao.insert(MemoryRow(title = "Ben Carter", body = "Sam told me Ben likes short updates.", source = "agent"))
        val pack = """[
          {"title":"Ben Carter","body":"CEO of Acme.","kind":"person","tags":"acme,ceo","pinned":false},
          {"title":"Northwind","body":"Contract client, tasks in Microsoft To Do.","kind":"weird","tags":["client","northwind"],"pinned":true},
          {"title":"","body":"no title, skipped"}
        ]"""
        assertEquals(2, MemoryPack.import(pack, dao))
        val ben = dao.byTitle("ben carter")!!
        assertTrue(ben.body.startsWith("Sam told me Ben likes short updates."))
        assertTrue(ben.body.contains("CEO of Acme."))
        assertEquals("agent", ben.source)
        val se = dao.byTitle("Northwind")!!
        assertEquals("fact", se.kind); assertEquals("client,northwind", se.topics); assertTrue(se.pinned); assertEquals("import", se.source)

        // A second pack replaces imported text instead of stacking it.
        MemoryPack.import("""[{"title":"Ben Carter","body":"CEO of Acme and FieldWorks."},{"title":"Northwind","body":"Updated."}]""", dao)
        assertEquals(1, Regex("From A.O.S memory bank").findAll(dao.byTitle("Ben Carter")!!.body).count())
        assertTrue(dao.byTitle("Ben Carter")!!.body.endsWith("CEO of Acme and FieldWorks."))
        assertEquals("Updated.", dao.byTitle("Northwind")!!.body)
        assertEquals(2, dao.list().size)

        // A pack never writes or touches a helper skill.
        dao.insert(MemoryRow(title = "Send a WhatsApp", body = "Helper steps.", kind = "skill", source = "agent"))
        MemoryPack.import("""[{"title":"Send a WhatsApp","body":"Bank note.","kind":"skill"}]""", dao)
        assertEquals("Helper steps.", dao.byTitle("Send a WhatsApp")!!.body)
        assertEquals("fact", dao.byTitle("Send a WhatsApp (A.O.S)")!!.kind)
        dao.delete(dao.byTitle("Send a WhatsApp")!!.id); dao.delete(dao.byTitle("Send a WhatsApp (A.O.S)")!!.id)

        // The agent can find it with its normal recall.
        assertEquals("Northwind", Recall.rank("northwind client", dao.list(), 3).first().title)
    }

    @Test fun pendingFileIsImportedOnceThenRenamed() = runBlocking {
        val db = AppDb.inMemory(ctx)
        val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, MemoryPack.FILE)
        f.writeText("""[{"title":"Brightwater","body":"Sam's consultancy.","kind":"fact","tags":"brightwater"}]""")
        assertEquals(1, MemoryPack.pending(ctx).size)
        assertEquals(1, MemoryPack.importPending(ctx, db.memory()))
        assertTrue(MemoryPack.pending(ctx).isEmpty())
        assertEquals(0, MemoryPack.importPending(ctx, db.memory()))
        assertNotNull(db.memory().byTitle("Brightwater"))
    }

    @Test fun brokenPackIsSetAsideNotRetried() = runBlocking {
        val db = AppDb.inMemory(ctx)
        File(ctx.filesDir, MemoryPack.FILE).writeText("{not json")
        assertEquals(0, MemoryPack.importPending(ctx, db.memory()))
        assertTrue(MemoryPack.pending(ctx).isEmpty())
        assertTrue(ctx.filesDir.listFiles()!!.any { it.name.startsWith("memory-pack.json.failed") })
    }
}
