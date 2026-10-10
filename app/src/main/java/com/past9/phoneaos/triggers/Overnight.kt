package com.past9.phoneaos.triggers

import android.content.Context
import com.past9.phoneaos.App
import com.past9.phoneaos.Graph
import com.past9.phoneaos.data.TriggerRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * The night shift. While the phone charges overnight the agent reads back over the day, files what
 * it learned into memory, folds the old conversation into a short running story (so the model starts
 * each day clean), and writes the morning screen: a greeting, one thought, and four things to try,
 * grounded in what it knows. First open after 4am shows that screen; the rest of the day is the chat.
 */
object Overnight {
    const val KIND = "nightly"
    const val NAME = "Overnight: tidy up and plan your morning"
    private const val MORNING_FROM = 4

    data class Idea(val icon: String, val text: String)
    data class Brief(val day: String, val greeting: String, val line: String, val body: String, val ideas: List<Idea>)

    private val _brief = MutableStateFlow<Brief?>(null)
    val brief: StateFlow<Brief?> = _brief
    private val _morning = MutableStateFlow(false)
    /** True while today's morning screen should show instead of the chat. */
    val morning: StateFlow<Boolean> = _morning
    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working
    private val lock = Mutex()

    private fun today() = LocalDate.now().toString()

    fun load(g: Graph) { if (_brief.value == null) _brief.value = parse(g.settings.extra("morning_brief")) }

    /** Every time the app comes to the front: is it a new morning, and is there a fresh brief for it? */
    fun onAppOpen(context: Context) {
        val g = App.graph(context); load(g)
        if (!g.settings.onboarded()) return
        val newMorning = LocalTime.now().hour >= MORNING_FROM && g.settings.extra("morning_seen") != today()
        if (newMorning) _morning.value = true
        // The phone never charged overnight (or it's a first run): make today's screen now, in the background.
        if (_brief.value?.day != today() && !_working.value) g.scope.launch { runCatching { run(g, full = false) } }
    }

    /** The user engaged (sent something, or chose to go back to the chat): the chat owns the rest of the day. */
    fun dismissMorning(context: Context) {
        if (!_morning.value) return
        App.graph(context).settings.setExtra("morning_seen", today()); _morning.value = false
    }

    /** The night routine always exists once; if the user deletes it, it stays deleted. */
    suspend fun ensureRoutine(g: Graph) {
        if (g.settings.extra("overnight_created") != null) return
        val existing = g.db.triggers().list().firstOrNull { it.kind == KIND }
        val t = existing ?: TriggerRow(name = NAME, kind = KIND, spec = "02:00",
            prompt = "While your phone charges: go over the day, save what I learned to memory, tidy the chat, and get your morning screen ready.")
        val id = existing?.id ?: g.db.triggers().upsert(t)
        g.settings.setExtra("overnight_created", "1")
        g.db.triggers().get(id)?.let { Routines.schedule(g.app, it) }
    }

    /**
     * @param full the real night run: also saves memories and folds the conversation into the story.
     *   The morning fallback (full = false) only writes the brief, fast.
     */
    suspend fun run(g: Graph, full: Boolean): String = lock.withLock {
        _working.value = true
        try {
            val s = g.settings.state.value
            val since = g.settings.extra("overnight_last")?.toLongOrNull() ?: (System.currentTimeMillis() - 86_400_000L)
            val chat = g.db.chat().all().first()
            val recent = chat.filter { it.createdAt > since && it.kind in setOf("user", "agent", "voice", "helper") }
                .joinToString("\n") { when (it.kind) {
                    "user" -> "USER: " + it.text.take(800)
                    // What the helpers did is part of the day: the brief and what came back.
                    "helper" -> JSONObject(it.meta).let { m -> "HELPER \"${m.optString("label")}\" [${m.optString("state")}]: ${it.text.take(200)} => ${m.optString("result").take(400)}" }
                    else -> "AGENT: " + it.text.take(800)
                } }.takeLast(16_000)
            val memories = g.db.memory().list().take(80).joinToString("\n") { "- ${it.title}" }
            val tasks = g.db.tasks().openTasks().take(25).joinToString("\n") { "- [${it.status}] ${it.title}" }
            val routines = g.db.triggers().list().filter { it.enabled && it.kind != KIND }.joinToString("\n") { "- ${it.name}" }
            val story = g.settings.extra("story").orEmpty()
            val name = s.userName.ifBlank { "there" }

            val prompt = buildString {
                appendLine(if (full) "OVERNIGHT RUN. ${s.userName.ifBlank { "The user" }} is asleep and the phone is charging. Nobody is watching."
                    else "MORNING PREP. ${s.userName.ifBlank { "The user" }} just opened the app. Be quick.")
                if (full) appendLine("1) Read what happened since last time (below). For anything durable that memory doesn't have yet (people, plans, preferences, projects, decisions), save or update a memory page now. Fix pages that are wrong. Don't save trivia.")
                appendLine("${if (full) "2" else "1"}) Then reply with ONLY one JSON object, no other text:")
                appendLine("""{"story": "...", "greeting": "...", "line": "...", "body": "...", "ideas": [{"icon": "...", "text": "..."}]}""")
                appendLine("- story: tomorrow you start with NO chat, only these notes, so write the final high-level report you will need to carry on without asking $name anything you already knew. Merge with your previous notes below. Plain text, max 3000 characters, in four parts with these exact headings:")
                appendLine("  WHO $name IS: the essentials (people, work, places, how they like things done).")
                appendLine("  WHAT WE WERE DOING: the threads of the last days, newest first, one line each.")
                appendLine("  WHERE THINGS STAND: for each open thread, where it got to and what's next (waiting on them, on a helper, or on something outside).")
                appendLine("  WHERE TO FIND THINGS: which memory pages, goals and routines hold the details, by name.")
                appendLine("- greeting: e.g. \"Morning $name.\" Short.")
                appendLine("- line: one thought in your own voice, under 70 characters: a question, an \"I wonder...\", or a nudge about something real from what you know (an open task, a plan, a person). If you know almost nothing yet, use \"What should I take off your plate?\"")
                appendLine("- body: one sentence, under 170 characters, on what you could do for them today.")
                appendLine("- ideas: exactly 4. Each is something $name could tap to ask you, written as their request to you, under 70 characters, specific to what you know about them. Mix kinds: something to look up, something to plan, a routine to set up, something to remember or finish. If you know little yet, make them useful but general.")
                appendLine("- icon: one of browse, plan, routine, memory, task, mail, idea, call.")
                appendLine("No emoji. No em dashes.")
                appendLine("\nYOUR PREVIOUS NOTES\n${story.ifBlank { "(none yet)" }}")
                appendLine("\nTHE CONVERSATION SINCE LAST TIME\n${recent.ifBlank { "(nothing new)" }}")
                appendLine("\nMEMORY PAGES\n${memories.ifBlank { "(none yet)" }}")
                appendLine("\nOPEN TASKS\n${tasks.ifBlank { "(none)" }}")
                appendLine("\nROUTINES\n${routines.ifBlank { "(none)" }}")
            }
            val out = g.runtime.quietRun(prompt, withMemoryTools = full) ?: return "No AI is set up yet, so the morning screen stays general."
            val json = runCatching { JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1)) }.getOrNull()
                ?: return "The overnight run didn't come back with a morning screen."
            val day = if (LocalTime.now().hour >= 20) LocalDate.now().plusDays(1).toString() else today()
            val b = Brief(day, json.optString("greeting").ifBlank { if (s.userName.isBlank()) g.app.getString(com.past9.phoneaos.R.string.svc_morning_greeting_anon) else g.app.getString(com.past9.phoneaos.R.string.svc_morning_greeting, name) }, json.optString("line"), json.optString("body"),
                (0 until (json.optJSONArray("ideas")?.length() ?: 0)).mapNotNull { i -> json.optJSONArray("ideas")?.optJSONObject(i) }
                    .map { Idea(it.optString("icon", "idea"), it.optString("text")) }.filter { it.text.isNotBlank() }.take(4))
            if (b.ideas.isNotEmpty()) { g.settings.setExtra("morning_brief", toJson(b)); _brief.value = b }
            json.optString("story").takeIf { it.isNotBlank() }?.let { g.settings.setExtra("story", it.take(3500)) }

            // Fold the old conversation into the story: tomorrow the model starts clean, with the notes.
            // Only when nobody has chatted for two hours, so a late-night conversation is never cut.
            if (full && (chat.lastOrNull { it.kind == "user" }?.createdAt ?: 0L) < System.currentTimeMillis() - 2 * 3_600_000L) {
                g.runtime.awaitIdle(); g.runtime.compact()
            }
            g.settings.setExtra("overnight_last", System.currentTimeMillis().toString())
            "Morning screen ready: ${b.line.ifBlank { b.greeting }}"
        } finally { _working.value = false }
    }

    private fun toJson(b: Brief) = JSONObject().put("day", b.day).put("greeting", b.greeting).put("line", b.line).put("body", b.body)
        .put("ideas", JSONArray(b.ideas.map { JSONObject().put("icon", it.icon).put("text", it.text) })).toString()

    private fun parse(raw: String?): Brief? = runCatching {
        val o = JSONObject(raw ?: return null); val a = o.getJSONArray("ideas")
        Brief(o.getString("day"), o.optString("greeting"), o.optString("line"), o.optString("body"),
            (0 until a.length()).map { a.getJSONObject(it).let { i -> Idea(i.optString("icon"), i.optString("text")) } })
    }.getOrNull()
}
