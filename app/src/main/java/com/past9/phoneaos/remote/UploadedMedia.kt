package com.past9.phoneaos.remote

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What this phone already holds of what it sent: a picture or file uploaded to the computer is still on the phone, so
 * the chat draws that copy instead of downloading it back over the link (30 Oct 2026: on mobile data a just-sent photo
 * showed as loading for ever, because a picture on the computer is only fetched on Wi-Fi or when he taps it).
 */
object UploadedMedia {
    private val paths = ConcurrentHashMap<String, String>()

    /** [pathOnComputer] is now this phone too, at [localPath]. */
    fun remember(pathOnComputer: String, localPath: String) {
        if (pathOnComputer.isBlank() || localPath.isBlank()) return
        paths[pathOnComputer] = localPath
    }

    /** The copy here, when it is still on disk. */
    fun local(pathOnComputer: String): String? = paths[pathOnComputer]?.takeIf { File(it).isFile && it.isNotBlank() }

    fun forget() = paths.clear()
}
