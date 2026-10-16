package com.past9.phoneaos.share

import java.util.Locale

/** What a shared item is, for the preview and for what the agent is told to do with it. */
enum class ShareKind { LINK, TEXT, IMAGE, VIDEO, AUDIO, PDF, FILE }

/** Everything pulled out of a share intent that does not need Android: strings only, so it can be tested. */
data class ShareInput(
    val multiple: Boolean,
    val type: String?,
    val text: String?,
    val subject: String?,
    /** content:// or file:// uris, as strings. */
    val streams: List<String>,
)

/** A shared file after it was copied into app storage (the share's read grant ends with the activity). */
data class SharedFile(
    val name: String,
    val path: String,
    val mime: String,
    val size: Long,
    val kind: ShareKind,
    val durationMs: Long = 0,
)

/** The finished chat message: [text] is what the agent reads, [images] attach like a photo, [fileNames] are the chips under the bubble. */
data class ShareMessage(val text: String, val images: List<String>, val fileNames: List<String>)

object ShareLogic {
    /** Over this on mobile data, or any video, waits for Wi-Fi or a yes from the user. */
    const val BIG_BYTES = 15L * 1024 * 1024

    private val url = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val audioExt = setOf("opus", "ogg", "oga", "m4a", "mp3", "aac", "amr", "wav", "flac", "awb", "mka")
    private val videoExt = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v")
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")

    /** Links in a shared text, in order, without trailing sentence punctuation. */
    fun links(text: String?): List<String> = url.findAll(text.orEmpty()).map { it.value.trimEnd('.', ',', ';', ':', ')', ']', '!', '?') }.distinct().toList()

    /** The shared text with its links taken out (what is left to say beside the link). */
    fun textBesideLinks(text: String?): String = url.replace(text.orEmpty(), " ").replace(Regex("\\s+"), " ").trim()

    fun domain(link: String): String = link.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').removePrefix("www.")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** From the mime type, or the file name when the sending app gave a vague type (WhatsApp voice notes often come as application/octet-stream). */
    fun kindOf(mime: String?, name: String?): ShareKind {
        val m = mime.orEmpty().lowercase(Locale.ROOT)
        val ext = extension(name.orEmpty())
        return when {
            m.startsWith("image/") -> ShareKind.IMAGE
            m.startsWith("video/") -> ShareKind.VIDEO
            m.startsWith("audio/") || m == "application/ogg" -> ShareKind.AUDIO
            m == "application/pdf" -> ShareKind.PDF
            ext in audioExt -> ShareKind.AUDIO
            ext in videoExt -> ShareKind.VIDEO
            ext in imageExt -> ShareKind.IMAGE
            ext == "pdf" -> ShareKind.PDF
            else -> ShareKind.FILE
        }
    }

    /** A mime type for a file when the provider did not give a useful one. */
    fun mimeFor(mime: String?, name: String): String {
        val m = mime.orEmpty().lowercase(Locale.ROOT)
        if (m.isNotBlank() && m != "*/*" && m != "application/octet-stream") return m
        return when (extension(name)) {
            "opus" -> "audio/ogg"; "ogg", "oga" -> "audio/ogg"; "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "aac" -> "audio/aac"
            "amr" -> "audio/amr"; "wav" -> "audio/wav"; "flac" -> "audio/flac"
            "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"; "webm" -> "video/webm"; "3gp" -> "video/3gpp"
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "heic" -> "image/heic"; "gif" -> "image/gif"
            "pdf" -> "application/pdf"; "txt" -> "text/plain"; "csv" -> "text/csv"
            else -> m.ifBlank { "application/octet-stream" }
        }
    }

    /** The share as a whole: a link, plain text, or the kind of the files when there are any. */
    fun primaryKind(input: ShareInput, fileKinds: List<ShareKind>): ShareKind = when {
        fileKinds.isNotEmpty() -> fileKinds.first()
        links(input.text).isNotEmpty() -> ShareKind.LINK
        else -> ShareKind.TEXT
    }

    /** A file name that is safe on any file system and short, extension kept. */
    fun safeName(original: String?): String {
        val base = original.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
        val ext = extension(base).replace(Regex("[^a-z0-9]"), "").take(8)
        var stem = (if (ext.isEmpty()) base else base.dropLast(base.length - base.lastIndexOf('.'))).replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.', '-')
        if (stem.length > 60) stem = stem.take(60)
        if (stem.isEmpty()) stem = "file"
        return if (ext.isEmpty()) stem else "$stem.$ext"
    }

    /** Stable name inside files/shared/. */
    fun storedName(timestamp: Long, original: String?, index: Int = 0): String = "$timestamp${if (index > 0) "-$index" else ""}-${safeName(original)}"

    /** True when this file must NOT be sent over mobile data without the user's say-so. */
    fun holdForWifi(sizeBytes: Long, isVideo: Boolean, metered: Boolean): Boolean = metered && (isVideo || sizeBytes > BIG_BYTES)

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    }

    fun formatDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** Evenly spaced moments (microseconds) to pull key frames from: the middle of each of [count] equal slices. */
    fun frameTimesUs(durationMs: Long, count: Int): List<Long> {
        val n = count.coerceIn(1, MAX_FRAMES)
        if (durationMs <= 0) return listOf(0L)
        return (0 until n).map { i -> (durationMs * 1000L * (2 * i + 1)) / (2L * n) }
    }
    const val MAX_FRAMES = 8

    /** Short noun for the visible line and the sheet. */
    fun label(kind: ShareKind, plural: Boolean = false): String = when (kind) {
        ShareKind.LINK -> if (plural) "links" else "a link"
        ShareKind.TEXT -> "some text"
        ShareKind.IMAGE -> if (plural) "photos" else "a photo"
        ShareKind.VIDEO -> if (plural) "videos" else "a video"
        ShareKind.AUDIO -> if (plural) "voice notes" else "a voice note"
        ShareKind.PDF -> if (plural) "PDFs" else "a PDF"
        ShareKind.FILE -> if (plural) "files" else "a file"
    }

    /**
     * The chat message. The first line is what the person sees in the bubble; everything after the
     * "[Shared with you" marker is for the agent only (the bubble hides it).
     */
    fun compose(
        note: String?, text: String?, subject: String?,
        files: List<SharedFile>, imagePaths: List<String>, metered: Boolean,
    ): ShareMessage {
        val links = links(text)
        val beside = textBesideLinks(text)
        val cleanNote = note.orEmpty().trim()
        val all = files.map { it.kind } + imagePaths.map { ShareKind.IMAGE }
        val visible = when {
            cleanNote.isNotEmpty() -> cleanNote
            links.isNotEmpty() && files.isEmpty() && imagePaths.isEmpty() -> text.orEmpty().trim()
            text.orEmpty().isNotBlank() && files.isEmpty() && imagePaths.isEmpty() -> text.orEmpty().trim()
            all.isNotEmpty() -> "Shared " + describe(all)
            else -> "Shared something with you."
        }
        val b = StringBuilder(visible)
        b.append("\n\n[Shared with you from another app. The first line above is what the user typed or what was shared. ")
        b.append(if (cleanNote.isEmpty()) "They gave no instruction: work out the most useful thing to do with it and do it, and ask one short question only if it is really unclear.]" else "Do what they asked about it.]")
        if (links.isNotEmpty()) {
            links.forEach { b.append("\nLink: ").append(it).append(" (").append(domain(it)).append(')') }
            if (!subject.isNullOrBlank()) b.append("\nPage title: ").append(subject.trim())
            if (beside.isNotEmpty()) b.append("\nText shared with it: ").append(beside)
            b.append("\nRead the page with web_fetch or the browser before answering about it.")
        } else if (!text.isNullOrBlank()) {
            if (!subject.isNullOrBlank()) b.append("\nSubject: ").append(subject.trim())
            b.append("\nShared text: ").append(text.trim())
        } else if (!subject.isNullOrBlank()) b.append("\nSubject: ").append(subject.trim())
        if (imagePaths.isNotEmpty()) b.append("\n").append(imagePaths.size).append(if (imagePaths.size == 1) " photo is attached to this message." else " photos are attached to this message.")
        var hold = false
        files.forEach { f ->
            val title = when (f.kind) { ShareKind.AUDIO -> "Voice note or audio"; ShareKind.VIDEO -> "Video"; ShareKind.PDF -> "PDF"; ShareKind.IMAGE -> "Image"; else -> "File" }
            b.append("\n").append(title).append(": ").append(f.name)
            b.append("\n  Path on the phone: ").append(f.path)
            b.append("\n  Type: ").append(f.mime).append(", ").append(formatSize(f.size))
            if (f.durationMs > 0) b.append(", ").append(formatDuration(f.durationMs)).append(" long")
            when (f.kind) {
                ShareKind.AUDIO -> b.append("\n  You cannot hear audio directly. Use media_transcribe with this path to get what is said, then act on it.")
                ShareKind.VIDEO -> b.append("\n  You cannot watch video directly. Use media_frames with this path to see a few frames, and media_transcribe to hear what is said.")
                ShareKind.PDF -> b.append("\n  Read it with run_code or ask for it again with files_pick; the original is at the path above.")
                else -> b.append("\n  The original file is at the path above; open it with run_code, or send it on with machine_upload or browser_upload.")
            }
            if (holdForWifi(f.size, f.kind == ShareKind.VIDEO, metered)) hold = true
        }
        if (hold) b.append("\nThe phone is on mobile data and this is a video or a big file. Do not upload it to any machine or website until the user says yes, or the phone is on Wi-Fi. Process it on the phone, or ask them first.")
        val names = files.map { it.name }
        return ShareMessage(b.toString(), imagePaths, names)
    }

    private fun describe(kinds: List<ShareKind>): String {
        val groups = kinds.groupBy { it }
        return if (groups.size == 1) { val (k, v) = groups.entries.first(); if (v.size == 1) label(k) else "${v.size} ${label(k, true)}" }
        else "${kinds.size} things"
    }

    /** The chat notice shown when a share is held back from mobile data. */
    fun holdNotice(files: List<SharedFile>, metered: Boolean): String? =
        if (files.any { holdForWifi(it.size, it.kind == ShareKind.VIDEO, metered) })
            "You are on mobile data, so I will keep that file on the phone and not upload it until you say yes or you are on Wi-Fi." else null
}
