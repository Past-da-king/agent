package com.past9.phoneaos

import com.past9.phoneaos.remote.InFlight
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class InFlightTest {
    @Test fun leasesReleaseExactlyOnceEvenAfterAnException() = runBlocking {
        val work = InFlight()
        val lease = work.acquire()
        assertEquals(1, work.count)
        assertTrue(lease.release())
        assertFalse(lease.release())
        lease.close()
        assertEquals(0, work.count)
        try { work.during { assertTrue(work.isBusy); error("failed") } } catch (_: IllegalStateException) {}
        assertFalse(work.isBusy)
    }
    @Test fun cancellationReleasesTheLease() = runBlocking {
        val work = InFlight()
        val started = CompletableDeferred<Unit>()
        val task = launch { work.during { started.complete(Unit); awaitCancellation() } }
        started.await()
        assertEquals(1, work.count)
        task.cancelAndJoin()
        assertEquals(0, work.count)
    }
    @Test fun guardedSelectionAndNewWorkCannotPassEachOther() {
        val work = InFlight()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val selection = pool.submit { work.guarded { assertFalse(work.isBusy); inside.countDown(); release.await(5, TimeUnit.SECONDS) } }
            assertTrue(inside.await(5, TimeUnit.SECONDS))
            val command = pool.submit { work.acquire().use { acquired.countDown() } }
            assertFalse("cannot register midway through socket replacement", acquired.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            selection.get(5, TimeUnit.SECONDS)
            command.get(5, TimeUnit.SECONDS)
            assertEquals(0, work.count)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
