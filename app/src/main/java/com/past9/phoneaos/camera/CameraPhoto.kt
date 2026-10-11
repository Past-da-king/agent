package com.past9.phoneaos.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File

/** Shrinking a camera photo before it goes to the agent, to save mobile data. */
object CameraPhoto {
    const val MAX_EDGE = 1600
    const val QUALITY = 85

    /** The size to shrink [w] x [h] to so the long edge is at most [maxEdge]. Aspect kept, never upscaled. */
    fun scaledSize(w: Int, h: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 1 to 1
        val long = maxOf(w, h)
        if (long <= maxEdge) return w to h
        val s = maxEdge.toFloat() / long
        return Math.round(w * s).coerceAtLeast(1) to Math.round(h * s).coerceAtLeast(1)
    }

    /**
     * Reads the raw capture (EXIF rotation is applied by the decoder, and the front camera's mirroring was
     * written into the file at capture), shrinks it, and saves a JPEG under uploads/ like a gallery photo.
     * Returns the new path, or null if the file could not be read. The raw capture is deleted.
     */
    fun compress(ctx: Context, raw: File): String? = runCatching { write(ctx, ImageDecoder.createSource(raw)) }.also { raw.delete() }.getOrNull()

    /** Same shrink for a photo shared in from another app (a content:// uri). Returns the new path, or null if it could not be read. */
    fun compressUri(ctx: Context, uri: android.net.Uri, name: String = "${System.currentTimeMillis()}"): String? =
        runCatching { write(ctx, ImageDecoder.createSource(ctx.contentResolver, uri), name) }.getOrNull()

    private fun write(ctx: Context, source: ImageDecoder.Source, name: String = "${System.currentTimeMillis()}"): String {
        val bmp: Bitmap = ImageDecoder.decodeBitmap(source) { d, info, _ ->
            val (w, h) = scaledSize(info.size.width, info.size.height)
            d.setTargetSize(w, h); d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = File(ctx.filesDir, "uploads/$name.jpg").apply { parentFile?.mkdirs() }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        return out.path
    }
}
