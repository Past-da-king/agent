package com.past9.phoneaos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.connections.*
import com.past9.phoneaos.data.SettingsStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Web apps the agent teaches itself: saving needs the user's yes, tools that change things ask first, results come back trimmed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LearnedAppsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private class Ctx(var answer: String? = "Approve") : ToolContext {
        override val agentLabel = "main"
        val asked = mutableListOf<String>(); val lines = mutableListOf<String>()
        override suspend fun activity(text: String, meta: JSONObject): Long { lines += text; return lines.size.toLong() }
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) { lines += text }
        override suspend fun ask(question: String, options: List<String>): String? { asked += question; return answer }
        override suspend fun notify(title: String, body: String) {}
    }
    private class Runner(var reply: String = """{"ok":true,"v":{"chats":["Mom","Sam"]}}""", var syntax: String = "", var failEnsure: Throwable? = null, var failSyntax: Throwable? = null) : PageRunner {
        val ran = mutableListOf<Pair<String, JSONObject>>(); var ensured = 0; val ranFor = mutableListOf<String>()
        override suspend fun ensure(app: LearnedApp, ctx: ToolContext) { failEnsure?.let { throw it }; ensured++ }
        override suspend fun run(body: String, args: JSONObject, ctx: ToolContext): String { ran += body to args; ranFor += ctx.agentLabel; return reply }
        override suspend fun syntaxError(body: String, ctx: ToolContext): String { failSyntax?.let { throw it }; return syntax }
    }
    private fun store() = LearnedStore(SettingsStore(ctx).also { it.setExtra("learned_apps", null) })

    private fun toolsJson(vararg t: JSONObject) = org.json.JSONArray().apply { t.forEach { put(it) } }
    private fun tool(name: String, ro: Boolean, js: String = "return 1", params: JSONObject = JSONObject()) =
        JSONObject().put("name", name).put("description", "Does $name").put("js", js).put("read_only", ro).put("params", params)
    private fun save(input: JSONObject, st: LearnedStore, c: Ctx, r: Runner) = runBlocking { WebAppSaveTool(st, r).run(input, c) }
    private fun input(vararg t: JSONObject) = JSONObject().put("name", "WhatsApp Web").put("start_url", "https://web.whatsapp.com").put("desktop", true).put("tools", toolsJson(*t))

    @Test fun savingAsksTheUserListsReadsAndChangesThenStoresTheApp() {
        val st = store(); val c = Ctx(); val r = Runner()
        val out = save(input(tool("list_chats", true), tool("send_message", false, params = JSONObject().put("chat", JSONObject().put("type", "string")))), st, c, r)
        assertTrue(out, out.startsWith("Saved WhatsApp Web with 2 tools"))
        assertTrue(out, out.contains("web_whatsapp_web_list_chats") && out.contains("web_whatsapp_web_send_message"))
        assertEquals(1, c.asked.size)
        assertTrue(c.asked[0], c.asked[0].startsWith("APPROVAL|Save the WhatsApp Web tools (2)|"))
        assertTrue(c.asked[0].contains("Reads: list_chats") && c.asked[0].contains("CHANGES things: send_message") && c.asked[0].contains("desktop browser"))
        val app = st.find("whatsapp web")!!
        assertTrue(app.desktop); assertFalse(app.trusted); assertEquals("web.whatsapp.com", app.host); assertEquals(1, app.writes)
        // It survives a restart.
        assertEquals(2, LearnedStore(SettingsStore(ctx)).apps.value.single().tools.size)
    }

    @Test fun aDeclinedOrUnansweredSaveStoresNothing() {
        val st = store(); val r = Runner()
        assertTrue(save(input(tool("a_tool", true)), st, Ctx("Decline"), r).startsWith("DECLINED"))
        assertTrue(save(input(tool("a_tool", true)), st, Ctx(null), r).startsWith("No answer"))
        assertTrue(st.apps.value.isEmpty())
    }

    @Test fun badDefinitionsAreRefusedBeforeTheUserIsAsked() {
        val st = store(); val c = Ctx(); val r = Runner()
        fun refused(i: JSONObject, why: String, rr: Runner = r) { val o = save(i, st, c, rr); assertTrue("$why: $o", o.startsWith("Not saved")); }
        refused(input(tool("Bad Name", true)), "tool name")
        refused(input(tool("ok_one", true, js = "  ")), "empty js")
        refused(input(tool("ok_one", true), tool("ok_one", false)), "duplicate")
        refused(input(tool("ok_one", true, params = JSONObject().put("x", JSONObject().put("type", "object")))), "param type")
        refused(JSONObject().put("name", "X").put("start_url", "http://insecure.example").put("tools", toolsJson(tool("ok_one", true))), "http")
        refused(input(tool("ok_one", true)), "syntax", Runner(syntax = "Unexpected token '}'"))
        refused(input(), "no tools")
        assertTrue("never asked", c.asked.isEmpty()); assertTrue(st.apps.value.isEmpty())
    }

    private fun app(st: LearnedStore, trusted: Boolean = false): LearnedApp {
        val a = LearnedApp("wa", "WhatsApp Web", "https://web.whatsapp.com", tools = listOf(
            LearnedTool("list_chats", "List chats", js = "return ['Mom']"),
            LearnedTool("send_message", "Send a message", JSONObject().put("chat", JSONObject().put("type", "string")).put("text", JSONObject().put("type", "string")), listOf("chat", "text"), "return 1", readOnly = false)))
        st.save(a); if (trusted) st.setTrusted("wa", true); return st.find("wa")!!
    }

    @Test fun readingRunsStraightAwayAndReturnsTheValue() = runBlocking {
        val st = store(); app(st); val c = Ctx(); val r = Runner()
        val out = LearnedCallTool("wa", st.find("wa")!!.tools[0], st, r).run(JSONObject(), c)
        assertEquals("""{"chats":["Mom","Sam"]}""", out)
        assertTrue(c.asked.isEmpty()); assertEquals(1, r.ensured); assertEquals("return ['Mom']", r.ran.single().first)
    }

    @Test fun changingThingsAsksFirstAndDeclineMeansItNeverRuns() = runBlocking {
        val st = store(); app(st); val r = Runner(); val t = st.find("wa")!!.tools[1]
        val args = JSONObject().put("chat", "Mom").put("text", "On my way")
        val no = Ctx("Decline"); assertTrue(LearnedCallTool("wa", t, st, r).run(args, no).startsWith("DECLINED")); assertTrue(r.ran.isEmpty())
        assertTrue(no.asked.single().contains("chat: Mom") && no.asked.single().contains("text: On my way"))
        val yes = Ctx("Approve"); LearnedCallTool("wa", t, st, r).run(args, yes); assertEquals(1, r.ran.size)
        val bg = Ctx(null); assertTrue(LearnedCallTool("wa", t, st, r).run(args, bg).startsWith("No answer")); assertEquals(1, r.ran.size)
    }

    @Test fun aTrustedAppChangesThingsWithoutAskingButOnlyTheUserCanTrust() = runBlocking {
        val st = store(); val r = Runner(); val c = Ctx()
        // Saved the way the agent saves it: the id comes from the name.
        assertTrue(WebAppSaveTool(st, r).run(input(tool("list_chats", true), tool("send_message", false, params = JSONObject().put("chat", JSONObject().put("type", "string")))), Ctx()).startsWith("Saved"))
        val id = st.find("WhatsApp Web")!!.id; assertEquals("whatsapp_web", id); assertFalse(st.find(id)!!.trusted)
        st.setTrusted(id, true)
        LearnedCallTool(id, st.find(id)!!.tools[1], st, r).run(JSONObject().put("chat", "Mom"), c)
        assertTrue("trusted: no question", c.asked.isEmpty()); assertEquals(1, r.ran.size)
        // The agent saving the app again (a fixed tool) leaves the user's trust choice as it was.
        assertTrue(WebAppSaveTool(st, r).run(input(tool("list_chats", true), tool("send_message", false)), Ctx()).startsWith("Saved"))
        assertTrue(st.find(id)!!.trusted)
        st.setTrusted(id, false); assertFalse(st.find(id)!!.trusted)
    }

    @Test fun aBrowserThatCannotStartIsAReadableErrorNotAThrownOne() = runBlocking {
        val st = store(); app(st)
        val out = LearnedCallTool("wa", st.find("wa")!!.tools[0], st, Runner(failEnsure = IllegalStateException("Too many browsers open at once (4). Finish one first."))).run(JSONObject(), Ctx())
        assertTrue(out, out.startsWith("Error: couldn't run list_chats") && out.contains("Too many browsers"))
    }

    @Test fun aSyntaxCheckThatCannotRunDoesNotBlockTheSave() {
        val st = store(); val c = Ctx()
        val out = save(input(tool("list_chats", true)), st, c, Runner(failSyntax = IllegalStateException("Too many browsers open at once (4). Finish one first.")))
        assertTrue(out, out.startsWith("Saved")); assertNotNull(st.find("whatsapp web"))
    }

    @Test fun eachAgentRunsInItsOwnBrowser() = runBlocking {
        val st = store(); app(st); val r = Runner(); val t = st.find("wa")!!.tools[0]
        val helperCtx = object : ToolContext by Ctx() { override val agentLabel = "WhatsApp helper" }
        LearnedCallTool("wa", t, st, r).run(JSONObject(), Ctx()); LearnedCallTool("wa", t, st, r).run(JSONObject(), helperCtx)
        assertEquals(listOf("main", "WhatsApp helper"), r.ranFor)
    }

    @Test fun aSyntaxErrorSaysHowMuchArrivedAndHowItStartedAndEnded() {
        val st = store(); val c = Ctx()
        val cutJs = "const r = await fetch('/s?k=' + args.q); const html = await r.text(); return html.sl"
        val out = save(input(tool("search", true, js = cutJs)), st, c, Runner(syntax = "Unexpected end of input"))
        assertTrue(out, out.startsWith("Not saved: the js of search doesn't parse: Unexpected end of input"))
        assertTrue(out, out.contains("arrived as ${cutJs.length} characters") && out.contains("ending \"") && out.contains("html.sl") && out.contains("cut off"))
        val empty = save(input(tool("search", true, js = "")), st, c, Runner()); assertTrue(empty, empty.contains("0 characters arrived"))
    }

    @Test fun argumentsThatCouldNotBeReadAreSaidNotQuietlyEmptied() = runBlocking {
        val ran = mutableListOf<String>()
        val tool = object : com.past9.phoneaos.agent.Tool {
            override val spec = com.past9.phoneaos.agent.ToolSpec("webapp_save", "x", JSONObject().put("type", "object"))
            override suspend fun run(input: JSONObject, ctx: ToolContext): String { ran += "ran"; return "ok" }
        }
        var turn = 0
        val provider = object : com.past9.phoneaos.agent.LlmProvider {
            override val name = "fake"
            override suspend fun complete(system: String, messages: List<com.past9.phoneaos.agent.Msg>, tools: List<com.past9.phoneaos.agent.ToolSpec>, model: String, maxTokens: Int): com.past9.phoneaos.agent.Completion {
                turn++
                return if (turn == 1) com.past9.phoneaos.agent.Completion(com.past9.phoneaos.agent.Msg(com.past9.phoneaos.agent.Role.ASSISTANT, listOf(com.past9.phoneaos.agent.Block.ToolCall("c1", "webapp_save", JSONObject().put(com.past9.phoneaos.agent.BAD_ARGUMENTS, 4090)))), "length")
                else com.past9.phoneaos.agent.Completion(com.past9.phoneaos.agent.Msg(com.past9.phoneaos.agent.Role.ASSISTANT, listOf(com.past9.phoneaos.agent.Block.Text("done"))), "stop")
            }
        }
        val history = mutableListOf(com.past9.phoneaos.agent.Msg.user("save it"))
        com.past9.phoneaos.agent.AgentLoop(provider, "m", listOf(tool), maxSteps = 3).run("sys", history, Ctx())
        assertTrue("the tool never ran on cut-off arguments", ran.isEmpty())
        val result = history.flatMap { it.blocks }.filterIsInstance<com.past9.phoneaos.agent.Block.ToolResult>().single()
        assertTrue(result.content, result.isError && result.content.contains("hit the length limit") && result.content.contains("4090 characters arrived") && result.content.contains("Nothing ran"))
    }

    @Test fun aSingleBackslashInsideJsonIsKeptNotQuietlyDropped() {
        // What a model writes for regex code: single backslashes, which are not valid JSON escapes.
        val raw = """{"name":"search","js":"const m = /\d+\.\d+/.exec(s); const q = document.querySelector([data-x=\"y\"]); return m && m[0]"}"""
        // Android's reader alone would eat them:
        val eaten = JSONObject(raw).getString("js")
        assertTrue("without the repair the regex loses its backslashes: $eaten", eaten.contains("/d+.d+/"))
        val kept = JSONObject(com.past9.phoneaos.agent.ToolArgs.fixEscapes(raw)).getString("js")
        assertEquals("""const m = /\d+\.\d+/.exec(s); const q = document.querySelector([data-x="y"]); return m && m[0]""", kept)
    }

    @Test fun properlyEscapedJsonIsLeftExactlyAsItWas() {
        val good = JSONObject().put("js", "const m = /\\d+\\/dp\\//.exec(s); return \"a\\nb\"").toString()   // valid JSON a model got right
        assertEquals(good, com.past9.phoneaos.agent.ToolArgs.fixEscapes(good))
        assertEquals("""{"a":"x\\w y\n z \"q\" \/ \u00e9"}""", com.past9.phoneaos.agent.ToolArgs.fixEscapes("""{"a":"x\\w y\n z \"q\" \/ \u00e9"}"""))
        assertEquals("no backslashes at all", com.past9.phoneaos.agent.ToolArgs.fixEscapes("no backslashes at all"))
    }

    @Test fun missingInputIsSaidNotRun() = runBlocking {
        val st = store(); app(st); val r = Runner()
        val out = LearnedCallTool("wa", st.find("wa")!!.tools[1], st, r).run(JSONObject().put("chat", "Mom"), Ctx())
        assertEquals("Missing input: text.", out); assertTrue(r.ran.isEmpty())
    }

    @Test fun resultsAreTrimmedAndErrorsAreReadable() {
        assertEquals("hello", WebApps.result("""{"ok":true,"v":"hello"}"""))
        assertEquals("Error: Not signed in", WebApps.result("""{"ok":false,"e":"Not signed in"}"""))
        assertEquals("null", WebApps.result("""{"ok":true,"v":null}"""))
        val big = WebApps.result("""{"ok":true,"v":"${"x".repeat(25_000)}"}""")
        assertTrue(big.length < 20_300 && big.contains("more characters"))
    }

    @Test fun toolsAreNamedAfterTheAppAndKeepTheirInputs() {
        val st = store(); app(st); val tools = learnedTools(st, Runner()).map { it.spec.name }
        for (n in listOf("webapp_guide", "webapp_list", "webapp_save", "webapp_test", "webapp_remove", "web_wa_list_chats", "web_wa_send_message")) assertTrue("$n in $tools", n in tools)
        assertTrue(tools.all { it.length <= 64 })
        val schema = learnedTools(st, Runner()).first { it.spec.name == "web_wa_send_message" }.spec.schema
        assertEquals("""["chat","text"]""", schema.getJSONArray("required").toString())
        assertTrue(learnedTools(st, Runner()).first { it.spec.name == "web_wa_send_message" }.spec.description.contains("CHANGES things"))
    }

    @Test fun removingNeedsAYesAndTestRunsOneTool() = runBlocking {
        val st = store(); app(st); val r = Runner()
        assertTrue(WebAppRemoveTool(st).run(JSONObject().put("app", "WhatsApp Web"), Ctx("Decline")).startsWith("DECLINED")); assertNotNull(st.find("wa"))
        assertTrue(WebAppTestTool(st, r).run(JSONObject().put("app", "whatsapp web").put("tool", "list_chats"), Ctx()).contains("Mom"))
        assertTrue(WebAppRemoveTool(st).run(JSONObject().put("app", "WhatsApp Web"), Ctx("Approve")).startsWith("Removed")); assertNull(st.find("wa"))
    }

    @Test fun thePageScriptsAreWrittenOut() {
        File("build/pagejs").mkdirs()
        File("build/pagejs/start.js").writeText(PageJs.start("__aosR1", "const r = args.n * 2; return {r, s: 'it\\'s \"quoted\"'}", JSONObject().put("n", 21)))
        File("build/pagejs/poll.js").writeText(PageJs.poll("__aosR1"))
        File("build/pagejs/pending.js").writeText(PageJs.pending("__aosD1"))
        File("build/pagejs/direct.js").writeText(PageJs.direct("__aosD1", "const v = await new Promise(r => setTimeout(() => r(args.x * 3), 5)); return {v}", JSONObject().put("x", 4)))
        File("build/pagejs/state_d1.js").writeText(PageJs.startedState("__aosD1"))
        File("build/pagejs/pending2.js").writeText(PageJs.pending("__aosD2"))
        File("build/pagejs/direct_syntax.js").writeText(PageJs.direct("__aosD2", "return }", JSONObject()))
        File("build/pagejs/state_d2.js").writeText(PageJs.startedState("__aosD2"))
        File("build/pagejs/start_trivial.js").writeText(PageJs.start("__aosT1", "const x=1;", JSONObject()))
        File("build/pagejs/syntax_trivial.js").writeText(PageJs.syntax("const x=1;"))
        File("build/pagejs/start_throw.js").writeText(PageJs.start("__aosE1", "throw new Error('not signed in')", JSONObject()))
        File("build/pagejs/start_syntax.js").writeText(PageJs.start("__aosE2", "return }", JSONObject()))
        File("build/pagejs/start_await.js").writeText(PageJs.start("__aosE3", "const v = await new Promise(r => setTimeout(() => r(args.x + 1), 5)); return [v, undefined]", JSONObject().put("x", 1)))
        File("build/pagejs/syntax_ok.js").writeText(PageJs.syntax("return 1"))
        File("build/pagejs/syntax_bad.js").writeText(PageJs.syntax("return }"))
        File("build/pagejs/net.js").writeText(PageJs.NET_SHIM)
        File("build/pagejs/netlog.js").writeText(PageJs.netLog("api", 5))
        assertTrue(PageJs.start("s", "return 1", JSONObject()).contains("window[k]"))
        // Only a real SyntaxError counts as a syntax error: a page that forbids (or traps) building functions throws something else.
        assertTrue(PageJs.syntax("x").contains("e.name==='SyntaxError'") && PageJs.start("s", "x", JSONObject()).contains("e.name==='SyntaxError'"))
    }

    /** The engine's js() leaves JSON quotes on a plain string result: a body that parses came back as two quote marks and every save was refused. */
    @Test fun aStringResultLosesItsJsonQuotes() {
        assertEquals("", PageJs.text("\"\""))
        assertEquals("blocked", PageJs.text("\"blocked\""))
        assertEquals("Unexpected token '}'", PageJs.text("\"Unexpected token '}'\""))
        assertEquals("timeout", PageJs.text("timeout"))
        assertEquals("{\"ok\":true}", PageJs.text("{\"ok\":true}"))
        assertEquals("0", PageJs.text("0"))
    }
}
