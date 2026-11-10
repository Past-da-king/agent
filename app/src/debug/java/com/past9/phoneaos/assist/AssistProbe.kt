package com.past9.phoneaos.assist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DEBUG BUILDS ONLY (src/debug): drives the assist sheet on an emulator, which has no microphone to talk into.
 *   adb shell am broadcast -n com.past9.phoneaos/.assist.AssistProbe --es open wake_word
 *     opens the sheet through AssistEntry, the way a wake word or a shortcut would;
 *   ... --es say "what is on my list today"
 *     puts the words in as if the user said them (the sheet must be open);
 *   ... --es read_screen on|off --es read_picture on|off
 *     flips the two screen switches;
 *   ... --es screen_pkg com.example.notes --es screen_label Notes --es screen_text "Milk, eggs"
 *     stands in for what the phone says is on screen (it still goes through the same gate as the real thing).
 * Everything is logged under the tag Assist.
 */
class AssistProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("read_screen")?.let { AssistPrefs.setReadScreen(context, it == "on"); Log.i(AssistService.TAG, "probe read_screen=$it") }
        intent.getStringExtra("read_picture")?.let { AssistPrefs.setReadScreenshot(context, it == "on"); Log.i(AssistService.TAG, "probe read_picture=$it") }
        intent.getStringExtra("open")?.let { Log.i(AssistService.TAG, "probe open: $it -> ${AssistEntry.open(context, it)}") }
        intent.getStringExtra("screen_pkg")?.let { pkg ->
            val ctx = ScreenContext.gate(AssistPrefs.readScreen(context), AssistPrefs.readScreenshot(context), pkg, intent.getStringExtra("screen_label").orEmpty(),
                intent.getStringExtra("screen_text").orEmpty(), intent.getStringExtra("screen_link").orEmpty(), "", secureWindow = false)
            Log.i(AssistService.TAG, "probe screen: $pkg -> ${ctx?.let { if (it.blocked) "blocked" else "read ${it.text.length} chars" } ?: "switch off"}")
            AssistHub.begin(); AssistHub.offer(ctx)
        }
        intent.getStringExtra("say")?.let { Log.i(AssistService.TAG, "probe say: $it"); AssistHub.probeSay(it) }
    }
}
