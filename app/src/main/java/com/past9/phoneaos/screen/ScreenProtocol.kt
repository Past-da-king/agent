package com.past9.phoneaos.screen

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * How the app and the screen helper talk. The helper is a small program of ours that runs as the phone's shell user
 * (started by Shizuku or by adb), because only the shell may put other apps on a hidden display and touch them there.
 * The two sides talk over the phone's own loopback address: nothing leaves the device.
 *
 * One message: a 4-byte length, then that many bytes of JSON. A message that carries a picture says so with
 * "bin": <bytes>, and the bytes follow the JSON.
 */
object ScreenProtocol {
    /** Bump when a message changes shape, so the app restarts a helper left over from an older build. */
    const val VERSION = 6
    const val MAX_JSON = 4 * 1024 * 1024
    const val MAX_BIN = 32 * 1024 * 1024

    /**
     * The helper listens on the phone's own loopback address (127.0.0.1), never on the network. A real phone does not
     * let an app open a local socket to the shell user (SELinux), which is what the first version did and why it only
     * worked on an emulator. Each side proves it knows the secret the app made, without ever sending it:
     * the app sends a nonce, the helper answers sha256(secret|nonce) and its own nonce, the app answers the same for that.
     */
    fun proof(token: String, nonce: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("$token|$nonce".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun nonce(): String = java.util.UUID.randomUUID().toString()

    class Message(val json: JSONObject, val bin: ByteArray?)

    fun write(out: DataOutputStream, json: JSONObject, bin: ByteArray? = null) {
        if (bin != null) json.put("bin", bin.size) else json.remove("bin")
        val b = json.toString().toByteArray(Charsets.UTF_8)
        synchronized(out) {
            out.writeInt(b.size); out.write(b)
            if (bin != null) out.write(bin)
            out.flush()
        }
    }

    fun read(inp: DataInputStream): Message {
        val n = inp.readInt()
        if (n < 2 || n > MAX_JSON) throw java.io.IOException("bad message length $n")
        val b = ByteArray(n); inp.readFully(b)
        val j = JSONObject(String(b, Charsets.UTF_8))
        val bn = j.optInt("bin", 0)
        if (bn < 0 || bn > MAX_BIN) throw java.io.IOException("bad picture length $bn")
        val bin = if (bn > 0) ByteArray(bn).also { inp.readFully(it) } else null
        return Message(j, bin)
    }

    fun ok() = JSONObject().put("ok", true)
    fun fail(why: String) = JSONObject().put("ok", false).put("error", why)
}
