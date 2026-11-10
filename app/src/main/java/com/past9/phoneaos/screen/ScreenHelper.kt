package com.past9.phoneaos.screen

import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** The helper is not running (or something that is not the helper answered). */
class HelperAbsent(message: String) : Exception(message)

/** The app's side of the conversation with the screen helper. A fake stands in for it in tests. */
interface ScreenBackend {
    suspend fun call(q: JSONObject, timeoutMs: Int = 20_000): ScreenProtocol.Message
    fun close() {}
}

fun op(name: String) = JSONObject().put("op", name)

/** Talks to the helper over the phone's own local socket. One request at a time; it reconnects by itself. */
class SocketBackend(private val pkg: String) : ScreenBackend {
    private val mutex = Mutex()
    private var socket: LocalSocket? = null
    private var inp: DataInputStream? = null
    private var out: DataOutputStream? = null

    private fun open(): LocalSocket {
        socket?.let { return it }
        val s = LocalSocket()
        try { s.connect(LocalSocketAddress(ScreenProtocol.socketName(pkg), LocalSocketAddress.Namespace.ABSTRACT)) }
        catch (e: Exception) { runCatching { s.close() }; throw HelperAbsent("the screen helper is not running") }
        // Only the phone's shell (or root) can be the helper. Another app that took the name is refused.
        val uid = runCatching { s.peerCredentials.uid }.getOrDefault(-1)
        if (uid != 0 && uid != android.os.Process.SHELL_UID) { runCatching { s.close() }; throw HelperAbsent("something that is not the screen helper answered (uid $uid)") }
        socket = s; inp = DataInputStream(s.inputStream); out = DataOutputStream(s.outputStream)
        return s
    }

    private fun drop() { runCatching { socket?.close() }; socket = null; inp = null; out = null }

    override suspend fun call(q: JSONObject, timeoutMs: Int): ScreenProtocol.Message = withContext(Dispatchers.IO) {
        mutex.withLock {
            var last: Exception? = null
            repeat(2) {
                try {
                    val s = open(); s.soTimeout = timeoutMs
                    ScreenProtocol.write(out!!, q)
                    return@withLock ScreenProtocol.read(inp!!)
                } catch (e: HelperAbsent) { throw e } catch (e: Exception) { last = e; drop() }
            }
            throw HelperAbsent("the screen helper stopped answering (${last?.message ?: "no reply"})")
        }
    }

    override fun close() { drop() }
}

/** Starting the helper: the one command, and the ways this phone can run it as the shell user. */
object ScreenStarter {
    const val HELPER_CLASS = "com.past9.phoneaos.screen.helper.HelperMain"

    /** The script that starts the helper. It finds the app's file by itself, so it keeps working after an update. */
    fun script(pkg: String): String = """
        #!/system/bin/sh
        # Starts the background screen helper for $pkg. Run it as the shell user:
        #   adb shell sh /sdcard/Android/data/$pkg/files/screen-helper.sh
        APK=${'$'}(pm path $pkg | head -n 1 | cut -d: -f2)
        if [ -z "${'$'}APK" ]; then echo "$pkg is not installed"; exit 1; fi
        export CLASSPATH=${'$'}APK
        app_process /system/bin $HELPER_CLASS $pkg --call '{"op":"exit"}' >/dev/null 2>&1
        setsid app_process /system/bin --nice-name=agent_screen_helper $HELPER_CLASS $pkg </dev/null >/dev/null 2>&1 &
        sleep 1
        echo "background screen helper started"
    """.trimIndent() + "\n"

    /** Write the script where the shell user can read it. The path to hand to adb, or null when storage is not there. */
    fun writeScript(context: Context): File? = runCatching {
        val dir = context.getExternalFilesDir(null) ?: return null
        File(dir, "screen-helper.sh").apply { writeText(script(context.packageName)) }
    }.getOrNull()

    /** What to type on a computer with the phone plugged in. */
    fun adbCommand(context: Context): String =
        "adb shell sh " + (writeScript(context)?.absolutePath?.replace("/storage/emulated/0/", "/sdcard/") ?: "/sdcard/Android/data/${context.packageName}/files/screen-helper.sh")

    // --- Shizuku: an app the person may already have, which lends the shell user to apps they allow. ---

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_REQUEST = 4471

    fun shizukuInstalled(context: Context): Boolean = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }.getOrDefault(false)
    fun shizukuRunning(): Boolean = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
    fun shizukuGranted(): Boolean = runCatching { rikka.shizuku.Shizuku.pingBinder() && rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun shizukuAsk() { runCatching { if (rikka.shizuku.Shizuku.pingBinder()) rikka.shizuku.Shizuku.requestPermission(SHIZUKU_REQUEST) } }

    /** Run the start script through Shizuku. Null when it ran; otherwise why not. */
    fun startWithShizuku(context: Context): String? {
        if (!shizukuRunning()) return if (shizukuInstalled(context)) "Shizuku is installed but not started" else "Shizuku is not installed"
        if (!shizukuGranted()) return "Shizuku has not been allowed for this app yet"
        return try {
            val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", script(context.packageName)), null, null) as Process
            Thread { runCatching { p.inputStream.readBytes() } }.apply { isDaemon = true; start() }
            runCatching { p.waitFor() }
            null
        } catch (t: Throwable) {
            "Shizuku could not start the helper: " + (((t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t).message ?: t.toString())
        }
    }
}
