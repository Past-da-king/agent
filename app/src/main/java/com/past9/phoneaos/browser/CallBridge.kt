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
 *   app to page   {"t":"arm","on":true}                 only while a call runs: fake microphone and speaker tap
 *                 {"t":"play","rate":24000,"d":"<b64>"} the agent's voice, into the fake microphone
 *                 {"t":"flush"}                         drop the voice not yet played (the person talked over it)
 *                 {"t":"status"}
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
            else -> FromPage.Bad("unknown")
        }
    }

    fun arm(on: Boolean): String = JSONObject().put("t", "arm").put("on", on).toString()
    fun play(pcm: ByteArray, rate: Int = OUT_RATE): String =
        JSONObject().put("t", "play").put("rate", rate).put("d", Base64.getEncoder().encodeToString(Pcm.even(pcm))).toString()
    fun flush(): String = """{"t":"flush"}"""
    fun status(): String = """{"t":"status"}"""

    /** The one tool the voice has in a call: it says when the conversation is over. Hanging up is the agent's click. */
    fun tools(): JSONArray = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(JSONObject()
        .put("name", "end_call")
        .put("description", "The call is over. Call this only after you have said goodbye and the other person has finished.")
        .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("reason", JSONObject().put("type", "STRING")))))))

    fun system(agentName: String, userName: String, peer: String, goal: String): String =
        "You are ${agentName.ifBlank { "an assistant" }}, speaking on a WhatsApp voice call on behalf of ${userName.ifBlank { "the person you work for" }}. " +
            "You are talking to ${peer.ifBlank { "the person who answered" }}. Say who you are and who you are calling for in your first sentence. " +
            "Why you are calling: ${goal.ifBlank { "to pass on a short message" }}. Speak plainly and briefly, one thought at a time, and listen. " +
            "Never invent facts, never agree to anything about money, and when the reason for the call is done, say goodbye and call end_call."
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
) {
    private val lock = Any()
    private var link: LiveLink? = null
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

    /** Every message the page posts lands here (any thread). */
    fun onPageMessage(raw: String?) {
        when (val m = CallBridgeProtocol.parse(raw)) {
            is CallBridgeProtocol.FromPage.Hello -> {
                // A fresh page (a reload): it starts disarmed, so a running call arms it again.
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
            is CallBridgeProtocol.FromPage.Bad -> Unit
        }
    }

    /**
     * Arms the page and starts the voice. Call it BEFORE the call is placed, so WhatsApp is handed the fake
     * microphone. Returns what happened, in words for the agent.
     */
    suspend fun start(peer: String, goal: String): String {
        synchronized(lock) { if (link != null) return "A call voice is already running. Stop it first." }
        if (!pageReady) return "WhatsApp Web has not loaded with the call bridge yet. Open https://web.whatsapp.com in a new tab (the switch applies to new tabs), then start again."
        val token = runCatching { auth() }.getOrElse { LiveAuth.Token.No(it.message ?: "The voice could not start.", false) }
        val ok = token as? LiveAuth.Token.Ok ?: return "The voice could not start: " + (token as LiveAuth.Token.No).why
        val (agent, user) = names()
        val l = open(ok.url, ok.model.ifBlank { DEFAULT_MODEL }, ok.voice.ifBlank { "Kore" }, CallBridgeProtocol.system(agent, user, peer, goal), CallBridgeProtocol.tools(), ::onEvent)
        synchronized(lock) {
            if (link != null) { runCatching { l.stop() }; return "A call voice is already running. Stop it first." }
            link = l; startedAt = now(); lines.clear(); lastWho = ""; frames.drain()
            ready = false; endRequested = false; failed = null; heardFrames = 0; sentFrames = 0; playedChunks = 0; heardPeak = 0f
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

    private fun onEvent(e: LiveSession.Event) {
        when (e) {
            is LiveSession.Event.SetupComplete -> ready = true
            is LiveSession.Event.Transcript -> synchronized(lock) {
                // The words arrive in pieces: one line per turn of speech, as on a phone call.
                if (lastWho == e.who && lines.isNotEmpty()) { val last = lines.removeAt(lines.size - 1); lines += Triple(last.first, last.second + e.text, last.third) }
                else { lines += Triple(e.who, e.text, now()); lastWho = e.who }
            }
            is LiveSession.Event.ToolCall -> {
                if (e.name == "end_call") endRequested = true
                synchronized(lock) { link }?.sendToolResult(e.id, e.name, JSONObject().put("ok", true))
            }
            is LiveSession.Event.Failed -> { failed = e.error; stop() }
            is LiveSession.Event.Closed -> stop()
            else -> Unit
        }
    }

    /** Ends the voice, disarms the page (WhatsApp gets the real microphone and speaker back) and saves the words. Safe to call twice. */
    fun stop(): String {
        val (l, words, began) = synchronized(lock) {
            val l = link ?: return if (armed) { armed = false; toPage(CallBridgeProtocol.arm(false)); "Stopped." } else "No call voice is running."
            link = null
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
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
        fun transcript(words: List<Triple<String, String, Long>>): String =
            words.joinToString("\n") { (w, t, _) -> (if (w == "in") "Them: " else "Voice: ") + t.trim() }
    }
}

/**
 * What runs in WhatsApp Web at document start, on that site only and only when the user switched the call voice on.
 * It wraps the two places a call touches sound: getUserMedia (the microphone WhatsApp is given) and
 * AudioNode.connect (what WhatsApp sends to the speaker). While disarmed both pass straight through, so chats,
 * voice notes and a call the user places by hand behave as they always did. It changes no WhatsApp setting and
 * forces no feature: a call only works if WhatsApp itself offers calling to the account.
 */
object CallBridgeJs {
    val HOOKS = """
(() => {
  if (window.__hina || window.top !== window || typeof hinaCall === 'undefined') return;
  const H = window.__hina = { log: [], gumCalls: 0, taps: 0, inject: false, muteRemote: true, forward: false, remoteFrames: 0, remotePeak: 0, played: 0 };
  const post = (o) => { try { hinaCall.postMessage(JSON.stringify(o)); } catch (e) {} };
  const log = H.L = (...a) => { const line = a.join(' '); H.log.push(line); if (H.log.length > 200) H.log.shift(); post({ t: 'log', m: line }); };
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
        H.micDest = H.micCtx.createMediaStreamDestination();
        H.micGain = H.micCtx.createGain(); H.micGain.gain.value = 1;
        origConnect.call(H.micGain, H.micDest);
        const cs = H.micCtx.createConstantSource(); cs.offset.value = 0; origConnect.call(cs, H.micGain); cs.start();
        H.playHead = 0; H.sources = new Set();
      }
      if (H.micCtx.state !== 'running') H.micCtx.resume().catch(() => {});
      return H.micDest.stream;
    };
    md.getUserMedia = async function (c) {
      if (H.inject && c && c.audio && !c.video) {
        H.gumCalls++; log('getUserMedia: fake microphone');
        const s = H.ensureMic();
        return new MediaStream(s.getAudioTracks().map(t => t.clone()));
      }
      return origGUM(c);
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

  H.state = () => ({ t: 'state', armed: H.inject, gum: H.gumCalls, taps: H.taps, remote_frames: H.remoteFrames, remote_peak: Math.round(H.remotePeak * 1000) / 1000, played: H.played, mic: H.micCtx ? H.micCtx.state : 'none' });
  hinaCall.onmessage = (e) => {
    let m; try { m = JSON.parse(e.data); } catch (err) { return; }
    if (m.t === 'arm') { H.inject = !!m.on; H.forward = !!m.on; if (!m.on) { H.flushMic(); held = []; heldN = 0; } log(m.on ? 'armed' : 'disarmed'); post(H.state()); }
    else if (m.t === 'play') { if (H.inject) H.playPcm(m.d, m.rate); }
    else if (m.t === 'flush') H.flushMic();
    else if (m.t === 'status') post(H.state());
  };
  post({ t: 'hello' });
})();
""".trim()
}
