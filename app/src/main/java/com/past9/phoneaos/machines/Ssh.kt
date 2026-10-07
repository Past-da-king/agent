package com.past9.phoneaos.machines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import net.schmizz.sshj.xfer.FileSystemFile
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

class SshException(message: String) : Exception(message)

data class RunResult(val exit: Int?, val output: String, val timedOut: Boolean)

/** Credentials for one connection, resolved from the store (kept out of [Machine] so they never land in JSON). */
data class Creds(val password: String?, val privateKey: String?, val passphrase: String?)

/**
 * SSH to any server: password (or keyboard-interactive, as many university servers use), a private key,
 * an optional gateway machine to hop through, and host-key pinning (trust on first use, refuse on change).
 */
object Ssh {
    init { Crypto.install() }

    fun fingerprint(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    }

    /** Pins the host key: the expected one must match; with none expected, the seen key is reported back. */
    private class Pin(private val expected: String?) : HostKeyVerifier {
        var seen: String? = null
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean { seen = fingerprint(key); return expected == null || expected == seen }
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    class Session(val client: SSHClient, private val hop: SSHClient?, val hostKey: String) : AutoCloseable {
        override fun close() { runCatching { client.disconnect() }; runCatching { hop?.disconnect() } }
    }

    private fun auth(c: SSHClient, m: Machine, cr: Creds) {
        try {
            if (!cr.privateKey.isNullOrBlank()) {
                val finder = cr.passphrase?.takeIf { it.isNotEmpty() }?.let { pass -> object : PasswordFinder {
                    override fun reqPassword(r: Resource<*>?) = pass.toCharArray(); override fun shouldRetry(r: Resource<*>?) = false } }
                c.authPublickey(m.user, c.loadKeys(cr.privateKey.trim() + "\n", null, finder))
            } else {
                val pw = cr.password ?: throw SshException("No password saved for ${m.name}.")
                val finder = object : PasswordFinder { override fun reqPassword(r: Resource<*>?) = pw.toCharArray(); override fun shouldRetry(r: Resource<*>?) = false }
                c.auth(m.user, AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(finder)))
            }
        } catch (e: SshException) { throw e } catch (e: Exception) {
            throw SshException("${m.name} refused the sign-in for ${m.user}. Check the username and ${if (cr.privateKey.isNullOrBlank()) "password" else "key"}. (${e.message})")
        }
    }

    private fun client(pin: Pin) = SSHClient(DefaultConfig()).apply { addHostKeyVerifier(pin); connectTimeout = 15_000; timeout = 0 }

    /**
     * Opens a session. [via] is the gateway machine (with its creds) when this one is only reachable through it.
     * Throws [SshException] with a message a person can act on.
     */
    fun open(m: Machine, cr: Creds, via: Pair<Machine, Creds>? = null): Session {
        var hop: SSHClient? = null
        try {
            if (via != null) {
                val hp = Pin(via.first.hostKey)
                hop = client(hp)
                try { hop.connect(via.first.host, via.first.port) } catch (e: Exception) { throw unreachable(via.first, hp, e) }
                auth(hop, via.first, via.second)
            }
            val pin = Pin(m.hostKey)
            val c = client(pin)
            try { if (hop != null) c.connectVia(hop.newDirectConnection(m.host, m.port)) else c.connect(m.host, m.port) } catch (e: Exception) { throw unreachable(m, pin, e) }
            auth(c, m, cr)
            return Session(c, hop, pin.seen ?: "")
        } catch (e: Exception) {
            runCatching { hop?.disconnect() }
            throw if (e is SshException) e else SshException("Couldn't connect to ${m.name}: ${e.message}")
        }
    }

    private fun unreachable(m: Machine, pin: Pin, e: Exception): SshException =
        if (m.hostKey != null && pin.seen != null && pin.seen != m.hostKey)
            SshException("${m.name}'s identity changed (expected ${m.hostKey}, got ${pin.seen}). That can mean someone is intercepting the connection, or the server was reinstalled. Not connecting. If you trust the change, forget and re-add the machine.")
        else SshException("Couldn't reach ${m.name} at ${m.host}:${m.port} (${e.message ?: e.javaClass.simpleName}). Check the address, and that you're on a network that can reach it (VPN or Tailscale for machines at home or on campus).")

    /** Runs a command and returns its exit code and output (stdout and stderr together, the last [maxChars]). */
    suspend fun run(s: Session, command: String, timeoutSec: Long = 120, maxChars: Int = 12_000, stdin: String? = null): RunResult = withContext(Dispatchers.IO) {
        s.client.startSession().use { session ->
            val cmd = session.exec(command)
            if (stdin != null) cmd.outputStream.use { it.write(stdin.toByteArray()) }
            val buf = StringBuffer()
            fun pump(i: java.io.InputStream) = Thread { runCatching { i.bufferedReader().forEachLine { l -> buf.append(l).append('\n'); if (buf.length > maxChars * 2) buf.delete(0, buf.length - maxChars) } } }.apply { isDaemon = true; start() }
            val a = pump(cmd.inputStream); val b = pump(cmd.errorStream)
            cmd.join(timeoutSec, TimeUnit.SECONDS)
            val done = !cmd.isOpen || cmd.exitStatus != null
            if (!done) runCatching { cmd.close() }
            a.join(2000); b.join(2000)
            val out = buf.toString().let { if (it.length > maxChars) "…" + it.takeLast(maxChars) else it }
            RunResult(cmd.exitStatus, out, !done)
        }
    }

    suspend fun upload(s: Session, local: File, remote: String) = withContext(Dispatchers.IO) { s.client.newSFTPClient().use { it.put(FileSystemFile(local), remote) } }
    suspend fun download(s: Session, remote: String, local: File) = withContext(Dispatchers.IO) { s.client.newSFTPClient().use { sftp ->
        val size = sftp.size(remote); if (size > 100L * 1024 * 1024) throw SshException("$remote is ${size / 1048576} MB; the limit is 100 MB.")
        sftp.get(remote, FileSystemFile(local)) } }
}
