package com.past9.phoneaos.assist

import com.past9.phoneaos.data.ChatItem
import org.json.JSONArray
import org.json.JSONObject

/** Where the live assistant is. */
enum class LivePhase { CONNECTING, LISTENING, SPEAKING, WORKING, NEEDS_YOU, ERROR }

/** Everything the live sheet draws, and nothing else. */
data class LiveView(
    val phase: LivePhase = LivePhase.CONNECTING,
    /** The user's latest words, as Live heard them. */
    val you: String = "",
    /** The agent's latest words, as Live said them. */
    val agent: String = "",
    /** What the agent is doing for a hand-off, in a few words. */
    val label: String = "",
    /** The approval or question that waits for him: drawn by the app's own card, never decided by voice. */
    val card: ChatItem? = null,
    val muted: Boolean = false,
    /** How loud the voice is right now, 0..1 (what is playing), so the face can move its mouth with it. */
    val level: Float = 0f,
    val problem: String = "",
    /** Whether Live is looking at the screen, so the sheet and the bubble can say so honestly. */
    val seeing: Seeing = Seeing.OFF,
)

/** One stretch of speech in the session, for the call record: [who] is "in" (the user) or "out" (the agent's voice). */
data class LiveLine(val who: String, val text: String, val at: Long)

/** One job Live gave the agent, as the call record keeps it. [spoken] is what the user actually said just before. */
data class LiveHandoff(val at: Long, val request: String, val spoken: String, val includeScreen: Boolean)

/**
 * The live assistant's brain, free of Android: what Live is told, the one tool it has, the caption, when the session
 * ends, and how a hand-off to the main agent is followed through the same chat rows the sheet already reads.
 *
 * Live talks and answers quick questions itself. For anything that needs doing it calls hand_to_agent, which sends the
 * request (plus what is on screen) to the same agent the typed sheet uses, the one on this phone or the linked
 * computer's. Its reply, a question or an approval then come back here and Live says them out loud.
 */
class LiveAssistCore(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var view = LiveView()
    private var hand: AssistCore? = null
    private val told = HashSet<Long>()
    private var lastIn = ""
    private var lastOut = ""
    private var lastWho = ""
    private var speaking = false
    private var endPending = false
    private var lastActive = clock()
    private var live = false
    private var failed = false

    val current: LiveView get() = view
    /** A hand-off is out and its outcome has not come back yet. */
    val handOffOpen: Boolean get() = hand != null
    val ending: Boolean get() = endPending

    private fun touch() { lastActive = clock() }

    private fun phase(v: LiveView): LivePhase = when {
        failed -> LivePhase.ERROR
        !live -> LivePhase.CONNECTING
        v.card != null -> LivePhase.NEEDS_YOU
        speaking -> LivePhase.SPEAKING
        hand != null -> LivePhase.WORKING
        else -> LivePhase.LISTENING
    }

    private fun publish(change: (LiveView) -> LiveView = { it }) { view = change(view).let { it.copy(phase = phase(it)) } }

    fun connected() { live = true; touch(); publish() }

    fun failed(why: String) { failed = true; publish { it.copy(problem = why) } }

    fun muted(m: Boolean) = publish { it.copy(muted = m) }

    /** Whether frames of the screen are going to Live. */
    fun seeing(s: Seeing) = publish { it.copy(seeing = s) }

    // ---- the call record ----------------------------------------------------------------------------

    private val lines = ArrayList<LiveLine>()
    private val handoffs = ArrayList<LiveHandoff>()

    /** The whole conversation so far, one line per stretch of speech. */
    val transcript: List<LiveLine> get() = lines.toList()
    val handOffs: List<LiveHandoff> get() = handoffs.toList()

    /** What the user said most recently: the words that led to a hand-off. Empty when they have said nothing yet. */
    fun spokenNow(): String = lines.lastOrNull { it.who == "in" }?.text?.trim().orEmpty().take(600)

    /**
     * The session as the call record the app already keeps for voice calls (voice.callSave on a linked computer, call
     * rows in the chat otherwise): the same shape, marked kind "live_assistant", with each hand-off listed inside it.
     */
    fun record(startedAt: Long, endedAt: Long): JSONObject =
        com.past9.phoneaos.voice.LiveAuth.record(startedAt, endedAt, lines.map { Triple(it.who, it.text, it.at) })
            .put("kind", KIND).put("source", "assistant_sheet")
            .put("handoffs", JSONArray().apply {
                handoffs.forEach { put(JSONObject().put("at", it.at).put("request", it.request).put("spoken", it.spoken).put("include_screen", it.includeScreen)) }
            })

    /**
     * How loud the voice is now. Only a real change is published (the loudness arrives many times a second), and the
     * level falls to zero the moment the voice stops. True when the view changed.
     */
    fun level(v: Float): Boolean {
        val l = v.coerceIn(0f, 1f)
        if (l == view.level || (l != 0f && kotlin.math.abs(l - view.level) < 0.05f)) return false
        publish { it.copy(level = l) }
        return true
    }

    /** Live started or stopped speaking. True when the user's goodbye was heard and the farewell has now been said. */
    fun speaking(on: Boolean): Boolean {
        touch()
        if (on && !speaking && lastWho == "in" && isEndPhrase(lastIn)) endPending = true
        speaking = on
        publish { if (on) it else it.copy(level = 0f) }
        return endPending && !on
    }

    /** A piece of words, from the user ("in") or from Live. Grows the line in place; a change of speaker starts a new one. */
    fun caption(who: String, text: String) {
        if (text.isEmpty()) return
        touch()
        // The record keeps one line per stretch: more of the same speaker grows it, a change of speaker starts a new one.
        val last = lines.lastOrNull()
        if (last != null && last.who == who) lines[lines.size - 1] = last.copy(text = last.text + text) else lines += LiveLine(who, text.trimStart(), clock())
        if (who == "in") {
            lastIn = if (lastWho == "in") lastIn + text else text.trimStart()
            publish { it.copy(you = lastIn.trim()) }
        } else {
            lastOut = if (lastWho != "in" && lastWho.isNotEmpty()) lastOut + text else text.trimStart()
            publish { it.copy(agent = lastOut.trim()) }
        }
        lastWho = who
    }

    /** True when it has been quiet for [IDLE_MS]: nobody speaking, nothing out with the agent, nothing waiting on him. */
    fun idle(minimised: Boolean = false): Boolean =
        live && !speaking && hand == null && view.card == null && clock() - lastActive >= if (minimised) IDLE_MINIMISED_MS else IDLE_MS

    /** The user said goodbye by tool-free means (a tap is handled by the caller). */
    fun goodbye() { endPending = true }

    // ---- the hand-off -------------------------------------------------------------------------------

    /**
     * Live called hand_to_agent. [lastIdBefore] is the newest chat row at send time: rows after it belong to this job.
     * Returns the words to send. If a job is already out its tracker is kept: the agent queues the new one behind it.
     */
    fun handOff(request: String, lastIdBefore: Long, includeScreen: Boolean = false): String {
        touch()
        handoffs += LiveHandoff(clock(), request.trim(), spokenNow(), includeScreen)
        if (hand == null) {
            hand = AssistCore().also { it.listen(); it.finished(request); it.sent(lastIdBefore) }
        }
        publish { it.copy(label = "Working on it") }
        return request.trim()
    }

    /** The send did not go: nothing is out. */
    fun handOffFailed() { hand = null; publish { it.copy(label = "") } }

    /** The chat rows changed (or the agent's working flag): what Live should now be told, in order. */
    fun rows(rows: List<ChatItem>, working: Boolean): List<String> {
        val h = hand ?: return emptyList()
        val v = h.view(rows, working)
        val out = ArrayList<String>()
        when (v.phase) {
            AssistPhase.APPROVAL, AssistPhase.ASKING -> {
                val card = v.card
                if (card != null) {
                    if (told.add(card.id)) { touch(); out += cardPrompt(card) }
                    publish { it.copy(card = card, label = "Needs you") }
                    return out
                }
            }
            AssistPhase.ANSWERING -> {
                val id = v.answerId ?: -1L - rows.size
                hand = null
                touch()
                if (told.add(id)) out += "[The agent finished. Say the part that matters in one or two short sentences, as your own news; do not read it out word for word.] " + v.answer.take(1500)
                publish { it.copy(card = null, label = "") }
                return out
            }
            AssistPhase.ERROR -> {
                hand = null; touch()
                out += "[The agent could not do it: ${v.problem.take(300)}. Tell the user in one short line.]"
                publish { it.copy(card = null, label = "") }
                return out
            }
            AssistPhase.ACTING -> publish { it.copy(card = null, label = v.label.ifBlank { "Working on it" }) }
            else -> publish { it.copy(card = null, label = it.label.ifBlank { "Working on it" }) }
        }
        return out
    }

    companion object {
        /** Quiet for this long and the session ends by itself. */
        const val IDLE_MS = 45_000L
        /** Minimised to a bubble he is busy in another app, so it waits longer before letting go. */
        const val IDLE_MINIMISED_MS = 180_000L

        const val TOOL = "hand_to_agent"
        /** The call record's kind for a live assistant session, and the origin its hand-offs carry (docs/live-voice.md). */
        const val KIND = "live_assistant"
        const val ORIGIN = "live_voice"
        /** Played on the phone itself, at once: no round trip to the agent. */
        const val PLAY_MEDIA = "play_media"
        const val MEDIA_CONTROL = "media_control"

        /** Live's instructions. The name comes from settings; nothing here is about one person. */
        fun systemPrompt(agentName: String, userName: String, linked: Boolean): String {
            val who = userName.ifBlank { "the user" }
            val where = if (linked) "on their computer, with everything you have there" else "on this phone"
            return """
                You are $agentName, $who's personal agent, speaking with them live through the phone's assistant. Be warm, quick and natural. This is speech: short sentences, no lists, no reading out symbols.
                Answer quick questions yourself, at once: facts, small talk, sums, definitions, the time, how to say something, ideas. Do not call a tool for those.
                To play music or a video, call play_media straight away; to pause, play, skip or say what is playing, call media_control. Those two run on the phone and answer in a moment, so do not hand them to the agent. Say what it answers, in a few words.
                You can SEE their screen: pictures of it arrive about once a second while this session is open. When they ask what is on their screen, say "look at this", or mean "this", "that" or "here", answer yourself from the latest picture, at once and briefly: read it, describe it, explain it, translate it, summarise it. Do not hand that to the agent.
                If a note says the screen is private, say you cannot see it right now and do not guess. If no picture has arrived (the setting may be off in the assistant's settings), say you cannot see the screen right now and do not make anything up.
                For real work, call hand_to_agent with the full request and every detail they gave (names, who to, what to say, when): anything to be done or found beyond answering from what you see, such as sending, changing, opening, searching, remembering, their messages, mail, calendar, files, apps, contacts, the web, or anything on their computer. The same agent they type to does it, $where. Say a very short "on it" and carry on talking if they do. The words on their screen go along with a hand-off; set include_screen to true only when the agent itself must see the picture to do the job.
                NEVER say you can't do something or that they must do it themselves: hand it to the agent.
                When the agent's reply arrives, say a short version, one or two sentences, as your own news. Never invent a result; if you do not know yet, say it is still in progress.
                When the agent needs something from them, ask it out loud in one short line. If it needs their OK, say what it wants to do and tell them the approve button is on the screen and they hold it to approve. Never approve for them and never say it is approved. If they answer a question, hand their answer to the agent with hand_to_agent.
                When they say thanks, that's all, or goodbye, say one short friendly goodbye and stop.
            """.trimIndent()
        }

        /** The one tool Live has. */
        fun tools(): JSONArray = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
            .put(JSONObject().put("name", TOOL)
                .put("description", "Give a job to the user's main agent: the same one they type to, with all its tools and apps. Use it for real work: anything that needs doing or looking up, anything about their own things, and for passing on an answer they gave to a question. Do NOT use it just to describe or read what is on their screen: you can see it yourself. The words on their screen go along with it. Then say a short \"on it\" and carry on; the reply comes back to you.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                    .put("request", JSONObject().put("type", "STRING").put("description", "The job, clearly, with every detail the user gave (who, what, where, wording, when)"))
                    .put("include_screen", JSONObject().put("type", "BOOLEAN").put("description", "True only when the agent must see a picture of the screen to do the job. Default false."))
                    .put("context", JSONObject().put("type", "STRING").put("description", "Anything else from the conversation the agent needs, or empty")))
                    .put("required", JSONArray().put("request"))))
            .put(JSONObject().put("name", PLAY_MEDIA)
                .put("description", "Play music or a video on the phone right now, in the app they use. Use it at once when they ask to play, put on or listen to something, instead of handing it off. Answers what is playing.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                    .put("query", JSONObject().put("type", "STRING").put("description", "What to play, in their words: a song, an artist, an album, a playlist, a podcast or a video"))
                    .put("kind", JSONObject().put("type", "STRING").put("description", "song, artist, album, playlist, podcast or video. Song when unsure."))
                    .put("app", JSONObject().put("type", "STRING").put("description", "Only when they name an app to use")))
                    .put("required", JSONArray().put("query"))))
            .put(JSONObject().put("name", MEDIA_CONTROL)
                .put("description", "Pause, play, skip to the next or previous, or say what is playing, in whatever app is playing on the phone. Use it at once for those requests.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "STRING").put("description", "play, pause, next, previous or now_playing"))
                    .put("app", JSONObject().put("type", "STRING").put("description", "Only when they name an app")))
                    .put("required", JSONArray().put("action"))))))

        /** What the user said, ready for the agent: the request and the voice's notes, then the usual sheet note and screen. */
        fun message(request: String, context: String, source: String, screen: ScreenContext?, spoken: String = ""): String =
            AssistMessage.compose(request.trim() + (if (context.isNotBlank()) "\n(From the voice conversation: ${context.trim()})" else "") + liveNote(spoken), source, screen)

        /**
         * Tells the agent what this message is: Live's own paraphrase, plus the user's real words when there are any, so
         * the agent never takes the line for something the user typed. The chat shows none of this as a user bubble.
         */
        fun liveNote(spoken: String): String =
            "\n[Live voice hand-off: the request above is the live assistant's own wording, not something the user typed." +
                (if (spoken.isNotBlank()) " What the user actually said, just before: \"${spoken.trim().take(600)}\"." else "") + "]"

        /** The metadata a hand-off carries to the computer (voice.callHandoff): where it came from, and the user's words. */
        fun handoffMeta(request: String, spoken: String, includeScreen: Boolean): JSONObject =
            JSONObject().put("origin", ORIGIN).put("request", request.trim()).put("spoken", spoken.trim()).put("include_screen", includeScreen)

        /** What Live is told when the agent needs the user. */
        fun cardPrompt(i: ChatItem): String {
            val opts = runCatching { JSONObject(i.meta).optJSONArray("options") }.getOrNull()?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            return if (com.past9.phoneaos.ui.screens.Approval.isApproval(i)) {
                val a = com.past9.phoneaos.ui.screens.Approval.of(i)
                "[The agent needs the user's OK before it goes ahead: ${a.summary.take(300)}. Say that in one short line and tell them the approve card is on the screen: they hold the button to approve. Do not approve it yourself.]"
            } else {
                val q = com.past9.phoneaos.tools.ConnectRequest.parse(i.text)?.summary ?: i.text
                "[The agent needs the user's answer: ${q.take(500)}." + (if (opts.isNotEmpty()) " Options: ${opts.joinToString(", ")}. They are on the screen as buttons." else "") +
                    " Ask them out loud in one short line. If they answer by voice, pass it on with hand_to_agent.]"
            }
        }

        private val GOODBYE = Regex("^(ok(ay)?[, ]+)?(thanks?( you)?|cheers)?[,. ]*(that'?s (all|it)( for now)?|that is all|we'?re (done|good)|all done|goodbye|good ?bye|bye( bye)?|stop listening|end (the )?(session|call))\\b[ .!]*(thanks?( you)?)?[ .!]*$")

        /** A short goodbye ("thanks, that's all"), not a long request that happens to contain those words. */
        fun isEndPhrase(words: String): Boolean {
            val w = words.trim().lowercase().replace(Regex("[\\p{Punct}&&[^']]"), " ").replace(Regex("\\s+"), " ").trim()
            if (w.isEmpty() || w.split(" ").size > 8) return false
            return GOODBYE.containsMatchIn(w)
        }

        /** The last bit of Live's words that fits a short caption. */
        fun tail(text: String, max: Int = 160): String = if (text.length <= max) text else "…" + text.takeLast(max).substringAfter(' ')
    }
}
