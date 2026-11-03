package com.past9.phoneaos.remote

import java.io.File
import java.io.RandomAccessFile

/**
 * What the computer said a file it showed is: its name, what it is, and how many bytes (the row's meta, and every
 * media.fetch answer).
 */
data class ShownFile(
    val chat: String,
    val token: String,
    val name: String,
    val mime: String,
    val kind: String,
    val size: Long,
)

/**
 * What a Range header asks for: the whole file, one part of it, or something the file does not have (416).
 * RFC 7233 as a player sends it: "bytes=0-99", "bytes=100-" (from there to the end), "bytes=-100" (the last 100).
 */
sealed interface MediaRange {
    data object All : MediaRange
    data class Part(val from: Long, val to: Long) : MediaRange
    data object Bad : MediaRange

    companion object {
        fun of(header: String?, size: Long): MediaRange {
            val text = header?.trim().orEmpty()
            if (text.isEmpty()) return if (size > 0) All else Bad
            val m = Regex("^bytes=(\\d*)-(\\d*)$").find(text) ?: return Bad
            val from = m.groupValues[1]
            val to = m.groupValues[2]
            if (from.isEmpty() && to.isEmpty()) return Bad
            if (from.isEmpty()) {
                val last = to.toLongOrNull() ?: return Bad
                if (last <= 0 || size <= 0) return Bad
                return Part(maxOf(0, size - last), size - 1)
            }
            val start = from.toLongOrNull() ?: return Bad
            if (start < 0 || start >= size) return Bad
            val end = if (to.isEmpty()) size - 1 else (to.toLongOrNull() ?: return Bad).coerceAtMost(size - 1)
            if (end < start) return Bad
            return Part(start, end)
        }
    }
}

/**
 * A video, picture or sound an agent showed on the computer, as this phone holds it.
 *
 * The bytes come over the link that is already open (media.fetch), one chunk at a time, and every chunk that arrives
 * is written into the cache file, so a player that scrubs back costs nothing and a picture is fetched once. Nothing is
 * downloaded until something asks for it: pressing play starts on the first chunk instead of after a whole film.
 *
 * Nothing here trusts the computer's answer blindly: a chunk that is not the length that was asked for is refused
 * (a file that changed on the computer, or a broken answer), so a player never reads half a frame as if it were one.
 */
class MediaFiles(private val dir: File, private val chunk: Long = CHUNK) {

    companion object {
        /** What one ask carries: a film needs few calls, and a phone on mobile data is not asked for more at once. */
        const val CHUNK = 512L * 1024

        /** How many chunks a file of [size] bytes is read in. */
        fun chunksFor(size: Long): Int = if (size <= 0L) 0 else (((size - 1) / CHUNK) + 1).toInt()

        /** A name safe for this phone's cache: the token is already safe, the file's own name may not be. */
        fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(48).ifBlank { "file" }
    }

    private val held = java.util.concurrent.ConcurrentHashMap<String, Held>()

    /** One file: where its bytes go here, and which of them have arrived. */
    inner class Held internal constructor(val info: ShownFile, val path: File) {
        private val arrived = java.util.BitSet(count(info.size))
        private fun count(size: Long): Int = if (size <= 0L) 0 else (((size - 1) / chunk) + 1).toInt()

        fun has(index: Int): Boolean = synchronized(arrived) { arrived.get(index) }
        internal fun mark(index: Int) = synchronized(arrived) { arrived.set(index) }
        fun count(): Int = count(info.size)

        /** Every byte of it is on this phone. */
        val whole: Boolean get() = synchronized(arrived) { arrived.cardinality() == count(info.size) }
    }

    /** Remember what a file is, and where its bytes will go. The same token and size answers the same file. */
    fun note(info: ShownFile): Held {
        held[info.token]?.let { if (it.info.size == info.size && it.info.chat == info.chat) return it }
        dir.mkdirs()
        val path = File(dir, info.token + "-" + safeName(info.name))
        if (path.exists() && path.length() != info.size) runCatching { path.delete() }
        runCatching { RandomAccessFile(path, "rw").use { it.setLength(info.size) } }
        val fresh = Held(info, path)
        held[info.token] = fresh
        return fresh
    }

    fun heldFor(token: String): Held? = held[token]

    /**
     * One range of the file, from the cache where it has arrived and from the computer for the rest. Null when the
     * computer could not answer, or answered something that is not the bytes that were asked for.
     */
    suspend fun bytes(held: Held, offset: Long, length: Int, ask: suspend (Long, Int) -> ByteArray?): ByteArray? {
        val size = held.info.size
        if (offset < 0 || length <= 0 || offset + length > size) return null
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val at = offset + done
            val index = (at / chunk).toInt()
            val start = index * chunk
            if (!held.has(index)) {
                val want = minOf(chunk, size - start).toInt()
                val got = ask(start, want) ?: return null
                if (got.size != want) return null
                if (!writeAt(held.path, start, got)) return null
                held.mark(index)
            }
            val inside = (at - start).toInt()
            val take = minOf(length - done, (chunk - inside).toInt())
            val part = readAt(held.path, at, take) ?: return null
            System.arraycopy(part, 0, out, done, take)
            done += take
        }
        return out
    }

    /** Every byte of it, for something that needs the whole file at once (a picture). */
    suspend fun whole(held: Held, ask: suspend (Long, Int) -> ByteArray?): Boolean {
        var at = 0L
        while (at < held.info.size) {
            val length = minOf(chunk, held.info.size - at).toInt()
            if (bytes(held, at, length, ask) == null) return false
            at += length
        }
        return true
    }

    /** Forget everything: the cache is emptied when the app is done with it. */
    fun forget() {
        held.clear()
        runCatching { dir.deleteRecursively() }
    }

    private fun writeAt(file: File, offset: Long, bytes: ByteArray): Boolean = runCatching {
        RandomAccessFile(file, "rw").use { it.seek(offset); it.write(bytes) }
        true
    }.getOrDefault(false)

    private fun readAt(file: File, offset: Long, length: Int): ByteArray? = runCatching {
        val out = ByteArray(length)
        RandomAccessFile(file, "r").use {
            it.seek(offset)
            var read = 0
            while (read < length) {
                val got = it.read(out, read, length - read)
                if (got <= 0) break
                read += got
            }
            if (read < length) null else out
        }
    }.getOrNull()
}
