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
        dao.insert(MemoryRow(title = "Alex Meyer", body = "Ayanda told me Alex likes short updates.", source = "agent"))
        val pack = """[
          {"title":"Alex Meyer","body":"CEO of Acme.","kind":"person","tags":"contact,ceo","pinned":false},
          {"title":"Acme","body":"Contract client, tasks in Microsoft To Do.","kind":"weird","tags":["client","acme"],"pinned":true},
          {"title":"","body":"no title, skipped"}
        ]"""
        assertEquals(2, MemoryPack.import(pack, dao))
        val ben = dao.byTitle("alex meyer")!!
        assertTrue(ben.body.startsWith("Ayanda told me Alex likes short updates."))
        assertTrue(ben.body.contains("CEO of Acme."))
        assertEquals("agent", ben.source)
        val se = dao.byTitle("Acme")!!
        assertEquals("fact", se.kind); assertEquals("client,acme", se.topics); assertTrue(se.pinned); assertEquals("import", se.source)

        // A second pack replaces imported text instead of stacking it.
        MemoryPack.import("""[{"title":"Alex Meyer","body":"CEO of Acme and Acme Labs."},{"title":"Acme","body":"Updated."}]""", dao)
        assertEquals(1, Regex("From A.O.S memory bank").findAll(dao.byTitle("Alex Meyer")!!.body).count())
        assertTrue(dao.byTitle("Alex Meyer")!!.body.endsWith("CEO of Acme and Acme Labs."))
        assertEquals("Updated.", dao.byTitle("Acme")!!.body)
        assertEquals(2, dao.list().size)

        // A pack never writes or touches a helper skill.
        dao.insert(MemoryRow(title = "Send a WhatsApp", body = "Helper steps.", kind = "skill", source = "agent"))
        MemoryPack.import("""[{"title":"Send a WhatsApp","body":"Bank note.","kind":"skill"}]""", dao)
        assertEquals("Helper steps.", dao.byTitle("Send a WhatsApp")!!.body)
        assertEquals("fact", dao.byTitle("Send a WhatsApp (A.O.S)")!!.kind)
        dao.delete(dao.byTitle("Send a WhatsApp")!!.id); dao.delete(dao.byTitle("Send a WhatsApp (A.O.S)")!!.id)

        // The agent can find it with its normal recall.
        assertEquals("Acme", Recall.rank("acme client", dao.list(), 3).first().title)
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
