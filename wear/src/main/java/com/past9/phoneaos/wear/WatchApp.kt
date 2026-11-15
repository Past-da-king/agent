package com.past9.phoneaos.wear

import android.app.Application
import kotlinx.coroutines.launch

class WatchApp : Application() {
    val controller: WatchController by lazy { WatchController(this) }
    /** A call with the agent from the watch (docs/wear.md, "Call"). */
    val call: WatchCall by lazy { WatchCall(this, controller.link, controller.scope) }
    private val startup = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Process start does no work on the main thread: the last picture is read and the gesture watchers are set up on a worker, so the
        // first frame of the activity (or the service that woke the process) is not waiting for them.
        startup.launch {
            WatchStore.cached(this@WatchApp)
            // Gestures follow what is going on: a call or a ring is ongoing, and keeps the sensors on that can end or dismiss it.
            launch { call.state.collect { WatchGestures.refresh(this@WatchApp) } }
            launch { WatchRing.ringing.collect { WatchGestures.refresh(this@WatchApp) } }
        }
    }
}
