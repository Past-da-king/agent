package com.past9.phoneaos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.MemoryRow
import com.past9.phoneaos.data.MemoryTransfer
import com.past9.phoneaos.tools.Recall
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Memory out as one zip and back in, other tools' notes brought in tagged for sorting, and search that answers to a page's tags. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryTransferTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun exportThenImportAfterAWipeBringsEverythingBack() = runBlocking {
        val a = AppDb.inMemory(ctx).memory()
        a.insert(MemoryRow(title = "Lerato", body = "Sam's sister, works at [[Acme]].\n\n---\n\nA rule inside the body.", kind = "person", topics = "family,sister", pinned = true, source = "user", createdAt = 1_700_000_000_000, updatedAt = 1_700_000_500_000))
        a.insert(MemoryRow(title = "Acme: the company", body = "Where Lerato works.", kind = "company", topics = "work", source = "agent"))
        a.insert(MemoryRow(title = "Takealot prices", body = "Steps that worked.", kind = "skill", topics = "skill,shopping", source = "agent"))
        val out = ByteArrayOutputStream(); assertEquals(3, MemoryTransfer.export(a, out))

        val b = AppDb.inMemory(ctx).memory()
        val s = MemoryTransfer.importFrom("agent-memories.zip", ByteArrayInputStream(out.toByteArray()), b)
        assertTrue(s.own); assertEquals(3, s.result.added); assertEquals(0, s.result.unsorted)
        val lerato = b.byTitle("Lerato")!!
        assertEquals("person", lerato.kind); assertEquals("family,sister", lerato.topics); assertTrue(lerato.pinned); assertEquals("user", lerato.source)
        assertTrue(lerato.body.contains("A rule inside the body.")); assertEquals(1_700_000_000_000, lerato.createdAt)
        assertEquals("company", b.byTitle("Acme: the company")!!.kind)
        assertEquals("skill", b.byTitle("Takealot prices")!!.kind)   // an export restores the helper skills too

        // Importing the same zip again changes nothing.
        val again = MemoryTransfer.importFrom("agent-memories.zip", ByteArrayInputStream(out.toByteArray()), b)
        assertEquals(0, again.result.added + again.result.updated); assertEquals(3, again.result.skipped)
    }

    @Test fun otherToolsNotesComeInTaggedForSortingWithTheirAliases() = runBlocking {
        val dao = AppDb.inMemory(ctx).memory()
        val data = zip(
            "notes/car-insurance.md" to "# Car insurance\nRenews every March with Discovery.\n\n#car #vehicle #insurance\n",
            "notes/gym.txt" to "Gym is at 6am on Tuesdays.\ntags: fitness, workout",
            "export/mem.json" to """{"memories":[{"name":"Bakkie","content":"The 2014 Hilux.","keywords":["truck"],"synonyms":"vehicle, auto","type":"fact"},{"name":"","content":"no title"}]}""",
            "export/more.jsonl" to "{\"title\":\"Dentist\",\"text\":\"Dr Naidoo, every 6 months.\",\"labels\":\"health\",\"important\":true}\n",
            "photos/cat.png" to "not a page",
        )
        val s = MemoryTransfer.importFrom("other-app.zip", ByteArrayInputStream(data), dao)
        assertFalse(s.own); assertEquals(4, s.result.added); assertEquals(1, s.ignored)
        val car = dao.byTitle("Car insurance")!!
        assertEquals("fact", car.kind); assertTrue(car.body.startsWith("Renews every March")); assertFalse(car.body.contains("#car"))
        for (t in listOf("car", "vehicle", "insurance", "imported", "unsorted")) assertTrue("car insurance tag $t in ${car.topics}", t in car.topics.split(","))
        assertTrue("fitness" in dao.byTitle("gym")!!.topics.split(","))
        val bakkie = dao.byTitle("Bakkie")!!.topics.split(",")
        for (t in listOf("truck", "vehicle", "auto", "imported")) assertTrue("bakkie tag $t in $bakkie", t in bakkie)
        assertFalse("a page with a kind is not unsorted", "unsorted" in bakkie)
        assertTrue(dao.byTitle("Dentist")!!.pinned)
        assertEquals("3 came in unsorted", 3, s.result.unsorted)
        assertTrue(s.message(), s.message().contains("unsorted"))
    }

    @Test fun frontMatterAliasesJoinTheTags() = runBlocking {
        val dao = AppDb.inMemory(ctx).memory()
        MemoryTransfer.importFrom("pages.zip", ByteArrayInputStream(zip("pages/hilux.md" to "---\ntitle: Hilux\nkind: fact\ntags: [truck, 4x4]\naliases: [bakkie, pickup]\n---\nDiesel, 2014.")), dao)
        assertEquals(listOf("truck", "4x4", "bakkie", "pickup", "imported"), dao.byTitle("Hilux")!!.topics.split(","))
    }

    @Test fun onlyEightPagesCanBePinned() = runBlocking {
        val dao = AppDb.inMemory(ctx).memory()
        val json = "[" + (1..12).joinToString(",") { """{"title":"Core $it","body":"Always know this $it.","kind":"fact","pinned":true}""" } + "]"
        val s = MemoryTransfer.importFrom("pack.json", ByteArrayInputStream(json.toByteArray()), dao)
        assertEquals(8, dao.pinned().size); assertEquals(4, s.result.pinnedDropped)
    }

    @Test fun aPageTheUserWroteKeepsItsTextAndAnIdenticalOneIsLeftAlone() = runBlocking {
        val dao = AppDb.inMemory(ctx).memory()
        dao.insert(MemoryRow(title = "Gym", body = "Tuesdays at 6am.", topics = "fitness", source = "user"))
        dao.insert(MemoryRow(title = "Send a WhatsApp", body = "Helper steps.", kind = "skill", source = "agent"))
        val s = MemoryTransfer.importFrom("n.zip", ByteArrayInputStream(zip(
            "gym.md" to "---\ntitle: Gym\nkind: fact\ntags: workout\n---\nTuesdays at 6am.",
            "who.md" to "---\ntitle: Gym\nkind: fact\n---\nAlso Thursdays.",
            "skill.md" to "---\ntitle: Send a WhatsApp\nkind: fact\n---\nA foreign version.")), dao)
        val gym = dao.byTitle("Gym")!!
        assertTrue(gym.body.startsWith("Tuesdays at 6am.")); assertTrue(gym.body.contains("Also Thursdays.")); assertEquals(1, Regex("Imported memory").findAll(gym.body).count())
        assertEquals("user", gym.source); assertTrue("workout" in gym.topics.split(","))
        assertEquals("Helper steps.", dao.byTitle("Send a WhatsApp")!!.body)
        assertEquals("A foreign version.", dao.byTitle("Send a WhatsApp (imported)")!!.body)
        assertEquals("fact", dao.byTitle("Send a WhatsApp (imported)")!!.kind)
        assertTrue(s.result.updated >= 1)
    }

    @Test fun aBrokenFileIsNotFatal() = runBlocking {
        val dao = AppDb.inMemory(ctx).memory()
        assertEquals(0, MemoryTransfer.importFrom("junk.zip", ByteArrayInputStream("PK not really a zip".toByteArray()), dao).pages)
        assertEquals(0, MemoryTransfer.importFrom("x.json", ByteArrayInputStream("{nope".toByteArray()), dao).pages)
        assertEquals(1, MemoryTransfer.importFrom("one.md", ByteArrayInputStream("# Solo\nOne page.".toByteArray()), dao).pages)
    }

    @Test fun searchAnswersToTagsAndPlurals() = runBlocking {
        val dao = AppDb.inMemory(ctx).memory()
        dao.insert(MemoryRow(title = "Corolla", body = "Service due in March at the Toyota dealer.", topics = "car,vehicle,auto"))
        dao.insert(MemoryRow(title = "Gym", body = "Tuesdays at 6am.", topics = "fitness"))
        val all = dao.list()
        assertEquals("Corolla", Recall.rank("when is my vehicle due", all, 3).first().title)
        assertEquals("Corolla", Recall.rank("tell me about my cars", all, 3).first().title)
        assertEquals("Corolla", Recall.rank("auto insurance", all, 3).first().title)
        assertTrue(Recall.rank("holiday plans", all, 3).isEmpty())
        assertEquals("Gym", Recall.rank("workouts and fitness", all, 3).first().title)
    }

    @Test fun theManifestIsReadable() = runBlocking {
        val dao = AppDb.inMemory(ctx).memory(); dao.insert(MemoryRow(title = "A", body = "b"))
        val out = ByteArrayOutputStream(); MemoryTransfer.export(dao, out)
        val z = java.util.zip.ZipInputStream(ByteArrayInputStream(out.toByteArray()))
        val names = generateSequence { z.nextEntry }.map { it.name }.toList()
        assertEquals(listOf("manifest.json", "pages/a.md"), names)
    }
}
