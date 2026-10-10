package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.*
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.runtime.SubscriptionRuntime
import com.past9.phoneaos.runtime.LocalMcpServer
import com.past9.phoneaos.tools.MemorySaveTool
import com.past9.phoneaos.tools.MemorySearchTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionPathTest {
    private val http = OkHttpClient()
    private fun post(url: String, token: String?, body: String) = http.newCall(Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
        .apply { if (token != null) header("Authorization", "Bearer $token") }.build()).execute()

    @Test fun mcpServerSpeaksMcpAndRequiresTheToken() = runBlocking {
        val db = AppDb.inMemory(ApplicationProvider.getApplicationContext())
        val ctx = ComposioGuardTest.Ctx("Approve")
        val mcp = LocalMcpServer("secret-token", { listOf(MemorySaveTool(db.memory()), MemorySearchTool(db.memory())) }, { ctx }).start()
        try {
            assertEquals(401, post(mcp.url, null, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").code)
            assertEquals(401, post(mcp.url, "wrong", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").code)
            val init = JSONObject(post(mcp.url, "secret-token", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""").body!!.string())
            assertEquals("phone", init.getJSONObject("result").getJSONObject("serverInfo").getString("name"))
            assertEquals(202, post(mcp.url, "secret-token", """{"jsonrpc":"2.0","method":"notifications/initialized"}""").code)
            val list = JSONObject(post(mcp.url, "secret-token", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").body!!.string())
            val names = list.getJSONObject("result").getJSONArray("tools").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
            assertEquals(listOf("memory_save", "memory_search"), names)
            val call = JSONObject(post(mcp.url, "secret-token", """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"memory_save","arguments":{"title":"Dog","body":"Has a dog called Max"}}}""").body!!.string())
            assertFalse(call.getJSONObject("result").getBoolean("isError"))
            assertEquals("Has a dog called Max", db.memory().list().single().body)
            val bad = JSONObject(post(mcp.url, "secret-token", """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope"}}""").body!!.string())
            assertTrue(bad.has("error"))
        } finally { mcp.stop() }
    }

    @Test fun subscriptionTurnStreamsIntoTheChatAndResumesTheSession() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = AppDb.inMemory(app); val settings = SettingsStore(app); settings.setMode(PowerMode.SUBSCRIPTION)
        val seen = mutableListOf<String?>()
        val rt = AgentRuntime(app, db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone(), { null })
        rt.subscription = object : SubscriptionEngine {
            override val ready = true
            override fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String, images: List<String>, model: String?, role: String) = flow {
                assertEquals("main", role)
                seen += resume
                assertTrue(mcpUrl.startsWith("http://127.0.0.1:")); assertEquals(settings.localToken(), mcpToken)
                emit(JSONObject().put("type", "session").put("id", "sess-1"))
                emit(JSONObject().put("type", "tool").put("name", "memory_search"))
                emit(JSONObject().put("type", "text").put("text", "Done: $prompt"))
                emit(JSONObject().put("type", "done").put("ok", true))
            }
        }
        rt.send("first"); withTimeout(10_000) { rt.awaitIdle() }
        rt.send("second"); withTimeout(10_000) { rt.awaitIdle() }
        assertEquals(listOf(null, "sess-1"), seen)
        assertEquals(listOf("user", "agent", "user", "agent"), db.chat().all().first().map { it.kind })
        assertEquals("Done: second", db.chat().all().first().last().text)
        rt.mcp.stop()
    }

    @Test fun subscriptionHelpersRunOnTheSameHarnessWithTheWorkerTools() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = AppDb.inMemory(app); val settings = SettingsStore(app); settings.setMode(PowerMode.SUBSCRIPTION); settings.setSubHelperModel("haiku")
        val calls = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, String?>>())
        val rt = AgentRuntime(app, db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone(), { null })
        var helperTools = emptyList<String>()
        rt.subscription = object : SubscriptionEngine {
            override val ready = true
            override fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String, images: List<String>, model: String?, role: String) = flow {
                calls += Triple(role, mcpUrl, model)
                when {
                    role == "helper" -> {
                        assertTrue(mcpUrl.contains("/mcp/h/")); assertTrue(system.contains("You are a HELPER"))
                        val list = JSONObject(post(mcpUrl, mcpToken, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").body!!.string()).getJSONObject("result").getJSONArray("tools")
                        helperTools = (0 until list.length()).map { list.getJSONObject(it).getString("name") }
                        emit(JSONObject().put("type", "text").put("text", "X is 42"))
                    }
                    prompt.contains("[Helper #") -> emit(JSONObject().put("type", "text").put("text", "The helper says X is 42."))
                    else -> {
                        val list = JSONObject(post(mcpUrl, mcpToken, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").body!!.string()).getJSONObject("result").getJSONArray("tools")
                        val mainTools = (0 until list.length()).map { list.getJSONObject(it).getString("name") }
                        assertTrue("delegate" in mainTools); assertFalse("routine_create" in mainTools)
                        post(mcpUrl, mcpToken, """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"delegate","arguments":{"tasks":["Find X"],"labels":["Find X"]}}}""").close()
                        emit(JSONObject().put("type", "text").put("text", "A helper is finding X."))
                    }
                }
                emit(JSONObject().put("type", "done").put("ok", true))
            }
        }
        rt.send("find X"); withTimeout(15_000) { rt.awaitIdle() }
        assertEquals(listOf("main", "helper", "main"), calls.map { it.first })
        assertEquals("haiku", calls[1].third)
        assertTrue("browser_open" in helperTools && "delegate" !in helperTools)
        assertEquals("The helper says X is 42.", db.chat().all().first().last { it.kind == "agent" }.text)
        assertEquals("done", JSONObject(db.chat().all().first().first { it.kind == "helper" }.meta).getString("state"))
        rt.mcp.stop()
    }

    @Test fun routinesRunOnTheSubscriptionWithTheWorkerTools() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = AppDb.inMemory(app); val settings = SettingsStore(app); settings.setMode(PowerMode.SUBSCRIPTION)
        val rt = AgentRuntime(app, db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone(), { null })
        var seenRole = ""; var routineTools = emptyList<String>()
        rt.subscription = object : SubscriptionEngine {
            override val ready = true
            override fun turn(prompt: String, system: String, resume: String?, mcpUrl: String, mcpToken: String, images: List<String>, model: String?, role: String) = flow {
                seenRole = role
                assertTrue(mcpUrl.contains("/mcp/r/")); assertTrue(prompt.contains("Summarise yesterday's sales"))
                val list = JSONObject(post(mcpUrl, mcpToken, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").body!!.string()).getJSONObject("result").getJSONArray("tools")
                routineTools = (0 until list.length()).map { list.getJSONObject(it).getString("name") }
                emit(JSONObject().put("type", "text").put("text", "Sales were up 4% yesterday."))
                emit(JSONObject().put("type", "done").put("ok", true))
            }
        }
        assertEquals("Sales were up 4% yesterday.", rt.runBackground("Sales summary", "Summarise yesterday's sales"))
        assertEquals("helper", seenRole)
        assertTrue("browser_open" in routineTools && "delegate" !in routineTools)
        assertEquals("Sales were up 4% yesterday.", db.chat().all().first().last { it.kind == "agent" }.text)
        rt.mcp.stop()
    }

    @Test fun untarsAnNpmStyleTarball() {
        fun header(name: String, size: Int, type: Char): ByteArray {
            val h = ByteArray(512); name.toByteArray().copyInto(h, 0)
            String.format("%011o", size).toByteArray().copyInto(h, 124); h[156] = type.code.toByte(); return h
        }
        val raw = ByteArrayOutputStream()
        fun file(name: String, content: String) { val b = content.toByteArray(); raw.write(header(name, b.size, '0')); raw.write(b); raw.write(ByteArray((512 - b.size % 512) % 512)) }
        raw.write(header("package/bin/", 0, '5'))
        file("package/bin/npm-cli.js", "console.log('npm')")
        file("package/package.json", """{"name":"npm"}""")
        raw.write(ByteArray(1024))
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw.toByteArray()) } }
        val dest = File.createTempFile("untar", "").apply { delete(); mkdirs() }
        SubscriptionRuntime.untarGz(gz.toByteArray().inputStream(), dest, stripFirst = true)
        assertEquals("console.log('npm')", File(dest, "bin/npm-cli.js").readText())
        assertTrue(File(dest, "package.json").exists())
    }
}
