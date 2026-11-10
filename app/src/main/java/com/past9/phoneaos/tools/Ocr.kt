package com.past9.phoneaos.tools

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reads the words in a picture on the phone itself (ML Kit, offline, free). Scanned PDFs, photos
 * of documents and screenshots become plain text, so even models that can't see images can use them.
 */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Where [words] are written in the picture: the middle of that line, in the picture's pixels. Null when they are not there. */
    suspend fun find(path: String, words: String): Pair<Int, Int>? {
        val bmp = BitmapFactory.decodeFile(path) ?: return null
        val want = words.trim(); if (want.isEmpty()) return null
        return suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { t ->
                    val lines = t.textBlocks.flatMap { it.lines }
                    val hit = lines.firstOrNull { it.text.trim().equals(want, true) } ?: lines.firstOrNull { it.text.contains(want, true) }
                    val box = hit?.elements?.takeIf { want.none { c -> c == ' ' } }?.firstOrNull { it.text.contains(want, true) }?.boundingBox ?: hit?.boundingBox
                    if (cont.isActive) cont.resume(box?.let { it.centerX() to it.centerY() })
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    }

    suspend fun text(path: String): String {
        val bmp = BitmapFactory.decodeFile(path) ?: return ""
        return suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                .addOnFailureListener { if (cont.isActive) cont.resume("") }
        }
    }
}
