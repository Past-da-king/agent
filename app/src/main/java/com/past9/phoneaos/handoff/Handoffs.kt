package com.past9.phoneaos.handoff

import android.content.Context
import com.past9.phoneaos.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * How a result from a machine reaches the phone, with no one in the middle and nothing running in the background: while the
 * app is open on screen it checks the user's own sync service once and then waits on it, so a result lands within a second.
 * When the app is closed, nothing runs and nothing drains the battery; whatever finished is waiting in the user's own log and
 * arrives the moment the app is opened. No push network, no periodic job, no foreground service.
 */
object Handoffs {
    @Volatile private var loop: Job? = null

    fun inbox(context: Context): HandoffInbox {
        val g = App.graph(context)
        return HandoffInbox(HandoffStore(g.settings), HandoffClient()) { note -> g.runtime.hearFromMachine(note) }
    }

    /** The app came on screen (or the user just signed in): catch up now, then wait for more. Safe to call again. */
    fun onScreen(context: Context) {
        val g = App.graph(context)
        loop?.cancel()
        if (!HandoffStore(g.settings).signedIn) return
        loop = g.scope.launch(Dispatchers.IO) {
            val inbox = inbox(context); var failures = 0
            while (isActive) {
                try { inbox.check(waitSec = if (failures == 0) 50 else 0); failures = 0 }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { failures++; delay(minOf(5 * 60_000L, 5_000L * (1L shl minOf(failures, 6)))) }
            }
        }
    }

    /** The app left the screen: stop completely. */
    fun offScreen() { loop?.cancel(); loop = null }
}
