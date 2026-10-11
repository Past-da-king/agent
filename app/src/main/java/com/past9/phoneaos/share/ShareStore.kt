package com.past9.phoneaos.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.past9.phoneaos.camera.CameraPhoto
import java.io.File

/** One shared item once it is safely in app storage. Photos are already shrunk like an attached photo. */
data class Prepared(
    val kind: ShareKind,
    val name: String,
    val path: String,
    val mime: String,
    val size: Long,
    val durationMs: Long = 0,
) {
    fun asFile() = SharedFile(name, path, mime, size, kind, durationMs)
}

/** The Android side of sharing: reading the intent, copying out of content:// uris, probing media. */
object ShareStore {
    const val MAX_ITEMS = 20

    fun parse(i: Intent): ShareInput {
        val multiple = i.action == Intent.ACTION_SEND_MULTIPLE
        val streams = buildList {
            if (multiple) IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { addAll(it) }
            else IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { add(it) }
            val clip = i.clipData
            if (clip != null) for (n in 0 until clip.itemCount) clip.getItemAt(n).uri?.let { add(it) }
        }.map { it.toString() }.distinct().take(MAX_ITEMS)
        return ShareInput(
            multiple = multiple, type = i.type,
            text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            subject = (i.getStringExtra(Intent.EXTRA_SUBJECT) ?: i.getStringExtra(Intent.EXTRA_TITLE)),
            streams = streams,
        )
    }

    fun isMetered(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    private fun displayName(cr: ContentResolver, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.lastPathSegment
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    /** Copy one shared uri into app storage right now (the read grant ends with the share). Null when it cannot be read. */
    fun import(ctx: Context, uriText: String, index: Int, stamp: Long): Prepared? {
        val uri = Uri.parse(uriText)
        val cr = ctx.contentResolver
        val name = displayName(cr, uri) ?: "file"
        val mime = ShareLogic.mimeFor(runCatching { cr.getType(uri) }.getOrNull(), name)
        val kind = ShareLogic.kindOf(mime, name)
        if (kind == ShareKind.IMAGE) {
            CameraPhoto.compressUri(ctx, uri, "share-$stamp-$index")?.let { p ->
                return Prepared(kind, name, p, "image/jpeg", File(p).length())
            }
        }
        val dir = File(ctx.filesDir, "shared").apply { mkdirs() }
        val out = File(dir, ShareLogic.storedName(stamp, name, index))
        val ok = runCatching { cr.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }; true }.getOrDefault(false)
        if (!ok) { out.delete(); return null }
        val duration = if (kind == ShareKind.AUDIO || kind == ShareKind.VIDEO) durationMs(out.path) else 0L
        return Prepared(kind, name, out.path, mime, out.length(), duration)
    }

    fun durationMs(path: String): Long = runCatching {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(path); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { r.release() }
    }.getOrDefault(0L)

    /** A small picture for a video, from its first second. */
    fun videoThumb(path: String, edge: Int = 480): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val f = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0) ?: return@runCatching null
            val (w, h) = CameraPhoto.scaledSize(f.width, f.height, edge)
            if (w == f.width) f else Bitmap.createScaledBitmap(f, w, h, true)
        } finally { r.release() }
    }.getOrNull()
}
