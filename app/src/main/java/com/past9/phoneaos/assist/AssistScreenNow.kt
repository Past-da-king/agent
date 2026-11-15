package com.past9.phoneaos.assist

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What is on screen RIGHT NOW, through the phone's own assistant path (the same one the side key uses), without
 * opening the sheet. Used when the live sheet is a bubble: he has moved to another app, so what was read when the
 * sheet opened is out of date. It respects the same two switches as the sheet (read the screen, send a picture) and the
 * money-app rule; with the switches off it returns null and nothing is read.
 */
object AssistScreenNow {
    private const val TAG = "AssistScreenNow"
    /** Extra on the session request: gather the screen only, do not open the sheet. */
    const val EXTRA_CAPTURE = "capture_only"
    /** Extra on the session request: this look is a frame for Live, kept apart from what the sheet knows. */
    const val EXTRA_FRAME = "live_frame"

    suspend fun capture(context: Context): ScreenContext? {
        if (!AssistPrefs.readScreen(context)) return null
        val svc = AssistService.live ?: return null
        AssistHub.begin()
        val shown = runCatching { svc.showSession(Bundle().apply { putBoolean(EXTRA_CAPTURE, true) }, AssistService.showFlags(context)); true }.getOrDefault(false)
        if (!shown) { Log.w(TAG, "the phone would not start a screen read"); return null }
        val text = withTimeoutOrNull(2_500) { AssistHub.screen.filterNotNull().first() }
        if (text == null) { Log.w(TAG, "nothing came back from the screen read"); return null }
        // The picture follows the words a moment later, when it is switched on.
        if (AssistPrefs.readScreenshot(context)) delay(600)
        return AssistHub.screen.value
    }

    /**
     * One look for Live: the words' app and a picture, through the assistant path, kept apart from [AssistHub] so it never
     * changes what the sheet or a hand-off thinks is on screen. Null when nothing came back. Only with Live's frames allowed.
     */
    suspend fun captureFrame(context: Context): ScreenContext? {
        if (!FrameGate.enabled(AssistPrefs.readScreen(context), AssistPrefs.liveSees(context))) return null
        val svc = AssistService.live ?: return null
        AssistFrameHub.begin()
        val flags = VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        val shown = runCatching { svc.showSession(Bundle().apply { putBoolean(EXTRA_CAPTURE, true); putBoolean(EXTRA_FRAME, true) }, flags); true }.getOrDefault(false)
        if (!shown) return null
        val base = withTimeoutOrNull(2_000) { AssistFrameHub.screen.filterNotNull().first() } ?: return null
        if (base.blocked || base.protectedWindow) return base
        return withTimeoutOrNull(1_200) { AssistFrameHub.screen.filterNotNull().first { it.screenshot.isNotBlank() } } ?: AssistFrameHub.screen.value
    }
}

/**
 * What one look for Live found, kept separate from [AssistHub]: the picture only ever attaches for a known app that is
 * not a money app, exactly as the sheet's own rule.
 */
object AssistFrameHub {
    private val _screen = MutableStateFlow<ScreenContext?>(null)
    val screen: StateFlow<ScreenContext?> = _screen
    private var base: ScreenContext? = null
    private var shot: String = ""

    @Synchronized fun begin() { base = null; shot = ""; _screen.value = null }
    @Synchronized fun offer(ctx: ScreenContext?) { base = ctx; publish() }
    @Synchronized fun offerShot(path: String) { shot = path; publish() }

    private fun publish() {
        val b = base
        _screen.value = when {
            b == null -> null
            b.blocked || b.pkg.isBlank() -> b.copy(screenshot = "")
            shot.isNotBlank() -> b.copy(screenshot = shot, protectedWindow = false)
            else -> b
        }
    }
}
