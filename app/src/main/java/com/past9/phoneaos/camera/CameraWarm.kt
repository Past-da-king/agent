package com.past9.phoneaos.camera

import android.content.Context
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Starts CameraX's provider early (no binding, no permission needed) and hands it over without blocking the main thread. */
object CameraWarm {
    @Volatile private var future: ListenableFuture<ProcessCameraProvider>? = null

    fun warm(ctx: Context) {
        if (future == null) synchronized(this) { if (future == null) future = ProcessCameraProvider.getInstance(ctx.applicationContext) }
    }

    suspend fun provider(ctx: Context): ProcessCameraProvider = withContext(Dispatchers.IO) { warm(ctx); future!!.get() }
}
