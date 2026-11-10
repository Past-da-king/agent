package com.past9.phoneaos.screen.helper

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.past9.phoneaos.screen.ScreenBlocklist
import com.past9.phoneaos.screen.ScreenProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The screen helper: a small program from this app that runs as the phone's shell user and owns the hidden display.
 *
 * Started with (one line, by Shizuku or from a computer over adb):
 *   CLASSPATH=<this app's apk> app_process /system/bin com.past9.phoneaos.screen.helper.HelperMain <package> --port <n> --token <secret>
 *
 * It listens on 127.0.0.1 only and answers only a caller that proves it knows the secret the app made. It never touches
 * display 0, the person's own screen. With nobody asking for ten minutes it closes the hidden display by itself.
 *
 * Banking and money apps are refused HERE, below every tool: it will not start one or move one onto the hidden
 * display, it gives no picture, no element list and no touch while one is there, and it closes one the moment it
 * lands there some other way (a link, a button in another app). The app can add to the list; nothing can shorten it.
 *
 * Test from a computer:  ... HelperMain <package> --call '{"op":"ping"}'  [--save picture.jpg]
 */
object HelperMain {
    private const val IDLE_RELEASE_MS = 10 * 60_000L
    private val lock = Any()
    private var display: HiddenDisplay? = null
    private var keyboard = ""
    @Volatile private var lastAsked = SystemClock.uptimeMillis()
    private var token = ""
    private var builtIn = ""
    @Volatile private var blocklist = ScreenBlocklist("")
    private val labels = HashMap<String, String>()
    /** The last money app that was turned away or closed, and when: the app reads it to tell the person. */
    @Volatile private var lastBlocked = ""
    @Volatile private var lastBlockedAt = 0L

    @JvmStatic fun main(args: Array<String>) {
        fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val pkg = args.getOrNull(0) ?: run { System.err.println("usage: HelperMain <package> --port <n> --token <secret> [--call json [--save file]]"); return }
        val port = arg("--port")?.toIntOrNull() ?: run { System.err.println("screen helper: no --port"); return }
        token = arg("--token").orEmpty()
        if (token.length < 16) { System.err.println("screen helper: no --token"); return }
        arg("--call")?.let { client(port, it, arg("--save")); return }

        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val server = try { java.net.ServerSocket(port, 16, java.net.InetAddress.getByName("127.0.0.1")) } catch (e: java.io.IOException) {
            println("screen helper: already running for $pkg (or port $port is taken)"); return
        }
        // Made here, on the main thread with its looper, before any caller needs it.
        runCatching { Sys.systemContext }.onFailure { System.err.println("screen helper: no system context: $it") }
        builtIn = readBuiltIn()
        blocklist = ScreenBlocklist(builtIn)
        if (blocklist.rules < 50) { System.err.println("screen helper: the list of blocked money apps could not be read, so nothing will be opened"); }
        Sys.leaveAdbGroup()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> System.err.println("screen helper: ${t.name}: $e"); e.printStackTrace() }
        println("screen helper: started for $pkg (port $port, version ${ScreenProtocol.VERSION}, pid ${android.os.Process.myPid()}, ${blocklist.rules} blocked-app rules)")
        Thread({ while (true) { val s = try { server.accept() } catch (e: Throwable) { break }; Thread({ serve(s) }, "screen-client").apply { isDaemon = true; start() } } }, "screen-accept").start()
        Thread({
            var n = 0
            while (true) {
                Thread.sleep(400)
                // A money app that lands on the hidden display is closed at once, whoever put it there.
                if (display != null) runCatching { sweep() }
                if (++n % 75 == 0) synchronized(lock) { if (display != null && SystemClock.uptimeMillis() - lastAsked > IDLE_RELEASE_MS) { println("screen helper: idle, closing the display"); release(emptySet()) } }
            }
        }, "screen-watch").apply { isDaemon = true; start() }
        Looper.loop()
    }

    /** The built-in list travels inside the app's own file, which is what this program was started from. */
    private fun readBuiltIn(): String = runCatching {
        val apk = System.getenv("CLASSPATH").orEmpty().split(':').first()
        java.util.zip.ZipFile(apk).use { z -> z.getInputStream(z.getEntry("assets/" + ScreenBlocklist.ASSET)).readBytes().toString(Charsets.UTF_8) }
    }.getOrDefault("")

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = java.util.concurrent.CountDownLatch(1)
        var out: Result<T>? = null
        Handler(Looper.getMainLooper()).post { out = runCatching(block); done.countDown() }
        if (!done.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw IllegalStateException("the helper is busy")
        return out!!.getOrThrow()
    }

    private fun label(pkg: String): String = synchronized(labels) {
        labels.getOrPut(pkg) {
            runCatching { val pm = Sys.systemContext.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("")
        }
    }

    /** Why this app is never used here, or null. With no list at all (it could not be read), everything is refused. */
    private fun whyBlocked(pkg: String): String? = if (blocklist.rules < 50) "the list of blocked apps is missing" else blocklist.blocked(pkg, label(pkg))

    private fun blockedReply(pkg: String): JSONObject {
        lastBlocked = pkg; lastBlockedAt = System.currentTimeMillis()
        return ScreenProtocol.fail(ScreenBlocklist.reason(label(pkg).ifBlank { pkg })).put("blocked", pkg)
    }

    /** Close every money app on the hidden display. The package that was closed, or null when there was none. */
    private fun sweep(): String? {
        val d = display ?: return null
        var hit: String? = null
        for (t in Tasks.all()) {
            if (t.displayId != d.id) continue
            val p = t.top?.packageName ?: continue
            if (whyBlocked(p) != null) { Tasks.remove(t.taskId); hit = p; println("screen helper: closed blocked app $p") }
        }
        if (hit != null) { lastBlocked = hit; lastBlockedAt = System.currentTimeMillis() }
        return hit
    }

    private fun serve(s: java.net.Socket) {
        try {
            s.tcpNoDelay = true
            val inp = DataInputStream(s.getInputStream()); val out = DataOutputStream(s.getOutputStream())
            // Who is calling: it must prove it knows the secret, and we prove the same to it.
            s.soTimeout = 5_000
            val hello = ScreenProtocol.read(inp).json
            val mine = ScreenProtocol.nonce()
            ScreenProtocol.write(out, ScreenProtocol.ok().put("proof", ScreenProtocol.proof(token, hello.optString("nonce"))).put("nonce", mine).put("version", ScreenProtocol.VERSION))
            val auth = ScreenProtocol.read(inp).json
            if (hello.optString("nonce").length < 8 || auth.optString("proof") != ScreenProtocol.proof(token, mine)) { s.close(); return }
            ScreenProtocol.write(out, ScreenProtocol.ok())
            s.soTimeout = 0
            while (true) {
                val m = try { ScreenProtocol.read(inp) } catch (e: java.io.IOException) { break }
                lastAsked = SystemClock.uptimeMillis()
                var bin: ByteArray? = null
                val reply = try { handle(m.json) { bin = it } } catch (t: Throwable) {
                    val c = Sys.cause(t); ScreenProtocol.fail(c.message ?: c.toString())
                }
                ScreenProtocol.write(out, reply, bin)
                if (m.json.optString("op") == "exit") { Thread.sleep(100); System.exit(0) }
            }
        } catch (_: Throwable) {
        } finally { runCatching { s.close() } }
    }

    private fun need(): HiddenDisplay = display ?: throw IllegalStateException("No hidden display is open.")

    private fun describe(o: JSONObject): JSONObject {
        val d = display
        o.put("version", ScreenProtocol.VERSION).put("uid", android.os.Process.myUid()).put("sdk", Sys.sdk).put("pid", android.os.Process.myPid()).put("rules", blocklist.rules)
        if (lastBlocked.isNotEmpty()) o.put("last_blocked", lastBlocked).put("last_blocked_at", lastBlockedAt)
        if (d != null) o.put("display", d.id).put("width", d.width).put("height", d.height).put("dpi", d.dpi).put("flags", JSONArray(Flags.names(d.flags)))
            .put("trusted", d.flags and Flags.TRUSTED != 0).put("own_focus", d.flags and Flags.OWN_FOCUS != 0)
            .put("keeps_focus_off", d.flags and Flags.STEAL_TOP_FOCUS_DISABLED != 0).put("keyboard", keyboard).put("frames", d.seq)
        else o.put("display", 0)
        return o
    }

    private fun tasksJson(): JSONArray = JSONArray(Tasks.all().map { t ->
        JSONObject().put("id", t.taskId).put("display", t.displayId).put("pkg", t.top?.packageName.orEmpty()).put("cls", t.top?.className.orEmpty()).put("visible", t.visible)
    })

    /** Close the hidden display. Apps named in [moveBack] go to the person's screen; the rest are closed. */
    private fun release(moveBack: Set<String>) {
        val d = display ?: return
        runCatching {
            for (t in Tasks.all().filter { it.displayId == d.id }) {
                if (t.top?.packageName in moveBack) runCatching { Tasks.move(t.taskId, 0) } else Tasks.remove(t.taskId)
            }
        }
        Tree.unlink()
        d.release(); display = null; keyboard = ""
    }

    private val guarded = setOf("frame", "touch", "idle", "tree", "text", "act", "type_keys", "tap", "swipe", "key")

    private fun handle(q: JSONObject, picture: (ByteArray) -> Unit): JSONObject {
        val op = q.optString("op")
        // Nothing is shown and nothing is touched while a money app is on the hidden display: it is closed first.
        if (op in guarded) sweep()?.let { return blockedReply(it) }
        when (op) {
            "ping" -> return describe(ScreenProtocol.ok())
            "frame" -> {
                val d = need()
                if (q.has("after") && q.optLong("after") == d.seq) return ScreenProtocol.ok().put("same", true).put("seq", d.seq)
                val f = d.frame(q.optInt("max", 720), q.optInt("q", 70)) ?: return ScreenProtocol.fail("Nothing has been drawn on the hidden display yet.")
                picture(f.jpeg)
                return ScreenProtocol.ok().put("width", f.width).put("height", f.height).put("seq", f.seq).put("blank", f.blank)
            }
            "touch" -> { val d = need(); return JSONObject().put("ok", Input.touch(d.id, q.optInt("a", MotionEvent.ACTION_MOVE), q.optDouble("x").toFloat(), q.optDouble("y").toFloat())) }
            "idle" -> return JSONObject().put("ok", true).put("settled", Tree.waitIdle(q.optLong("quiet", 300), q.optLong("timeout", 2_500)))
            else -> synchronized(lock) { return locked(op, q) }
        }
    }

    /** The package an `am start` would open, from its arguments: -n package/class, or -p package. Null when it names none. */
    private fun targetOf(args: List<String>): String? {
        val n = args.indexOf("-n").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.substringBefore('/')
        val p = args.indexOf("-p").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        return (n ?: p)?.takeIf { it.isNotBlank() }
    }

    private fun locked(op: String, q: JSONObject): JSONObject {
        when (op) {
            "create" -> {
                val w = q.optInt("w", 720); val h = q.optInt("h", 1600); val dpi = q.optInt("dpi", 320)
                q.optJSONArray("block")?.let { a -> blocklist = ScreenBlocklist(builtIn, (0 until a.length()).map { a.getString(it) }) }
                display?.let { if (it.width == w && it.height == h) return describe(ScreenProtocol.ok()) else release(emptySet()) }
                val d = onMain { HiddenDisplay.create("Agent background screen", w, h, dpi, q.optBoolean("locked", false)) }
                display = d
                keyboard = Tasks.hideKeyboardOn(d.id)
                return describe(ScreenProtocol.ok())
            }
            "block" -> {
                // The app's own additions. They can only add: the built-in list is read from the app's file, never from here.
                val a = q.optJSONArray("add") ?: JSONArray()
                blocklist = ScreenBlocklist(builtIn, (0 until a.length()).map { a.getString(it) })
                sweep()
                return ScreenProtocol.ok().put("rules", blocklist.rules)
            }
            "is_blocked" -> { val p = q.optString("pkg"); return ScreenProtocol.ok().put("why", whyBlocked(p) ?: "") }
            "release" -> {
                val back = q.optJSONArray("move_back"); release((0 until (back?.length() ?: 0)).map { back!!.getString(it) }.toSet())
                return ScreenProtocol.ok()
            }
            "exit" -> { release(emptySet()); return ScreenProtocol.ok() }
            "tasks" -> return ScreenProtocol.ok().put("tasks", tasksJson())
            "start" -> {
                val d = need(); val a = q.optJSONArray("args") ?: return ScreenProtocol.fail("start needs args")
                val args = (0 until a.length()).map { a.getString(it) }
                // An app must be named, so it can be checked. A bare link that any app might take is refused.
                val target = targetOf(args) ?: return ScreenProtocol.fail("Name the app to open.")
                if (whyBlocked(target) != null) return blockedReply(target)
                val r = Sys.exec("am", "start", "-W", "--display", d.id.toString(), *args.toTypedArray(), timeoutMs = 20_000)
                val bad = r.code != 0 || r.out.contains("Error:") || r.out.contains("Exception")
                // What opened may not be what was named (an app that hands straight over to another).
                sweep()?.let { return blockedReply(it) }
                return JSONObject().put("ok", !bad).put("out", r.out.trim().takeLast(600)).apply { if (bad) put("error", r.out.trim().lines().lastOrNull { it.isNotBlank() } ?: "The app did not start.") }
            }
            "move" -> {
                val d = need(); val to = if (q.optString("to") == "main") 0 else d.id
                val task = q.optInt("task", -1)
                val row = Tasks.all().firstOrNull { it.taskId == task } ?: return ScreenProtocol.fail("No such task.")
                // Only between the person's screen and the hidden one, never anywhere else.
                if (row.displayId != 0 && row.displayId != d.id) return ScreenProtocol.fail("That app is on another display.")
                val p = row.top?.packageName.orEmpty()
                if (to != 0 && whyBlocked(p) != null) return blockedReply(p)
                Tasks.move(task, to)
                return ScreenProtocol.ok()
            }
            "remove" -> {
                val d = need(); val task = q.optInt("task", -1)
                if (Tasks.all().none { it.taskId == task && it.displayId == d.id }) return ScreenProtocol.fail("That app is not on the hidden display.")
                return JSONObject().put("ok", Tasks.remove(task))
            }
            "tree" -> {
                val d = need()
                val t = Tree.dump(d.id, q.optInt("max", 300))
                // An app that hides its screen and talks about balances, cards or PINs is treated as a money app.
                val blank = runCatching { d.frame(120, 30)?.blank }.getOrNull() ?: false
                t.put("protected", blank)
                if (blank) {
                    val nodes = t.optJSONArray("nodes") ?: JSONArray()
                    val words = (0 until nodes.length()).map { nodes.getJSONObject(it).let { n -> n.optString("t") + " " + n.optString("d") } }
                    if (blocklist.moneyScreen(words) != null) {
                        val front = Tasks.all().firstOrNull { it.displayId == d.id && it.visible }
                        front?.let { Tasks.remove(it.taskId) }
                        return blockedReply(front?.top?.packageName ?: "a protected money screen")
                    }
                }
                return t
            }
            "text" -> return Tree.setText(need().id, q.optInt("node", -1), q.optString("text"), q.optBoolean("append", false))
            "act" -> return Tree.act(need().id, q.optInt("node", -1), q.optString("action"))
            "type_keys" -> {
                // The fallback when the app's field does not take text directly: the shell's own typing.
                val r = Sys.exec("input", "-d", need().id.toString(), "text", q.optString("text").replace(" ", "%s"))
                return JSONObject().put("ok", r.code == 0).put("out", r.out.trim().take(200))
            }
            "tap" -> return JSONObject().put("ok", Input.tap(need().id, q.optDouble("x").toFloat(), q.optDouble("y").toFloat(), q.optLong("ms", 60)))
            "swipe" -> return JSONObject().put("ok", Input.swipe(need().id, q.optDouble("x1").toFloat(), q.optDouble("y1").toFloat(), q.optDouble("x2").toFloat(), q.optDouble("y2").toFloat(), q.optLong("ms", 300)))
            "key" -> return JSONObject().put("ok", Input.key(need().id, q.optInt("code"), q.optBoolean("long", false), q.optInt("meta", 0)))
            else -> return ScreenProtocol.fail("Unknown request: $op")
        }
    }

    /** `--call`: send one request to the running helper and print the answer. For testing from a computer. */
    private fun client(port: Int, json: String, save: String?) {
        val s = java.net.Socket()
        try {
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 2_000)
            val inp = DataInputStream(s.getInputStream()); val out = DataOutputStream(s.getOutputStream())
            val mine = ScreenProtocol.nonce()
            ScreenProtocol.write(out, JSONObject().put("op", "hello").put("nonce", mine))
            val h = ScreenProtocol.read(inp).json
            if (h.optString("proof") != ScreenProtocol.proof(token, mine)) throw IllegalStateException("what answered is not the helper")
            ScreenProtocol.write(out, JSONObject().put("op", "auth").put("proof", ScreenProtocol.proof(token, h.optString("nonce"))))
            ScreenProtocol.read(inp)
            ScreenProtocol.write(out, JSONObject(json))
            val m = ScreenProtocol.read(inp)
            if (m.bin != null && save != null) java.io.File(save).writeBytes(m.bin)
            println(m.json.toString())
        } catch (t: Throwable) {
            println(JSONObject().put("ok", false).put("error", "The screen helper is not running: ${t.message}").toString())
        } finally { runCatching { s.close() } }
    }
}
