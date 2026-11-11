package com.past9.phoneaos.people

import com.past9.phoneaos.data.PeopleDao
import com.past9.phoneaos.data.PersonLogRow
import com.past9.phoneaos.data.PersonRow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.ZoneId
import java.time.ZonedDateTime

/** A birthday, an anniversary or any date the person cares about. [date]'s year may be 0 when it is not known. */
data class ImportantDate(
    /** Stable id inside the person's row, so a reminder can name the same date after a restart. */
    val key: String,
    val label: String,
    val date: LocalDate,
    /** True for a birthday or anniversary: it comes round every year. */
    val yearly: Boolean = true,
    /** How many days ahead the reminder comes. */
    val daysBefore: Int = 3,
) {
    val hasYear: Boolean get() = date.year > 0
    fun monthDay(): String = "%02d-%02d".format(date.monthValue, date.dayOfMonth)

    /** The next time this date comes round on or after [day] (29 February falls on the 28th in other years). */
    fun occurrenceOnOrAfter(day: LocalDate): LocalDate {
        if (!yearly) return date
        val thisYear = inYear(day.year)
        return if (thisYear.isBefore(day)) inYear(day.year + 1) else thisYear
    }

    fun inYear(year: Int): LocalDate =
        LocalDate.of(year, date.month, date.dayOfMonth.coerceAtMost(Month.of(date.monthValue).length(Year.isLeap(year.toLong()))))

    /** "14 Nov" when the year is not known, "14 Nov 1985" when it is. */
    fun short(): String = if (hasYear) "%d %s %d".format(date.dayOfMonth, date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, date.year)
        else "%d %s".format(date.dayOfMonth, date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() })
}

/** One present the person could give, and the occasion it is for. */
data class GiftIdea(val key: String, val text: String, val occasion: String = "", val given: Boolean = false)

/** A person, as the app sees them once one row has been opened. */
data class Person(
    val id: Long,
    val name: String,
    val note: String = "",
    val dates: List<ImportantDate> = emptyList(),
    val gifts: List<GiftIdea> = emptyList(),
    /** When the person was last spoken to (ms), or null when there is no entry yet. */
    val lastLogAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** One entry on a person's timeline. */
data class PersonLog(val id: Long, val personId: Long, val kind: String, val text: String, val at: Long) {
    companion object {
        /** call | message | note | met | other */
        val KINDS = listOf("call", "message", "note", "met", "other")
        fun label(kind: String) = when (kind) {
            "call" -> "Called"
            "message" -> "Messaged"
            "met" -> "Met up"
            "note" -> "Note"
            else -> "Contact"
        }
        fun normalise(kind: String) = if (kind.lowercase() in KINDS) kind.lowercase() else "other"
    }
}

/**
 * The one way in and out of the people tables (GitHub issue #13). Every write goes through [PersonCipher.seal] and
 * every read through [PersonCipher.open], so nothing readable about a person is ever stored or logged, and the rest
 * of the app only ever sees [Person] and [PersonLog].
 */
class PeopleRepository(
    private val dao: PeopleDao,
    private val cipher: PersonCipher = PersonCipher.keystore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun people(): List<Person> = dao.list().mapNotNull { runCatching { open(it) }.getOrNull() }

    suspend fun person(id: Long): Person? = dao.person(id)?.let { runCatching { open(it) }.getOrNull() }

    /** A person by id, or by name (exact first, then any page whose name contains what was said). */
    suspend fun find(idOrName: String): Person? {
        idOrName.trim().toLongOrNull()?.let { n -> person(n)?.let { return it } }
        val want = idOrName.trim()
        if (want.isEmpty()) return null
        val all = people()
        return all.firstOrNull { it.name.equals(want, true) }
            ?: all.firstOrNull { it.name.startsWith(want, true) }
            ?: all.firstOrNull { it.name.contains(want, true) }
    }

    suspend fun logs(personId: Long): List<PersonLog> = dao.logs(personId).mapNotNull { row ->
        runCatching {
            val o = JSONObject(cipher.open(row.sealed))
            PersonLog(row.id, row.personId, PersonLog.normalise(o.optString("kind")), o.optString("text"), row.at)
        }.getOrNull()
    }

    /** Add a person. Returns the id; a name that already exists is refused (see [find]) rather than doubled. */
    suspend fun add(name: String, note: String = ""): Long {
        val clean = name.trim().ifBlank { "Someone" }
        val now = clock()
        val sealed = cipher.seal(body(person = null, name = clean, note = note))
        return dao.insertPerson(PersonRow(sealed = sealed, createdAt = now, updatedAt = now))
    }

    suspend fun log(personId: Long, kind: String, text: String, at: Long = clock()): Long {
        val row = JSONObject().put("kind", PersonLog.normalise(kind)).put("text", text.trim())
        val id = dao.insertLog(PersonLogRow(personId = personId, sealed = cipher.seal(row.toString()), at = at))
        dao.touch(personId, at)
        return id
    }

    /** Add an important date. Returns its key. */
    suspend fun addDate(personId: Long, label: String, date: LocalDate, yearly: Boolean = true, daysBefore: Int = 3): String? =
        edit(personId) { p ->
            val key = "d" + java.util.UUID.randomUUID().toString().take(8)
            p.copy(dates = p.dates + ImportantDate(key, label.trim().ifBlank { "Date" }, date, yearly, daysBefore.coerceIn(0, 60))) to key
        }

    suspend fun addGift(personId: Long, text: String, occasion: String = ""): String? =
        edit(personId) { p ->
            val key = "g" + java.util.UUID.randomUUID().toString().take(8)
            p.copy(gifts = p.gifts + GiftIdea(key, text.trim(), occasion.trim())) to key
        }

    suspend fun removeDate(personId: Long, key: String) { edit(personId) { p -> p.copy(dates = p.dates.filterNot { it.key == key }) to null } }
    suspend fun removeGift(personId: Long, key: String) { edit(personId) { p -> p.copy(gifts = p.gifts.filterNot { it.key == key }) to null } }

    suspend fun rename(personId: Long, name: String) { edit(personId) { p -> p.copy(name = name.trim().ifBlank { p.name }) to null } }

    suspend fun delete(personId: Long) { dao.deleteLogs(personId); dao.deletePerson(personId) }
    suspend fun deleteLog(id: Long) = dao.deleteLog(id)

    /**
     * The reminder moments for every date of every person: the day's lead time, recomputed from the dates themselves,
     * so nothing about a date is stored beside the sealed row.
     */
    suspend fun dueReminders(now: Long = clock()): List<PersonReminderPlan.Due> {
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
        return people().flatMap { p ->
            p.dates.mapNotNull { d ->
                val occurrence = d.occurrenceOnOrAfter(today)
                // A yearly date whose reminder moment has gone by comes round again next year, so a reminder is set
                // once and never re-armed for a moment already past.
                val at = occurrence.minusDays(d.daysBefore.toLong()).atTime(9, 0).atZone(zone()).toInstant().toEpochMilli()
                if (at <= now && d.yearly) return@mapNotNull null
                val key = "${p.id}:${d.key}:$at"
                PersonReminderPlan.Due(key, p.id, d.key, d.label, occurrence.atStartOfDay(zone()).toInstant().toEpochMilli(), at)
            }
        }
    }

    private suspend fun edit(personId: Long, change: (Person) -> Pair<Person, String?>): String? {
        val row = dao.person(personId) ?: return null
        val current = open(row)
        val (next, key) = change(current)
        val now = clock()
        dao.updatePerson(row.copy(sealed = cipher.seal(body(next, next.name, next.note)), updatedAt = now))
        return key
    }

    private fun open(row: PersonRow): Person {
        val o = JSONObject(cipher.open(row.sealed))
        val dates = o.optJSONArray("dates")?.let { a -> (0 until a.length()).mapNotNull { i ->
            val d = a.optJSONObject(i) ?: return@mapNotNull null
            runCatching { ImportantDate(d.optString("key"), d.optString("label"), LocalDate.parse(d.optString("date")), d.optBoolean("yearly", true), d.optInt("daysBefore", 3)) }.getOrNull()
        } }.orEmpty()
        val gifts = o.optJSONArray("gifts")?.let { a -> (0 until a.length()).mapNotNull { i ->
            val g = a.optJSONObject(i) ?: return@mapNotNull null
            GiftIdea(g.optString("key"), g.optString("text"), g.optString("occasion"), g.optBoolean("given"))
        } }.orEmpty()
        return Person(row.id, o.optString("name"), o.optString("note"), dates, gifts, row.lastLogAt, row.createdAt, row.updatedAt)
    }

    private fun body(person: Person?, name: String, note: String): String {
        val o = JSONObject().put("name", name).put("note", note)
        o.put("dates", JSONArray().apply { person?.dates?.forEach { d ->
            put(JSONObject().put("key", d.key).put("label", d.label).put("date", d.date.toString()).put("yearly", d.yearly).put("daysBefore", d.daysBefore)) } })
        o.put("gifts", JSONArray().apply { person?.gifts?.forEach { g ->
            put(JSONObject().put("key", g.key).put("text", g.text).put("occasion", g.occasion).put("given", g.given)) } })
        return o.toString()
    }
}

/** "How long since" in the words the list uses. */
fun spokeLine(lastLogAt: Long?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (lastLogAt == null) return "No entry yet"
    val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(lastLogAt).atZone(zone).toLocalDate(),
        java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    return when {
        days <= 0L -> "Spoke today"
        days == 1L -> "Spoke yesterday"
        days < 7 -> "Spoke ${days} days ago"
        days < 14 -> "Spoke last week"
        days < 60 -> "Spoke ${days / 7} weeks ago"
        days < 730 -> "Spoke ${days / 30} months ago"
        else -> "Spoke over ${days / 365} years ago"
    }
}

/** Going quiet: nobody has spoken to them for this long. */
const val QUIET_DAYS = 30

/** Days since the last entry (a very large number when there has never been one). */
fun daysQuiet(lastLogAt: Long?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
    if (lastLogAt == null) return Long.MAX_VALUE / 4
    return java.time.temporal.ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(lastLogAt).atZone(zone).toLocalDate(),
        java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()).coerceAtLeast(0)
}

/** The next date coming up for a person, with how many days away it is. */
data class UpcomingDate(val date: ImportantDate, val day: LocalDate, val inDays: Long)

fun nextDate(p: Person, now: ZonedDateTime = ZonedDateTime.now()): UpcomingDate? {
    val today = now.toLocalDate()
    return p.dates.map { d -> val day = d.occurrenceOnOrAfter(today); UpcomingDate(d, day, java.time.temporal.ChronoUnit.DAYS.between(today, day)) }
        .minByOrNull { it.inDays }
}

/**
 * A date as people write it: "1985-04-12", "12 April 1985", "12 April", "12/04/1985" or "April 12". A date with no
 * year is real (a birthday you don't know the year of) and comes back with year 0.
 */
fun parsePersonDate(text: String, today: LocalDate = LocalDate.now()): LocalDate? {
    val t = text.trim().replace(",", " ").replace(Regex("\\s+"), " ")
    if (t.isEmpty()) return null
    val month = { name: String -> Month.entries.firstOrNull { it.name.lowercase().startsWith(name.take(3).lowercase()) } }
    runCatching { LocalDate.parse(t) }.getOrNull()?.let { return it }
    Regex("""^(\d{1,2})[/-](\d{1,2})(?:[/-](\d{4}))?$""").find(t)?.let { m ->
        val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val y = m.groupValues[3].ifBlank { "0" }.toInt()
        return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
    }
    Regex("""^(\d{1,2})[\s-]+([A-Za-z]+)(?:[\s-]+(\d{4}))?$""").find(t)?.let { m ->
        val d = m.groupValues[1].toInt(); val mo = month(m.groupValues[2]) ?: return null
        val y = m.groupValues[3].ifBlank { "0" }.toInt()
        return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
    }
    Regex("""^([A-Za-z]+)[\s-]+(\d{1,2})(?:[\s-]+(\d{4}))?$""").find(t)?.let { m ->
        val mo = month(m.groupValues[1]) ?: return null
        val d = m.groupValues[2].toInt(); val y = m.groupValues[3].ifBlank { "0" }.toInt()
        return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
    }
    return null
}

/** A calendar day in words: "8 Nov", or "8 Nov 2025" when it is not this year. */
fun addDateLabel(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val d = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val f = if (d.year == LocalDate.now(zone).year) java.time.format.DateTimeFormatter.ofPattern("d MMM")
    else java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
    return d.format(f)
}

/** Where a reminder's tap asks the app to go: that person's page. */
object PeopleNav {
    val open = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
}
