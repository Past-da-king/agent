package com.past9.phoneaos.remote

import java.io.File
import java.security.MessageDigest
import java.util.Properties

data class ReceiveResult(val status: String, val bytes: Long, val path: String? = null, val nextIndex: Int? = null)

/** One durable, ordered transfer per id. A repeated chunk reports the current resume point. */
class ReceiveFileStore(private val root: File, private val save: (String, File) -> String) {
    companion object { private val lock = Any() }
    fun accept(id: String, index: Int, total: Int?, data: ByteArray, name: String?, size: Long?, sha256: String?): ReceiveResult = synchronized(lock) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid transferId" }
        require(index >= 0 && data.size <= 1024 * 1024) { "Invalid chunk" }
        root.mkdirs()
        val meta = File(root, "$id.properties")
        val part = File(root, "$id.part")
        val p = Properties()
        if (meta.exists()) meta.inputStream().use { p.load(it) }
        else {
            require(index == 0 && total != null && total in 1..100000 && size != null && size >= 0 && sha256 != null && sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "First chunk needs total, size and sha256" }
            val safeName = name?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: error("First chunk needs a name")
            p.setProperty("name", safeName)
            p.setProperty("total", total.toString())
            p.setProperty("size", size.toString())
            p.setProperty("sha256", sha256.lowercase())
            p.setProperty("next", "0")
            p.setProperty("bytes", "0")
            meta.outputStream().use { p.store(it, null) }
        }
        if (p.getProperty("status") == "received") return@synchronized ReceiveResult("received", p.getProperty("bytes").toLong(), p.getProperty("path"))
        if (index == 0 && (name != p.getProperty("name") || size != p.getProperty("size").toLong() || sha256?.lowercase() != p.getProperty("sha256") || total != p.getProperty("total").toInt())) error("Transfer metadata does not match")
        val next = p.getProperty("next").toInt()
        val expectedBytes = p.getProperty("bytes").toLong()
        if (index != next) return@synchronized ReceiveResult("partial", expectedBytes, nextIndex = next)
        require(index < p.getProperty("total").toInt()) { "Invalid chunk index" }
        if (part.exists() && part.length() != expectedBytes) java.io.RandomAccessFile(part, "rw").use { it.setLength(expectedBytes) }
        require(expectedBytes + data.size <= p.getProperty("size").toLong()) { "File exceeds declared size" }
        part.appendBytes(data)
        val bytes = expectedBytes + data.size
        val following = next + 1
        if (following < p.getProperty("total").toInt()) {
            p.setProperty("next", following.toString())
            p.setProperty("bytes", bytes.toString())
            meta.outputStream().use { p.store(it, null) }
            return@synchronized ReceiveResult("partial", bytes, nextIndex = following)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (bytes != p.getProperty("size").toLong() || actual != p.getProperty("sha256")) {
            meta.delete(); part.delete()
            return@synchronized ReceiveResult("hash_mismatch", bytes)
        }
        val path = save(p.getProperty("name"), part)
        p.setProperty("status", "received")
        p.setProperty("bytes", bytes.toString())
        p.setProperty("path", path)
        meta.outputStream().use { p.store(it, null) }
        part.delete()
        ReceiveResult("received", bytes, path)
    }
}
