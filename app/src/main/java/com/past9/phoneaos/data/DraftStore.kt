package com.past9.phoneaos.data

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Small text files saved off the main thread: each save is written to a temp name, synced and renamed, so a kill at any
 * moment leaves the last whole version. A save comes within [delayMs]; [flush] writes what is waiting now.
 */
class QuietSaver(private val delayMs: Long = 300) {
    private val pending = LinkedHashMap<File, String?>()
    private var scheduled = false
    private val pool: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aos-draft-saver").apply { isDaemon = true } }

    /** Save [text] to [file] soon; null deletes it. */
    fun save(file: File, text: String?) {
        synchronized(pending) {
            pending[file] = text
            if (scheduled) return
            scheduled = true
        }
        pool.schedule(::drain, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Write everything waiting, and wait a moment for it (leaving the screen, the app about to be killed). */
    fun flush(waitMs: Long = 250) {
        runCatching { pool.submit(::drain).get(waitMs, TimeUnit.MILLISECONDS) }
    }

    private fun drain() {
        val work = synchronized(pending) { scheduled = false; LinkedHashMap(pending).also { pending.clear() } }
        work.forEach { (f, t) -> runCatching { if (t == null) f.delete() else write(f, t) } }
    }

    companion object {
        fun write(f: File, text: String) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            FileOutputStream(tmp).use { o -> o.write(text.toByteArray()); o.fd.sync() }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}

/**
 * What each message box holds, on disk, one file per chat: "main", "chat:<id>", "reply:<card>". Nothing on screen owns
 * it. A reload, a reconnect, a rotation, a killed app or a restart finds the same words. Only an accepted send empties it.
 */
class DraftFiles(private val dir: File, private val saver: QuietSaver = QuietSaver()) {
    private val cells = HashMap<String, Cell>()

    private fun fileOf(key: String) = File(dir, URLEncoder.encode(key, "UTF-8") + ".txt")

    private inner class Cell(val key: String, first: String) : MutableState<String> {
        private val inner = mutableStateOf(first)
        override var value: String
            get() = inner.value
            set(v) {
                if (v == inner.value) return
                inner.value = v
                saver.save(fileOf(key), v.ifEmpty { null })
            }
        override fun component1() = value
        override fun component2(): (String) -> Unit = { value = it }
    }

    /** The one state for this chat's box, in this process. */
    fun cell(key: String): MutableState<String> = synchronized(cells) {
        cells.getOrPut(key) { Cell(key, runCatching { fileOf(key).takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()) }
    }

    /** Whether this chat's box holds words (read once: restoring an answer card's reply box after a restart). */
    fun has(key: String): Boolean = cell(key).value.isNotBlank()

    fun flush() = saver.flush()
}

/** The app's message boxes on disk. In-memory only until [init] runs (a test with no app). */
object DraftStore {
    @Volatile private var files: DraftFiles? = null
    private val scratch = HashMap<String, MutableState<String>>()

    fun init(dir: File) { if (files == null) files = DraftFiles(dir) }

    /** Tests: use this folder from now on, as a fresh process would. */
    fun openAt(dir: File) { files = DraftFiles(dir) }

    fun cell(key: String): MutableState<String> = files?.cell(key) ?: synchronized(scratch) { scratch.getOrPut(key) { mutableStateOf("") } }
    fun has(key: String): Boolean = cell(key).value.isNotBlank()
    fun flush() { files?.flush() }
}
