package com.past9.phoneaos.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/** The seam between the camera card and CameraX, so the card can be driven without hardware. */
interface CameraBinder {
    /** Opens the camera for [owner]; returns the handle (zoom, flash) or null when there is none to give. */
    suspend fun bind(owner: LifecycleOwner, front: Boolean, surface: Preview.SurfaceProvider, capture: ImageCapture): Camera?

    /** Releases the camera. Main thread, synchronous: when this returns the camera is no longer bound. */
    fun unbind()
}

class CameraXBinder(private val ctx: Context) : CameraBinder {
    private var provider: ProcessCameraProvider? = null

    override suspend fun bind(owner: LifecycleOwner, front: Boolean, surface: Preview.SurfaceProvider, capture: ImageCapture): Camera {
        val p = CameraWarm.provider(ctx)
        // The card may have closed while the provider was loading; never bind to a lifecycle that is already gone.
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) throw kotlinx.coroutines.CancellationException("camera card closed")
        provider = p
        p.unbindAll()
        val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build().also { it.surfaceProvider = surface }
        return p.bindToLifecycle(owner, sel, preview, capture)
    }

    override fun unbind() {
        runCatching { provider?.unbindAll() }
    }
}

/**
 * The lifecycle the camera is bound to: it follows the host (so the camera still stops when the app leaves the screen)
 * but ends the moment the card is closed, so CameraX releases the camera even if an explicit unbind never ran.
 */
class CardLifecycle(private val host: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private var dead = false
    private val follow = LifecycleEventObserver { _, _ -> if (!dead) registry.currentState = host.currentState }

    init {
        registry.currentState = host.currentState
        host.addObserver(follow)
    }

    override val lifecycle: Lifecycle get() = registry

    fun destroy() {
        if (dead) return
        dead = true
        host.removeObserver(follow)
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
