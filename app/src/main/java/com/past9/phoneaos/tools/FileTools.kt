package com.past9.phoneaos.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** The open screen shows Android's file picker when the agent asks for files. */
object FilePickBroker {
    class Request(val mimeTypes: Array<String>, val result: CompletableDeferred<List<Uri>> = CompletableDeferred())
    val pending = MutableStateFlow<Request?>(null)
    fun answer(r: Request, uris: List<Uri>) { r.result.complete(uris); if (pending.value === r) pending.value = null }
}

/**
 * Reads files the user picks (a PDF statement, a CSV, notes). Android's picker means the agent
 * only ever sees exactly the files the user chose; nothing else on the phone.
 */
class FilesPickTool(private val context: Context) : Tool {
    override val spec = ToolSpec("files_pick", "Ask the user to pick one or more files from their phone (documents, notes, spreadsheets, photos) and read them. You only see what they choose.",
        schema(listOf("reason"), "reason" to str("What you need, e.g. 'your latest bank statement'")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val a = ctx.ask("Pick ${input.optString("reason")} for me?", listOf("Choose files", "Not now"))
        if (a != "Choose files") return "The user didn't pick any files."
        val r = FilePickBroker.Request(arrayOf("*/*")); FilePickBroker.pending.value = r
        ctx.notify("Pick a file", "Open the app to choose ${input.optString("reason")}")
        val uris = withTimeoutOrNull(300_000) { r.result.await() }.orEmpty()
        if (uris.isEmpty()) return "No files picked."
        return withContext(Dispatchers.IO) { uris.map { read(it) }.map { ocrPages(it) }.joinToString("\n\n") }.also { ctx.activity("Read ${uris.size} file${if (uris.size > 1) "s" else ""} you picked", JSONObject().put("tool", "files")) }
    }

    /** A document the user attached in chat: its text (OCR'd if scanned) plus any page images. */
    suspend fun readDocument(uri: Uri): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val text = ocrPages(read(uri))
        val imgs = com.past9.phoneaos.agent.imageMarker.findAll(text).map { it.groupValues[1] }.toList()
        com.past9.phoneaos.agent.imageMarker.replace(text, "").trim() to imgs
    }

    /** Keep a copy in the workspace so the agent can upload the original file somewhere later. */
    suspend fun keepCopy(uri: Uri, name: String): java.io.File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = java.io.File(context.filesDir, "workspace/attachments").apply { mkdirs() }
            val f = java.io.File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }; f
        }.getOrNull()
    }

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c -> c.moveToFirst(); c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"

    private fun read(uri: Uri): String {
        val cr = context.contentResolver
        val (name, size) = cr.query(uri, null, null, null, null)?.use { c ->
            c.moveToFirst(); c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
        } ?: ("file" to 0L)
        val type = cr.getType(uri).orEmpty().ifBlank { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()).orEmpty() }
        val head = "## $name ($type, ${size / 1024} KB)"
        return when {
            type.startsWith("text/") || type.contains("json") || type.contains("csv") || type.contains("xml") || name.endsWith(".md") ->
                head + "\n" + (cr.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: "").take(30_000)
            type == "application/pdf" -> head + "\n" + pdfText(uri).let { t ->
                // A scanned PDF has no text layer: show the model the pages as pictures instead.
                if (t.startsWith("(This PDF has no readable text")) "Scanned PDF, here are its pages:\n" + pdfPages(uri).joinToString("\n") { "[[image:$it]]" } else t
            }
            type.startsWith("image/") -> {
                val f = java.io.File(context.filesDir, "uploads/pick-${System.nanoTime()}.jpg").apply { parentFile?.mkdirs() }
                cr.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                "$head\n[[image:${f.path}]]"
            }
            // Word documents: the text lives in word/document.xml inside the zip.
            name.endsWith(".docx", true) || type.contains("wordprocessingml") -> head + "\n" + runCatching {
                java.util.zip.ZipInputStream(cr.openInputStream(uri)!!).use { z ->
                    generateSequence { z.nextEntry }.firstOrNull { it.name == "word/document.xml" }?.let {
                        z.readBytes().decodeToString().replace("</w:p>", "\n").replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    }.orEmpty()
                }
            }.getOrDefault("").take(30_000)
            else -> "$head\n(Can't read this kind of file as text.)"
        }
    }

    /** Every picture also gets its words read on the phone, so text-only models can use it too. */
    private suspend fun ocrPages(chunk: String): String {
        val imgs = com.past9.phoneaos.agent.imageMarker.findAll(chunk).map { it.groupValues[1] }.toList()
        if (imgs.isEmpty()) return chunk
        val text = imgs.mapIndexed { i, p -> val t = Ocr.text(p); if (t.isBlank()) "" else "--- page/image ${i + 1} text (read on the phone) ---\n$t" }.filter { it.isNotBlank() }
        return chunk + if (text.isNotEmpty()) "\n" + text.joinToString("\n").take(30_000) else ""
    }

    /** Render up to 3 pages of a PDF to JPEGs the model can look at. */
    private fun pdfPages(uri: Uri): List<String> = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { r ->
                (0 until minOf(6, r.pageCount)).map { i ->
                    r.openPage(i).use { pg ->
                        val scale = 1400f / maxOf(pg.width, pg.height)
                        val bmp = android.graphics.Bitmap.createBitmap((pg.width * scale).toInt(), (pg.height * scale).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        java.io.File(context.filesDir, "uploads/pdf-${System.nanoTime()}-$i.jpg").apply { parentFile?.mkdirs(); outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }.path
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    /** PDFs: pull the text layer out of the raw stream (works for most statements and invoices). */
    private fun pdfText(uri: Uri): String {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        val out = StringBuilder()
        val streams = Regex("stream\\r?\\n").findAll(raw.decodeToString(throwOnInvalidSequence = false)).map { it.range.last + 1 }.toList()
        for (start in streams) {
            val endIdx = indexOf(raw, "endstream".toByteArray(), start).takeIf { it > 0 } ?: continue
            val chunk = raw.copyOfRange(start, endIdx)
            val text = runCatching { java.util.zip.InflaterInputStream(chunk.inputStream()).readBytes().decodeToString() }.getOrElse { chunk.decodeToString() }
            Regex("\\(((?:\\\\.|[^\\\\)])*)\\)\\s*Tj|\\[(.*?)]\\s*TJ").findAll(text).forEach { m ->
                val s = m.groupValues[1].ifEmpty { Regex("\\(((?:\\\\.|[^\\\\)])*)\\)").findAll(m.groupValues[2]).joinToString("") { it.groupValues[1] } }
                out.append(s.replace("\\(", "(").replace("\\)", ")")).append(' ')
            }
            if (text.contains("ET")) out.append('\n')
            if (out.length > 30_000) break
        }
        return out.toString().replace(Regex("[ \\t]+"), " ").trim().ifBlank { "(This PDF has no readable text layer; it may be a scan.)" }.take(30_000)
    }

    private fun indexOf(a: ByteArray, b: ByteArray, from: Int): Int {
        outer@ for (i in from..a.size - b.size) { for (j in b.indices) if (a[i + j] != b[j]) continue@outer; return i }
        return -1
    }
}
