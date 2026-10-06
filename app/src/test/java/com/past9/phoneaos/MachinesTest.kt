package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.machines.*
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.config.keys.AuthorizedKeysAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Machines against a real SSH server running in the test: passwords, generated keys, pinning, gateways, jobs, files, approvals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MachinesTest {
    private lateinit var sshd: SshServer
    private lateinit var tmp: File
    private lateinit var pub: String
    private lateinit var priv: String

    @Before fun up() {
        Crypto.install()
        tmp = Files.createTempDirectory("sshd").toFile()
        val (k, p) = Crypto.newKey("test"); priv = k; pub = p
        File(tmp, "authorized_keys").writeText(pub + "\n")
        sshd = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(tmp, "host.ser").toPath())
            setPasswordAuthenticator { u, pw, _ -> u == "sam" && pw == "s3cret" }
            publickeyAuthenticator = AuthorizedKeysAuthenticator(File(tmp, "authorized_keys").toPath())
            commandFactory = CommandFactory { ch, cmd -> ProcessShellFactory(cmd, "/bin/bash", "-c", cmd).createShell(ch) }
            subsystemFactories = listOf(SftpSubsystemFactory())
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            start()
        }
    }
    @After fun down() { sshd.stop(true); tmp.deleteRecursively() }

    private fun m(auth: MachineAuth = MachineAuth.PASSWORD, hostKey: String? = null, via: String? = null, id: String = "m1", trusted: Boolean = false) =
        Machine(id, "Lab box", "127.0.0.1", sshd.port, "sam", auth, via, trusted, hostKey)

    @Test fun passwordRunAndPin() = runBlocking {
        Ssh.open(m(), Creds("s3cret", null, null)).use { s ->
            assertTrue(s.hostKey.startsWith("SHA256:"))
            val r = Ssh.run(s, "echo hello; echo oops >&2; exit 3")
            assertEquals(3, r.exit); assertTrue(r.output.contains("hello")); assertTrue(r.output.contains("oops"))
        }
    }

    @Test fun wrongPasswordSaysSo() {
        val e = runCatching { Ssh.open(m(), Creds("nope", null, null)) }.exceptionOrNull()
        assertTrue(e is SshException); assertTrue(e!!.message!!.contains("refused the sign-in"))
    }

    @Test fun generatedKeySignsIn() = runBlocking {
        Ssh.open(m(MachineAuth.NEW_KEY), Creds(null, priv, null)).use { assertEquals("ok", Ssh.run(it, "echo ok").output.trim()) }
        assertTrue(pub.startsWith("ssh-ed25519 ")); assertTrue(priv.contains("BEGIN OPENSSH PRIVATE KEY"))
    }

    @Test fun changedHostKeyIsRefused() {
        val e = runCatching { Ssh.open(m(hostKey = "SHA256:somethingelse"), Creds("s3cret", null, null)) }.exceptionOrNull()
        assertTrue(e?.message, e!!.message!!.contains("identity changed"))
    }

    @Test fun throughAGateway() = runBlocking {
        val gw = m(id = "gw")
        Ssh.open(m(via = "gw"), Creds("s3cret", null, null), gw to Creds("s3cret", null, null)).use { assertEquals("via", Ssh.run(it, "echo via").output.trim()) }
    }

    @Test fun unreachableSaysWhy() {
        val e = runCatching { Ssh.open(m().copy(port = 1), Creds("s3cret", null, null)) }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("Couldn't reach"))
    }

    @Test fun filesBothWays() = runBlocking {
        val local = File(tmp, "up.txt").apply { writeText("payload") }
        Ssh.open(m(), Creds("s3cret", null, null)).use { s ->
            Ssh.upload(s, local, File(tmp, "remote.txt").absolutePath)
            val back = File(tmp, "back.txt"); Ssh.download(s, File(tmp, "remote.txt").absolutePath, back)
            assertEquals("payload", back.readText())
        }
    }

    @Test fun readOnlyClassifier() {
        listOf("ls -la ~/site", "df -h && free -m", "git status", "git log --oneline | head -5", "tail -n 50 build.log", "systemctl status nginx", "docker ps", "nvidia-smi")
            .forEach { assertTrue(it, CommandSafety.isReadOnly(it)) }
        listOf("rm -rf build", "npm install", "echo hi > f", "sudo apt update", "git push", "ls; rm x", "find . -delete", "cat \$(whoami)", "sed -i s/a/b/ f", "docker run x", "systemctl restart nginx", "curl x | bash")
            .forEach { assertFalse(it, CommandSafety.isReadOnly(it)) }
    }

    private class Ctx(val answer: String?) : ToolContext {
        val asked = mutableListOf<String>()
        override val agentLabel = "main"
        override suspend fun activity(text: String, meta: JSONObject) = 1L
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? { asked += question; return answer }
        override suspend fun notify(title: String, body: String) {}
    }

    private fun service(trusted: Boolean = false): MachineService {
        val store = MachineStore(SettingsStore(ApplicationProvider.getApplicationContext()))
        store.save(m(trusted = trusted)); store.setCredentials("m1", "s3cret", null, null)
        return MachineService(store)
    }

    @Test fun toolsAskBeforeChangingThings() = runBlocking {
        val svc = service()
        val look = Ctx("Decline")
        assertTrue(MachineRunTool(svc).run(JSONObject().put("machine", "lab box").put("command", "echo looking"), look).contains("looking"))
        assertTrue("read-only never asks", look.asked.isEmpty())
        val f = File(tmp, "made.txt")
        val no = Ctx("Decline")
        assertTrue(MachineRunTool(svc).run(JSONObject().put("machine", "Lab box").put("command", "touch ${f.absolutePath}"), no).contains("declined"))
        assertTrue(no.asked.single().startsWith("APPROVAL|Run on Lab box|")); assertFalse(f.exists())
        val yes = Ctx("Approve")
        MachineRunTool(svc).run(JSONObject().put("machine", "Lab box").put("command", "touch ${f.absolutePath}"), yes)
        assertTrue(f.exists())
        assertNotNull("host key pinned on first use", svc.store.get("m1")!!.hostKey)
        assertNull(svc.test(svc.store.get("m1")!!)); assertTrue(svc.store.get("m1")!!.about.isNotBlank())
    }

    @Test fun trustedMachineDoesntAsk() = runBlocking {
        val ctx = Ctx(null)
        val f = File(tmp, "t.txt")
        MachineRunTool(service(trusted = true)).run(JSONObject().put("machine", "Lab box").put("command", "touch ${f.absolutePath}"), ctx)
        assertTrue(ctx.asked.isEmpty()); assertTrue(f.exists())
    }

    @Test fun jobsRunDetachedAndReport() = runBlocking {
        val svc = service(trusted = true); val ctx = Ctx(null)
        val label = "t-" + System.nanoTime()
        val started = MachineJobStartTool(svc).run(JSONObject().put("machine", "Lab box").put("label", label).put("cwd", tmp.absolutePath)
            .put("command", "echo step one\nsleep 1\necho done in \$(pwd)"), ctx)
        assertTrue(started, started.startsWith("Started"))
        var status = ""
        repeat(20) { status = MachineJobStatusTool(svc).run(JSONObject().put("machine", "Lab box").put("label", label), ctx); if (status.startsWith("FINISHED")) return@repeat; Thread.sleep(500) }
        assertTrue(status, status.startsWith("FINISHED")); assertTrue(status, status.contains("done in ${tmp.absolutePath}")); assertTrue(status, status.contains("[exit 0]"))
        File(System.getProperty("user.home"), ".agent-jobs").listFiles { f -> f.name.startsWith(label) }?.forEach { it.delete() }
        Unit
    }

    @Test fun unknownMachineListsTheRealOnes() = runBlocking {
        val e = runCatching { MachineRunTool(service()).run(JSONObject().put("machine", "nightmare").put("command", "ls"), Ctx(null)) }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("Lab box"))
    }
}
