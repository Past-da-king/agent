package com.past9.phoneaos.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What makes the app selectable as the phone's digital assistant (Settings > Apps > Default apps > Digital assistant
 * app). The phone binds this service while the app holds that role and asks it for a session when the person calls
 * the assistant (the side key, a corner swipe, the assist key).
 */
class AssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        live = this
        runCatching { applyShowContext() }
        Log.i(TAG, "assistant service ready")
    }

    override fun onShutdown() {
        if (live === this) live = null
        super.onShutdown()
    }

    /** Tell the phone not to gather what the two switches do not allow, so a screen that may not be read never leaves the system. */
    fun applyShowContext() {
        var off = 0
        if (!AssistPrefs.readScreen(this)) off = off or VoiceInteractionSession.SHOW_WITH_ASSIST
        // A picture is gathered when the sheet may send one, or when Live voice is on and allowed to see the screen.
        if (!AssistPrefs.screenshotAllowed(this)) off = off or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        setDisabledShowContext(off)
    }

    companion object {
        const val TAG = "Assist"
        /** The bound service, while the app is the phone's assistant. */
        @Volatile var live: AssistService? = null
            private set

        /** What a session the app itself asks for may gather: only what the switches allow. */
        fun showFlags(context: Context): Int =
            (if (AssistPrefs.readScreen(context)) VoiceInteractionSession.SHOW_WITH_ASSIST else 0) or
                (if (AssistPrefs.readScreenshot(context)) VoiceInteractionSession.SHOW_WITH_SCREENSHOT else 0)
    }
}

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/**
 * One call of the assistant. It draws nothing itself: it starts the sheet (an activity of its own, so it shows over
 * any app and the lock screen) at once, and passes on what the phone says is on screen when that arrives a moment later.
 */
class AssistSession(context: Context) : VoiceInteractionSession(context) {
    private val main = Handler(Looper.getMainLooper())
    /** This look is a frame for Live: what it finds goes to [AssistFrameHub], never to the sheet's own [AssistHub]. */
    private var forFrame = false

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // The phone itself called (the side key, the assist gesture): there is no source in its bundle.
        com.past9.phoneaos.voice.LiveTiming.mark("entry")
        // Asked only to read the screen (the live bubble at hand-off time): gather it, show nothing.
        forFrame = args?.getBoolean(AssistScreenNow.EXTRA_FRAME) == true
        if (args?.getBoolean(AssistScreenNow.EXTRA_CAPTURE) == true) {
            if (forFrame) AssistFrameHub.begin() else AssistHub.begin()
            main.removeCallbacksAndMessages(null)
            main.postDelayed({ runCatching { hide() } }, 4_000)
            return
        }
        com.past9.phoneaos.assist.LiveToken.warm(context)
        val source = AssistEntry.source(args?.getString(AssistEntry.EXTRA_SOURCE) ?: AssistEntry.SIDE_KEY)
        AssistHub.begin()
        Log.i(AssistService.TAG, "session shown source=$source flags=$showFlags")
        runCatching { startAssistantActivity(AssistEntry.intent(context, source)) }
            .onFailure { Log.w(AssistService.TAG, "could not start the sheet from the session: ${it.message}"); runCatching { context.startActivity(AssistEntry.intent(context, source)) } }
        // The session has nothing more to do once the screen has been handed over.
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ runCatching { hide() } }, 4_000)
    }

    override fun onHandleAssist(state: AssistState) {
        if (state.index > 0 && !state.isFocused) return
        take(state.assistStructure, state.assistContent)
    }

    @Deprecated("Android 9 only")
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        if (android.os.Build.VERSION.SDK_INT < 29) take(structure, content)
    }

    private fun take(structure: AssistStructure?, content: AssistContent?) {
        if (!AssistPrefs.readScreen(context)) return
        val ctx = runCatching { ScreenContext.from(context, structure, content) }.getOrNull()
        if (forFrame) AssistFrameHub.offer(ctx) else AssistHub.offer(ctx)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot == null) return
        // A look for Live's frames needs only its own two switches; the sheet's picture needs "send a picture of the screen".
        if (forFrame) { if (FrameGate.enabled(AssistPrefs.readScreen(context), AssistPrefs.liveSees(context))) AssistFrameHub.offerShot(ScreenContext.save(context, screenshot)); return }
        if (!AssistPrefs.readScreenshot(context)) return
        AssistHub.offerShot(ScreenContext.save(context, screenshot))
    }

    override fun onHide() { main.removeCallbacksAndMessages(null); super.onHide() }
}

/**
 * What one opening of the sheet knows, kept in memory only: what is on screen (when the switches allow), and, on test
 * builds, words put in by hand where there is no microphone. Cleared when the sheet opens again and when it closes.
 */
object AssistHub {
    private val _screen = MutableStateFlow<ScreenContext?>(null)
    /** What is on screen for this opening, already through the gate. Null: nothing was read. */
    val screen: StateFlow<ScreenContext?> = _screen
    private var base: ScreenContext? = null
    private var shot: String = ""

    private var begunAt = 0L

    /** A new call of the assistant: whatever an earlier one knew is gone. */
    @Synchronized fun begin(now: Long = System.currentTimeMillis()) { base = null; shot = ""; _screen.value = null; begunAt = now }

    /**
     * The sheet opened. If the phone's assistant session started it a moment ago, what that session learned about the
     * screen stays; anything older belongs to another opening and is dropped.
     */
    @Synchronized fun keepIfFresh(now: Long = System.currentTimeMillis()) { if (now - begunAt > 5_000) begin(now) }

    @Synchronized fun offer(ctx: ScreenContext?) { base = ctx; publish() }

    @Synchronized fun offerShot(path: String) { shot = path; publish() }

    /** A picture is only ever passed on for an app that is known and is not a money app: no name, no picture. */
    private fun publish() {
        val b = base
        _screen.value = when {
            b == null -> null
            b.blocked || b.pkg.isBlank() -> b.copy(screenshot = "")
            shot.isNotBlank() -> b.copy(screenshot = shot, protectedWindow = false)
            else -> b
        }
    }

    @Synchronized fun clear() { begin(0L) }

    private val _said = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Words put in as if spoken (test builds, an emulator with no microphone). */
    val said: SharedFlow<String> = _said
    fun probeSay(text: String) { _said.tryEmit(text) }
}
