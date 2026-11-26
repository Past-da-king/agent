package com.past9.phoneaos.remote

import java.util.concurrent.atomic.AtomicBoolean

/** One counter for work that must finish on its current connection. */
class InFlight {
    private val lock = Any()
    private var active = 0
    val count: Int get() = synchronized(lock) { active }
    val isBusy: Boolean get() = count > 0

    fun acquire(): Lease = synchronized(lock) {
        active++
        Lease { synchronized(lock) { active-- } }
    }

    /** Registering work and deciding whether to replace its connection use the same lock.
     * The block must be short and must not suspend or perform network I/O. */
    fun <T> guarded(block: () -> T): T = synchronized(lock) { block() }

    suspend fun <T> during(block: suspend () -> T): T {
        val lease = acquire()
        return try { block() } finally { lease.close() }
    }

    class Lease internal constructor(private val end: () -> Unit) : AutoCloseable {
        private val ended = AtomicBoolean()
        /** True once only; callers may use it to release other resources exactly once. */
        fun release(): Boolean {
            if (!ended.compareAndSet(false, true)) return false
            end()
            return true
        }
        override fun close() { release() }
    }
}
