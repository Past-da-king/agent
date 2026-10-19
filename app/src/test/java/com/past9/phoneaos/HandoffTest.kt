package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.handoff.*
import com.past9.phoneaos.machines.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Hand-offs end to end: tool -> SSH -> the real Agent Host -> a job -> the user's own sync service -> the phone -> the main agent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandoffTest {
    private lateinit var tmp: File
    private lateinit var sshd: SshServer
    private lateinit var sync: MockWebServer
    private val rows = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
    private val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var host: Process? = null

    @Before fun up() {
        tmp = Files.createTempDirectory("handoff").toFile()
        // A stand-in for the user's sync service: keeps pushed rows, serves them back on pull, records every path it was asked for.
        sync = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    calls += r.path!!.substringBefore('?')
                    val auth = r.getHeader("Authorization")
                    if (r.path!!.startsWith("/v1/auth/")) return MockResponse().setBody("""{"userId":1,"token":"tok-phone"}""")
                    if (auth != "Bearer tok-phone" && auth != "Bearer tok-host") return MockResponse().setResponseCode(401).setBody("""{"error":{"message":"sign in first"}}""")
                    if (r.path!!.startsWith("/v1/sync/push")) {
                        val changes = JSONObject(r.body.readUtf8()).getJSONArray("changes")
                        for (i in 0 until changes.length()) rows += changes.getJSONObject(i).put("seq", rows.size + 1L)
                        return MockResponse().setBody("""{"cursor":${rows.size}}""")
                    }
                    val since = Regex("since=(\\d+)").find(r.path!!)?.groupValues?.get(1)?.toLong() ?: 0L
                    return MockResponse().setBody(JSONObject().put("changes", JSONArray(rows.filter { it.getLong("seq") > since })).put("cursor", rows.size).toString())
                }
            }
            start()
        }
        sshd = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(tmp, "host.ser").toPath())
            setPasswordAuthenticator { u, pw, _ -> u == "sam" && pw == "s3cret" }
            commandFactory = CommandFactory { ch, cmd -> ProcessShellFactory(cmd, "/bin/bash", "-c", cmd).createShell(ch) }
            subsystemFactories = listOf(SftpSubsystemFactory())
            start()
        }
    }
    @After fun down() { File(tmp, "setup/host.pid").takeIf { it.exists() }?.readText()?.trim()?.let { runCatching { ProcessBuilder("kill", it).start().waitFor() } }; host?.destroy(); sshd.stop(true); sync.shutdown(); tmp.deleteRecursively() }

    private fun machine() = Machine("m1", "Test PC", "127.0.0.1", sshd.port, "sam", MachineAuth.PASSWORD)
    private fun svc(): MachineService {
        val settings = SettingsStore(ApplicationProvider.getApplicationContext())
        val store = MachineStore(settings).apply { save(machine()); setCredentials("m1", "s3cret", null, null) }
        return MachineService(store)
    }
    private class Ctx : ToolContext {
        val asked = mutableListOf<String>()
        override val agentLabel = "main"
        override suspend fun activity(text: String, meta: JSONObject) = 1L
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? { asked += question; return "Approve" }
        override suspend fun notify(title: String, body: String) {}
    }
    private fun until(ms: Long = 15_000, f: () -> Boolean) { val t = System.currentTimeMillis(); while (!f()) { check(System.currentTimeMillis() - t < ms) { "timed out" }; Thread.sleep(50) } }

    private fun startHost(linked: Boolean): Pair<Int, File> {
        val js = File("../host/agent-host.mjs").absoluteFile; val fake = File("../host/test/fake-claude.mjs").absoluteFile
        assumeTrue("needs node and the Host source", js.exists() && fake.exists() && runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false))
        fake.setExecutable(true)
        val home = File(tmp, "agent-host").apply { mkdirs() }
        if (linked) File(home, "sync.json").writeText(JSONObject().put("url", "http://127.0.0.1:${sync.port}").put("token", "tok-host").put("device", "host-test").toString())
        val port = java.net.ServerSocket(0).use { it.localPort }
        host = ProcessBuilder("node", js.path, "serve", "--port", port.toString()).redirectErrorStream(true).also {
            it.environment()["AGENT_HOST_HOME"] = home.path; it.environment()["AGENT_HOST_BIN_CLAUDE"] = fake.path
        }.start()
        until { runCatching { java.net.Socket("127.0.0.1", port).close(); File(home, "token").exists() }.getOrDefault(false) }
        return port to home
    }

    @Test fun aJobHandedToAMachineComesBackToTheMainAgentWithNothingInBetween() = runBlocking {
        val (port, home) = startHost(linked = true)
        val tool = MachineHandoffTool(svc(), HostSpot(port, home.path))
        val told = tool.run(JSONObject().put("machine", "Test PC").put("task", "check the weekly stock").put("title", "Stock check").put("ref", "routine-7"), Ctx())
        assertTrue(told, told.startsWith("Handed \"Stock check\" to Test PC (job "))
        assertFalse("it must not claim to be unlinked", told.contains("WARNING"))
        until { rows.any { it.optString("entity") == "handoff" } }
        val row = rows.first { it.optString("entity") == "handoff" }.getJSONObject("payload")
        assertEquals("done", row.getString("state")); assertEquals("routine-7", row.getString("ref")); assertEquals("Stock check", row.getString("title"))

        // The phone: signs in to the same service, pulls, and the main agent is told once.
        val settings = SettingsStore(ApplicationProvider.getApplicationContext())
        val store = HandoffStore(settings); val client = HandoffClient()
        store.save("http://127.0.0.1:${sync.port}", "me@x.com", client.signIn("http://127.0.0.1:${sync.port}", "me@x.com", "secret1", false))
        val told2 = mutableListOf<String>()
        val inbox = HandoffInbox(store, client) { told2 += it }
        assertEquals(1, inbox.check()); assertEquals(0, inbox.check())
        assertEquals(1, told2.size)
        assertTrue(told2[0], told2[0].contains("\"Stock check\" (ref routine-7)") && told2[0].contains("Result:") && told2[0].contains("done"))
        assertTrue("only the user's own sync service is ever contacted", calls.all { it.startsWith("/v1/") })
    }

    @Test fun anUnlinkedHostSaysResultsWontComeBackAndTheStatusToolStillWorks() = runBlocking {
        val (port, home) = startHost(linked = false)
        val s = svc()
        val told = MachineHandoffTool(s, HostSpot(port, home.path)).run(JSONObject().put("machine", "Test PC").put("task", "x").put("title", "T"), Ctx())
        assertTrue(told, told.contains("WARNING") && told.contains("NOT come back"))
        val id = Regex("job (\\w+)").find(told)!!.groupValues[1]
        until { MachineHandoffStatusTool(s, HostSpot(port, home.path)).let { runBlocking { it.run(JSONObject().put("machine", "Test PC").put("job", id), Ctx()) } }.contains("done") }
        assertTrue(rows.isEmpty())
    }

    @Test fun noHostGivesAClearNextStepAndNeedsApproval() = runBlocking {
        val spot = HostSpot(1, File(tmp, "nothing").path)
        val ctx = Ctx()
        val r = MachineHandoffTool(svc(), spot).run(JSONObject().put("machine", "Test PC").put("task", "x").put("title", "T"), ctx)
        assertTrue(r, r.contains("machine_host_setup")); assertTrue(ctx.asked.single().startsWith("APPROVAL|Hand off"))
    }

    @Test fun clientRefusesPlainHttpAndWrongPasswordsSaySo() {
        assertTrue(runCatching { HandoffClient().signIn("http://example.com", "a@b.c", "secret1", false) }.exceptionOrNull()!!.message!!.contains("https://"))
        sync.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setResponseCode(401).setBody("""{"error":{"message":"email or password is not correct"}}""") }
        assertEquals("email or password is not correct", runCatching { HandoffClient().signIn("http://127.0.0.1:${sync.port}", "a@b.c", "secret1", false) }.exceptionOrNull()!!.message)
    }

    @Test fun aFailedJobIsReportedAsFailedNotAsDone() {
        val h = Handoff(1, "j", null, "Backup", "failed", "disk full", "Test PC", 0)
        assertTrue(h.note().contains("did not finish") && h.note().contains("disk full") && !h.note().contains("finished:"))
    }

    @Test fun setupInstallsTheHostOnAMachineStartsItAndLinksItToTheUsersSync() = runBlocking {
        val js = File("../host/agent-host.mjs").absoluteFile
        assumeTrue("needs node, curl and the Host source", js.exists() && runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 && ProcessBuilder("curl", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val port = java.net.ServerSocket(0).use { it.localPort }
        val spot = HostSpot(port, File(tmp, "setup").path, useService = false)
        val ctx = Ctx()
        val r = MachineHostSetupTool(svc(), { js }, { "http://127.0.0.1:${sync.port}" to "tok-host" }, spot).run(JSONObject().put("machine", "Test PC"), ctx)
        assertTrue(r, r.contains("The Agent Host is running on Test PC") && r.contains("linked to the user's sync service"))
        assertTrue(ctx.asked.single().startsWith("APPROVAL|Install the Agent Host"))
        val cfg = JSONObject(File(tmp, "setup/sync.json").readText())
        assertEquals("tok-host", cfg.getString("token")); assertEquals("host-test-pc", cfg.getString("device")); assertEquals("http://127.0.0.1:${sync.port}", cfg.getString("url"))
        assertEquals("the sync token must not be world readable", 0, java.nio.file.Files.getPosixFilePermissions(File(tmp, "setup/sync.json").toPath()).count { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") })
        val st = MachineHandoffStatusTool(svc(), spot).run(JSONObject().put("machine", "Test PC"), Ctx())
        assertTrue(st, st.contains("No jobs"))                       // the Host answers on its own port, authenticated with its own token
        val again = MachineHostSetupTool(svc(), { js }, { null }, spot).run(JSONObject().put("machine", "Test PC"), Ctx())
        assertTrue("safe to run twice: $again", again.contains("running on Test PC"))
    }
}
