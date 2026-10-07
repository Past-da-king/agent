package com.past9.phoneaos.tools

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Whether the user let the agent look through the phone's shared storage (Settings > All files access). */
fun phoneFilesAllowed() = Environment.isExternalStorageManager()

fun openAllFilesSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * The phone's own files, like `ls` and `find`: list a folder, or search Downloads, Documents and the rest by
 * name. Found files can go straight to browser_upload (a site), machine_upload (a computer) or send_file.
 * Needs the user's one-time "All files access"; asks for it the first time.
 */
class PhoneFilesTool(private val context: Context) : Tool {
    override val spec = ToolSpec("phone_files", "THE way to look at files on the user's phone (run_code can't see them): action 'find' searches by name (e.g. 'cv', 'invoice march', '.pdf', '.m4a'), action 'list' shows a folder. Where things live: Download, Documents, DCIM/Camera, DCIM/Screen recordings, Recordings/Voice Recorder, Recordings/Call, WhatsApp media and statuses in Android/media/com.whatsapp/WhatsApp/Media (statuses: .Statuses). Returns full paths with size and date, for browser_upload, machine_upload or send_file. Asks the user once for access to their files.",
        schema(listOf("action"), "action" to str("find or list"), "query" to str("For find: words in the file name, or an extension like .pdf"), "folder" to str("For list (or to narrow find): a folder under the phone's storage, e.g. Download"), "limit" to int("Max results, default 30")))

    private val root get() = Environment.getExternalStorageDirectory()
    private val fmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale.UK)
    private fun line(f: File) = (if (f.isDirectory) "📁 " else "") + f.absolutePath + if (f.isFile) " · ${size(f.length())} · ${fmt.format(Date(f.lastModified()))}" else ""
    private fun size(b: Long) = when { b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0); b >= 1024 -> "${b / 1024} KB"; else -> "$b B" }

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        if (!phoneFilesAllowed()) {
            val a = ctx.ask("Let me look through the files on your phone? Android will show a switch called \"Allow access to manage all files\". You can turn it off any time.", listOf("Open settings", "Not now"))
            if (a != "Open settings") return if (a == null) "No access to the phone's files and nobody is around to allow it." else "The user didn't allow access to their files. Use files_pick to let them choose a file instead."
            openAllFilesSettings(context)
            for (i in 0 until 90) { delay(1000); if (phoneFilesAllowed()) break }
            if (!phoneFilesAllowed()) return "Access not given yet. Ask the user to switch it on, or use files_pick."
            ctx.activity("You allowed access to your files", JSONObject().put("tool", "files"))
        }
        val limit = input.optInt("limit", 30).coerceIn(1, 200)
        val base = input.optString("folder").trim().trim('/').takeIf { it.isNotBlank() }?.let { File(root, it) } ?: root
        if (!base.exists()) return "No folder ${base.absolutePath}. Top-level folders: " + (root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.joinToString { it.name } ?: "none")
        return withContext(Dispatchers.IO) {
            when (input.optString("action").lowercase()) {
                "list", "ls" -> {
                    // Hidden folders matter here (WhatsApp keeps statuses in .Statuses), so they're listed too.
                    val kids = base.listFiles()?.filter { it.name != ".thumbnails" }?.sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() }).orEmpty()
                    ctx.activity("Looked in ${base.relativeTo(root).path.ifBlank { "phone storage" }}", JSONObject().put("tool", "files"))
                    if (kids.isEmpty()) "Empty: ${base.absolutePath}" else kids.take(limit).joinToString("\n", "${base.absolutePath} (${kids.size} items, newest first):\n") { line(it) }
                }
                else -> {
                    val words = input.optString("query").lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (words.isEmpty()) return@withContext "Say what to look for, e.g. query 'cv' or '.pdf'."
                    // Everything readable, hidden folders included (WhatsApp statuses live in .Statuses under Android/media).
                    // Only app-private Android/data and Android/obb (unreadable anyway) and thumbnail caches are skipped.
                    val skip = setOf(File(root, "Android/data").absolutePath, File(root, "Android/obb").absolutePath)
                    val hits = base.walkTopDown().onEnter { d -> d.absolutePath !in skip && d.name != ".thumbnails" && !d.name.startsWith(".trash") && !d.name.startsWith(".Trash") }
                        .filter { it.isFile && words.all { w -> it.name.lowercase().contains(w) } }.take(2000).toList()
                        .sortedByDescending { it.lastModified() }
                    ctx.activity("Searched your phone for \"${words.joinToString(" ")}\" · ${hits.size} found", JSONObject().put("tool", "files"))
                    if (hits.isEmpty()) "Nothing on the phone named like \"${words.joinToString(" ")}\"." else hits.take(limit).joinToString("\n", "${hits.size} found, newest first:\n") { line(it) }
                }
            }
        }
    }
}
