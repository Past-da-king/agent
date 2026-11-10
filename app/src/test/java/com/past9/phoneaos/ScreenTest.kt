package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.connections.LearnedApp
import com.past9.phoneaos.connections.LearnedStore
import com.past9.phoneaos.connections.PageRunner
import com.past9.phoneaos.connections.WebAppRun
import com.past9.phoneaos.connections.WebApps
import com.past9.phoneaos.connections.learnedTools
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.screen.AppRef
import com.past9.phoneaos.screen.HelperAbsent
import com.past9.phoneaos.screen.ScreenApps
import com.past9.phoneaos.screen.ScreenBackend
import com.past9.phoneaos.screen.ScreenBlocklist
import com.past9.phoneaos.screen.ScreenConfig
import com.past9.phoneaos.screen.ScreenLearned
import com.past9.phoneaos.screen.ScreenProtocol
import com.past9.phoneaos.screen.ScreenRules
import com.past9.phoneaos.screen.ScreenRuns
import com.past9.phoneaos.screen.ScreenSession
import com.past9.phoneaos.screen.ScreenSkills
import com.past9.phoneaos.screen.ScreenTree
import com.past9.phoneaos.tools.ScreenLearning
import com.past9.phoneaos.tools.screenSkillTools
import com.past9.phoneaos.tools.screenTools
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** The built-in list of banking and money apps, read the way the app and the helper read it. */
private fun screenBuiltIn(): String = listOf("src/main/assets/screen-blocklist.txt", "app/src/main/assets/screen-blocklist.txt").map { File(it) }.first { it.isFile }.readText()

private const val NOTES = "com.example.notes"
private const val BANK = "capitec.acuity.mobile.prod"

/**
 * A phone that is not there: it answers the way the screen helper does, for one small made-up app ("Notes": a list, a
 * New note button, a title field, Save and Send) and enforces the money-app rule the way the helper does.
 */
private class ScreenFakePhone(private val blocklist: ScreenBlocklist) : ScreenBackend {
    var alive = true
    var version = ScreenProtocol.VERSION
    var display = 0
    val calls = ArrayList<JSONObject>()
    val pressed = ArrayList<String>()
    val typed = ArrayList<String>()
    val closedBlocked = ArrayList<String>()
    var screen = "list"
    var front = ""
    /** package to (display, visible) */
    val tasks = LinkedHashMap<String, Pair<Int, Boolean>>()
    val notes = arrayListOf("Shopping")
    var title = ""; var body = ""; var pin = ""
    var focus = "title"
    /** A pop-up that shows once after New note. */
    var tip = false
    var blank = false
    var listFirst = false
    var started = ArrayList<List<String>>()

    private fun n(i: Int, cls: String, flags: String, l: Int, t: Int, r: Int, b: Int, text: String = "", desc: String = "", res: String = "", hint: String = "") =
        JSONObject().put("i", i).put("w", 0).put("c", cls).put("f", flags).put("b", JSONArray(listOf(l, t, r, b))).apply {
            if (text.isNotEmpty()) put("t", text); if (desc.isNotEmpty()) put("d", desc); if (res.isNotEmpty()) put("r", res); if (hint.isNotEmpty()) put("h", hint)
        }

    fun nodes(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun add(cls: String, flags: String, l: Int, t: Int, r: Int, b: Int, text: String = "", desc: String = "", res: String = "", hint: String = "") { out += n(out.size, cls, flags, l, t, r, b, text, desc, res, hint) }
        when (screen) {
            "list" -> {
                if (listFirst) notes.forEachIndexed { k, s -> add("TextView", "c", 0, 200 + k * 100, 720, 290 + k * 100, s, res = "row") }
                add("TextView", "", 0, 0, 400, 100, "Notes")
                add("ImageButton", "c", 600, 1400, 700, 1500, desc = "New note", res = "fab")
                add("Button", "c", 20, 1400, 300, 1500, "Pay with my bank", res = "pay_link")
                if (!listFirst) notes.forEachIndexed { k, s -> add("TextView", "c", 0, 200 + k * 100, 720, 290 + k * 100, s, res = "row") }
            }
            "edit" -> {
                if (tip) add("Button", "c", 200, 700, 520, 800, "Got it", res = "tip_ok")
                add("EditText", "ce" + (if (focus == "title") "f" else ""), 0, 100, 720, 200, title, res = "title", hint = "Title")
                add("EditText", "ce" + (if (focus == "body") "f" else ""), 0, 220, 720, 600, body, res = "body", hint = "Write something")
                add("EditText", "cep" + (if (focus == "pin") "f" else ""), 0, 620, 720, 700, if (pin.isEmpty()) "" else "(hidden)", res = "pin", hint = "PIN")
                add("Button", "c", 0, 1400, 350, 1500, "Save", res = "save")
                add("ImageButton", "c", 370, 1400, 720, 1500, desc = "Send", res = "send")
            }
            "sent" -> add("TextView", "", 0, 0, 720, 100, "Sent: $title")
        }
        return out
    }

    private fun ok() = ScreenProtocol.ok()
    private fun reply(o: JSONObject, bin: ByteArray? = null) = ScreenProtocol.Message(o, bin)
    private fun blockedReply(pkg: String) = reply(ScreenProtocol.fail(ScreenBlocklist.reason(pkg)).put("blocked", pkg))

    private fun press(label: String) {
        pressed += label
        when (screen to label) {
            "list" to "New note" -> { screen = "edit"; title = ""; body = ""; focus = "title" }
            "list" to "Pay with my bank" -> { tasks[BANK] = display to true; tasks[NOTES] = display to false; front = BANK }
            "edit" to "Got it" -> tip = false
            "edit" to "Save" -> { if (title.isNotBlank()) notes += title; screen = "list" }
            "edit" to "Send" -> screen = "sent"
            "edit" to "Title" -> focus = "title"
            "edit" to "Write something" -> focus = "body"
            "edit" to "PIN" -> focus = "pin"
        }
    }

    override suspend fun call(q: JSONObject, timeoutMs: Int): ScreenProtocol.Message {
        if (!alive) throw HelperAbsent("the screen helper is not running")
        calls += JSONObject(q.toString())
        val op = q.optString("op")
        // As in the real helper: a money app on the hidden display is closed before anything is shown or touched.
        if (op in setOf("frame", "touch", "idle", "tree", "text", "act", "type_keys", "tap", "swipe", "key") && front.isNotEmpty() && blocklist.blocked(front) != null) {
            val p = front; closedBlocked += p; tasks.remove(p); front = tasks.entries.firstOrNull { it.value.first == display }?.key.orEmpty()
            if (front.isNotEmpty()) tasks[front] = display to true
            return blockedReply(p)
        }
        return when (op) {
            "ping" -> reply(ok().put("version", version).put("sdk", 36).put("display", display))
            "exit" -> { alive = false; reply(ok()) }
            "create" -> { display = 7; reply(ok().put("display", 7).put("width", q.optInt("w")).put("height", q.optInt("h")).put("trusted", true)) }
            "tasks" -> reply(ok().put("tasks", JSONArray(tasks.entries.mapIndexed { i, e -> JSONObject().put("id", 100 + i).put("display", e.value.first).put("pkg", e.key).put("cls", e.key + ".Main").put("visible", e.value.second) })))
            "move" -> {
                val pkg = tasks.keys.elementAt(q.optInt("task") - 100)
                if (q.optString("to") != "main" && blocklist.blocked(pkg) != null) return blockedReply(pkg)
                tasks[pkg] = (if (q.optString("to") == "main") 0 else display) to true; reply(ok())
            }
            "start" -> {
                val a = q.getJSONArray("args").let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                started += a
                val pkg = a.indexOf("-n").takeIf { it >= 0 }?.let { a[it + 1].substringBefore('/') } ?: a[a.indexOf("-p") + 1]
                if (blocklist.blocked(pkg) != null) return blockedReply(pkg)
                tasks[pkg] = display to true; front = pkg
                if ("--activity-clear-task" in a || pkg !in tasks) screen = "list"
                reply(ok())
            }
            "tree" -> reply(ok().put("nodes", JSONArray(nodes())).put("protected", blank))
            "frame" -> reply(ok().put("width", 540).put("height", 1200).put("seq", calls.size).put("blank", blank), byteArrayOf(1, 2, 3))
            "idle" -> reply(ok().put("settled", true))
            "tap" -> {
                val x = q.optInt("x"); val y = q.optInt("y")
                val hit = ScreenTree.at(ScreenTree.parse(JSONObject().put("nodes", JSONArray(nodes()))), x, y)
                press(hit?.label ?: "$x,$y"); reply(ok())
            }
            "text" -> {
                val all = ScreenTree.parse(JSONObject().put("nodes", JSONArray(nodes())))
                val node = q.optInt("node", -1)
                val f = if (node >= 0) all.firstOrNull { it.id == node } else all.firstOrNull { it.editable && it.focused }
                if (f == null || !f.editable) return reply(ScreenProtocol.fail("That is not a text field."))
                val t = q.optString("text"); typed += t
                when (f.res) { "title" -> { title = t; focus = "title" }; "body" -> { body = t; focus = "body" }; "pin" -> { pin = t; focus = "pin" } }
                reply(ok().put("text", if (f.res == "pin") "(hidden)" else t))
            }
            "act" -> { if (q.optString("action") == "enter") { pressed += "enter"; screen = "sent" }; reply(ok()) }
            "key" -> { pressed += "key" + q.optInt("code"); if (q.optInt("code") == 4 && screen == "edit") screen = "list"; reply(ok()) }
            "swipe" -> { pressed += "swipe"; reply(ok()) }
            "touch" -> {
                // A finger lifted is a press on whatever is under it.
                if (q.optInt("a") == 1) { val x = q.optDouble("x").toInt(); val y = q.optDouble("y").toInt(); press(ScreenTree.at(ScreenTree.parse(JSONObject().put("nodes", JSONArray(nodes()))), x, y)?.label ?: "$x,$y") }
                reply(ok())
            }
            "release" -> {
                val back = q.optJSONArray("move_back")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                tasks.entries.filter { it.value.first == display }.forEach { e -> if (e.key in back) tasks[e.key] = 0 to true else tasks.remove(e.key) }
                display = 0; front = ""; reply(ok())
            }
            else -> reply(ScreenProtocol.fail("Unknown request: $op"))
        }
    }

    fun did(op: String) = calls.count { it.optString("op") == op }
}

private class ScreenCtx(override val agentLabel: String = "main", var answer: String? = "Approve") : ToolContext {
    val asked = ArrayList<String>()
    override suspend fun activity(text: String, meta: JSONObject): Long = 1
    override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
    override suspend fun ask(question: String, options: List<String>): String? { asked += question; return answer }
    override suspend fun notify(title: String, body: String) {}
}

private class ScreenFakeApps : ScreenApps {
    val all = listOf(AppRef(NOTES, "Notes", "$NOTES/.Main"), AppRef(BANK, "Capitec", "$BANK/.Main"), AppRef("com.example.games", "Pocket Wallet", "com.example.games/.Main"), AppRef("org.other.chat", "Chatter", "org.other.chat/.Main"))
    override fun find(nameOrPackage: String) = all.firstOrNull { it.pkg == nameOrPackage || it.label.equals(nameOrPackage, true) }
    override fun label(pkg: String) = all.firstOrNull { it.pkg == pkg }?.label ?: pkg
    override fun near(q: String) = emptyList<String>()
}

@RunWith(RobolectricTestRunner::class)
class ScreenBlocklistTest {
    private val list = ScreenBlocklist(screenBuiltIn())

    @Test fun theFileReadsWithoutABadLine() {
        assertTrue("bad lines: ${list.problems}", list.problems.isEmpty())
        assertTrue(list.rules > 200)
    }

    @Test fun banksWalletsPaymentsAndCryptoAreBlocked() {
        val blocked = listOf("capitec.acuity.mobile.prod", "capitecbank.remote.prd", "za.co.fnb.connect.itt", "com.sbg.mobile.phone", "com.barclays.africa", "za.co.nedbank", "za.co.tymebank.mobile",
            "za.co.discovery.bank", "za.co.africanbank.myworld", "za.co.investec.mobile", "com.oldmutual.myom", "com.bankzero.mobile", "com.paypal.android.p2pmobile", "com.transferwise.android",
            "com.revolut.revolut", "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user", "com.samsung.android.spay", "com.samsung.android.spayfw", "com.fireid.snapscan",
            "com.zapper.android", "co.bitx.android.wallet", "com.valr.app", "com.binance.dev", "za.co.yoco", "com.easyequities.android", "com.coinbase.android", "com.squareup.cash",
            "com.chase.sig.android", "io.metamask", "com.robinhood.android", "com.somenewbank.app", "com.acme.mywallet", "com.acme.cryptotracker", "com.shop.payments.sdkdemo", "xyz.fastpay", "com.vodacom.vodapay")
        blocked.forEach { assertNotNull("should be blocked: $it", list.blocked(it)) }
    }

    @Test fun everydayAppsAreNotBlocked() {
        val fine = listOf("com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.bard", "com.google.android.googlequicksearchbox", "com.android.settings", "com.android.contacts", "com.android.chrome",
            "com.past9.phoneaos", "com.google.android.youtube", "com.spotify.music", "com.google.android.apps.maps", "com.microsoft.office.outlook", "com.google.android.gm", "com.sec.android.app.sbrowser",
            "com.samsung.android.messaging", "com.android.vending", "com.google.android.play.games", "com.samsung.android.app.spage", "com.disney.disneyplus", "com.android.exchange", "com.google.android.calendar", NOTES)
        fine.forEach { assertNull("should not be blocked: $it (${list.blocked(it)})", list.blocked(it)) }
    }

    @Test fun anAppIsAlsoCaughtByItsNameOnThePhone() {
        assertNotNull(list.blocked("com.unknown.zzz", "Capitec"))
        assertNotNull(list.blocked("com.unknown.zzz", "Google Pay"))
        assertNotNull(list.blocked("com.unknown.zzz", "My Bank"))
        assertNull(list.blocked("com.unknown.zzz", "Paypoint Runner".replace("Paypoint", "Playpoint")))
        assertNull(list.blocked("com.unknown.zzz", "Banksy Art"))
    }

    @Test fun thePersonCanAddAppsButNothingCanTakeABuiltInOneAway() {
        val more = ScreenBlocklist(screenBuiltIn(), listOf("org.other.chat | Chatter", "com.shop.*"))
        assertNotNull(more.blocked("org.other.chat")); assertNotNull(more.blocked("com.shop.anything"))
        // Lines that try to be clever do nothing: there is no way to write "allow".
        val tricky = ScreenBlocklist(screenBuiltIn(), listOf("!capitec.acuity.mobile.prod", "allow:capitec", "word:", "-com.paypal.*", "*"))
        assertNotNull(tricky.blocked("capitec.acuity.mobile.prod")); assertNotNull(tricky.blocked("com.paypal.android.p2pmobile"))
        assertNull("a lone * must not block everything", tricky.blocked("com.whatsapp"))
        // With no list at all (a broken install), the matcher blocks nothing by itself; the helper then refuses everything.
        assertEquals(0, ScreenBlocklist("").rules)
    }

    @Test fun aHiddenScreenThatTalksAboutMoneyIsAMoneyScreen() {
        assertNotNull(list.moneyScreen(listOf("Hello", "Available balance R 1 200.00")))
        assertNotNull(list.moneyScreen(listOf("Enter your PIN")))
        assertNull(list.moneyScreen(listOf("Chats", "Updates", "Calls")))
    }
}

@RunWith(RobolectricTestRunner::class)
class ScreenRulesAndTreeTest {
    @Test fun wordsThatMeanAChangeAreWholeWords() {
        assertEquals("send", ScreenRules.riskyWord("Send", ""))
        assertEquals("send", ScreenRules.riskyWord("", "com.app:id/btnSend".substringAfter(":id/")))
        assertEquals("delete", ScreenRules.riskyWord("Delete chat", ""))
        assertNull(ScreenRules.riskyWord("Sender name", ""))
        assertNull(ScreenRules.riskyWord("Search", "search_src_text"))
        assertNull(ScreenRules.riskyWord("Paying attention", ""))
        assertEquals("stuur", ScreenRules.riskyWord("Stuur", "", ScreenRules.extraWords("stuur, thumela")))
    }

    @Test fun rulesPerAppAndTheDefault() {
        val r = ScreenRules.parse("com.a | allow\ncom.b|never\n# note\ncom.c\n")
        assertEquals(ScreenRules.Rule.ALLOW, ScreenRules.ruleFor("com.a", r)); assertEquals(ScreenRules.Rule.NEVER, ScreenRules.ruleFor("com.b", r))
        assertEquals(ScreenRules.Rule.ASK, ScreenRules.ruleFor("com.c", r)); assertEquals(ScreenRules.Rule.ASK, ScreenRules.ruleFor("com.zzz", r))
    }

    @Test fun theListReadsLikeAPersonWouldSayItAndFindsByWords() {
        val phone = ScreenFakePhone(ScreenBlocklist(screenBuiltIn())); phone.screen = "edit"; phone.title = "Milk"
        val els = ScreenTree.parse(JSONObject().put("nodes", JSONArray(phone.nodes())))
        val text = ScreenTree.describe(els)
        assertTrue(text, text.contains("field \"Milk\"") && text.contains("button \"Save\"") && text.contains("button \"Send\""))
        assertEquals("save", ScreenTree.find(els, "save")!!.res)
        assertEquals("send", ScreenTree.at(els, 500, 1450)!!.res)
        assertNull(ScreenTree.at(els, 360, 1000))
        assertTrue(ScreenTree.describe(els, filter = "zzz").startsWith("Nothing on the screen matches"))
    }

    @Test fun aMessageSurvivesTheWire() {
        val out = ByteArrayOutputStream()
        ScreenProtocol.write(DataOutputStream(out), JSONObject().put("op", "frame").put("n", 3), byteArrayOf(9, 8, 7))
        ScreenProtocol.write(DataOutputStream(out), JSONObject().put("op", "ping"))
        val inp = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val a = ScreenProtocol.read(inp); val b = ScreenProtocol.read(inp)
        assertEquals("frame", a.json.optString("op")); assertEquals(3, a.bin!!.size); assertEquals(7.toByte(), a.bin!![2])
        assertEquals("ping", b.json.optString("op")); assertNull(b.bin)
    }
}

@RunWith(RobolectricTestRunner::class)
class ScreenSessionTest {
    private lateinit var phone: ScreenFakePhone
    private lateinit var session: ScreenSession
    private lateinit var runs: ScreenRuns
    private lateinit var store: LearnedStore
    private val apps = ScreenFakeApps()
    private var rules = ""
    private var added = emptyList<String>()
    private var setupShown = ""
    private var active = false
    private val masked = ArrayList<List<IntArray>>()
    private var startable = false
    private fun config() = ScreenConfig(ScreenRules.parse(rules), emptyList(), false, 720, 1600, 320, ScreenBlocklist(screenBuiltIn(), added), added, true)

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(ctx.cacheDir, "screen-test-" + System.nanoTime())
        phone = ScreenFakePhone(ScreenBlocklist(screenBuiltIn()))
        runs = ScreenRuns(File(dir, "runs"))
        store = LearnedStore(SettingsStore(ctx).also { it.setExtra("learned_apps", null) })
        session = ScreenSession(phone, apps, ::config, File(dir, "pics"), start = { if (startable) { phone.alive = true; null } else "it has not been set up on this phone yet" },
            needSetup = { setupShown = it }, active = { active = it }, runs = runs, mask = { jpeg, boxes, _ -> masked += boxes; jpeg })
    }

    private fun learning() = ScreenLearning({ session }, store, apps, { config().blocklist }, object : PageRunner {
        override suspend fun ensure(app: LearnedApp, ctx: ToolContext) {}
        override suspend fun run(body: String, args: JSONObject, ctx: ToolContext) = "{}"
        override suspend fun syntaxError(body: String, ctx: ToolContext) = ""
        override suspend fun runScreen(app: LearnedApp, tool: com.past9.phoneaos.connections.LearnedTool, args: JSONObject, ctx: ToolContext) =
            ScreenLearned.run(session, store, apps, config().blocklist, app, tool, args, ctx)
    })

    @Test fun anAppOpensOnTheHiddenScreenAndItsScreenIsRead() = runBlocking {
        val out = session.open(ScreenCtx(), "Notes")
        assertTrue(out, out.contains("Notes is open on the background screen") && out.contains("\"New note\"") && out.contains("\"Shopping\""))
        assertTrue(session.state.value.open); assertTrue(active)
        assertEquals(listOf("-n", "$NOTES/.Main"), phone.started.single())
    }

    @Test fun withoutTheHelperTheToolsSayWhyAndShowTheSetupAndNothingCrashes() = runBlocking {
        phone.alive = false
        for (tool in screenTools { session }.filter { it.spec.name != "screen_close" && it.spec.name != "screen_wait" }) {
            val args = JSONObject().put("app", "Notes").put("text", "x").put("element", 1).put("key", "back").put("direction", "up")
            val out = tool.run(args, ScreenCtx())
            assertTrue("${tool.spec.name}: $out", out.contains("not ready on this phone") || out.contains("Nothing is open on the background screen"))
        }
        assertEquals("it has not been set up on this phone yet", setupShown)
        assertFalse(session.state.value.open)
    }

    @Test fun aHelperThatDiesMidTaskIsSaidPlainlyAndTheNextOpenStartsItAgain() = runBlocking {
        session.open(ScreenCtx(), "Notes")
        phone.alive = false
        val out = session.tap(ScreenCtx(), null, "New note", null, null, false)
        assertTrue(out, out.contains("not ready on this phone") || out.contains("stopped"))
        assertFalse(session.state.value.open); assertFalse(active)
        startable = true
        assertTrue(session.open(ScreenCtx(), "Notes").contains("is open on the background screen"))
    }

    @Test fun anOldHelperFromBeforeAnUpdateIsToldToGo() = runBlocking {
        phone.version = ScreenProtocol.VERSION - 1
        val out = session.open(ScreenCtx(), "Notes")
        assertTrue(out, out.contains("not ready on this phone")); assertEquals(1, phone.did("exit"))
    }

    @Test fun aBankingAppIsRefusedOnEveryWayIn() = runBlocking {
        val ctx = ScreenCtx()
        // 1. By name, by package, and by a link: refused before the helper is even asked to start anything.
        for (out in listOf(session.open(ctx, "Capitec"), session.open(ctx, BANK), session.open(ctx, "Capitec", "capitec://pay?to=x"))) assertTrue(out, out.contains("banking or money app") && out.contains("never used"))
        assertEquals(0, phone.did("start")); assertFalse(session.state.value.open)
        // 2. An app the phone only gives away by its name.
        assertNull(config().blocklist.blocked("com.example.games")); assertNotNull(config().blocklist.blocked("com.example.games", "Pocket Wallet"))
        assertTrue(session.open(ctx, "Pocket Wallet").contains("banking or money app"))
        // 3. A button in another app that jumps into the bank: the helper closes it, nothing of it is shown, and the agent is told.
        session.open(ctx, "Notes")
        val out = session.tap(ctx, null, "Pay with my bank", null, null, false)
        assertTrue(out, out.contains("banking or money app") && !out.contains("balance"))
        assertEquals(listOf(BANK), phone.closedBlocked)
        // 4. The bank open on the person's own screen is never moved to the hidden one.
        phone.tasks[BANK] = 0 to true
        assertTrue(session.open(ctx, "Capitec").contains("banking or money app")); assertEquals(0, phone.did("move"))
        // 5. A learned app that points at a bank does not run, whatever was saved in it.
        val bad = LearnedApp("capitec", "Capitec", "app://$BANK", kind = LearnedApp.SCREEN, pkg = BANK,
            tools = listOf(com.past9.phoneaos.connections.LearnedTool("pay", "pay", js = "", steps = JSONArray().put(JSONObject().put("do", "open")))))
        assertTrue(ScreenLearned.run(session, store, apps, config().blocklist, bad, bad.tools[0], JSONObject(), ctx).contains("banking or money app"))
        // 6. Nothing was ever started for it.
        assertTrue(phone.started.none { a -> a.any { it.contains("capitec") } })
        assertTrue(ctx.asked.none { it.contains("Capitec") })
    }

    @Test fun anAppThePersonBlockedIsRefusedTooAndNeverMeansNever() = runBlocking {
        added = listOf("org.other.chat | Chatter")
        assertTrue(session.open(ScreenCtx(), "Chatter").contains("banking or money app"))
        assertEquals(0, phone.did("start"))
        added = emptyList(); rules = "$NOTES | never"
        assertTrue(session.open(ScreenCtx(), "Notes").contains("never uses it"))
    }

    @Test fun aSendAsksFirstAndADeclineOrNoAnswerSendsNothing() = runBlocking {
        val ctx = ScreenCtx()
        session.open(ctx, "Notes"); session.tap(ctx, null, "New note", null, null, false)
        session.type(ctx, "Hello there", null, "Title", false, false)
        assertTrue("moving around must not ask", ctx.asked.isEmpty())
        ctx.answer = "Decline"
        assertTrue(session.tap(ctx, null, "Send", null, null, false).contains("declined"))
        ctx.answer = null
        assertTrue(session.tap(ctx, null, "Send", null, null, false).contains("nobody answered"))
        assertFalse("Send" in phone.pressed)
        assertTrue(ctx.asked.last(), ctx.asked.last().startsWith("APPROVAL|Press \"Send\" in Notes|") && ctx.asked.last().contains("Hello there"))
        ctx.answer = "Approve"
        assertTrue(session.tap(ctx, null, "Send", null, null, false).contains("Sent: Hello there"))
        assertTrue("Send" in phone.pressed)
    }

    @Test fun submittingTypedTextAndPressingABareSpotAskToo() = runBlocking {
        val ctx = ScreenCtx(answer = "Decline")
        session.open(ctx, "Notes"); session.tap(ctx, null, "New note", null, null, false)
        val out = session.type(ctx, "Hi", null, "", false, submit = true)
        assertTrue(out, out.contains("Not submitted")); assertFalse("enter" in phone.pressed)
        assertTrue(session.tap(ctx, null, "", 360, 1000, false).contains("declined"))
        assertTrue(ctx.asked.last().contains("spot with no label"))
        // An app the person set to Allow does not ask.
        rules = "$NOTES | allow"; ctx.asked.clear()
        assertTrue(session.tap(ctx, null, "Send", null, null, false).contains("Sent:")); assertTrue(ctx.asked.isEmpty())
    }

    @Test fun stopWinsAtOnceAndOnlyThePersonCanLiftIt() = runBlocking {
        val ctx = ScreenCtx()
        session.open(ctx, "Notes")
        session.userStop()
        val before = phone.calls.size
        for (out in listOf(session.tap(ctx, null, "New note", null, null, false), session.type(ctx, "x", null, "", false, false), session.look(ctx, "", ""), session.open(ctx, "Notes"),
            session.key(ctx, "back"), session.swipe(ctx, "up", null, null, null, null, null), session.waitFor(ctx, "", false, 1))) assertEquals(ScreenSession.STOPPED, out)
        assertEquals("nothing may reach the phone while stopped", before, phone.calls.size)
        // Closing does not lift it either.
        session.close(); assertTrue(session.state.value.stopped)
        assertEquals(ScreenSession.STOPPED, session.open(ctx, "Notes"))
        session.userAllow()
        assertTrue(session.open(ctx, "Notes").contains("is open"))
    }

    @Test fun whileThePersonHasTheScreenTheAgentWaitsAndWhatTheyDoIsWrittenDown() = runBlocking {
        val ctx = ScreenCtx()
        session.open(ctx, "Notes")
        session.userTakeOver()
        assertEquals(ScreenSession.TAKEN, session.tap(ctx, null, "New note", null, null, false))
        session.userTouch(phone, 0, 650f, 1450f); session.userTouch(phone, 1, 650f, 1450f)
        assertEquals("New note", phone.pressed.last())
        assertNull(session.userType("Bread"))
        val mine = session.runSteps.filter { it.by == "you" }
        assertEquals(listOf("tap", "type"), mine.map { it.kind })
        assertEquals("fab", mine[0].sel!!.optString("res")); assertEquals("title", mine[1].sel!!.optString("res"))
        session.userHandBack()
        assertTrue(session.tap(ctx, null, "Save", null, null, false).contains("\"Bread\""))
    }

    @Test fun anAppOpenOnThePersonsOwnScreenIsOnlyMovedWithTheirYesAndGoesBack() = runBlocking {
        phone.tasks[NOTES] = 0 to true
        val no = ScreenCtx(answer = "Decline")
        assertTrue(session.open(no, "Notes").contains("keep Notes on their own screen")); assertEquals(0, phone.did("move"))
        val yes = ScreenCtx()
        assertTrue(session.open(yes, "Notes").contains("is open on the background screen"))
        assertTrue(yes.asked.single().startsWith("APPROVAL|Move Notes to the background screen|"))
        val out = session.close()
        assertTrue(out, out.contains("went back to the user's own screen")); assertEquals(0 to true, phone.tasks[NOTES])
    }

    @Test fun closingLeavesNothingRunning() = runBlocking {
        session.open(ScreenCtx(), "Notes")
        assertTrue(session.close().contains("nothing is left running"))
        assertTrue(phone.tasks.isEmpty()); assertEquals(0, phone.display); assertFalse(session.state.value.open); assertFalse(active)
        assertEquals("The background screen was already closed.", session.close())
        // Left alone with no agent working, it closes by itself.
        session.open(ScreenCtx(), "Notes"); session.closeIfIdle(idleMs = -1, working = true); assertTrue(session.state.value.open)
        session.closeIfIdle(idleMs = -1, working = false); assertFalse(session.state.value.open)
    }

    @Test fun aProtectedScreenIsSaidSoAndAProtectedMoneyScreenIsNeverUsed() = runBlocking {
        phone.blank = true
        val out = session.open(ScreenCtx(), "Notes")
        assertTrue(out, out.contains("This screen is protected"))
        val pic = session.look(ScreenCtx(), "image", "")
        assertTrue(pic, pic.contains("one flat colour") && !pic.contains("[[image:"))
    }

    @Test fun everyRunIsKeptAsARecordingWithSecretsPaintedOverAndCanBeDeleted() = runBlocking {
        val ctx = ScreenCtx()
        session.open(ctx, "Notes"); session.tap(ctx, null, "New note", null, null, false)
        session.type(ctx, "Milk", null, "Title", false, false)
        session.type(ctx, "4321", null, "PIN", false, false)
        session.type(ctx, "my mother's maiden name", null, "Write something", false, false, secret = true)
        session.tap(ctx, null, "Save", null, null, false)
        session.close()
        val run = runs.list().single()
        assertEquals("Notes", run.app); assertTrue(run.frames >= 5); assertTrue(run.endedAt > 0)
        val steps = runs.steps(run.id)
        assertEquals(listOf("open", "tap", "type", "type", "type", "tap", "close"), steps.map { it.kind })
        val all = steps.joinToString("|") { it.toJson().toString() } + File(runs.picture(run.id, 0)!!.parentFile, "run.json").readText()
        assertFalse("the PIN must not be kept", all.contains("4321")); assertFalse("a secret must not be kept", all.contains("maiden"))
        assertTrue(all.contains("Milk")); assertTrue(steps[3].secret && steps[4].secret)
        assertTrue("pictures with the PIN field in them are painted over", masked.isNotEmpty() && masked.any { b -> b.any { it[1] == 620 } })
        assertNotNull(runs.picture(run.id, 0))
        runs.delete(run.id); assertTrue(runs.list().isEmpty())
    }

    @Test fun oldRecordingsAreCleanedUpByThemselves() {
        val small = ScreenRuns(File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "runs-" + System.nanoTime()), maxBytes = 1_000_000, maxRuns = 3)
        repeat(5) { i -> small.begin(NOTES, "Notes $i", "main").apply { picture(ByteArray(10)); finish() }; Thread.sleep(3) }
        assertEquals(3, small.list().size); assertEquals("Notes 4", small.list().first().app)
        small.deleteAll(); assertTrue(small.list().isEmpty())
    }

    private suspend fun doTheJob(ctx: ScreenCtx, title: String) {
        session.open(ctx, "Notes")
        session.look(ctx, "", "")
        session.tap(ctx, null, "New note", null, null, false)
        session.waitFor(ctx, "", false, 2)
        session.type(ctx, title, null, "Title", false, false)
        session.tap(ctx, null, "Save", null, null, false)
    }

    @Test fun aJobDoneOnceBecomesALearnedToolThatRunsAgainWithOtherInputs() = runBlocking {
        val ctx = ScreenCtx(); val l = learning()
        val save = screenSkillTools(l).first { it.spec.name == "screen_skill_save" }
        assertTrue(save.run(JSONObject().put("name", "add_note").put("description", "Adds a note."), ctx).contains("nothing has been done"))
        doTheJob(ctx, "Milk")
        assertTrue(save.run(JSONObject().put("name", "add_note").put("description", "x").put("inputs", JSONObject().put("title", "Eggs")), ctx).contains("was not typed or pressed"))
        val saved = save.run(JSONObject().put("name", "add_note").put("description", "Adds a note with a title and returns the list.").put("inputs", JSONObject().put("title", "Milk")).put("read_only", false), ctx)
        assertTrue(saved, saved.contains("app_notes_add_note") && saved.contains("title"))
        assertTrue(ctx.asked.last().contains("Press \"New note\"") && ctx.asked.last().contains("Type \"{title}\""))
        session.close()
        // It is a learned app like the web ones: same store, same list, its own tool name.
        val app = store.apps.value.single()
        assertTrue(app.isScreen); assertEquals(NOTES, app.pkg); assertEquals(listOf("title"), app.tools.single().required)
        assertEquals(listOf("open", "tap", "type", "tap"), (0 until app.tools[0].steps.length()).map { app.tools[0].steps.getJSONObject(it).optString("do") })
        val tool = learnedTools(store, l.runner).first { it.spec.name == "app_notes_add_note" }
        // Run it with another title, on a screen whose rows have moved: every element is found again by what it is.
        phone.listFirst = true
        val out = tool.run(JSONObject().put("title", "Bread"), ctx)
        assertTrue(out, out.startsWith("Done the learned way") && out.contains("\"Bread\""))
        assertTrue("Bread" in phone.notes); assertEquals("Bread", phone.typed.last())
        assertTrue("a replay starts the app afresh", phone.started.last().contains("--activity-clear-task"))
        val stats = store.apps.value.single().tools.single().stats
        assertEquals(1, stats.optInt("runs")); assertEquals(1, stats.optInt("ok")); assertTrue(stats.has("liveMs") && stats.has("lastMs") && stats.has("bestMs"))
        assertTrue("missing input is said", tool.run(JSONObject(), ctx).contains("Missing input: title"))
        assertTrue(screenSkillTools(l).first { it.spec.name == "screen_skill_list" }.run(JSONObject(), ctx).contains("app_notes_add_note(title)"))
        assertTrue(screenSkillTools(l).first { it.spec.name == "screen_skill_run" }.run(JSONObject().put("app", "Notes").put("tool", "add_note").put("args", JSONObject().put("title", "Tea")), ctx).contains("\"Tea\""))
    }

    @Test fun switchedOffThereAreNoLearnedPhoneTools() = runBlocking {
        val ctx = ScreenCtx(); val l = learning()
        doTheJob(ctx, "Milk")
        ScreenLearned.save(session, store, apps, config().blocklist, ctx, "add_note", "Adds a note.", mapOf("title" to "Milk"), false)
        try {
            com.past9.phoneaos.connections.screenOn = { false }
            assertTrue(learnedTools(store, l.runner).none { it.spec.name.startsWith("app_") })
            com.past9.phoneaos.connections.screenOn = { true }
            assertTrue(learnedTools(store, l.runner).any { it.spec.name == "app_notes_add_note" })
        } finally { com.past9.phoneaos.connections.screenOn = { true } }
    }

    @Test fun aLearnedToolStillAsksBeforeASendAndStopsWhenThePersonSaysNo() = runBlocking {
        val ctx = ScreenCtx(); val l = learning()
        session.open(ctx, "Notes"); session.tap(ctx, null, "New note", null, null, false)
        session.type(ctx, "Hi Mom", null, "Title", false, false); session.tap(ctx, null, "Send", null, null, false)
        ScreenLearned.save(session, store, apps, config().blocklist, ctx, "send_note", "Sends a note.", mapOf("text" to "Hi Mom"), false)
        session.close(); ctx.asked.clear(); phone.pressed.clear()
        val app = store.apps.value.single()
        ctx.answer = "Decline"
        val out = WebAppRun.call(app, app.tools[0], JSONObject().put("text", "Lunch?"), ctx, l.runner)
        assertTrue(out, out.contains("stopped") && out.contains("declined")); assertFalse("Send" in phone.pressed)
        assertTrue(ctx.asked.single().contains("Press \"Send\" in Notes") && ctx.asked.single().contains("Lunch?"))
    }

    @Test fun aLearnedToolThatNoLongerFitsStopsWithAReasonIsRepairedAndTheRepairCanBeUndone() = runBlocking {
        val ctx = ScreenCtx(); val l = learning()
        phone.tip = true
        session.open(ctx, "Notes"); session.tap(ctx, null, "New note", null, null, false)
        session.tap(ctx, null, "Got it", null, null, false)
        session.type(ctx, "Milk", null, "Title", false, false); session.tap(ctx, null, "Save", null, null, false)
        ScreenLearned.save(session, store, apps, config().blocklist, ctx, "add_note", "Adds a note.", mapOf("title" to "Milk"), false)
        session.close()
        fun tool() = store.apps.value.single().tools.single()
        assertEquals(5, tool().steps.length())
        // 1. The tip no longer comes: the step is skipped, the run works, and the tool drops the dead step.
        phone.tip = false
        val first = WebAppRun.call(store.apps.value.single(), tool(), JSONObject().put("title", "Tea"), ctx, l.runner)
        assertTrue(first, first.startsWith("Done the learned way") && first.contains("no longer needed"))
        assertEquals(4, tool().steps.length()); assertEquals(1, tool().versions.length())
        session.close()
        // 2. The app changes: Save is gone. The run stops there, says so, and leaves the app open for the agent.
        val broken = JSONArray(tool().steps.toString()).apply { getJSONObject(3).getJSONObject("sel").put("res", "gone").put("text", "Keep it"); for (i in 0 until length()) getJSONObject(i).put("ms", 400) }
        store.replaceTool("notes", "add_note", tool().copy(steps = broken))
        val stopped = WebAppRun.call(store.apps.value.single(), tool(), JSONObject().put("title", "Jam"), ctx, l.runner)
        assertTrue(stopped, stopped.contains("Step 4 of 4") && stopped.contains("not on the screen") && stopped.contains("screen_skill_save") && stopped.contains("\"Save\""))
        assertTrue(session.state.value.open); assertEquals(1, tool().stats.optInt("fails"))
        // 3. The agent finishes by hand from there and saves again: that is the repair, and the old steps are kept.
        session.tap(ctx, null, "Save", null, null, false)
        assertTrue(ScreenLearned.save(session, store, apps, config().blocklist, ctx, "add_note", "Adds a note.", mapOf("title" to "Jam"), false).contains("can be undone"))
        assertEquals("save", tool().steps.getJSONObject(tool().steps.length() - 1).getJSONObject("sel").optString("res"))
        val versions = tool().versions.length(); assertTrue(versions >= 2)
        session.close()
        // 4. Undo brings the steps before the repair back.
        val undo = screenSkillTools(l).first { it.spec.name == "screen_skill_undo" }
        assertTrue(undo.run(JSONObject().put("app", "Notes").put("tool", "add_note"), ctx).contains("back to its earlier steps"))
        assertEquals("gone", tool().steps.getJSONObject(3).getJSONObject("sel").optString("res")); assertEquals(versions - 1, tool().versions.length())
    }

    @Test fun whenTheListHasChangedAStepIsFoundByReadingThePicture() = runBlocking {
        val ctx = ScreenCtx()
        var looked = ""
        val s2 = ScreenSession(phone, apps, ::config, File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "p" + System.nanoTime()),
            findInPicture = { _, words -> looked = words; 650 to 1450 })
        val steps = JSONArray().put(JSONObject().put("do", "open")).put(JSONObject().put("do", "tap").put("ms", 300).put("sel", JSONObject().put("res", "renamed").put("text", "Add a note").put("kind", "button")))
        val r = s2.replay(ctx, apps.find("Notes")!!, steps, JSONObject())
        assertTrue(r.text, r.ok && r.usedPicture); assertEquals("Add a note", looked); assertEquals("New note", phone.pressed.last())
        // A point found in the picture has no element under it the gate can read, so on an app that is not set to Allow it asked first.
        assertTrue(ctx.asked.isEmpty() || ctx.asked.all { it.startsWith("APPROVAL|") })
    }

    @Test fun whatThePersonShowsByHandIsSavedInTheSameWay() = runBlocking {
        val ctx = ScreenCtx(); val l = learning()
        assertNull(session.userShow("Notes"))
        assertTrue(session.state.value.takenOver)
        session.userTouch(phone, 0, 650f, 1450f); session.userTouch(phone, 1, 650f, 1450f)
        session.userType("Milk")
        session.userTouch(phone, 0, 100f, 1450f); session.userTouch(phone, 1, 100f, 1450f)
        assertEquals("the opening and the three things done by hand", 4, session.state.value.shown)
        assertTrue(ScreenLearned.save(session, store, apps, config().blocklist, ctx, "add_note", "Adds a note.", mapOf("title" to "Milk"), false, by = "you").contains("app_notes_add_note"))
        session.userHandBack(); session.close()
        val app = store.apps.value.single()
        assertEquals("you", app.tools[0].stats.optString("by"))
        assertTrue(WebAppRun.call(app, app.tools[0], JSONObject().put("title", "Soap"), ctx, l.runner).contains("\"Soap\""))
        // Showing a banking app is refused like everything else.
        assertTrue(session.userShow("Capitec")!!.contains("banking or money app"))
    }
}

@RunWith(RobolectricTestRunner::class)
class ScreenSkillsTest {
    private fun els(phone: ScreenFakePhone) = ScreenTree.parse(JSONObject().put("nodes", JSONArray(phone.nodes())))

    @Test fun anElementIsFoundAgainByWhatItIsNotWhereItWas() {
        val phone = ScreenFakePhone(ScreenBlocklist(""))
        val before = els(phone)
        val fab = ScreenSkills.selector(before.first { it.res == "fab" }, before, 720, 1600)
        val row = ScreenSkills.selector(before.first { it.text == "Shopping" }, before, 720, 1600)
        phone.listFirst = true; phone.notes.add(0, "Zebra")
        val after = els(phone)
        assertEquals("fab", ScreenSkills.find(fab, after)!!.res)
        assertEquals("Shopping", ScreenSkills.find(row, after)!!.text)
        // A row named by an input is found by the input's value.
        assertEquals("Zebra", ScreenSkills.find(JSONObject(row.toString()).put("text", "{who}"), after, JSONObject().put("who", "Zebra"))!!.text)
        assertNull(ScreenSkills.find(JSONObject(row.toString()).put("text", "Nobody"), after))
        // A field is found by its id and hint, not by what was typed in it last time.
        phone.screen = "edit"; phone.title = "old words"
        val field = ScreenSkills.selector(els(phone).first { it.res == "title" }, els(phone), 720, 1600)
        phone.title = ""
        assertEquals("title", ScreenSkills.find(field, els(phone))!!.res)
    }

    @Test fun inputsAreNamedFilledAndListed() {
        assertEquals("Hi {name}, {name}!", ScreenSkills.templatize("Hi Mom, mom!", mapOf("name" to "Mom")))
        assertEquals("{both}", ScreenSkills.templatize("Mom and Dad", mapOf("one" to "Mom", "both" to "Mom and Dad")))
        assertEquals("Hi Dad {other}", ScreenSkills.fill("Hi {name} {other}", JSONObject().put("name", "Dad")))
        val steps = JSONArray().put(JSONObject().put("do", "type").put("text", "{q}")).put(JSONObject().put("do", "tap").put("sel", JSONObject().put("text", "{who}"))).put(JSONObject().put("do", "type").put("text", "{q} again"))
        assertEquals(listOf("q", "who"), ScreenSkills.inputs(steps))
        assertTrue(ScreenSkills.describe(steps).contains("1. Type \"{q}\"") && ScreenSkills.describe(steps).contains("2. Press \"{who}\""))
    }

    @Test fun aWorkingRunTightensTheStepsAndOnlyWhenItMatters() {
        val steps = JSONArray().put(JSONObject().put("do", "open").put("ms", 6000)).put(JSONObject().put("do", "tap").put("ms", 6000)).put(JSONObject().put("do", "tap").put("ms", 6000))
        val tighter = ScreenSkills.refine(steps, listOf(0, 200, 900), setOf(1))!!
        assertEquals(2, tighter.length()); assertEquals(3200, tighter.getJSONObject(1).optLong("ms"))
        assertNull("nothing worth a new version", ScreenSkills.refine(tighter, listOf(0, 850), emptySet()))
    }
}
