package com.past9.phoneaos.wear

import android.app.Application

class WatchApp : Application() {
    val controller: WatchController by lazy { WatchController(this) }
    override fun onCreate() { super.onCreate(); WatchStore.cached(this) }
}
