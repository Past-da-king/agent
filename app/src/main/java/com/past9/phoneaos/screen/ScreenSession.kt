package com.past9.phoneaos.screen

import com.past9.phoneaos.agent.ToolContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An installed app the agent may open: how the person names it and how to start it. */
data class AppRef(val pkg: String, val label: String, val component: String)

/** Finding installed apps. The real one asks the phone; tests hand in a list. */
interface ScreenApps {
    fun find(nameOrPackage: String): AppRef?
    fun label(pkg: String): String
    /** Names close to [q], to help the agent when its guess was wrong. */
    fun near(q: String): List<String>
}

/** What Settings holds for the background screen, read fresh each time. */
data class ScreenConfig(
    val rules: Map<String, ScreenRules.Rule> = emptyMap(), val extraWords: List<String> = emptyList(),
    /** May it keep working while the phone is locked. Off unless the person switches it on. */
    val whileLocked: Boolean = false,
    val width: Int = 720, val height: Int = 1600, val dpi: Int = 320,
    /** Banking and money apps: built in, plus what the person added. Never used on the background screen. */
    val blocklist: ScreenBlocklist = ScreenBlocklist(""), val blockAdded: List<String> = emptyList(),
    /** Keep a watchable recording of each run. */
    val record: Boolean = true,
)

/** What the person sees about the background screen: the notification, the peek sheet and the chat chip read this. */
data class ScreenState(
    val open: Boolean = false, val pkg: String = "", val app: String = "", val owner: String = "", val step: String = "",
    /** The person pressed Stop. Nothing more happens there until they allow it again; an agent cannot lift this. */
    val stopped: Boolean = false,
    /** The person is using the hidden screen themselves. The agent waits. */
    val takenOver: Boolean = false,
    val width: Int = 0, val height: Int = 0,
    /** How many steps this run has that could be learned, and how many of them the person did by hand. */
    val steps: Int = 0, val shown: Int = 0,
)

/** A money app was asked for, or turned up. Carries the words for the agent. */
class ScreenBlocked(val pkg: String, message: String) : Exception(message)

/** How a replay of learned steps went. */
class Replay(val ok: Boolean, val text: String, val failedAt: Int, val tookMs: Long, val stepMs: List<Long>, val skipped: Set<Int>, val usedPicture: Boolean)

/**
 * The background screen: one hidden display where the agent uses other apps while the person keeps their own screen.
 * Every tool goes through here, so the rules live in one place: only the hidden display is ever touched, the person's
 * Stop and Take over always win, banking and money apps are never used, an app set to Never is never opened, and a
 * press that sends, pays or deletes asks first through the app's own approval card. Each run is written down step by
 * step: that is the recording the person can watch, and what a learned app is made from.
 */
class ScreenSession(
    private val backend: ScreenBackend,
    private val apps: ScreenApps,
    private val config: () -> ScreenConfig,
    private val pictures: File,
    /** Try to start the helper without the person (Shizuku they already allowed). Null when it was started. */
    private val start: suspend () -> String? = { "nothing on this phone can start it yet" },
    /** Show the person the setup steps, with the reason. */
    private val needSetup: (String) -> Unit = {},
    /** The screen opened or closed: start or stop the foreground service that keeps the app awake for it. */
    private val active: (Boolean) -> Unit = {},
    /** Read the words in a picture, for apps that draw their own screen and give no element list. */
    private val readPicture: suspend (String) -> String = { "" },
    /** Where some words are in a picture (x, y in its pixels), or null: the last resort when a learned step's element is not in the list. */
    private val findInPicture: suspend (String, String) -> Pair<Int, Int>? = { _, _ -> null },
    private val runs: ScreenRuns? = null,
    /** Paint over these boxes (left, top, right, bottom, in screen pixels) in a picture of a screen [width] wide before it is kept. */
    private val mask: (ByteArray, List<IntArray>, Int) -> ByteArray = { jpeg, _, _ -> jpeg },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = _state
    @Volatile var lastUsed = 0L; private set
    private var displayId = 0
    private var elements: List<ScreenElement> = emptyList()
    /** Apps that were on the person's screen (or in their recents) before the agent took them: they go back on close. */
    private val borrowed = LinkedHashSet<String>()
    private var pictureNo = 0
    private var run: ScreenRuns.Run? = null
    /** Texts typed in this run that must not show in its recording. */
    private val secrets = ArrayList<String>()

    companion object {
        const val STOPPED = "The user pressed Stop on the background screen. Nothing more may be done there until they allow it again. Do not try another way; tell them it is stopped."
        const val TAKEN = "The user has taken over the background screen and is using it themselves. Wait for them (screen_wait), or carry on with other work. Nothing was done."
        const val CLOSED = "Nothing is open on the background screen. Start with screen_open_app."
        fun notReady(why: String) = "The background screen is not ready on this phone: $why. Nothing was done. The setup steps are showing to the user now " +
            "(Settings > Background screen). Until then you can still: use a website with the browser tools, hand an app something with android_intent, or ask the user to do this step."
    }

    /** The steps of the run so far: what a learned app is saved from. */
    val runSteps: List<RunStep> get() = run?.steps ?: emptyList()
    val runPackage: String get() = run?.pkg ?: _state.value.pkg

    // ---- reaching the helper ----

    private suspend fun ask(q: JSONObject, timeoutMs: Int = 20_000): JSONObject {
        val r = backend.call(q, timeoutMs).json
        // The helper turned a money app away, or closed one that turned up.
        if (r.has("blocked")) throw ScreenBlocked(r.optString("blocked"), r.optString("error").ifBlank { ScreenBlocklist.REASON })
        return r
    }

    private fun err(r: JSONObject, fallback: String) = r.optString("error").ifBlank { fallback }

    private inline fun safely(block: () -> String): String = try { block() } catch (e: ScreenBlocked) {
        note("blocked", "Blocked: a banking or money app")
        e.message ?: ScreenBlocklist.REASON
    } catch (e: HelperAbsent) {
        forget(); "The background screen stopped: ${e.message}. Whatever was open on it is closed. Start again with screen_open_app."
    }

    /** Null when the helper is there and current; otherwise what to tell the agent. */
    private suspend fun helper(): String? {
        var ping = runCatching { backend.call(op("ping"), 4_000).json }.getOrNull()
        if (ping != null && ping.optInt("version") != ScreenProtocol.VERSION) {
            // A helper left over from before an update: it must go, the new one speaks the new protocol.
            runCatching { backend.call(op("exit"), 4_000) }; backend.close(); forget(); ping = null
        }
        if (ping == null) {
            if (displayId != 0) forget()
            val why = runCatching { start() }.getOrElse { it.message ?: "it could not be started" }
            if (why != null) { needSetup(why); return notReady(why) }
            var tries = 0
            while (ping == null && tries++ < 25) { delay(200); ping = runCatching { backend.call(op("ping"), 2_000).json }.getOrNull() }
            if (ping == null) { val w = "the helper was started but did not answer"; needSetup(w); return notReady(w) }
        }
        val p = ping!!
        if (p.optInt("sdk") in 1..29) return "The background screen needs Android 11 or newer. This phone is older, so it cannot be used here."
        // The helper closes a display nobody used for ten minutes: catch up with it.
        if (displayId != 0 && p.optInt("display") != displayId) forget()
        return null
    }

    private fun forget() {
        displayId = 0; elements = emptyList(); borrowed.clear()
        run?.finish(); run = null; secrets.clear()
        if (_state.value.open) { _state.value = _state.value.copy(open = false, pkg = "", app = "", step = "", takenOver = false, steps = 0, shown = 0); active(false) }
    }

    /** What stops a tool before it does anything, or null. */
    private fun blocked(ctx: ToolContext, needOpen: Boolean): String? {
        val s = _state.value
        if (ctx.agentLabel == "you") return null
        if (s.stopped) return STOPPED
        if (s.takenOver) return TAKEN
        if (s.open && s.owner.isNotBlank() && s.owner != "you" && s.owner != ctx.agentLabel && now() - lastUsed < 90_000)
            return "The background screen is in use by ${if (s.owner == "main") "the main agent" else "\"${s.owner}\""} right now. Wait a little and try again."
        if (needOpen && !s.open) return CLOSED
        return null
    }

    private fun touch(owner: String, step: String) {
        lastUsed = now()
        _state.value = _state.value.copy(owner = owner, step = step)
    }

    // ---- the run: every step written down, with a picture ----

    private suspend fun snap(): Int {
        val r = run ?: return -1
        if (!config().record) return -1
        val m = runCatching { backend.call(op("frame").put("max", 540).put("q", 55), 6_000) }.getOrNull() ?: return -1
        val bin = m.bin ?: return -1
        // Password fields, and anything typed as a secret, are painted over before the picture is kept.
        val hide = elements.filter { e -> 'p' in e.flags || (e.text.isNotBlank() && secrets.any { it.isNotBlank() && e.text.contains(it) }) }.map { intArrayOf(it.left, it.top, it.right, it.bottom) }
        val safe = if (hide.isEmpty()) bin else runCatching { mask(bin, hide, _state.value.width) }.getOrNull() ?: return -1
        return r.picture(safe)
    }

    private suspend fun log(kind: String, say: String, sel: JSONObject? = null, text: String = "", secret: Boolean = false, by: String = "agent", extra: JSONObject? = null, took: Long = 0, picture: Boolean = true) {
        val r = run ?: return
        r.add(RunStep(now(), kind, say, sel, if (secret) "" else text, secret, if (picture) snap() else -1, by, extra, took))
        val all = r.steps.filter { it.kind in ScreenSkills.DOING }
        _state.value = _state.value.copy(steps = all.size, shown = all.count { it.by == "you" })
    }

    private fun note(kind: String, say: String) { run?.add(RunStep(now(), kind, say)) }

    private fun selOf(e: ScreenElement?, all: List<ScreenElement> = elements): JSONObject? = e?.let { ScreenSkills.selector(it, all, _state.value.width, _state.value.height) }

    // ---- reading ----

    private class Front(val pkg: String, val cls: String, val taskId: Int)

    private suspend fun tasks(): List<JSONObject> {
        val a = ask(op("tasks")).optJSONArray("tasks") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private suspend fun front(): Front? = tasks().firstOrNull { it.optInt("display") == displayId && it.optBoolean("visible") && it.optString("pkg").isNotBlank() }
        ?.let { Front(it.optString("pkg"), it.optString("cls"), it.optInt("id")) }

    private suspend fun settle() { runCatching { ask(op("idle").put("quiet", 350).put("timeout", 2_500), 6_000) }.onFailure { if (it is ScreenBlocked) throw it } }

    /** Read the element list only, quietly. True when it could be read. */
    private suspend fun readTree(): Boolean {
        val tree = ask(op("tree").put("max", 300))
        elements = if (tree.optBoolean("ok")) ScreenTree.parse(tree) else emptyList()
        return tree.optBoolean("ok")
    }

    /** Read the screen and remember its elements. Returns the text for the agent. */
    private suspend fun read(mode: String = "tree", filter: String = ""): String {
        val f = front()
        if (f != null && f.pkg != _state.value.pkg) _state.value = _state.value.copy(pkg = f.pkg, app = apps.label(f.pkg))
        val sb = StringBuilder()
        sb.append(if (f == null) "The background screen shows no app right now." else "On the background screen: ${apps.label(f.pkg)} (${f.cls.substringAfterLast('.')}).").append('\n')
        val tree = ask(op("tree").put("max", 300))
        elements = if (tree.optBoolean("ok")) ScreenTree.parse(tree) else emptyList()
        val usable = elements.any { it.label.isNotBlank() || it.clickable }
        var wantPicture = mode == "image" || mode == "both"
        if (!usable) {
            sb.append(if (tree.optBoolean("ok")) "The app gives no list of what is on its screen (it draws it itself)." else "The element list could not be read (${err(tree, "no reason given")}).")
                .append(" Use the picture and press by x,y.\n")
            wantPicture = true
        } else if (mode != "image") sb.append(ScreenTree.describe(elements, filter)).append('\n')
        if (tree.optBoolean("protected") && usable) sb.append("This screen is protected: its picture is blank, but the list above can be used.\n")
        if (wantPicture) {
            val m = runCatching { backend.call(op("frame").put("max", config().width).put("q", 72), 8_000) }.getOrNull()
            val bin = m?.bin
            if (bin == null) sb.append("No picture: ${m?.json?.let { err(it, "nothing drawn yet") } ?: "the helper did not answer"}.\n")
            else {
                pictures.mkdirs()
                val file = File(pictures, "screen-${pictureNo++ % 8}.jpg").apply { writeBytes(bin) }
                if (m!!.json.optBoolean("blank")) sb.append("The picture is one flat colour: the app protects this screen from being seen, or has drawn nothing. ")
                    .append(if (usable) "Work from the element list.\n" else "Ask the user to take over for this step.\n")
                else {
                    sb.append("Picture (${m.json.optInt("width")}x${m.json.optInt("height")}, x,y on it are the screen's own):\n[[image:${file.absolutePath}]]\n")
                    if (!usable) runCatching { readPicture(file.absolutePath) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { sb.append("Words read from the picture:\n").append(it.take(2500)).append('\n') }
                }
            }
        }
        return sb.toString().trimEnd()
    }

    private fun rule(pkg: String) = ScreenRules.ruleFor(pkg, config().rules)

    /** Banking and money apps: refused here before the helper is even asked, and again by the helper itself. */
    private fun money(pkg: String, label: String = apps.label(pkg)): String? = if (config().blocklist.blocked(pkg, label) != null) ScreenBlocklist.reason(label.ifBlank { pkg }) else null

    private suspend fun approve(ctx: ToolContext, gate: ScreenRules.Gate): String? = when (gate) {
        ScreenRules.Gate.Go -> null
        is ScreenRules.Gate.Refuse -> gate.reason
        is ScreenRules.Gate.Ask -> {
            val a = ctx.ask("APPROVAL|${gate.what}|${gate.details}", listOf("Approve", "Decline"))
            log("approval", "${gate.what}: ${when (a) { "Approve" -> "you approved"; null -> "nobody answered"; else -> "you declined" }}", picture = false)
            when (a) { "Approve" -> null; null -> "Needs the user's yes and nobody answered. Not done."; else -> "The user declined. Not done." }
        }
    }

    // ---- what the tools and a replay both do (the caller holds the lock) ----

    private suspend fun doOpen(ctx: ToolContext, ref: AppRef, link: String, fresh: Boolean, by: String = "agent"): String? {
        money(ref.pkg, ref.label)?.let { note("blocked", "Blocked: ${ref.label}"); return it }
        if (rule(ref.pkg) == ScreenRules.Rule.NEVER) return ScreenRules.NEVER_REASON
        helper()?.let { return it }
        val c = config()
        val made = ask(op("create").put("w", c.width).put("h", c.height).put("dpi", c.dpi).put("locked", c.whileLocked).put("block", JSONArray(c.blockAdded)))
        if (!made.optBoolean("ok")) return "The phone refused a background screen: ${err(made, "no reason given")}. Nothing was opened."
        displayId = made.optInt("display")
        // Most apps have one window. If it is on the person's own screen, taking it would pull it out from under them.
        val mine = tasks().firstOrNull { it.optString("pkg") == ref.pkg && it.optInt("display") == 0 }
        if (mine != null) {
            if (mine.optBoolean("visible")) {
                val a = ctx.ask("APPROVAL|Move ${ref.label} to the background screen|${ref.label} is open on your screen right now. Your agent wants to use it in the background, so it will leave your screen until the agent is done, then come back.", listOf("Approve", "Decline"))
                if (a != "Approve") return if (a == null) "${ref.label} is open on the user's own screen and nobody answered whether it may be moved. Not opened." else "The user wants to keep ${ref.label} on their own screen. Not opened."
            }
            val moved = ask(op("move").put("task", mine.optInt("id")).put("to", "hidden"))
            if (!moved.optBoolean("ok")) return "${ref.label} is open on the user's screen and could not be moved to the background screen (${err(moved, "refused")}). Not opened."
            borrowed += ref.pkg
        }
        // A link opens by intent, held to the named app so no other app can take it. A fresh start clears where the app was left.
        val args = (if (link.isNotBlank()) listOf("-a", "android.intent.action.VIEW", "-d", link, "-p", ref.pkg) else listOf("-n", ref.component)) +
            if (fresh && ref.pkg !in borrowed) listOf("--activity-clear-task") else emptyList()
        val started = ask(op("start").put("args", JSONArray(args)), 30_000)
        if (!started.optBoolean("ok")) return "${ref.label} did not open on the background screen: ${err(started, "no reason given")}."
        if (run == null || run?.pkg != ref.pkg) { run?.finish(); secrets.clear(); run = runs?.begin(ref.pkg, ref.label, ctx.agentLabel) }
        _state.value = _state.value.copy(open = true, pkg = ref.pkg, app = ref.label, width = made.optInt("width"), height = made.optInt("height"))
        touch(ctx.agentLabel, "Opened ${ref.label}")
        active(true)
        settle()
        runCatching { readTree() }.onFailure { if (it is ScreenBlocked) throw it }
        log("open", "Opened ${ref.label}", by = by, extra = JSONObject().put("link", link))
        if (!made.optBoolean("trusted")) return "!Note: this phone only allows a basic background screen, so the keyboard or the focus may show on the user's screen while typing."
        return null
    }

    /** Press [target], or the point. Null when done; otherwise why not. [quick]: a replay, which does not wait for the screen to settle. */
    private suspend fun doPress(ctx: ToolContext, target: ScreenElement?, x: Int, y: Int, long: Boolean, by: String = "agent", quick: Boolean = false): String? {
        val s = _state.value
        val px = target?.centerX ?: x; val py = target?.centerY ?: y
        if (px !in 0 until s.width.coerceAtLeast(1) || py !in 0 until s.height.coerceAtLeast(1)) return "That point is off the screen (${s.width}x${s.height})."
        val before = elements
        val under = target ?: ScreenTree.at(before, px, py)
        val inside = under?.let { ScreenTree.wordsInside(before, it) }.orEmpty()
        val pkg = front()?.pkg ?: s.pkg
        money(pkg)?.let { return it }
        approve(ctx, ScreenRules.beforePress(apps.label(pkg), rule(pkg), under, inside, before.filter { it.editable && it.text.isNotBlank() && 'p' !in it.flags }.map { it.text }, config().extraWords))?.let { return it }
        // The person may have pressed Stop while the approval card was up.
        blocked(ctx, needOpen = true)?.let { return it }
        val t0 = now()
        var ok = ask(op("tap").put("x", px).put("y", py).put("ms", if (long) 700 else 60)).optBoolean("ok")
        if (!ok && target != null) ok = ask(op("act").put("node", target.id).put("action", if (long) "long" else "click")).optBoolean("ok")
        if (!ok) return "The press did not go through. Look again (screen_look)."
        val name = inside.ifBlank { under?.label.orEmpty() }.ifBlank { "$px,$py" }.take(60)
        val sel = selOf(under, before)
        touch(ctx.agentLabel, "Pressed $name")
        if (!quick) settle() else delay(120)
        runCatching { readTree() }.onFailure { if (it is ScreenBlocked) throw it }
        log(if (long) "long" else "tap", "Pressed \"$name\"", sel, by = by, took = now() - t0,
            extra = JSONObject().put("fx", px.toDouble() / s.width.coerceAtLeast(1)).put("fy", py.toDouble() / s.height.coerceAtLeast(1)))
        return null
    }

    private suspend fun doType(ctx: ToolContext, text: String, target: ScreenElement?, append: Boolean, submit: Boolean, secret: Boolean, by: String = "agent", quick: Boolean = false): Pair<String?, String> {
        val s = _state.value
        val pkg = front()?.pkg ?: s.pkg
        money(pkg)?.let { return it to "" }
        if (rule(pkg) == ScreenRules.Rule.NEVER) return ScreenRules.NEVER_REASON to ""
        val t0 = now()
        val before = elements
        var r = ask(op("text").put("node", target?.id ?: -1).put("text", text).put("append", append))
        if (!r.optBoolean("ok") && target != null && !target.editable) {
            // The words named a label or a search box's shell: press it, then type into whatever took the focus.
            ask(op("tap").put("x", target.centerX).put("y", target.centerY).put("ms", 60)); settle()
            r = ask(op("text").put("node", -1).put("text", text).put("append", append))
        }
        if (!r.optBoolean("ok")) {
            // Some apps draw their own text box. Type it key by key instead.
            val keys = ask(op("type_keys").put("text", text))
            if (!keys.optBoolean("ok")) return "The text did not go in: ${err(r, "the app refused it")}. Press the field first (screen_tap), then type." to ""
        }
        // The field as it was before the text went in: that is how it is found again (its hint, its id), not by what was typed.
        val was = target?.takeIf { it.editable } ?: before.firstOrNull { it.editable && it.focused }
        val sel = selOf(was, before)
        runCatching { readTree() }.onFailure { if (it is ScreenBlocked) throw it }
        val field = elements.firstOrNull { it.editable && it.focused } ?: was
        val hidden = secret || (field != null && 'p' in field.flags) || (was != null && 'p' in was.flags)
        if (hidden && text.isNotBlank()) secrets += text
        touch(ctx.agentLabel, "Typed in ${s.app}")
        val reads = if (hidden) "(hidden)" else r.optString("text").ifBlank { text }.take(300)
        var said = "Typed. The field now reads: \"$reads\"."
        var submitted = false
        if (submit) {
            val gate = approve(ctx, ScreenRules.beforePress(apps.label(pkg), rule(pkg), null, "", listOf(if (hidden) "(hidden)" else text), config().extraWords, submit = true))
            if (gate != null) {
                log("type", "Typed ${if (hidden) "(hidden)" else "\"${text.take(80)}\""}", sel, text, hidden, by, JSONObject().put("append", append).put("submit", false), now() - t0)
                return null to "$said\nNot submitted: $gate"
            }
            blocked(ctx, needOpen = true)?.let { return it to "" }
            submitted = ask(op("act").put("node", field?.takeIf { it.editable }?.id ?: -1).put("action", "enter")).optBoolean("ok") || ask(op("key").put("code", 66)).optBoolean("ok")
            said += if (submitted) " Submitted." else " The submit key did not go through: press the app's own send or search button."
            if (!quick) settle() else delay(150)
            runCatching { readTree() }.onFailure { if (it is ScreenBlocked) throw it }
        }
        log("type", "Typed ${if (hidden) "(hidden)" else "\"${text.take(80)}\""}${if (submitted) " and submitted" else ""}", sel, text, hidden, by,
            JSONObject().put("append", append).put("submit", submitted), now() - t0)
        return null to said
    }

    private val keys = mapOf("back" to 4, "enter" to 66, "tab" to 61, "delete" to 67, "escape" to 111, "search" to 84, "up" to 19, "down" to 20, "left" to 21, "right" to 22)

    private suspend fun doKey(ctx: ToolContext, name: String, by: String = "agent", quick: Boolean = false): String? {
        val code = keys[name.lowercase()] ?: return "Keys: ${keys.keys.joinToString(", ")}."
        val s = _state.value
        val pkg = front()?.pkg ?: s.pkg
        money(pkg)?.let { return it }
        if (rule(pkg) == ScreenRules.Rule.NEVER) return ScreenRules.NEVER_REASON
        val typed = elements.filter { it.editable && it.text.isNotBlank() && 'p' !in it.flags }.map { it.text }
        if (code == 66 && typed.isNotEmpty()) approve(ctx, ScreenRules.beforePress(apps.label(pkg), rule(pkg), null, "", typed, config().extraWords, submit = true))?.let { return it }
        blocked(ctx, needOpen = true)?.let { return it }
        if (!ask(op("key").put("code", code)).optBoolean("ok")) return "The key did not go through."
        touch(ctx.agentLabel, "Pressed ${name.lowercase()}")
        if (!quick) settle() else delay(120)
        runCatching { readTree() }.onFailure { if (it is ScreenBlocked) throw it }
        log("key", "Pressed the ${name.lowercase()} key", by = by, extra = JSONObject().put("key", name.lowercase()))
        return null
    }

    private suspend fun doSwipe(ctx: ToolContext, direction: String, box: ScreenElement?, x1: Int?, y1: Int?, x2: Int?, y2: Int?, by: String = "agent", quick: Boolean = false): String? {
        val s = _state.value
        val l = box?.left ?: 0; val t = box?.top ?: 0; val r = box?.right ?: s.width; val b = box?.bottom ?: s.height
        val cx = (l + r) / 2; val cy = (t + b) / 2; val dx = (r - l) * 3 / 10; val dy = (b - t) * 3 / 10
        // "up" moves the finger up, which shows what is further down the page.
        val p = when (direction.lowercase()) {
            "up" -> listOf(cx, cy + dy, cx, cy - dy)
            "down" -> listOf(cx, cy - dy, cx, cy + dy)
            "left" -> listOf(cx + dx, cy, cx - dx, cy)
            "right" -> listOf(cx - dx, cy, cx + dx, cy)
            else -> if (x1 != null && y1 != null && x2 != null && y2 != null) listOf(x1, y1, x2, y2) else return "Give a direction (up, down, left, right) or from and to points."
        }
        val pkg = front()?.pkg ?: s.pkg
        money(pkg)?.let { return it }
        if (rule(pkg) == ScreenRules.Rule.NEVER) return ScreenRules.NEVER_REASON
        val sel = selOf(box)
        if (!ask(op("swipe").put("x1", p[0]).put("y1", p[1]).put("x2", p[2]).put("y2", p[3]).put("ms", 320)).optBoolean("ok")) return "The swipe did not go through."
        touch(ctx.agentLabel, "Swiped ${direction.ifBlank { "across" }}")
        if (!quick) settle() else delay(250)
        runCatching { readTree() }.onFailure { if (it is ScreenBlocked) throw it }
        log("swipe", "Swiped ${direction.ifBlank { "across" }}", sel, by = by, extra = JSONObject().put("dir", direction.lowercase()).put("x1", p[0]).put("y1", p[1]).put("x2", p[2]).put("y2", p[3]))
        return null
    }

    private suspend fun doWait(text: String, gone: Boolean, seconds: Int): Pair<Boolean, String> {
        val secs = seconds.coerceIn(1, 60)
        val until = now() + secs * 1000L
        val t0 = now()
        while (true) {
            lastUsed = now()
            readTree()
            val there = text.isNotBlank() && elements.any { it.label.contains(text, true) }
            if (text.isBlank() || there != gone) {
                log("wait", if (text.isBlank()) "Waited for the screen to settle" else "Waited until \"$text\" ${if (gone) "was gone" else "showed"}", text = text,
                    extra = JSONObject().put("gone", gone).put("seconds", secs), took = now() - t0)
                return true to (if (text.isBlank()) "" else if (gone) "\"$text\" is gone." else "\"$text\" is on the screen.")
            }
            if (now() >= until) return false to "Waited $secs s: \"$text\" ${if (gone) "is still there" else "did not appear"}."
            delay(400)
            if (_state.value.stopped) return false to STOPPED
        }
    }

    // ---- the tools ----

    suspend fun status(): String = mutex.withLock { safely {
        val s = _state.value
        helper()?.let { return it }
        val p = ask(op("ping"))
        buildString {
            append("The background screen is ready (Android ${p.optInt("sdk")}).")
            if (p.optInt("display") == 0 || !s.open) append(" Nothing is open on it.")
            else append(" ${s.app.ifBlank { "An app" }} is open on it (${p.optInt("width")}x${p.optInt("height")})${if (s.owner.isNotBlank()) ", used by ${s.owner}" else ""}.")
            if (s.stopped) append(" The user has stopped it.")
            if (s.takenOver) append(" The user has taken it over.")
        }
    } }

    /** Open [app] on the hidden display: its front door, or [link] inside it. */
    suspend fun open(ctx: ToolContext, app: String, link: String = ""): String = mutex.withLock { safely {
        blocked(ctx, needOpen = false)?.let { return it }
        val ref = apps.find(app) ?: return "No app called \"$app\" is installed.${apps.near(app).takeIf { it.isNotEmpty() }?.let { " Close matches: ${it.joinToString(", ")}." } ?: " Use android_apps to see what is installed."}"
        val out = doOpen(ctx, ref, link, fresh = false)
        if (out != null && !out.startsWith("!")) return out
        runCatching { ctx.activity("Background screen: opened ${ref.label}", JSONObject().put("tool", "screen").put("app", ref.pkg)) }
        "${ref.label} is open on the background screen. The user's own screen was not touched.\n" + (out?.removePrefix("!")?.plus("\n") ?: "") + read()
    } }

    suspend fun look(ctx: ToolContext, mode: String, filter: String): String = mutex.withLock { safely {
        blocked(ctx, needOpen = true)?.let { return it }
        helper()?.let { return it }
        if (displayId == 0) return CLOSED
        touch(ctx.agentLabel, "Looking")
        read(mode.ifBlank { "tree" }, filter).also { log("look", "Looked at the screen") }
    } }

    suspend fun tap(ctx: ToolContext, element: Int?, words: String, x: Int?, y: Int?, long: Boolean): String = mutex.withLock { safely {
        blocked(ctx, needOpen = true)?.let { return it }
        helper()?.let { return it }
        if (displayId == 0) return CLOSED
        val target = when {
            element != null -> elements.firstOrNull { it.id == element } ?: return "There is no element [$element] on the screen as you last saw it. Look again (screen_look)."
            words.isNotBlank() -> ScreenTree.find(elements, words) ?: run { readTree(); ScreenTree.find(elements, words) } ?: return "Nothing on the screen says \"$words\". Look again (screen_look), or scroll."
            x != null && y != null -> null
            else -> return "Say what to press: element (its number), text (its words) or x and y."
        }
        doPress(ctx, target, x ?: 0, y ?: 0, long)?.let { return it }
        val last = runSteps.lastOrNull { it.kind == "tap" || it.kind == "long" }?.say ?: "Pressed"
        runCatching { ctx.activity("Background screen: ${last.replaceFirstChar { it.lowercase() }} in ${_state.value.app}", JSONObject().put("tool", "screen").put("app", _state.value.pkg)) }
        "$last.\n" + read()
    } }

    suspend fun type(ctx: ToolContext, text: String, element: Int?, words: String, append: Boolean, submit: Boolean, secret: Boolean = false): String = mutex.withLock { safely {
        blocked(ctx, needOpen = true)?.let { return it }
        helper()?.let { return it }
        if (displayId == 0) return CLOSED
        val target = when {
            element != null -> elements.firstOrNull { it.id == element } ?: return "There is no element [$element] on the screen as you last saw it. Look again (screen_look)."
            words.isNotBlank() -> ScreenTree.find(elements.filter { it.editable }, words) ?: ScreenTree.find(elements, words) ?: return "No field on the screen says \"$words\". Look again (screen_look)."
            else -> null
        }
        val (why, said) = doType(ctx, text, target, append, submit, secret)
        if (why != null) return why
        said + "\n" + read()
    } }

    suspend fun swipe(ctx: ToolContext, direction: String, element: Int?, x1: Int?, y1: Int?, x2: Int?, y2: Int?): String = mutex.withLock { safely {
        blocked(ctx, needOpen = true)?.let { return it }
        helper()?.let { return it }
        if (displayId == 0) return CLOSED
        val box = element?.let { id -> elements.firstOrNull { it.id == id } ?: return "There is no element [$id] on the screen as you last saw it. Look again (screen_look)." }
        doSwipe(ctx, direction, box, x1, y1, x2, y2)?.let { return it }
        "Swiped.\n" + read()
    } }

    suspend fun key(ctx: ToolContext, name: String): String = mutex.withLock { safely {
        blocked(ctx, needOpen = true)?.let { return it }
        helper()?.let { return it }
        if (displayId == 0) return CLOSED
        doKey(ctx, name)?.let { return it }
        "Pressed ${name.lowercase()}.\n" + read()
    } }

    /** Wait until [text] shows (or, with [gone], is no longer there), or until the screen stops changing. */
    suspend fun waitFor(ctx: ToolContext, text: String, gone: Boolean, seconds: Int): String {
        // While the person has the screen, waiting means waiting for them, without holding the lock they need.
        val until = now() + seconds.coerceIn(1, 60) * 1000L
        while (_state.value.takenOver && now() < until) delay(500)
        return mutex.withLock { safely {
            blocked(ctx, needOpen = true)?.let { return it }
            helper()?.let { return it }
            if (displayId == 0) return CLOSED
            if (text.isBlank()) settle()
            val (_, said) = doWait(text, gone, ((until - now()) / 1000).toInt().coerceAtLeast(1))
            (if (said.isBlank()) "" else said + "\n") + read()
        } }
    }

    /** Close the hidden display. Apps borrowed from the person's screen go back to it; with [keepApp] the app in front does too. */
    suspend fun close(keepApp: Boolean = false): String = mutex.withLock {
        if (displayId == 0 && !_state.value.open) return "The background screen was already closed."
        val back = LinkedHashSet(borrowed)
        if (keepApp) _state.value.pkg.takeIf { it.isNotBlank() }?.let { back += it }
        note("close", "Closed the background screen")
        val r = runCatching { backend.call(op("release").put("move_back", JSONArray(back.toList()))) }.getOrNull()
        val wasStopped = _state.value.stopped
        forget()
        _state.value = ScreenState(stopped = wasStopped)
        active(false)
        if (r == null) "The background screen is closed (the helper was already gone, so its apps closed with it)."
        else "The background screen is closed and nothing is left running on it." + if (back.isNotEmpty()) " ${back.joinToString(", ") { apps.label(it) }} went back to the user's own screen." else ""
    }

    // ---- doing learned steps again ----

    /**
     * Do [steps] again in [ref], with [args] for the parts that change. Each step finds its element on the screen as
     * it is now; a press that sends, pays or deletes still asks. When a step cannot be done the replay stops there,
     * with the app left open at that point so the agent can carry on by hand.
     */
    suspend fun replay(ctx: ToolContext, ref: AppRef, steps: JSONArray, args: JSONObject): Replay = mutex.withLock {
        val t0 = now()
        val stepMs = ArrayList<Long>(); val skipped = HashSet<Int>(); var usedPicture = false
        fun fail(i: Int, why: String) = Replay(false, why, i, now() - t0, stepMs, skipped, usedPicture)
        try {
            blocked(ctx, needOpen = false)?.let { return fail(-1, it) }
            // Start from the app's front door, so the steps begin where they were learned.
            val first = steps.optJSONObject(0)?.takeIf { it.optString("do") == "open" }
            val opened = doOpen(ctx, ref, ScreenSkills.fill(first?.optJSONObject("extra")?.optString("link").orEmpty(), args), fresh = true)
            if (opened != null && !opened.startsWith("!")) return fail(-1, opened)
            suspend fun await(sel: JSONObject, ms: Long): ScreenElement? {
                val until = now() + ms
                while (true) {
                    ScreenSkills.find(sel, elements, args)?.let { return it }
                    if (now() >= until) return null
                    delay(180); readTree()
                }
            }
            for (i in 0 until steps.length()) {
                val s = steps.getJSONObject(i); val kind = s.optString("do")
                if (kind == "open") { stepMs += 0L; continue }
                blocked(ctx, needOpen = true)?.let { return fail(i, it) }
                val st = now()
                val sel = s.optJSONObject("sel"); val extra = s.optJSONObject("extra") ?: JSONObject()
                val budget = s.optLong("ms", ScreenSkills.DEFAULT_WAIT_MS)
                var target: ScreenElement? = null
                var point: Pair<Int, Int>? = null
                val needsTarget = sel != null && (kind == "tap" || kind == "long" || (kind == "type" && sel.optString("kind") == "field") || (kind == "swipe" && sel.optString("res").isNotBlank()))
                if (needsTarget) {
                    target = await(sel!!, budget)
                    if (target == null) {
                        // Not there. If the step after it can already be done, this one was not needed (a pop-up that no longer comes).
                        val next = steps.optJSONObject(i + 1)?.optJSONObject("sel")
                        if (next != null && ScreenSkills.find(next, elements, args) != null) { skipped += i; stepMs += now() - st; continue }
                        // Last resort: look for its words in the picture itself.
                        val words = ScreenSkills.fill(sel.optString("text").ifBlank { sel.optString("desc") }, args)
                        if (words.isNotBlank() && kind != "swipe") {
                            val m = runCatching { backend.call(op("frame").put("max", config().width).put("q", 80), 8_000) }.getOrNull()
                            val bin = m?.bin
                            if (bin != null && !m!!.json.optBoolean("blank")) {
                                pictures.mkdirs()
                                val f = File(pictures, "replay.jpg").apply { writeBytes(bin) }
                                point = runCatching { findInPicture(f.absolutePath, words) }.getOrNull()
                                if (point != null) usedPicture = true
                            }
                        }
                        if (point == null) return fail(i, "Step ${i + 1} of ${steps.length()} (${ScreenSkills.say(s)}) could not be done: that is not on the screen.\n" + read())
                    }
                }
                val why: String? = when (kind) {
                    "tap", "long" -> doPress(ctx, target, point?.first ?: 0, point?.second ?: 0, kind == "long", quick = true)
                    "type" -> doType(ctx, ScreenSkills.fill(s.optString("text"), args), target, extra.optBoolean("append"), extra.optBoolean("submit"), secret = s.optString("text").startsWith("{secret"), quick = true).first
                    "swipe" -> doSwipe(ctx, extra.optString("dir"), target, extra.optInt("x1"), extra.optInt("y1"), extra.optInt("x2"), extra.optInt("y2"), quick = true)
                    "key" -> doKey(ctx, extra.optString("key"), quick = true)
                    "wait" -> doWait(ScreenSkills.fill(s.optString("text"), args), extra.optBoolean("gone"), extra.optInt("seconds", 20)).let { (ok, said) -> if (ok) null else said }
                    else -> null
                }
                if (why != null) return fail(i, "Step ${i + 1} of ${steps.length()} (${ScreenSkills.say(s)}) stopped: $why\n" + runCatching { read() }.getOrDefault(""))
                stepMs += now() - st
            }
            settle()
            Replay(true, read(), -1, now() - t0, stepMs, skipped, usedPicture)
        } catch (e: ScreenBlocked) {
            note("blocked", "Blocked: a banking or money app"); fail(-1, e.message ?: ScreenBlocklist.REASON)
        } catch (e: HelperAbsent) {
            forget(); fail(-1, "The background screen stopped: ${e.message}.")
        }
    }

    // ---- the person's own controls: these always win ----

    /** Stop: the agent may do nothing more on the background screen until the person allows it again. */
    fun userStop() { _state.value = _state.value.copy(stopped = true, takenOver = false, step = "Stopped by you"); note("stop", "You pressed Stop") }
    fun userAllow() { _state.value = _state.value.copy(stopped = false, step = "") }
    fun userTakeOver() { if (_state.value.open) { _state.value = _state.value.copy(takenOver = true, step = "You have the screen"); note("takeover", "You took over") } }
    fun userHandBack() { _state.value = _state.value.copy(takenOver = false, step = ""); note("handback", "You handed it back") }

    private var downAt = 0L; private var downX = 0f; private var downY = 0f
    private var downOn: ScreenElement? = null
    private var downAll: List<ScreenElement> = emptyList()
    private val you = object : ToolContext {
        override val agentLabel = "you"
        override suspend fun activity(text: String, meta: JSONObject): Long = 0
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? = "Approve"
        override suspend fun notify(title: String, body: String) {}
    }

    /**
     * The person's finger on the preview, passed to the hidden display. Only while they have taken over. What they do
     * is written down against the element they touched, so what they show can be learned and done again.
     * [action]: 0 down, 2 move, 1 up (as in MotionEvent).
     */
    suspend fun userTouch(link: ScreenBackend, action: Int, x: Float, y: Float) {
        if (!_state.value.takenOver || displayId == 0) return
        val r = runCatching { link.call(op("touch").put("a", action).put("x", x.toDouble()).put("y", y.toDouble()), 3_000).json }.getOrNull()
        if (r?.has("blocked") == true) { note("blocked", "Blocked: a banking or money app"); return }
        when (action) {
            0 -> { downAt = now(); downX = x; downY = y; downAll = elements; downOn = ScreenTree.at(downAll, x.toInt(), y.toInt()) }
            1 -> mutex.withLock {
                val s = _state.value; val moved = kotlin.math.hypot((x - downX).toDouble(), (y - downY).toDouble())
                val on = downOn; val sel = selOf(on, downAll); val held = now() - downAt
                val name = on?.let { ScreenTree.wordsInside(downAll, it) }.orEmpty().ifBlank { "${downX.toInt()},${downY.toInt()}" }.take(60)
                lastUsed = now()
                runCatching { backend.call(op("idle").put("quiet", 300).put("timeout", 1_500), 4_000); readTree() }
                if (moved > 24) {
                    val dx = x - downX; val dy = y - downY
                    val dir = if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) (if (dy < 0) "up" else "down") else (if (dx < 0) "left" else "right")
                    log("swipe", "You swiped $dir", sel.takeIf { on?.scrollable == true }, by = "you", extra = JSONObject().put("dir", dir).put("x1", downX.toInt()).put("y1", downY.toInt()).put("x2", x.toInt()).put("y2", y.toInt()))
                } else log(if (held > 550) "long" else "tap", "You pressed \"$name\"", sel, by = "you",
                    extra = JSONObject().put("fx", downX.toDouble() / s.width.coerceAtLeast(1)).put("fy", downY.toDouble() / s.height.coerceAtLeast(1)))
            }
        }
    }

    suspend fun userKey(name: String) {
        if (!_state.value.takenOver || displayId == 0) return
        mutex.withLock { runCatching { doKey(you, name, by = "you") } }
    }

    /** The person types into the field that has the focus on the hidden display. */
    suspend fun userType(text: String, secret: Boolean = false): String? {
        if (!_state.value.takenOver || displayId == 0) return "Take over first."
        return mutex.withLock { runCatching { doType(you, text, null, append = false, submit = false, secret = secret, by = "you").first }.getOrElse { it.message } }
    }

    /** The person shows a job themselves: open the app with the screen already in their hands. Null when it opened. */
    suspend fun userShow(app: String): String? = mutex.withLock {
        val ref = apps.find(app) ?: return "That app is not installed."
        if (_state.value.stopped) _state.value = _state.value.copy(stopped = false)
        val out = try { doOpen(you, ref, "", fresh = true, by = "you") } catch (e: ScreenBlocked) { e.message } catch (e: HelperAbsent) { "The background screen is not running." }
        if (out != null && !out.startsWith("!")) return out
        _state.value = _state.value.copy(takenOver = true, owner = "you", step = "Show how it is done")
        null
    }

    /** Bring the app in front to the person's own screen and close the hidden display. */
    suspend fun userBringToMyScreen(): Boolean = mutex.withLock {
        if (displayId == 0) return false
        val f = runCatching { front() }.getOrNull() ?: return false
        val ok = runCatching { ask(op("move").put("task", f.taskId).put("to", "main")).optBoolean("ok") }.getOrDefault(false)
        if (ok) {
            runCatching { backend.call(op("release").put("move_back", JSONArray(borrowed.toList()))) }
            val stopped = _state.value.stopped
            forget(); _state.value = ScreenState(stopped = stopped); active(false)
        }
        ok
    }

    /** Nobody has used it for a while and no agent is working: close it so nothing is left running. */
    suspend fun closeIfIdle(idleMs: Long, working: Boolean) {
        val s = _state.value
        if (s.open && !s.takenOver && !working && now() - lastUsed > idleMs) close()
    }
}
