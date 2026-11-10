package com.past9.phoneaos.browser

import com.past9.phoneaos.voice.ExternalAudio
import com.past9.phoneaos.voice.LiveAuth
import com.past9.phoneaos.voice.LiveLink
import com.past9.phoneaos.voice.LiveSession
import com.past9.phoneaos.voice.Pcm
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * The words the app and the WhatsApp Web page say to each other during a call the agent speaks in. Text messages
 * over a WebMessageListener named [OBJECT], which exists only on [ORIGIN]:
 *
 *   page to app   {"t":"hello"}                        the hooks are installed (sent at document start)
 *                 {"t":"pcm","rate":16000,"d":"<b64>"}  what the other person said, PCM16 mono
 *                 {"t":"state", ...}                    counters, answering {"t":"status"}
 *                 {"t":"log","m":"..."}
 *                 {"t":"ev","k":"wa_connected","d":"","at":1731240000000}   something the call's story needs
 *                 {"t":"err","m":"...","at":...}       a page error during a call
 *                 {"t":"sample", ...}                   cumulative counters, answering {"t":"sample"}
 *   app to page   {"t":"arm","on":true}                 only while a call runs: fake microphone and speaker tap
 *                 {"t":"play","rate":24000,"d":"<b64>"} the agent's voice, into the fake microphone
 *                 {"t":"flush"}                         drop the voice not yet played (the person talked over it)
 *                 {"t":"status"}
 *                 {"t":"sample"}                        every five seconds while a call voice runs
 */
object CallBridgeProtocol {
    const val OBJECT = "hinaCall"
    const val ORIGIN = "https://web.whatsapp.com"
    const val IN_RATE = 16000
    const val OUT_RATE = 24000
    /** A page message longer than this is not sound from a call (two seconds of 48 kHz audio is about 256,000 characters). */
    const val MAX_CHARS = 400_000

    sealed interface FromPage {
        object Hello : FromPage
        class Audio(val pcm: ByteArray, val rate: Int) : FromPage
        data class State(val json: JSONObject) : FromPage
        data class Log(val text: String) : FromPage
        data class Happened(val kind: String, val detail: String, val at: Long) : FromPage
        data class PageError(val text: String, val at: Long) : FromPage
        data class Sample(val json: JSONObject) : FromPage
        data class Bad(val why: String) : FromPage
    }

    fun parse(raw: String?): FromPage {
        if (raw.isNullOrEmpty()) return FromPage.Bad("empty")
        if (raw.length > MAX_CHARS) return FromPage.Bad("too long")
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return FromPage.Bad("not json")
        return when (o.optString("t")) {
            "hello" -> FromPage.Hello
            "pcm" -> {
                val rate = o.optInt("rate", IN_RATE)
                if (rate !in 8000..96000) return FromPage.Bad("rate")
                val bytes = runCatching { Base64.getDecoder().decode(o.optString("d")) }.getOrNull() ?: return FromPage.Bad("not base64")
                if (bytes.size < 2) return FromPage.Bad("no sound")
                FromPage.Audio(Pcm.even(bytes), rate)
            }
            "state" -> FromPage.State(o)
            "log" -> FromPage.Log(o.optString("m").take(300))
            "ev" -> o.optString("k").take(40).let { k -> if (k.isEmpty() || k.any { !(it.isLetterOrDigit() || it == '_') }) FromPage.Bad("event") else FromPage.Happened(k, o.optString("d").take(600), o.optLong("at")) }
            "err" -> FromPage.PageError(o.optString("m").take(300), o.optLong("at"))
            "sample" -> FromPage.Sample(o)
            else -> FromPage.Bad("unknown")
        }
    }

    fun arm(on: Boolean): String = JSONObject().put("t", "arm").put("on", on).toString()
    fun play(pcm: ByteArray, rate: Int = OUT_RATE): String =
        JSONObject().put("t", "play").put("rate", rate).put("d", Base64.getEncoder().encodeToString(Pcm.even(pcm))).toString()
    fun flush(): String = """{"t":"flush"}"""
    fun status(): String = """{"t":"status"}"""
    fun sample(): String = """{"t":"sample"}"""

    /** The one tool the voice has in a call: it says when the conversation is over. Hanging up is the agent's click. */
    fun tools(): JSONArray = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(JSONObject()
        .put("name", "end_call")
        .put("description", "The call is over. Call this only after you have said goodbye and the other person has finished.")
        .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("reason", JSONObject().put("type", "STRING")))))))

    /** Two names for one person: the same words, or one is the other's first name ("Sam" and "Sam Jones"). */
    fun sameName(a: String, b: String): Boolean {
        val x = a.trim().lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        val y = b.trim().lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (x.size == 1 && x[0] == y[0]) || (y.size == 1 && y[0] == x[0])
    }

    private fun first(name: String) = name.trim().substringBefore(' ')

    /**
     * What the voice is told before the call: who it is, for whom it calls, who will answer, to greet that person by
     * name first, why it calls, and to end politely. When the person called IS the user, it greets them as its own
     * person and does not say it calls on anyone's behalf.
     */
    fun system(agentName: String, userName: String, peer: String, goal: String): String {
        val me = agentName.trim().ifEmpty { "an assistant" }
        val user = userName.trim()
        val who = peer.trim()
        val self = sameName(user, who)
        return buildString {
            append(if (agentName.isBlank()) "You are an Ai assistant on a WhatsApp voice call that you placed " else "You are $me, an Ai assistant, on a WhatsApp voice call that you placed ")
            append(when { self -> "to $user, the person you work for. "; user.isEmpty() -> "for the person you work for. "; else -> "for $user. " })
            append(when {
                self -> "The person who answers is $user. Greet them by name first, like \"Hi ${first(user)}, it's $me\". You are calling them directly, so do not say you are calling for anyone. "
                who.isNotEmpty() -> "The person who answers will be $who. Greet them by name first, like \"Hi ${first(who)}\", then say that you are $me" +
                    (if (user.isEmpty()) "" else ", calling for $user") + ". If someone else answers, ask for ${first(who)}. "
                else -> "You do not know who will answer. Say hello first, then say that you are $me" + (if (user.isEmpty()) "" else ", calling for $user") + ". "
            })
            append("Why you are calling: ${goal.trim().trimEnd('.').ifEmpty { "to pass on a short message" }}. ")
            append("Let them say hello first when they do. Speak plainly and briefly, one thought at a time, listen, and answer in the language they speak. ")
            append("Never invent facts and never agree to anything about money. When the reason for the call is done, say a polite goodbye, let them answer, then call end_call.")
        }
    }
}

/**
 * One call the agent speaks in, inside the browser. The page (WhatsApp Web, with [CallBridgeJs.HOOKS] installed)
 * owns the call; this owns the voice. The other person's sound comes from the page at whatever rate the page has,
 * is brought to 16 kHz in 100 ms frames and sent to the Live session. The model's 24 kHz voice goes back to the
 * page, which plays it into the microphone it handed WhatsApp. This phone's own microphone and speaker are never
 * opened. Everything it needs comes in through the constructor, so it runs the same in a test.
 */
class CallBridge(
    /** A message for the page; the engine delivers it on the main thread. False when no page is listening. */
    private val toPage: (String) -> Boolean,
    /** The key or one-use token for the voice, as phone calls get it. */
    private val auth: suspend () -> LiveAuth.Token,
    private val open: (url: String, model: String, voice: String, system: String, tools: JSONArray, onEvent: (LiveSession.Event) -> Unit) -> LiveLink,
    /** The call's words when it ends: started, ended, ("in" | "out", text, at). */
    private val save: (Long, Long, List<Triple<String, String, Long>>) -> Unit,
    private val names: () -> Pair<String, String> = { "" to "" },
    /** The browser is in use: its service must not close it for being idle. */
    private val keepAlive: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** True while a call voice runs: the phone must stay awake and on the network, screen on or off. */
    private val hold: (Boolean) -> Unit = {},
    /** Runs a block again and again, that many milliseconds apart, until the returned stop is called. */
    private val every: (Long, () -> Unit) -> (() -> Unit) = { ms, block -> timer(ms, block) },
) {
    private val lock = Any()
    private var link: LiveLink? = null
    /** The call's own story: who ended it, a timeline, counters. Kept after the voice stops, until the next one starts. */
    val diag = CallDiag(now)
    private var stopTick: (() -> Unit)? = null
    @Volatile private var unanswered = 0
    @Volatile private var saidIn = false
    @Volatile private var saidOut = false
    private var startedAt = 0L
    private val lines = ArrayList<Triple<String, String, Long>>()
    private var lastWho = ""
    private val frames = Pcm.Chunker(CallBridgeProtocol.IN_RATE / 10 * 2)
    @Volatile var pageReady = false; private set
    @Volatile var armed = false; private set
    @Volatile var ready = false; private set
    @Volatile var endRequested = false; private set
    @Volatile var failed: String? = null; private set
    @Volatile var heardFrames = 0; private set
    @Volatile var sentFrames = 0; private set
    @Volatile var playedChunks = 0; private set
    @Volatile var heardPeak = 0f; private set
    @Volatile var pageState: JSONObject? = null; private set
    val pageLog = java.util.concurrent.ConcurrentLinkedDeque<String>()
    val running: Boolean get() = synchronized(lock) { link != null }
    /** Turns of speech so far, either side. */
    val turns: Int get() = synchronized(lock) { lines.size }
    /** The words so far, as they will be saved. */
    fun words(): String = transcript(synchronized(lock) { lines.toList() })
    /** A line only the voice hears, from the app: "the call is connected, greet them now". Nothing when no voice runs. */
    fun nudge(text: String): Boolean = synchronized(lock) { link }?.let { runCatching { it.announce(text) }.isSuccess } ?: false

    /** Every message the page posts lands here (any thread). */
    fun onPageMessage(raw: String?) {
        when (val m = CallBridgeProtocol.parse(raw)) {
            is CallBridgeProtocol.FromPage.Hello -> {
                // A fresh page (a reload): it starts disarmed, so a running call arms it again.
                if (armed) diag.add("page", "page_loaded_again", "WhatsApp Web loaded again during the call voice: any call in it is gone.")
                pageReady = true
                if (armed) toPage(CallBridgeProtocol.arm(true))
            }
            is CallBridgeProtocol.FromPage.Audio -> {
                if (!armed) return
                keepAlive()
                val pcm = Pcm.resample(m.pcm, m.rate, CallBridgeProtocol.IN_RATE)
                heardPeak = maxOf(heardPeak, Pcm.peak(pcm))
                val out = synchronized(lock) { val l = link; frames.push(pcm).map { l to it } }
                out.forEach { (l, f) -> heardFrames++; if (l?.sendAudio(f) == true) sentFrames++ }
            }
            is CallBridgeProtocol.FromPage.State -> pageState = m.json
            is CallBridgeProtocol.FromPage.Log -> { pageLog.add(m.text); while (pageLog.size > 60) pageLog.pollFirst() }
            is CallBridgeProtocol.FromPage.Happened -> diag.add(if (m.kind.startsWith("wa_")) "whatsapp" else "page", m.kind, m.detail, m.at)
            is CallBridgeProtocol.FromPage.PageError -> diag.error(m.text, m.at)
            is CallBridgeProtocol.FromPage.Sample -> {
                if (unanswered >= SILENT_TICKS) diag.add("app", CallDiag.PAGE_BACK, "The page answers again.")
                unanswered = 0
                diag.sample(m.json, heardFrames, sentFrames)
            }
            is CallBridgeProtocol.FromPage.Bad -> Unit
        }
    }

    /**
     * Arms the page and starts the voice. Call it BEFORE the call is placed, so WhatsApp is handed the fake
     * microphone. Returns what happened, in words for the agent.
     */
    suspend fun start(peer: String, goal: String): String {
        synchronized(lock) { if (link != null) return "A call voice is already running. Stop it first." }
        if (!pageReady) return "WhatsApp Web has not loaded with the call bridge yet. Open https://web.whatsapp.com (or reload it), then start again."
        val token = runCatching { auth() }.getOrElse { LiveAuth.Token.No(it.message ?: "The voice could not start.", false) }
        val ok = token as? LiveAuth.Token.Ok ?: return "The voice could not start: " + (token as LiveAuth.Token.No).why
        val (agent, user) = names()
        val l = open(ok.url, ok.model.ifBlank { DEFAULT_MODEL }, ok.voice.ifBlank { "Kore" }, CallBridgeProtocol.system(agent, user, peer, goal), CallBridgeProtocol.tools(), ::onEvent)
        synchronized(lock) {
            if (link != null) { runCatching { l.stop() }; return "A call voice is already running. Stop it first." }
            link = l; startedAt = now(); lines.clear(); lastWho = ""; frames.drain()
            ready = false; endRequested = false; failed = null; heardFrames = 0; sentFrames = 0; playedChunks = 0; heardPeak = 0f
            unanswered = 0; saidIn = false; saidOut = false
            diag.reset(); diag.add("app", "voice_armed")
            runCatching { hold(true) }
            stopTick = runCatching { every(TICK_MS, ::tick) }.getOrNull()
        }
        armed = true
        if (!toPage(CallBridgeProtocol.arm(true))) { stop(); return "The WhatsApp Web page is not listening. Reload it, then start again." }
        l.openExternalAudio(object : ExternalAudio {
            override fun play(pcm: ByteArray) { playedChunks++; toPage(CallBridgeProtocol.play(pcm)) }
            override fun flush() { toPage(CallBridgeProtocol.flush()) }
        })
        l.start()
        keepAlive()
        return "The call voice is armed. Now place the WhatsApp call (press the voice call button in the chat). The voice speaks when the other person answers. Check with action status, and end with action stop after you hang up."
    }

    /**
     * Every [TICK_MS] while the voice runs: the browser is in use, and the page is asked for its counters. A page
     * that stops answering (frozen, reloaded, closed) is written into the call's story once.
     */
    fun tick() {
        if (!running) return
        keepAlive()
        val sent = toPage(CallBridgeProtocol.sample())
        if (++unanswered == SILENT_TICKS) diag.add("app", CallDiag.PAGE_SILENT, if (sent) "The page has not answered the app for ${SILENT_TICKS * TICK_MS / 1000} seconds." else "No page is listening.")
    }

    /** The one who placed the call says how it ended ([by] is a CallDiag word, empty when it did not end it itself). */
    fun callEnded(by: String, why: String, whatsapp: JSONObject?) {
        diag.ended(by, why, whatsapp)
        if (by.isNotEmpty()) diag.add("app", "agent_ended", "$by: $why".trim().trimEnd(':'))
    }

    /** The call's story so far: who or what ended it, a timeline, counters every five seconds, the page's last errors. */
    fun diagnostics(): JSONObject = diag.json(pageState)

    private fun onEvent(e: LiveSession.Event) {
        when (e) {
            is LiveSession.Event.SetupComplete -> { ready = true; diag.add("voice", "voice_ready") }
            is LiveSession.Event.Reconnecting -> diag.add("voice", "voice_reconnecting", "The voice's connection went and is being opened again.")
            is LiveSession.Event.Transcript -> synchronized(lock) {
                if (e.who == "in" && !saidIn) { saidIn = true; diag.add("voice", "first_words_heard") }
                if (e.who != "in" && !saidOut) { saidOut = true; diag.add("voice", "first_words_spoken") }
                // The words arrive in pieces: one line per turn of speech, as on a phone call.
                if (lastWho == e.who && lines.isNotEmpty()) { val last = lines.removeAt(lines.size - 1); lines += Triple(last.first, last.second + e.text, last.third) }
                else { lines += Triple(e.who, e.text, now()); lastWho = e.who }
            }
            is LiveSession.Event.ToolCall -> {
                if (e.name == "end_call") { endRequested = true; diag.add("voice", CallDiag.END_CALL, e.args.optString("reason")) }
                synchronized(lock) { link }?.sendToolResult(e.id, e.name, JSONObject().put("ok", true))
            }
            is LiveSession.Event.Failed -> { failed = e.error; diag.add("voice", CallDiag.VOICE_FAILED, e.error); stop() }
            is LiveSession.Event.Closed -> {
                // The voice's connection closed for good in the middle of a call: the call has no voice left, so whoever
                // placed it must know (it used to go on in silence until max_minutes).
                val said = (if (e.code != 0) "code ${e.code} " else "") + e.reason
                if (!endRequested) failed = "The voice's connection closed ($said)."
                diag.add("voice", CallDiag.VOICE_CLOSED, said); stop()
            }
            else -> Unit
        }
    }

    /** Ends the voice, disarms the page (WhatsApp gets the real microphone and speaker back) and saves the words. Safe to call twice. */
    fun stop(): String {
        val (l, words, began) = synchronized(lock) {
            val l = link ?: return if (armed) { armed = false; toPage(CallBridgeProtocol.arm(false)); "Stopped." } else "No call voice is running."
            link = null
            runCatching { stopTick?.invoke() }; stopTick = null
            runCatching { hold(false) }
            diag.add("app", "voice_stopped")
            Triple(l, lines.map { Triple(it.first, it.second.trim(), it.third) }.filter { it.second.isNotEmpty() }, startedAt).also { lines.clear(); lastWho = "" }
        }
        armed = false; ready = false
        toPage(CallBridgeProtocol.flush()); toPage(CallBridgeProtocol.arm(false))
        runCatching { l.stop() }
        if (words.isNotEmpty()) runCatching { save(began, now(), words) }
        return if (words.isEmpty()) "Stopped. Nothing was said." else "Stopped. The call's words were saved to the chat:\n" + transcript(words)
    }

    fun status(): JSONObject {
        toPage(CallBridgeProtocol.status())
        val words = synchronized(lock) { lines.toList() }
        return JSONObject()
            .put("running", running).put("page_ready", pageReady).put("armed", armed).put("voice_ready", ready)
            .put("end_requested", endRequested).put("failed", failed ?: JSONObject.NULL)
            .put("heard_frames", heardFrames).put("sent_frames", sentFrames).put("heard_peak", Math.round(heardPeak * 1000) / 1000.0)
            .put("voice_chunks_played", playedChunks)
            .put("page", pageState ?: JSONObject.NULL).put("page_log", JSONArray(pageLog.toList().takeLast(15)))
            .put("transcript", transcript(words.takeLast(30)))
            .also { s -> if (diag.started) { val d = diagnostics(); for (k in listOf("ended", "timeline", "counters", "page_errors")) s.put(k, d.opt(k)) } }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
        const val TICK_MS = 5_000L
        /** This many counter requests in a row without an answer: the page is gone or frozen. */
        const val SILENT_TICKS = 3

        /** A daemon timer: [block] every [ms], until the returned stop is called. */
        fun timer(ms: Long, block: () -> Unit): () -> Unit {
            val t = java.util.Timer("aos-call-diag", true)
            t.scheduleAtFixedRate(object : java.util.TimerTask() { override fun run() { runCatching { block() } } }, ms, ms)
            return { t.cancel() }
        }
        fun transcript(words: List<Triple<String, String, Long>>): String =
            words.joinToString("\n") { (w, t, _) -> (if (w == "in") "Them: " else "Voice: ") + t.trim() }
    }
}

/**
 * What runs in WhatsApp Web at document start, on that site only and only when the user switched the call voice on.
 * It wraps the two places a call touches sound: getUserMedia (the microphone WhatsApp is given) and
 * AudioNode.connect (what WhatsApp sends to the speaker). While disarmed both pass straight through, so chats,
 * voice notes and a call the user places by hand behave as they always did.
 *
 * It also answers the two questions WhatsApp asks before it captures a microphone, because this browser answers
 * them in a way that makes WhatsApp send no sound at all (seen on a real call, 10 Nov 2026): the device list
 * comes back without ids, and the microphone permission reads "prompt". So, on this site and with the switch on:
 * enumerateDevices lists one microphone with a stable id ([MIC_ID], "Agent mic") and leaves speakers as they are,
 * permissions.query says "granted" for the microphone only, and the made-up microphone is mono at 48 kHz.
 * With the switch off this script is not installed, so nothing is changed.
 *
 * While a call voice runs it also keeps the call's own story for the app ([CallDiag]): page errors, the traffic on
 * the call's connections, the state of the made-up microphone (restarted if the browser stops it, and never
 * perfectly silent), and the names of WhatsApp's own call events (the other side hung up, traffic stopped, the
 * call broke), which it reads on their way to WhatsApp and passes on untouched.
 *
 * It changes no WhatsApp setting and forces no feature: that is [WhatsAppCallJs.ENABLE_CALLING], which only runs
 * for a number the user marked as a test account.
 */
object CallBridgeJs {
    const val MIC_ID = "default"
    const val MIC_LABEL = "Agent mic"
    const val MIC_GROUP = "agent-audio"
    val HOOKS = """
(() => {
  if (window.__hina || window.top !== window || typeof hinaCall === 'undefined') return;
  const H = window.__hina = { log: [], gumCalls: 0, taps: 0, inject: false, muteRemote: true, forward: false, remoteFrames: 0, remotePeak: 0, played: 0, deviceLists: 0, micAsked: 0, tracksOut: 0, tracksEnded: 0, tracksMuted: 0, micResumes: 0, errs: 0, pcs: [], watch: false, waHooked: false, waSeen: {} };
  const post = (o) => { try { hinaCall.postMessage(JSON.stringify(o)); } catch (e) {} };
  const log = H.L = (...a) => { const line = a.join(' '); H.log.push(line); if (H.log.length > 200) H.log.shift(); post({ t: 'log', m: line }); };
  // Something the call's own story needs (who ended it, what broke), stamped with this phone's clock.
  const ev = H.ev = (k, d) => post({ t: 'ev', k: k, d: d === undefined || d === null ? '' : String(d).slice(0, 600), at: Date.now() });
  const origConnect = AudioNode.prototype.connect;
  H.origConnect = origConnect;

  // ---- microphone: a made-up stream the agent's voice is played into ----
  const md = navigator.mediaDevices;
  if (md && md.getUserMedia) {
    const origGUM = md.getUserMedia.bind(md);
    H.origGUM = origGUM;
    H.ensureMic = () => {
      if (!H.micCtx) {
        H.micCtx = new AudioContext({ sampleRate: 48000 });
        H.micCtx.__hinaOwn = true;
        // A microphone that stops mid-call is a dead line to the other person: say so, and start it again.
        H.micCtx.onstatechange = () => {
          const st = H.micCtx.state; ev('mic_context', st);
          if (H.inject && st !== 'running' && st !== 'closed') { H.micResumes++; H.micCtx.resume().catch(() => {}); }
        };
        H.micDest = H.micCtx.createMediaStreamDestination();
        // One channel: WhatsApp captures a mono microphone at 48 kHz.
        try { H.micDest.channelCount = 1; H.micDest.channelCountMode = 'explicit'; H.micDest.channelInterpretation = 'speakers'; } catch (e) {}
        H.micGain = H.micCtx.createGain(); H.micGain.gain.value = 1;
        origConnect.call(H.micGain, H.micDest);
        const cs = H.micCtx.createConstantSource(); cs.offset.value = 0; origConnect.call(cs, H.micGain); cs.start();
        // A real microphone is never perfectly silent. Between the voice's sentences WhatsApp gets a steady hiss far
        // too quiet to hear (about -80 dB) instead of exact zeros, for the whole call, so nothing reads it as a dead microphone.
        try {
          const nb = H.micCtx.createBuffer(1, 48000, 48000); const nd = new Float32Array(48000);
          for (let i = 0; i < nd.length; i++) nd[i] = (Math.random() * 2 - 1) * 0.0001;
          nb.copyToChannel(nd, 0);
          const ns = H.micCtx.createBufferSource(); ns.buffer = nb; ns.loop = true; origConnect.call(ns, H.micGain); ns.start(); H.noise = ns;
        } catch (e) {}
        H.playHead = 0; H.sources = new Set();
      }
      if (H.micCtx.state !== 'running') H.micCtx.resume().catch(() => {});
      return H.micDest.stream;
    };
    // Each microphone track handed to WhatsApp is watched: one that ends or goes mute by itself is written down.
    H.watchTrack = (t) => {
      H.tracksOut++;
      try {
        t.addEventListener('ended', () => { H.tracksEnded++; ev('mic_track', 'ended'); });
        t.addEventListener('mute', () => { H.tracksMuted++; ev('mic_track', 'mute'); });
        t.addEventListener('unmute', () => ev('mic_track', 'unmute'));
      } catch (e) {}
      return t;
    };
    md.getUserMedia = async function (c) {
      if (H.inject && c && c.audio && !c.video) {
        H.gumCalls++; log('getUserMedia: fake microphone');
        const s = H.ensureMic();
        return new MediaStream(s.getAudioTracks().map(t => H.watchTrack(t.clone())));
      }
      return origGUM(H.realMic(c));
    };
  }

  // ---- what WhatsApp asks before it captures: which microphones are there, and may the page use one ----
  const MIC_ID = 'default';
  H.micInfo = () => {
    const d = { kind: 'audioinput', deviceId: MIC_ID, label: 'Agent mic', groupId: 'agent-audio' };
    Object.defineProperty(d, 'toJSON', { value: () => ({ deviceId: d.deviceId, kind: d.kind, label: d.label, groupId: d.groupId }) });
    return d;
  };
  // A request that names the made-up microphone while no call voice runs goes to the phone's real one.
  H.realMic = (c) => {
    try {
      const a = c && c.audio;
      if (a && typeof a === 'object' && a.deviceId !== undefined) {
        const id = a.deviceId; const v = (id && typeof id === 'object' && !Array.isArray(id)) ? (id.exact !== undefined ? id.exact : id.ideal) : id;
        if (v === MIC_ID || (Array.isArray(v) && v.includes(MIC_ID))) { const b = Object.assign({}, a); delete b.deviceId; return Object.assign({}, c, { audio: b }); }
      }
    } catch (e) {}
    return c;
  };
  if (md && md.enumerateDevices) {
    const origEnum = md.enumerateDevices.bind(md);
    H.origEnum = origEnum;
    md.enumerateDevices = async function () {
      H.deviceLists++;
      let real = []; try { real = Array.from(await origEnum()); } catch (e) {}
      // One microphone with an id that stays the same. Speakers and cameras are handed back untouched, so
      // WhatsApp keeps its default speaker and never asks for one by a made-up id.
      return [H.micInfo()].concat(real.filter(d => d && d.kind !== 'audioinput'));
    };
  }
  const perms = navigator.permissions;
  if (perms && perms.query) {
    const origQuery = perms.query.bind(perms);
    H.origQuery = origQuery;
    perms.query = function (d) {
      if (d && d.name === 'microphone') {
        H.micAsked++;
        const st = new EventTarget(); st.name = 'microphone'; st.state = 'granted'; st.onchange = null;
        return Promise.resolve(st);
      }
      return origQuery(d);
    };
  }
  // 16-bit PCM (base64) at `rate`, queued end to end into the fake microphone
  H.playPcm = (b64, rate) => {
    if (!H.ensureMic) return 0;
    H.ensureMic();
    const bin = atob(b64); const n = bin.length >> 1; if (!n) return 0; const f = new Float32Array(n);
    for (let i = 0; i < n; i++) { let v = bin.charCodeAt(2 * i) | (bin.charCodeAt(2 * i + 1) << 8); if (v >= 32768) v -= 65536; f[i] = v / 32768; }
    const buf = H.micCtx.createBuffer(1, n, rate || 24000); buf.copyToChannel(f, 0);
    const src = H.micCtx.createBufferSource(); src.buffer = buf; origConnect.call(src, H.micGain);
    const t = Math.max(H.micCtx.currentTime + 0.03, H.playHead); src.start(t); H.playHead = t + buf.duration;
    H.sources.add(src); src.onended = () => H.sources.delete(src); H.played++;
    return buf.duration;
  };
  H.flushMic = () => { if (!H.sources) return; for (const s of H.sources) { try { s.stop(); } catch (e) {} } H.sources.clear(); H.playHead = 0; };

  // ---- the other person: tap whatever the call sends to a speaker, bring it to 16 kHz, hand it to the app ----
  let held = [];
  let heldN = 0;
  const onRemote = (f32, rate) => {
    H.remoteFrames++;
    let pk = 0; for (let i = 0; i < f32.length; i += 16) { const a = Math.abs(f32[i]); if (a > pk) pk = a; }
    if (pk > H.remotePeak) H.remotePeak = pk;
    if (!H.forward) return;
    const ratio = rate / 16000; const n = Math.floor(f32.length / ratio); const out = new Int16Array(n);
    for (let i = 0; i < n; i++) { const a = Math.floor(i * ratio), b = Math.min(f32.length, Math.max(a + 1, Math.floor((i + 1) * ratio))); let s = 0; for (let k = a; k < b; k++) s += f32[k]; s /= (b - a) || 1; out[i] = Math.max(-32768, Math.min(32767, Math.round(s * 32768))); }
    held.push(out); heldN += n;
    if (heldN < 1600) return;
    const all = new Int16Array(heldN); let o = 0; for (const p of held) { all.set(p, o); o += p.length; } held = []; heldN = 0;
    const u8 = new Uint8Array(all.buffer); let s = ''; for (let i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
    post({ t: 'pcm', rate: 16000, d: btoa(s) });
  };
  const tapInto = (node) => {
    const ctx = node.context;
    if (!ctx.__hinaTap) {
      const sp = ctx.createScriptProcessor(2048, 1, 1);
      const mute = ctx.createGain(); mute.gain.value = 0;
      origConnect.call(sp, mute); origConnect.call(mute, ctx.destination);
      sp.onaudioprocess = (e) => { try { onRemote(e.inputBuffer.getChannelData(0), ctx.sampleRate); } catch (err) {} };
      ctx.__hinaTap = sp; H.taps++;
      // WhatsApp's own sound for the call: if the browser stops it mid-call, say so and start it again.
      try { ctx.addEventListener('statechange', () => { ev('call_context', ctx.state); if (H.inject && ctx.state !== 'running' && ctx.state !== 'closed') ctx.resume().catch(() => {}); }); } catch (e) {}
      log('speaker tap at ' + ctx.sampleRate + ' Hz');
    }
    origConnect.call(node, ctx.__hinaTap);
  };
  AudioNode.prototype.connect = function (dest, ...r) {
    try {
      if (H.inject && !this.context.__hinaOwn && (dest instanceof AudioDestinationNode || dest instanceof MediaStreamAudioDestinationNode)) {
        tapInto(this);
        if (H.muteRemote && dest instanceof AudioDestinationNode) return dest;
      }
    } catch (e) { log('connect hook error ' + e.message); }
    return origConnect.call(this, dest, ...r);
  };

  // ---- the call's own story: page errors, the call's connections, WhatsApp's own call events ----
  // Only while a call voice runs (H.watch). Nothing here changes what WhatsApp does: it reads and passes on.
  const onErr = (m) => { H.errs++; if (H.watch) post({ t: 'err', m: String(m).slice(0, 300), at: Date.now() }); };
  if (typeof addEventListener === 'function') {
    addEventListener('error', (e) => onErr((e && e.message) || 'error'));
    addEventListener('unhandledrejection', (e) => { const r = e && e.reason; onErr((r && r.message) || r || 'unhandled rejection'); });
  }
  // The connections WhatsApp opens for a call, so their traffic can be counted. Each call passes straight on.
  const PC = window.RTCPeerConnection;
  if (PC && PC.prototype) {
    const note = (pc) => { try { if (H.watch && !H.pcs.includes(pc)) { H.pcs.push(pc); if (H.pcs.length > 24) H.pcs.shift(); } } catch (e) {} };
    for (const m of ['createDataChannel', 'setLocalDescription', 'setRemoteDescription']) {
      const o = PC.prototype[m]; if (typeof o !== 'function') continue;
      PC.prototype[m] = function (...a) { note(this); return o.apply(this, a); };
    }
  }
  // Cumulative counters, answering the app every five seconds. The connection that carries the call is the busiest one.
  H.sample = async () => {
    const o = { t: 'sample', at: Date.now(), played: H.played, remote_frames: H.remoteFrames, mic: H.micCtx ? H.micCtx.state : 'none', tracks_ended: H.tracksEnded,
      hidden: typeof document !== 'undefined' && !!document.hidden, pcs: 0, bytes_sent: 0, bytes_received: 0, ice: '', errors: H.errs };
    let best = -1;
    for (const pc of H.pcs.slice()) {
      try {
        if (pc.connectionState === 'closed' || pc.signalingState === 'closed') continue;
        o.pcs++;
        let ts = 0, tr = 0, ps = 0, pr = 0;
        (await pc.getStats()).forEach((x) => {
          if (x.type === 'transport') { ts += x.bytesSent || 0; tr += x.bytesReceived || 0; }
          else if (x.type === 'candidate-pair' && (x.nominated || x.selected)) { ps += x.bytesSent || 0; pr += x.bytesReceived || 0; }
        });
        const sent = Math.max(ts, ps), got = Math.max(tr, pr);
        if (sent + got > best) { best = sent + got; o.bytes_sent = sent; o.bytes_received = got; o.ice = String(pc.iceConnectionState || ''); }
      } catch (e) {}
    }
    post(o);
  };
  const req = (n) => { try { return typeof window.require === 'function' ? window.require(n) : null; } catch (e) { return null; } };
  const waCalls = () => { const m = req('WAWebCallCollection'); return m ? (m.CallCollection || m.default || m) : null; };
  const callId = (c) => { try { const id = c.id; return String((id && (id._serialized || id.id)) || id || ''); } catch (e) { return ''; } };
  const callBrief = (c) => {
    const o = { id: callId(c) };
    for (const k of ['callOutcome', 'callDuration', 'wasEverConnected', 'userEndedCall', 'callFailedReason', 'callLogResult']) { try { const v = c[k]; if (v !== undefined && (v === null || typeof v !== 'object')) o[k] = v; } catch (e) {} }
    return o;
  };
  // WhatsApp's call engine reports every call event (the other side hung up, traffic stopped, the call broke) to one
  // function. While a call voice runs its name is written down, then it goes on to WhatsApp untouched.
  const WA_ALWAYS = ['CallStateChanged', 'CallTerminateReceived', 'CallRejectReceived', 'CallEnding', 'CallFatal', 'RxTimeout', 'TxTimeout', 'RxTrafficStopped', 'RxTrafficStarted', 'RTCPByeReceived',
    'RelayBindsFailed', 'DataChannelConnectionTimeout', 'DataChannelReady', 'MediaStreamError', 'MediaStreamStartError', 'AudioInitError', 'AudioDriverRestart', 'SoundPortCreateFailed',
    'NoSamplingRatesForAudioRecord', 'P2PTransportCreateFailed', 'RelayElectionSendFailed', 'MissingRelayInfo', 'CallCaptureEnded', 'InterruptionStateChanged', 'AudioStreamStarted', 'AudioTxStarted'];
  const WA_DATA = ['CallEnding', 'CallFatal', 'AudioDriverRestart', 'CallStateChanged', 'InterruptionStateChanged'];
  H.hookWa = () => {
    if (H.waHooked) return true;
    const M = req('WAWebVoipHandleNativeCallEvent');
    if (!M || typeof M.handleWAWebVoipNativeCallEvent !== 'function') return false;
    const orig = M.handleWAWebVoipNativeCallEvent;
    const wrapped = function (type, data) {
      try {
        if (H.watch) {
          let n = String(type);
          try { const E = req('WAWebVoipWaCallEnums'); n = (E && E.CallEvent && E.CallEvent.getName(type)) || n; } catch (e) {}
          const seen = H.waSeen[n] = (H.waSeen[n] || 0) + 1;
          // The events that decide how a call ended are always written down; the chatty ones three times each.
          if (WA_ALWAYS.includes(n) ? seen <= 40 : seen <= 3) {
            let d = '';
            if (WA_DATA.includes(n)) { try { d = typeof data === 'string' ? data : (data && typeof data === 'object' ? JSON.stringify(data) : ''); } catch (e) {} }
            ev('wa_event', d ? n + ' ' + d.slice(0, 400) : n);
          }
        }
      } catch (e) {}
      return orig.apply(this, arguments);
    };
    try { M.handleWAWebVoipNativeCallEvent = wrapped; H.waHooked = M.handleWAWebVoipNativeCallEvent === wrapped; } catch (e) {}
    if (H.waHooked) log('reading WhatsApp call events');
    return H.waHooked;
  };
  // Once a second while a call voice runs: is the call connected, and has it ended (with what WhatsApp wrote down).
  H.watchTick = () => {
    try {
      H.hookWa();
      // A microphone the browser stopped without saying so (no statechange) is started again here.
      if (H.inject && H.micCtx && H.micCtx.state !== 'running' && H.micCtx.state !== 'closed') { H.micResumes++; H.micCtx.resume().catch(() => {}); }
      const col = waCalls(); if (!col) return;
      const conn = !!col.isInConnectedCall;
      if (conn !== H.waConn) { H.waConn = conn; ev(conn ? 'wa_connected' : 'wa_not_connected'); }
      const e = col.lastEndedCall; const id = e ? callId(e) : '';
      if (id && id !== H.waEndedId) { H.waEndedId = id; ev('wa_ended', JSON.stringify(callBrief(e))); if (!H.inject) H.stopWatch(); }
    } catch (err) {}
  };
  H.startWatch = () => {
    H.watch = true; H.waConn = false; H.waSeen = {}; H.waEndedId = '';
    try { const c = waCalls(); const e = c && c.lastEndedCall; H.waEndedId = e ? callId(e) : ''; } catch (e) {}
    if (H.offTimer) { clearTimeout(H.offTimer); H.offTimer = null; }
    if (!H.watchTimer) H.watchTimer = setInterval(H.watchTick, 1000);
    H.watchTick();
  };
  H.stopWatch = () => { H.watch = false; if (H.watchTimer) { clearInterval(H.watchTimer); H.watchTimer = null; } if (H.offTimer) { clearTimeout(H.offTimer); H.offTimer = null; } H.pcs = []; };
  // The voice stopped: the end of the call may still be on its way, so the page keeps looking a little longer.
  H.endWatchSoon = () => { if (!H.watch || H.offTimer) return; H.offTimer = setTimeout(() => { H.offTimer = null; if (!H.inject) H.stopWatch(); }, 20000); };

  H.state = () => ({ t: 'state', armed: H.inject, gum: H.gumCalls, taps: H.taps, remote_frames: H.remoteFrames, remote_peak: Math.round(H.remotePeak * 1000) / 1000, played: H.played, mic: H.micCtx ? H.micCtx.state : 'none', mic_channels: H.micDest ? H.micDest.channelCount : 0, device_lists: H.deviceLists, mic_asked: H.micAsked,
    tracks_out: H.tracksOut, tracks_ended: H.tracksEnded, tracks_muted: H.tracksMuted, mic_resumes: H.micResumes, connections: H.pcs.length, wa_events: H.waHooked, errors: H.errs,
    hidden: typeof document !== 'undefined' && !!document.hidden });
  hinaCall.onmessage = (e) => {
    let m; try { m = JSON.parse(e.data); } catch (err) { return; }
    if (m.t === 'arm') { H.inject = !!m.on; H.forward = !!m.on; if (!m.on) { H.flushMic(); held = []; heldN = 0; } if (m.on) H.startWatch(); else H.endWatchSoon(); log(m.on ? 'armed' : 'disarmed'); post(H.state()); }
    else if (m.t === 'play') { if (H.inject) H.playPcm(m.d, m.rate); }
    else if (m.t === 'flush') H.flushMic();
    else if (m.t === 'status') post(H.state());
    else if (m.t === 'sample') { if (H.watch) H.sample(); }
  };
  post({ t: 'hello' });
})();
""".trim()
}
