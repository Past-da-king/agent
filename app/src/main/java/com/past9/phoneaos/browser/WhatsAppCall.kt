package com.past9.phoneaos.browser

import org.json.JSONObject

/**
 * Settings > Browser > "Turn on calling for a test account".
 *
 * WhatsApp decides per account whether WhatsApp Web may place calls. For an account it has not turned calling on
 * for, the call buttons are simply not there. The app can switch that on inside the open page (nothing is sent to
 * WhatsApp, and it is gone on reload), but forcing a feature WhatsApp has not given an account could get the number
 * banned. So it is off by default, the user marks the numbers it may be done for, and it is only ever done when the
 * number signed in to WhatsApp Web is one of those. No number lives in this source.
 */
object CallTestAccounts {
    const val MAX = 5

    sealed interface Parsed {
        data class Ok(val number: String) : Parsed
        data class Bad(val why: String) : Parsed
    }

    enum class Gate { ALLOWED, SWITCH_OFF, NO_NUMBER, NOT_SIGNED_IN, OTHER_ACCOUNT }

    /** What the user typed, as digits with the country code and nothing else, or why it can't be a number. */
    fun normalise(input: String): Parsed {
        val t = input.trim()
        if (t.isEmpty()) return Parsed.Bad("Type the test account's WhatsApp number, with its country code.")
        if (t.any { !(it.isDigit() || it in "+ -().") }) return Parsed.Bad("A phone number has only digits.")
        var d = t.filter { it.isDigit() }
        val plus = t.startsWith("+")
        if (!plus && d.startsWith("00")) d = d.drop(2)
        else if (!plus && d.startsWith("0")) return Parsed.Bad("Use the full number with its country code, like +44 20 7946 0958.")
        if (d.length !in 8..15 || d.startsWith("0")) return Parsed.Bad("That is not a full phone number. Include the country code.")
        return Parsed.Ok(d)
    }

    /** The stored text (one number per line) as a clean list. Nothing stored means no test accounts. */
    fun parse(stored: String?): List<String> =
        stored.orEmpty().lineSequence().mapNotNull { (normalise("+$it") as? Parsed.Ok)?.number }.distinct().take(MAX).toList()

    fun encode(numbers: List<String>): String = numbers.joinToString("\n")

    /** [list] with [input] added. The error is null when it was added. */
    fun add(list: List<String>, input: String): Pair<List<String>, String?> = when (val p = normalise(input)) {
        is Parsed.Bad -> list to p.why
        is Parsed.Ok -> when {
            p.number in list -> list to "${show(p.number)} is already listed."
            list.size >= MAX -> list to "The list is full. Remove a number first."
            else -> (list + p.number) to null
        }
    }

    fun remove(list: List<String>, number: String): List<String> = list.filterNot { it == digits(number) }

    fun show(number: String): String = "+" + digits(number)

    /** The number inside what WhatsApp calls the signed-in user ("15550100@c.us", "15550100:12@c.us", "15550100"). */
    fun digits(me: String?): String = me.orEmpty().substringBefore('@').substringBefore(':').filter { it.isDigit() }

    /** May calling be switched on for the account signed in as [me]? Only with the switch on AND that exact number marked. */
    fun gate(on: Boolean, marked: List<String>, me: String?): Gate {
        if (!on) return Gate.SWITCH_OFF
        if (marked.isEmpty()) return Gate.NO_NUMBER
        val mine = digits(me)
        if (mine.isEmpty()) return Gate.NOT_SIGNED_IN
        return if (mine in marked) Gate.ALLOWED else Gate.OTHER_ACCOUNT
    }

    /** The gate in words, for the agent. */
    fun why(gate: Gate): String = when (gate) {
        Gate.ALLOWED -> "The signed-in number is marked as a test account."
        Gate.SWITCH_OFF -> "The switch 'Turn on calling for a test account' is off (Settings > Browser). Only the user can turn it on, and only for a test number."
        Gate.NO_NUMBER -> "The switch 'Turn on calling for a test account' has no number marked (Settings > Browser)."
        Gate.NOT_SIGNED_IN -> "WhatsApp Web did not say which number is signed in."
        Gate.OTHER_ACCOUNT -> "The switch 'Turn on calling for a test account' is on, but the number signed in to WhatsApp Web is not one the user marked as a test account. It is never forced for any other number."
    }

    /** The warning shown beside the switch, word for word. */
    const val WARNING = "WhatsApp has not turned on calling in WhatsApp Web for this account. Forcing it on could get the number banned. Use a test number only, never your main number."
}

/**
 * The small scripts [WhatsAppCall] runs in the WhatsApp Web page, each the body of an async function that sees
 * `args`. They read WhatsApp's own modules and fall back to the page itself, because module names move between
 * WhatsApp builds. None of them is installed in the page: they run only while the agent places a call.
 */
object WhatsAppCallJs {
    private val Q = """
const q = (n) => { try { return require(n); } catch (e) { return null; } };
const me = () => { try { const u = q('WAWebUserPrefsMeUser').getMaybeMePnUser(); return u ? String(u.user || u._serialized || '').split('@')[0].split(':')[0].replace(/[^0-9]/g, '') : null; } catch (e) { return null; } };
const lab = (e) => String(e.getAttribute('aria-label') || e.getAttribute('title') || '').trim().toLowerCase();
const callBtn = () => {
  const h = document.querySelector('#main header'); if (!h) return null;
  let b = h.querySelector('[aria-label="Voice call"]');
  if (!b) b = Array.from(h.querySelectorAll('[aria-label],[title]')).find(e => ['voice call', 'audio call', 'start voice call'].includes(lab(e))) || null;
  if (!b) { const i = h.querySelector('[data-icon="voice-call"],[data-icon="audio-call"],[data-icon="call-voice"]'); b = i ? (i.closest('button,[role=button]') || i) : null; }
  return b;
};
const endBtn = () => {
  let b = Array.from(document.querySelectorAll('button,[role=button]')).find(e => ['end call', 'hang up', 'leave call', 'cancel call'].includes(lab(e))) || null;
  if (!b) { const i = document.querySelector('[data-icon="end-call"],[data-icon="call-end"],[data-icon="hang-up"]'); b = i ? (i.closest('button,[role=button]') || i) : null; }
  return b;
};
const calls = () => { const m = q('WAWebCallCollection') || {}; return m.CallCollection || m.default || m; };
const val = (o, k) => { try { const v = o[k]; return typeof v === 'function' ? v.call(o) : v; } catch (e) { return undefined; } };
""".trim() + "\n"

    /** Where the page stands: signed in, the call voice hooks present, which number, and whether WhatsApp offers calling. */
    val INFO = Q + """
let calling = false, unsupported = null;
try { calling = !!q('WAWebVoipGatingUtils').isCallingEnabled(); } catch (e) {}
try { const r = q('WAWebVoipGatingUtils').getUnsupportedBrowserReason(); unsupported = r ? String(r) : null; } catch (e) {}
return { on: location.origin === 'https://web.whatsapp.com', loaded: !!document.querySelector('#pane-side'), hooks: !!window.__hina, me: me(), calling: calling, unsupported: unsupported };
""".trim()

    /**
     * Switches calling on in the open page, for [args.number] only: it reads the signed-in number again itself and
     * does nothing when it is any other. In memory only: nothing is sent to WhatsApp, and a reload undoes it.
     */
    val ENABLE_CALLING = Q + """
const mine = me();
if (!mine || mine !== String(args.number)) return { ok: false, why: 'The signed-in number is not the marked test account.' };
try {
  const code = q('WAWebABPropsConfigs').ABPropConfigs['enable_web_calling'][0];
  const entry = q('WAWebABPropsCache').getAllABPropsMap().get(code);
  if (!entry) return { ok: false, why: 'This WhatsApp Web build has no such setting.' };
  entry.configValue = true;
} catch (e) { return { ok: false, why: 'WhatsApp Web changed: ' + String(e && e.message || e) }; }
let calling = false; try { calling = !!q('WAWebVoipGatingUtils').isCallingEnabled(); } catch (e) {}
return { ok: true, calling: calling };
""".trim()

    /** Opens one chat by its exact name, its number, its id, or a part of its name when only one chat matches. */
    val OPEN_CHAT = Q + """
const Col = (q('WAWebChatCollection') || {}).ChatCollection;
if (!Col) return { ok: false, why: 'WhatsApp Web has not finished loading.' };
const want = String(args.chat || '').trim(); const low = want.toLowerCase(); const num = want.replace(/[^0-9]/g, '');
const all = Col.getModelsArray ? Col.getModelsArray() : [];
const title = (c) => { try { return String(c.formattedTitle || c.name || (c.contact && (c.contact.name || c.contact.pushname)) || ''); } catch (e) { return ''; } };
const sid = (c) => { try { return String(c.id._serialized || c.id); } catch (e) { return ''; } };
const phone = (c) => { try { const p = c.contact && (c.contact.phoneNumber || c.contact.pn); return p ? String(p.user || p).split('@')[0] : ''; } catch (e) { return ''; } };
let hit = null;
if (want.includes('@')) hit = all.find(c => sid(c) === want) || null;
if (!hit && low) { const same = all.filter(c => title(c).toLowerCase() === low); if (same.length === 1) hit = same[0]; else if (same.length > 1) return { ok: false, why: 'More than one chat has that name. Give the chat id or the number.' }; }
if (!hit && num.length >= 7 && want.replace(/[0-9+ ()-]/g, '') === '') hit = all.find(c => sid(c).split('@')[0] === num || phone(c) === num) || null;
if (!hit && low.length >= 3) { const part = all.filter(c => title(c).toLowerCase().includes(low)); if (part.length === 1) hit = part[0]; else if (part.length > 1) return { ok: false, why: 'Several chats match: ' + part.slice(0, 6).map(title).join(', ') + '. Give the exact name.' }; }
if (!hit) return { ok: false, why: 'No chat matches. Give the exact chat name, the number with its country code, or the chat id.' };
if (sid(hit).endsWith('@g.us')) return { ok: false, why: 'That is a group. This places a call to one person.' };
try {
  const Cmd = q('WAWebCmd').Cmd;
  if (args.reopen) { try { await Cmd.closeChat(hit); } catch (e) {} await new Promise(r => setTimeout(r, 400)); }
  await Cmd.openChatBottom({ chat: hit });
} catch (e) { return { ok: false, why: 'The chat did not open: ' + String(e && e.message || e) }; }
for (let i = 0; i < 25 && !document.querySelector('#main header'); i++) await new Promise(r => setTimeout(r, 200));
if (!document.querySelector('#main header')) return { ok: false, why: 'The chat did not open.' };
return { ok: true, id: sid(hit), name: title(hit) };
""".trim()

    val HAS_BUTTON = Q + """
const b = callBtn(); return { button: !!b, disabled: !!(b && (b.disabled || b.getAttribute('aria-disabled') === 'true')) };
""".trim()

    /** Presses the voice call button from the page's own script: a finger-style tap on it does not start the call. */
    val PRESS_CALL = Q + """
const b = callBtn(); if (!b) return { ok: false }; b.click(); return { ok: true };
""".trim()

    /** The call as WhatsApp sees it, in a few plain values. */
    val STATE = Q + """
const col = calls();
const brief = (c) => {
  if (!c) return null; const o = {};
  const id = val(c, 'id'); if (id != null) o.id = String(id._serialized || id.id || id);
  for (const k of ['callOutcome', 'callDuration', 'wasEverConnected', 'userEndedCall', 'callFailedReason', 'callLogResult']) { const v = val(c, k); if (v !== undefined && (v === null || typeof v !== 'object')) o[k] = v; }
  const st = c.getState ? val(c, 'getState') : c.state; if (st != null && typeof st !== 'object') o.state = String(st);
  return o;
};
const d = document.querySelector('[role=dialog]');
return { pending: !!val(col, 'pendingOutgoingCall'), connected: !!val(col, 'isInConnectedCall'), active: brief(val(col, 'lastActiveCall')), ended: brief(val(col, 'lastEndedCall')),
  end_button: !!endBtn(), dialog: d ? String(d.innerText || '').trim().slice(0, 200) : null };
""".trim()

    /** Hangs up: the page's own End call button, else the call's own way to end. */
    val HANG_UP = Q + """
const b = endBtn(); if (b) { b.click(); return { ok: true, how: 'button' }; }
const col = calls(); const c = val(col, 'lastActiveCall') || val(col, 'pendingOutgoingCall');
for (const k of ['endCall', 'hangUp', 'hangup', 'end']) { try { if (c && typeof c[k] === 'function') { await c[k](); return { ok: true, how: k }; } } catch (e) {} }
return { ok: false };
""".trim()
}

/**
 * Places one WhatsApp voice call from the open WhatsApp Web page and lets the agent's voice hold it: opens the chat,
 * checks the page and the call button, arms the voice, presses Voice call, waits for an answer, hangs up when the
 * voice is done or the time is up, stops the voice and hands back what was said. The page and the voice come in as
 * small ports, so every path runs in a test with a made-up page.
 */
class WhatsAppCall(
    private val page: Page,
    private val voice: Voice,
    /** The test-account switch and the numbers the user marked, read when the call is placed. */
    private val test: () -> Pair<Boolean, List<String>>,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    data class Info(val on: Boolean, val loaded: Boolean, val hooks: Boolean, val me: String?, val calling: Boolean, val unsupported: String?)
    data class Chat(val ok: Boolean, val id: String = "", val name: String = "", val why: String = "")
    data class State(val pending: Boolean, val connected: Boolean, val activeId: String?, val endedId: String?, val endButton: Boolean, val raw: JSONObject = JSONObject()) {
        /** WhatsApp shows nothing of a call any more. */
        val quiet: Boolean get() = !pending && !connected && !endButton
    }

    interface Page {
        /** Null when the page did not answer. */
        suspend fun info(): Info?
        /** Loads WhatsApp Web (or loads it again). */
        suspend fun open()
        suspend fun enableCalling(number: String): Chat
        suspend fun openChat(chat: String, reopen: Boolean): Chat
        suspend fun hasCallButton(): Boolean
        suspend fun pressCall(): Boolean
        suspend fun state(): State?
        suspend fun hangUp(): Boolean
        /** The last resort when hanging up did not work: leave the page, which takes the call down with it. */
        suspend fun leave()
    }

    interface Voice {
        /** The switch 'Let the agent speak in WhatsApp calls'. */
        val on: Boolean
        /** Null when the voice is armed, else why not. */
        suspend fun start(peer: String, goal: String, minSeconds: Int = 0): String?
        /** The call was answered: a minimum time counts from here. */
        fun answered() {}
        val endRequested: Boolean
        val failed: String?
        /** Turns of speech so far. */
        val turns: Int
        fun nudge(text: String)
        fun words(): String
        /** Ends the voice and saves the words to the chat. */
        fun stop()
        /** How the call ended, for the call's own story: [by] is a [CallDiag] word, empty when the agent did not end it. */
        fun ended(by: String, why: String, whatsapp: JSONObject?) {}
        /** The call's own story (who ended it, a timeline, counters, page errors), when the voice keeps one. */
        fun diagnostics(): JSONObject? = null
    }

    class Result(val outcome: String, val reason: String, val contact: String = "", val chat: String = "", val rangSeconds: Long = 0, val durationSeconds: Long = 0,
                 val endedBy: String = "", val transcript: String = "", val callingOffered: Boolean? = null, val testAccount: String = "", val whatsapp: JSONObject? = null,
                 /** Who or what ended it, a timeline, counters every five seconds and the page's last errors ([CallDiag.json]). */
                 val diagnostics: JSONObject? = null, val minSeconds: Int = 0) {
        fun json(): JSONObject = JSONObject().put("outcome", outcome).put("reason", reason).put("contact", contact).put("chat", chat)
            .put("rang_seconds", rangSeconds).put("duration_seconds", durationSeconds).put("ended_by", endedBy).put("min_seconds", minSeconds)
            .put("transcript", transcript).put("saved_to_chat", transcript.isNotBlank())
            .put("calling_offered", callingOffered ?: JSONObject.NULL).put("test_account", testAccount)
            .put("whatsapp", whatsapp ?: JSONObject.NULL)
            .also { o -> diagnostics?.let { d -> for (k in listOf("ended", "timeline", "counters", "page_errors")) if (d.has(k)) o.put(k, d.opt(k)) } }
    }

    /** Where the call is, in a word, for anyone asking while it runs. */
    @Volatile var phase = "starting"; private set

    private fun failed(why: String, info: Info? = null, gate: CallTestAccounts.Gate? = null, contact: String = "", chat: String = "", raw: JSONObject? = null) =
        Result(FAILED, why, contact, chat, callingOffered = info?.calling, testAccount = gate?.let { CallTestAccounts.why(it) }.orEmpty(), whatsapp = raw).also { phase = "failed" }

    suspend fun run(chat: String, goal: String, maxMinutes: Int = DEFAULT_MINUTES, contact: String = "", minSeconds: Int = 0): Result {
        if (chat.isBlank()) return failed("Say which chat to call: its name, its number or its id.")
        if (!voice.on) return failed("The call voice is off. The user turns it on in Settings > Browser > Let the agent speak in WhatsApp calls. Ask them; do not work around it.")
        val max = maxMinutes.coerceIn(1, MAX_MINUTES) * 60_000L
        // max_minutes wins: a minimum longer than the limit is the limit.
        val min = minSeconds.coerceIn(0, (max / 1000).toInt())

        // ---- the page: WhatsApp Web, signed in, with the call voice in it ----
        phase = "opening WhatsApp Web"
        var info = page.info()
        if (info == null || !info.on) { page.open(); info = settle { it.loaded } }
        else if (!info.loaded) info = settle { it.loaded }
        if (info == null || !info.on || !info.loaded) return failed("WhatsApp Web did not load, or it is not signed in. Open https://web.whatsapp.com and check.", info)
        if (!info.hooks) { page.open(); info = settle { it.loaded && it.hooks } }
        if (info == null || !info.loaded) return failed("WhatsApp Web did not load again. Open https://web.whatsapp.com and check.", info)
        if (!info.hooks) return failed("The call voice is not in the WhatsApp Web page. This phone's browser may be too old for it.", info)
        info.unsupported?.let { return failed("WhatsApp Web says this browser can't place calls ($it). Check that web.whatsapp.com is listed in Settings > Browser > Sites that need advanced browser features.", info) }

        // ---- calling for this account: offered by WhatsApp, or switched on for a number the user marked as a test account ----
        val (testOn, marked) = test()
        val gate = CallTestAccounts.gate(testOn, marked, info.me)
        var forced = false
        if (!info.calling && gate == CallTestAccounts.Gate.ALLOWED) {
            val did = page.enableCalling(CallTestAccounts.digits(info.me))
            if (!did.ok) return failed("Calling could not be switched on for the test account: ${did.why}", info, gate)
            forced = true
        }

        phase = "opening the chat"
        val opened = page.openChat(chat.trim(), reopen = forced)
        if (!opened.ok) return failed(opened.why.ifBlank { "The chat did not open." }, info, gate)
        val who = contact.trim().ifEmpty { opened.name }
        var button = false
        for (i in 0 until 6) { if (page.hasCallButton()) { button = true; break }; pause(500) }
        if (!button) return failed(
            if (forced) "Calling was switched on for the test account, but this chat still has no voice call button."
            else "WhatsApp does not offer calling in WhatsApp Web for this account: the chat has no voice call button. " + CallTestAccounts.why(gate),
            info, gate, who, opened.id)

        // ---- the voice first, so WhatsApp is handed the agent's microphone; then the call ----
        phase = "starting the voice"
        voice.start(who, goal, min)?.let { return failed(it, info, gate, who, opened.id) }
        val before = page.state()
        val placedAt = now()
        if (!page.pressCall()) { voice.stop(); return failed("The voice call button could not be pressed.", info, gate, who, opened.id) }

        phase = "ringing"
        var started = false
        var answeredAt = 0L
        var endedAt = 0L
        var endedBy = ""
        var why = ""
        var silent = 0
        var lost = 0
        var nudged = false
        var last: State? = before
        while (true) {
            pause(POLL_MS)
            val s = page.state()
            val t = now()
            if (s == null) {
                // The page stopped answering (it reloaded or crashed): the call is gone with it.
                if (++silent >= 10) { endedBy = "page"; why = "WhatsApp Web stopped answering during the call."; endedAt = t; break }
                continue
            }
            silent = 0; last = s
            val newEnded = s.endedId != null && s.endedId != before?.endedId
            if (s.pending || s.connected || newEnded || (s.activeId != null && s.activeId != before?.activeId)) started = true
            if (s.connected && answeredAt == 0L) { answeredAt = t; phase = "answered"; voice.answered() }
            if (newEnded) { endedBy = OTHER_SIDE; endedAt = t; break }
            if (answeredAt == 0L) {
                if (!started && t - placedAt >= START_MS) {
                    hangUp(); endedAt = t; endedBy = "agent"
                    why = "The call did not start." + s.raw.optString("dialog").takeIf { it.isNotBlank() && it != "null" }?.let { " WhatsApp shows: $it" }.orEmpty(); break
                }
                if (t - placedAt >= RING_MS) { hangUp(); endedAt = t; endedBy = "agent"; why = "Nobody answered in ${RING_MS / 1000} seconds."; break }
                continue
            }
            // Answered.
            if (!s.connected) {
                // Five seconds with no call left on the page: it is over on the other side. While WhatsApp still shows a call
                // (it is reconnecting), it gets RECONNECT_POLLS seconds to come back before the agent ends it.
                lost++
                if (s.quiet && lost >= LOST_POLLS) { endedBy = OTHER_SIDE; endedAt = t; break }
                if (lost >= RECONNECT_POLLS) { endedBy = "agent"; endedAt = t; why = "The call was not connected for ${RECONNECT_POLLS * POLL_MS / 1000} seconds."; hangUp(); break }
                continue
            } else lost = 0
            voice.failed?.let { why = "The voice stopped: $it" }
            if (why.isNotEmpty()) { endedBy = "agent"; endedAt = t; hangUp(); break }
            if (voice.endRequested) { pause(GOODBYE_MS); endedBy = "agent"; endedAt = now(); why = FINISHED; hangUp(); break }
            if (t - answeredAt >= max) { endedBy = "max_minutes"; endedAt = t; hangUp(); why = "The call reached its limit of ${max / 60_000} minutes."; break }
            // Connected and nobody has said a word: the voice greets first.
            if (!nudged && voice.turns == 0 && t - answeredAt >= QUIET_MS) { nudged = true; voice.nudge("The call is connected and nobody has spoken yet. Greet them now.") }
        }

        phase = "ending"
        val words = voice.words()
        // Ended on the other side: the page's last words on the call (WhatsApp's own events) get a moment to arrive.
        if (endedBy == OTHER_SIDE) pause(SETTLE_MS)
        val asked = voice.endRequested
        voice.stop()
        val end = page.state() ?: last
        val raw = end?.raw
        val ended = raw?.optJSONObject("ended")?.takeIf { it.optString("id") != (before?.endedId ?: "") }
        voice.ended(when {
            endedBy == "max_minutes" -> CallDiag.AGENT_MAX_MINUTES
            endedBy == "page" -> CallDiag.BY_PAGE
            endedBy == "agent" && why.startsWith("The voice stopped") -> CallDiag.AGENT_VOICE_FAILED
            endedBy == "agent" && asked && answeredAt > 0 && why == FINISHED -> CallDiag.AGENT_END_CALL
            endedBy == "agent" -> CallDiag.AGENT_OTHER
            else -> ""
        }, why, ended)
        val diag = runCatching { voice.diagnostics() }.getOrNull()
        // WhatsApp's own word on whether the call was ever connected wins over what the polling happened to see.
        if (answeredAt == 0L && ended?.optBoolean("wasEverConnected") == true) answeredAt = endedAt - ended.optLong("callDuration", 0) * 1000
        val rang = ((if (answeredAt > 0) answeredAt else endedAt) - placedAt).coerceAtLeast(0) / 1000
        return when {
            answeredAt > 0 -> Result(ANSWERED, why.ifEmpty { diag?.optJSONObject("ended")?.optString("detail")?.takeIf { it.isNotBlank() } ?: "The call ended on the other side, or it dropped." }, who, opened.id, rang, ((endedAt - answeredAt).coerceAtLeast(0)) / 1000, endedBy, words, info.calling || forced, tested(forced, gate), raw, diag, min)
            started -> Result(NO_ANSWER, why.ifEmpty { "The call ended before anyone answered (not answered, declined or unreachable)." }, who, opened.id, rang, 0, endedBy, words, info.calling || forced, tested(forced, gate), raw, diag, min)
            else -> Result(FAILED, why.ifEmpty { "The call did not start." }, who, opened.id, rang, 0, endedBy, words, info.calling || forced, tested(forced, gate), raw, diag, min)
        }.also { phase = "done" }
    }

    private fun tested(forced: Boolean, gate: CallTestAccounts.Gate) =
        if (forced) "Calling was switched on in the page for this call, for the marked test account. A reload undoes it." else if (gate == CallTestAccounts.Gate.ALLOWED) "Marked test account; WhatsApp offered calling itself." else ""

    /** Waits for the page to be [ready], up to [LOAD_MS]. The last answer, ready or not. */
    private suspend fun settle(ready: (Info) -> Boolean): Info? {
        var info: Info? = null
        var waited = 0L
        while (waited <= LOAD_MS) {
            info = page.info()
            if (info != null && info.on && ready(info)) return info
            pause(POLL_MS); waited += POLL_MS
        }
        return info
    }

    /** Hangs up and makes sure: twice by the page's own means, then by leaving the page. */
    private suspend fun hangUp() {
        for (attempt in 0 until 2) {
            page.hangUp()
            for (i in 0 until 4) { pause(POLL_MS); val s = page.state() ?: return; if (s.quiet) return }
        }
        page.leave()
    }

    companion object {
        const val ANSWERED = "answered"
        const val NO_ANSWER = "no_answer"
        const val FAILED = "failed"
        const val OTHER_SIDE = "other side or dropped"
        const val DEFAULT_MINUTES = 5
        const val MAX_MINUTES = 30
        const val POLL_MS = 1_000L
        /** A page load, sign-in restored, chats drawn. */
        const val LOAD_MS = 45_000L
        /** WhatsApp shows the call within this, or it never started. */
        const val START_MS = 20_000L
        const val RING_MS = 60_000L
        /** Time for the goodbye to be heard before hanging up. */
        const val GOODBYE_MS = 3_000L
        const val QUIET_MS = 4_000L
        const val LOST_POLLS = 5
        /** A call WhatsApp still shows but that is not connected (it is reconnecting) gets this long to come back. */
        const val RECONNECT_POLLS = 30
        /** After the other side ended it: time for the page's last call events to reach the app. */
        const val SETTLE_MS = 1_500L
        const val FINISHED = "The conversation was finished."

        fun parseInfo(v: JSONObject?): Info? = v?.let {
            Info(it.optBoolean("on"), it.optBoolean("loaded"), it.optBoolean("hooks"), it.optString("me").takeIf { m -> m.isNotBlank() && m != "null" }, it.optBoolean("calling"),
                it.optString("unsupported").takeIf { u -> u.isNotBlank() && u != "null" })
        }
        fun parseChat(v: JSONObject?): Chat = v?.let { Chat(it.optBoolean("ok"), it.optString("id"), it.optString("name"), it.optString("why")) } ?: Chat(false, why = "The page did not answer.")
        fun parseState(v: JSONObject?): State? = v?.let {
            fun id(k: String) = it.optJSONObject(k)?.optString("id")?.takeIf { s -> s.isNotBlank() }
            State(it.optBoolean("pending"), it.optBoolean("connected"), id("active"), id("ended"), it.optBoolean("end_button"), it)
        }
        /** What [BrowserEngine.runAsync] hands back ({ok, v} or {ok:false, e}) as the script's own object, or null. */
        fun value(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()?.takeIf { it.optBoolean("ok") }?.optJSONObject("v")
    }
}
