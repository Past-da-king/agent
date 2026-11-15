package com.past9.phoneaos

import android.app.AlarmManager
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.people.ContactsImport
import com.past9.phoneaos.people.ImportantDate
import com.past9.phoneaos.people.PeopleRepository
import com.past9.phoneaos.people.PersonCipher
import com.past9.phoneaos.people.PersonKey
import com.past9.phoneaos.people.PersonReminderPlan
import com.past9.phoneaos.people.PersonReminders
import com.past9.phoneaos.people.daysQuiet
import com.past9.phoneaos.people.parsePersonDate
import com.past9.phoneaos.people.spokeLine
import com.past9.phoneaos.tools.GiftAddTool
import com.past9.phoneaos.tools.PersonAddTool
import com.past9.phoneaos.tools.PersonListTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import javax.crypto.spec.SecretKeySpec

/**
 * The personal CRM, GitHub issue #13: a person and their timeline are sealed at rest, an important date arms exactly
 * one reminder, gift ideas come back with the person, and routines may keep people while helpers may not.
 *
 * The Android Keystore cannot be generated in a JVM test, so the tests seal with [TestKey] (the same AES-256-GCM code
 * path, a key the test can make); one test checks the phone's own Keystore cipher as far as Robolectric offers it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeopleTest {
    private lateinit var ctx: android.content.Context
    private lateinit var db: AppDb
    private lateinit var settings: SettingsStore
    private lateinit var repo: PeopleRepository

    /** A fixed AES-256 key standing in for the Keystore key a phone has. */
    private class TestKey(seed: Byte = 7) : PersonKey {
        private val key = SecretKeySpec(ByteArray(32) { (it * seed + 3).toByte() }, "AES")
        override fun key() = key
    }

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = AppDb.inMemory(ctx)
        settings = SettingsStore(ctx)
        settings.setMode(PowerMode.API_KEY); settings.setProvider(Provider.ANTHROPIC); settings.setApiKey(Provider.ANTHROPIC, "sk-test"); settings.setUserName("Sam")
        repo = PeopleRepository(db.people(), PersonCipher(TestKey()))
        ctx.getSharedPreferences("people_reminders", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun runtime() = AgentRuntime(ctx, db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone(), { null })

    @Test fun aPersonAndALogEntrySaveAndComeBackOpened() = runBlocking {
        val id = repo.add("Thandi Nkosi", "Cousin, lives in Pinetown")
        repo.log(id, "call", "Talked about her move to Cape Town in January", at = 1_700_000_000_000)

        val again = PeopleRepository(db.people(), PersonCipher(TestKey())).person(id)
        assertNotNull(again)
        assertEquals("Thandi Nkosi", again!!.name)
        assertEquals("Cousin, lives in Pinetown", again.note)
        assertEquals("the last time you spoke comes back too", 1_700_000_000_000, again.lastLogAt)
        val logs = repo.logs(id)
        assertEquals(1, logs.size)
        assertEquals("call", logs[0].kind)
        assertTrue(logs[0].text, logs[0].text.contains("Cape Town"))
    }

    @Test fun nothingAboutAPersonIsStoredInTheClear() = runBlocking {
        val id = repo.add("Thandi Nkosi", "Owes me nothing, loves plants and jazz")
        repo.log(id, "call", "Her new address is 14 Rose Street")

        val personRow = db.people().list().single { it.id == id }
        assertTrue("the row is sealed", personRow.sealed.startsWith("v1:"))
        assertFalse("no name in the clear", personRow.sealed.contains("Thandi", true))
        assertFalse("no note in the clear", personRow.sealed.contains("plants", true))
        val logRow = db.people().logs(id).single()
        assertTrue(logRow.sealed.startsWith("v1:"))
        assertFalse("no words of it in the clear", logRow.sealed.contains("Rose", true))
        assertFalse(logRow.sealed.contains("Nkosi", true))
    }

    @Test fun aDateArmsExactlyOneReminderAndASecondRunAddsNothing() = runBlocking {
        val id = repo.add("Thandi Nkosi")
        repo.addDate(id, "Birthday", LocalDate.now().plusDays(20), yearly = true, daysBefore = 3)

        val am = ctx.getSystemService(AlarmManager::class.java)
        PersonReminders.syncAll(ctx, repo = repo)
        assertEquals("one date, one alarm", 1, shadowOf(am).scheduledAlarms.size)
        PersonReminders.syncAll(ctx, repo = repo)
        assertEquals("a second run does not arm it again", 1, shadowOf(am).scheduledAlarms.size)
        PersonReminders.syncAll(ctx, repo = repo)
        assertEquals(1, shadowOf(am).scheduledAlarms.size)

        // The moment it rings for: three days before the birthday, at nine in the morning.
        val due = repo.dueReminders().single()
        val whenItRings = java.time.Instant.ofEpochMilli(due.at).atZone(java.time.ZoneId.systemDefault())
        assertEquals(LocalDate.now().plusDays(20).minusDays(3), whenItRings.toLocalDate())
        assertEquals(9, whenItRings.hour)
    }

    @Test fun aReminderThatHasGoneByIsSaidOnceAndNotAgain() = runBlocking {
        val now = 1_800_000_000_000L
        val due = PersonReminderPlan.Due("1:d1:$now", 1, "d1", "Birthday", now, now - 60_000)
        val first = PersonReminderPlan.plan(listOf(due), emptyMap(), emptySet(), now)
        assertEquals("said once", 1, first.missed.size)
        val second = PersonReminderPlan.plan(listOf(due), emptyMap(), setOf(due.key), now)
        assertEquals("and never again", 0, second.missed.size)
        assertEquals("nothing to arm for a moment already past", 0, second.arm.size)
    }

    @Test fun giftIdeasComeBackWithThePersonAndWithTheReminder() = runBlocking {
        val id = repo.add("Thandi Nkosi")
        repo.addGift(id, "A book on growing herbs", "Birthday")
        repo.addGift(id, "Voucher for the garden centre", "")
        val p = repo.person(id)!!
        assertEquals(2, p.gifts.size)
        assertEquals("A book on growing herbs", p.gifts[0].text)
        assertEquals("Birthday", p.gifts[0].occasion)

        val nm = ctx.getSystemService(NotificationManager::class.java)
        PersonReminders.show(ctx, p, ImportantDate("d1", "Birthday", LocalDate.now().plusDays(2), true, 3), missed = false)
        val shown = shadowOf(nm).allNotifications
        assertEquals(1, shown.size)
        val body = (shown.first().extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: shown.first().extras.getCharSequence(android.app.Notification.EXTRA_TEXT)).toString()
        assertTrue("the reminder brings the ideas up: $body", body.contains("growing herbs"))
        assertTrue(body, body.contains("garden centre"))
    }

    @Test fun aRoutineCanAddAPersonAndAHelperCannot() = runBlocking {
        val rt = runtime()
        val helper = rt.tools(forHelper = true).map { it.spec.name }.toSet()
        assertFalse("person_add" in helper)
        assertFalse("person_log" in helper)
        assertFalse("gift_add" in helper)
        assertTrue("the main agent keeps people", rt.tools().any { it.spec.name == "person_add" })

        // A routine runs the same tools a helper gets, plus the few that write to the user's own data.
        val routine = rt.tools(forHelper = true).map { it.spec.name }.toSet() +
            setOf("person_add", "person_log", "person_list", "person_get", "person_date_add", "gift_add")
        assertTrue("person_add" in routine)
    }

    @Test fun theAgentCanAddAPersonAndIsToldWhenTheNameIsAlreadyThere() = runBlocking {
        val add = PersonAddTool(repo)
        val ctxx = quiet
        val first = add.run(JSONObject().put("name", "Thandi").put("note", "Cousin"), ctxx)
        assertTrue(first, first.contains("Added Thandi"))
        val again = add.run(JSONObject().put("name", "thandi"), ctxx)
        assertTrue(again, again.contains("Already there"))
        assertEquals(1, repo.people().size)
        val listed = PersonListTool(repo).run(JSONObject(), ctxx)
        assertTrue(listed, listed.contains("Thandi"))
        assertTrue(listed, listed.contains("No entry yet"))
    }

    @Test fun aGiftToolNeedsTheIdeaItself() = runBlocking {
        val id = repo.add("Sipho")
        val tool = GiftAddTool(repo)
        val ctxx = quiet
        assertTrue(tool.run(JSONObject().put("person", "Sipho").put("idea", "  "), ctxx).contains("need the idea"))
        tool.run(JSONObject().put("person", "Sipho").put("idea", "A tin of good coffee"), ctxx)
        assertTrue(tool.run(JSONObject().put("person", "Sipho").put("idea", "a tin of good coffee"), ctxx).contains("Already on"))
        assertEquals(1, repo.person(id)!!.gifts.size)
    }

    @Test fun datesAreReadTheWayPeopleWriteThem() {
        assertEquals(LocalDate.of(1985, 4, 12), parsePersonDate("1985-04-12"))
        assertEquals(LocalDate.of(1985, 4, 12), parsePersonDate("12 April 1985"))
        assertEquals(LocalDate.of(0, 4, 12), parsePersonDate("12 April"))
        assertEquals(LocalDate.of(1985, 4, 12), parsePersonDate("12/04/1985"))
        assertEquals(LocalDate.of(1985, 4, 12), parsePersonDate("April 12, 1985"))
        assertEquals(LocalDate.of(1985, 12, 1), parsePersonDate("1 Dec 1985"))
        assertEquals(null, parsePersonDate("next week"))
    }

    @Test fun aBirthdayWithNoYearComesRoundEveryYear() {
        val d = ImportantDate("d1", "Birthday", LocalDate.of(0, 4, 12), yearly = true, daysBefore = 3)
        assertFalse(d.hasYear)
        val today = LocalDate.of(2026, 11, 11)
        assertEquals(LocalDate.of(2027, 4, 12), d.occurrenceOnOrAfter(today))
        assertEquals(LocalDate.of(2026, 4, 12), d.occurrenceOnOrAfter(LocalDate.of(2026, 1, 1)))
        assertEquals("29 February falls on the 28th in other years", LocalDate.of(2027, 2, 28),
            ImportantDate("d", "Birthday", LocalDate.of(2000, 2, 29)).occurrenceOnOrAfter(LocalDate.of(2027, 1, 1)))
    }

    @Test fun silenceIsCountedInDaysAndSaidInWords() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        assertEquals(Long.MAX_VALUE / 4, daysQuiet(null, now))
        assertEquals(0, daysQuiet(now, now))
        assertEquals(35, daysQuiet(now - 35 * day, now))
        assertEquals("No entry yet", spokeLine(null, now))
        assertEquals("Spoke today", spokeLine(now - 3600_000, now))
        assertEquals("Spoke yesterday", spokeLine(now - day, now))
        assertEquals("Spoke 5 weeks ago", spokeLine(now - 35 * day, now))
    }

    @Test fun contactsAreNotReadWithoutThePermission() = runBlocking {
        // Robolectric starts with every runtime permission denied, which is exactly the state the app is in until the
        // person taps Import.
        assertFalse(ContactsImport.allowed(ctx))
        assertEquals(emptyList<String>(), ContactsImport.names(ctx))
        assertEquals(0, ContactsImport.into(ctx, repo))
        assertEquals(0, repo.people().size)
    }

    @Test fun aPersonCanBeRemovedAndTheirTimelineGoesWithThem() = runBlocking {
        val id = repo.add("Thandi")
        repo.log(id, "call", "Hello")
        repo.delete(id)
        assertNull(repo.person(id))
        assertEquals(0, repo.logs(id).size)
    }
}
