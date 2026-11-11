package com.past9.phoneaos.assist

import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.delay
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
}
