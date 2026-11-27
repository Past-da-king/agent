package com.past9.phoneaos.remote

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.past9.phoneaos.App
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.system.AndroidPhone
import org.json.JSONObject
import java.io.File

class ReceiveFileTool(private val context: Context) : Tool {
    override val spec = ToolSpec("receive_file", "Send a file to this phone in ordered base64 chunks. Every call includes transferId, index and dataBase64; index 0 also includes name, size, sha256 and total. Resume at nextIndex.",
        schema(listOf("transferId", "index", "dataBase64"),
            "transferId" to str("Stable unique transfer ID"),
            "index" to JSONObject().put("type", "integer").put("description", "Zero-based chunk index"),
            "total" to JSONObject().put("type", "integer").put("description", "Number of chunks, required on first chunk"),
            "dataBase64" to str("Base64 encoded chunk, at most 1 MiB decoded"),
            "name" to str("File name, required on first chunk"),
            "size" to JSONObject().put("type", "integer").put("description", "Total bytes, required on first chunk"),
            "sha256" to str("SHA-256 hex digest, required on first chunk")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val data = android.util.Base64.decode(input.getString("dataBase64"), android.util.Base64.DEFAULT)
        val store = ReceiveFileStore(File(context.cacheDir, "received-transfers"), ::save)
        val result = store.accept(input.getString("transferId"), input.getInt("index"),
            if (input.has("total")) input.getInt("total") else null, data,
            if (input.has("name")) input.getString("name") else null,
            if (input.has("size")) input.getLong("size") else null,
            if (input.has("sha256")) input.getString("sha256") else null)
        return JSONObject().put("status", result.status).put("bytes", result.bytes)
            .apply { result.path?.let { put("path", it) }; result.nextIndex?.let { put("nextIndex", it) } }.toString()
    }

    private fun save(name: String, file: File): String {
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val uri: Uri = if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads is not available")
            try {
                resolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Cannot write to Downloads")
                resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { resolver.delete(target, null, null); throw e }
            target
        } else {
            val target = File(context.filesDir, "downloads/linked/" + name).apply { parentFile?.mkdirs() }
            file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = NotificationCompat.Builder(context, AndroidPhone.CH_MESSAGES)
            .setSmallIcon(com.past9.phoneaos.system.Identity.statIcon(context, App.graph(context).settings.state.value.mascot))
            .setContentTitle(name).setContentText(context.getString(com.past9.phoneaos.R.string.link_saved_downloads))
            .setContentIntent(pi).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(uri.hashCode(), notice) }
        return uri.toString()
    }
}
