package com.past9.phoneaos.voice

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The agent's voice on its way to the speaker. The model sends audio much faster than it plays, so it waits here and
 * a thread of its own feeds the speaker. Writing straight from the socket's thread blocked that thread behind seconds
 * of audio, so "the user is talking over you" was read late and the agent kept talking. Now an interrupt empties
 * the queue and the speaker at once, and "speaking" means sound is really coming out.
 */
class PlaybackQueue(private val sink: Sink, private val onSpeaking: (Boolean) -> Unit, private val onLevel: (Float) -> Unit = {}) {
    interface Sink {
        /** Plays [pcm]; may block while the speaker's buffer is full. */
        fun write(pcm: ByteArray)
        /** Drops whatever the speaker still holds, and makes a blocked [write] return. */
        fun flush()
    }

    private class Chunk(val epoch: Int, val pcm: ByteArray)

    private val queue = LinkedBlockingQueue<Chunk>()
    private val lock = Any()
    @Volatile private var open = true
    @Volatile private var epoch = 0
    private var turnDone = true
    private var speaking = false

    /** Chunks still waiting to be played. */
    val pending: Int get() = queue.count { it.pcm.isNotEmpty() }

    fun start() {
        thread(name = "aos-speaker", isDaemon = true) { while (open) runCatching { pump(200) } }
    }

    fun enqueue(pcm: ByteArray) {
        if (!open || pcm.isEmpty()) return
        synchronized(lock) { turnDone = false; queue.offer(Chunk(epoch, pcm)) }
    }

    /** The model has sent the whole reply: once the queue is played out, the agent is no longer speaking. */
    fun turnComplete() {
        synchronized(lock) { turnDone = true; queue.offer(Chunk(epoch, EMPTY)) }
    }

    /** The user talked over the agent: nothing queued is played, and the speaker goes quiet now. */
    fun interrupt() {
        val changed = synchronized(lock) { epoch++; queue.clear(); turnDone = true; mark(false) }
        runCatching { sink.flush() }
        if (changed) onSpeaking(false)
    }

    fun close() {
        open = false
        synchronized(lock) { epoch++; queue.clear(); turnDone = true; speaking = false }
    }

    /** One step of the speaker thread: plays the next chunk, or notices the reply has played out. */
    internal fun pump(waitMs: Long) {
        val c = queue.poll(waitMs, TimeUnit.MILLISECONDS)
        if (c != null && c.pcm.isNotEmpty()) {
            val started = synchronized(lock) { if (c.epoch != epoch) return; mark(true) }
            if (started) onSpeaking(true)
            runCatching { onLevel(LiveSession.loudness(c.pcm, c.pcm.size)) }
            if (c.epoch == epoch && open) runCatching { sink.write(c.pcm) }
        }
        val ended = synchronized(lock) { if (pending == 0 && turnDone) mark(false) else false }
        if (ended) { onLevel(0f); onSpeaking(false) }
    }

    /** Sets speaking; true when it changed. Call with [lock] held. */
    private fun mark(on: Boolean): Boolean { if (speaking == on) return false; speaking = on; return true }

    private companion object { val EMPTY = ByteArray(0) }
}
