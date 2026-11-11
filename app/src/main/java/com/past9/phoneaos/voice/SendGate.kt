package com.past9.phoneaos.voice

/**
 * Holds messages back until the connection is ready, then lets them through in order. The live session opens the
 * microphone before it has anything to connect to; what is said meanwhile waits here (up to [max] pieces, the oldest
 * dropped first) and goes out, in order, the moment [open] is called. Closed again on a reconnect.
 */
class SendGate(private val max: Int = 100) {
    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private var open = false

    /** How many messages are waiting. */
    val waiting: Int get() = synchronized(lock) { queue.size }

    /** Sends [msg] now when open, else keeps it. Sending happens inside the lock so a flush and a live message never swap places. */
    fun submit(msg: String, send: (String) -> Unit) {
        synchronized(lock) {
            if (!open) { queue.addLast(msg); while (queue.size > max) queue.removeFirst() } else send(msg)
        }
    }

    /** Sends everything that waited, in order, then lets later messages straight through. */
    fun open(send: (String) -> Unit) {
        synchronized(lock) { queue.forEach(send); queue.clear(); open = true }
    }

    /** Back to keeping messages (the connection dropped and is being reopened). */
    fun close() { synchronized(lock) { open = false } }
}
