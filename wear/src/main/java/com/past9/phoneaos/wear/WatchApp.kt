package com.past9.phoneaos.wear

import android.app.Application
import kotlinx.coroutines.launch

class WatchApp : Application() {
    val controller: WatchController by lazy { WatchController(this) }
    /** A call with the agent from the watch (docs/wear.md, "Call"). */
    val call: WatchCall by lazy { WatchCall(this, controller.link, controller.scope) }

    override fun onCreate() {
        super.onCreate()
        WatchStore.cached(this)
        // Gestures follow what is going on: a call or a ring is ongoing, and keeps the sensors on that can end or dismiss it.
        controller.scope.launch { call.state.collect { WatchGestures.refresh(this@WatchApp) } }
        controller.scope.launch { WatchRing.ringing.collect { WatchGestures.refresh(this@WatchApp) } }
    }
}
