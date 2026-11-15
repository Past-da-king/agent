package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.bool
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.people.PeopleRepository
import com.past9.phoneaos.people.Person
import com.past9.phoneaos.people.PersonLog
import com.past9.phoneaos.people.QUIET_DAYS
import com.past9.phoneaos.people.addDateLabel
import com.past9.phoneaos.people.daysQuiet
import com.past9.phoneaos.people.nextDate
import com.past9.phoneaos.people.parsePersonDate
import com.past9.phoneaos.people.spokeLine
import org.json.JSONObject
import java.time.LocalDate

/**
 * The personal CRM as the agent uses it (GitHub issue #13): people, when you last spoke to them, the dates that
 * matter and the presents to buy. The phone's People screen and these tools are the same repository, so whatever the
 * user logged by hand is what the agent reads and writes.
 */

/** One line for a person in a list: who they are, when you last spoke, what is coming up. */
fun personLine(p: Person, now: Long = System.currentTimeMillis()): String {
    val quiet = daysQuiet(p.lastLogAt, now)
    val bits = mutableListOf("#${p.id} ${p.name}", spokeLine(p.lastLogAt, now))
    if (quiet >= QUIET_DAYS) bits += "going quiet"
    nextDate(p)?.let { u -> bits += "${u.date.label} ${u.date.short()} (in ${u.inDays} days)" }
    if (p.gifts.isNotEmpty()) bits += "${p.gifts.size} gift idea${if (p.gifts.size == 1) "" else "s"}"
    return bits.joinToString(" · ")
}

class PersonAddTool(private val repo: PeopleRepository) : Tool {
    override val spec = ToolSpec("person_add", "Add a person to the user's own People list (family, friends, anyone they want to keep up with). Refuses a name that is already there and says which page it is.",
        schema(listOf("name"), "name" to str("Their name, as the user says it"), "note" to str("Anything worth remembering: who they are, how they know each other")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val name = input.optString("name").trim()
        if (name.isEmpty()) return "Not added: I need a name."
        repo.find(name)?.let { return "Already there as #${it.id} ${it.name}. Use person_get or person_log on it." }
        val id = repo.add(name, input.optString("note"))
        ctx.activity("Added ${name} to People", JSONObject().put("tool", "people"))
        return "Added ${name} as person #$id. Add a birthday with person_date_add, and present ideas with gift_add."
    }
}

class PersonLogTool(private val repo: PeopleRepository) : Tool {
    override val spec = ToolSpec("person_log", "Write one entry on a person's timeline: a call, a message, meeting up, or a note. This is how the app knows when you last spoke to someone.",
        schema(listOf("person", "kind"), "person" to str("Their name or the person id"), "kind" to str("call, message, met, note or other"),
            "text" to str("What was said or what to remember, in one line"), "when" to str("When it happened: 'now' (the default), or a date like 2026-11-08")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val p = repo.find(input.optString("person")) ?: return "No person called \"${input.optString("person")}\". Add them first with person_add."
        val whenText = input.optString("when").trim()
        val at = if (whenText.isEmpty() || whenText.equals("now", true) || whenText.equals("today", true)) System.currentTimeMillis()
        else parsePersonDate(whenText)?.let { d -> d.atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() } ?: return "Not logged: I couldn't read the date \"$whenText\"."
        val id = repo.log(p.id, input.optString("kind").ifBlank { "note" }, input.optString("text"), at)
        ctx.activity("Logged ${PersonLog.label(input.optString("kind"))} with ${p.name}", JSONObject().put("tool", "people"))
        return "Logged on ${p.name} (#${p.id}) as entry #$id."
    }
}

class PersonListTool(private val repo: PeopleRepository) : Tool {
    override val spec = ToolSpec("person_list", "Everyone on the user's People list: when they last spoke to each, who is going quiet, and the next date coming up.",
        schema(emptyList(), "quiet" to bool("Only the people going quiet (no contact for 30 days or more)"), "query" to str("Only people whose name contains this")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val now = System.currentTimeMillis()
        val q = input.optString("query").trim()
        val all = repo.people().filter { q.isEmpty() || it.name.contains(q, true) }
        val list = if (input.optBoolean("quiet")) all.filter { daysQuiet(it.lastLogAt, now) >= QUIET_DAYS } else all
        if (list.isEmpty()) return if (all.isEmpty()) "Nobody on the list yet. Add someone with person_add." else "Nobody matches."
        return list.sortedByDescending { daysQuiet(it.lastLogAt, now) }.joinToString("\n") { personLine(it, now) }
    }
}

class PersonGetTool(private val repo: PeopleRepository) : Tool {
    override val spec = ToolSpec("person_get", "One person in full: the note, every important date with its reminder, the gift ideas, and the last few entries on their timeline.",
        schema(listOf("person"), "person" to str("Their name or the person id")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val p = repo.find(input.optString("person")) ?: return "No person called \"${input.optString("person")}\"."
        val logs = repo.logs(p.id).take(8)
        return buildString {
            append("# ${p.name} (#${p.id})\n")
            append(spokeLine(p.lastLogAt))
            if (p.note.isNotBlank()) append("\n\n${p.note}")
            append("\n\nImportant dates:")
            append(if (p.dates.isEmpty()) " none. Add one with person_date_add." else "")
            p.dates.forEach { d -> append("\n- ${d.label}: ${d.short()}" + (if (d.yearly) " (every year)" else "") + ", reminder ${d.daysBefore} days before") }
            append("\n\nGift ideas:")
            append(if (p.gifts.isEmpty()) " none. Add one with gift_add." else "")
            p.gifts.forEach { g -> append("\n- ${g.text}" + if (g.occasion.isNotBlank()) " (for ${g.occasion})" else "") }
            append("\n\nTimeline:")
            append(if (logs.isEmpty()) " nothing logged yet." else "")
            logs.forEach { l -> append("\n- ${addDateLabel(l.at)}: ${PersonLog.label(l.kind)}" + if (l.text.isNotBlank()) " · ${l.text}" else "") }
        }
    }
}

class PersonDateAddTool(private val repo: PeopleRepository, private val onChange: suspend () -> Unit) : Tool {
    override val spec = ToolSpec("person_date_add", "Add an important date to a person: a birthday, an anniversary, or any one-off date. A reminder is set that many days before, using the phone's own alarm clock (no routine needed).",
        schema(listOf("person", "label", "date"), "person" to str("Their name or the person id"),
            "label" to str("What it is: Birthday, Anniversary, Wedding, Matric dance..."),
            "date" to str("The date, as the user says it: 1985-04-12, 12 April 1985, or just 12 April when the year doesn't matter"),
            "yearly" to bool("Comes round every year. Default true; a one-off date sets false"),
            "days_before" to int("How many days ahead to be reminded. Default 3")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val p = repo.find(input.optString("person")) ?: return "No person called \"${input.optString("person")}\". Add them first with person_add."
        val day: LocalDate = parsePersonDate(input.optString("date")) ?: return "Not added: I couldn't read the date \"${input.optString("date")}\"."
        val yearly = if (input.has("yearly")) input.optBoolean("yearly") else true
        val label = input.optString("label").ifBlank { "Date" }
        val key = repo.addDate(p.id, label, day, yearly, input.optInt("days_before", 3)) ?: return "Not added: that person is gone."
        onChange()
        ctx.activity("${label} saved for ${p.name}", JSONObject().put("tool", "people"))
        val next = nextDate(repo.person(p.id) ?: p)
        return "${p.name}: $label on ${day.let { if (it.year > 0) it.toString() else "%02d-%02d (year not known)".format(it.monthValue, it.dayOfMonth) }}" +
            (if (next != null) ". The reminder will come ${next.inDays} days from today." else ".")
    }
}

class GiftAddTool(private val repo: PeopleRepository) : Tool {
    override val spec = ToolSpec("gift_add", "Add a present idea for a person. The reminder for their birthday brings the list up, so the ideas are there when they are needed.",
        schema(listOf("person", "idea"), "person" to str("Their name or the person id"), "idea" to str("The present idea"),
            "occasion" to str("What it is for: Birthday, Christmas, Anniversary...")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val p = repo.find(input.optString("person")) ?: return "No person called \"${input.optString("person")}\"."
        val idea = input.optString("idea").trim()
        if (idea.isEmpty()) return "Not added: I need the idea itself."
        if (p.gifts.any { it.text.equals(idea, true) }) return "Already on ${p.name}'s list."
        repo.addGift(p.id, idea, input.optString("occasion"))
        ctx.activity("Gift idea for ${p.name}: $idea", JSONObject().put("tool", "people"))
        return "Added to ${p.name}'s gift ideas."
    }
}
