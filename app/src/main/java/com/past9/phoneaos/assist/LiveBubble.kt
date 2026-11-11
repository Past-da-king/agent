package com.past9.phoneaos.assist

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.past9.phoneaos.App
import com.past9.phoneaos.ui.theme.AppTheme
import kotlin.math.hypot

/**
 * The live voice bubble, drawn in its own window over other apps (the "display over other apps" permission). Drag it
 * anywhere; a circle appears at the bottom while dragging and letting go on it ends the session; tap it to open the
 * sheet again. Its content is [LiveBubbleContent] from the live state, so it always shows what the voice is doing.
 */
class LiveBubble(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val dm = context.resources.displayMetrics
    private val owner = Owner()
    private var bubble: android.view.View? = null
    private var target: ComposeView? = null
    private var over by mutableStateOf(false)

    private fun dp(v: Int) = (v * dm.density).toInt()

    private val bubbleParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(120) }

    private val targetParams = WindowManager.LayoutParams(
        dp(96), dp(96), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(48) }

    fun show() {
        if (bubble != null) return
        val st = App.graph(context).settings.state.value
        val v = DragView().also { frame ->
            frame.setViewTreeLifecycleOwner(owner); frame.setViewTreeSavedStateRegistryOwner(owner); frame.setViewTreeViewModelStoreOwner(owner)
            val dv = ComposeView(context)
            frame.addView(dv, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            dv.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            dv.setContent {
                AppTheme(accent = st.accent, mascot = st.mascot) {
                    val view by LiveHost.view.collectAsState()
                    LiveBubbleContent(view, onMute = { LiveHost.mute(it) }, onClose = { LiveHost.end("") },
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { LiveHost.expand(context) }) })
                }
            }
        }
        bubble = v
        runCatching { wm.addView(v, bubbleParams) }.onFailure { bubble = null }
    }

    /** Moves the bubble to ([x], [y]) on screen, kept fully visible, and works out whether it is over the dismiss circle. */
    private fun place(x: Int, y: Int) {
        val v = bubble ?: return
        bubbleParams.x = BubbleMath.clamp(x, v.width, dm.widthPixels)
        bubbleParams.y = BubbleMath.clamp(y, v.height, dm.heightPixels)
        runCatching { wm.updateViewLayout(v, bubbleParams) }
        over = BubbleMath.overTarget(bubbleParams.x + v.width / 2f, bubbleParams.y + v.height / 2f, dm.widthPixels / 2f, dm.heightPixels - dp(96).toFloat(), dp(72).toFloat())
    }

    private fun showTarget() {
        if (target != null) return
        over = false
        val st = App.graph(context).settings.state.value
        val t = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner); setViewTreeSavedStateRegistryOwner(owner); setViewTreeViewModelStoreOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { AppTheme(accent = st.accent, mascot = st.mascot) { LiveDismissTarget(over) } }
        }
        target = t
        runCatching { wm.addView(t, targetParams) }.onFailure { target = null }
    }

    private fun hideTarget() {
        target?.let { runCatching { wm.removeView(it) } }
        target = null; over = false
    }

    fun hide() {
        hideTarget()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        owner.destroy()
    }

    /**
     * Drag by the finger's position on the SCREEN (not in the window, which moves with it): once the finger moves past
     * the touch slop the drag takes over from the taps, so a tap still opens the sheet and a drag never does.
     */
    private inner class DragView : android.widget.FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var dragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startX = bubbleParams.x; startY = bubbleParams.y; dragging = false }
                MotionEvent.ACTION_MOVE -> if (!dragging && hypot(ev.rawX - downX, ev.rawY - downY) > slop) { dragging = true; showTarget() }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) place(startX + (ev.rawX - downX).toInt(), startY + (ev.rawY - downY).toInt())
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    val drop = over && ev.actionMasked == MotionEvent.ACTION_UP
                    hideTarget()
                    if (drop) LiveHost.end("")
                }
            }
            return dragging || super.onTouchEvent(ev)
        }
    }

    /** A window of its own has no activity to lend it a lifecycle, so it brings one. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val viewModelStore = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        init { controller.performAttach(); controller.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
    }
}
