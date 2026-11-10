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
        // A clean emulator has no AI set up. This points the app at a stand-in model on the test machine (no real key anywhere):
        //   ... --es setup_url http://127.0.0.1:8391/v1 --es setup_name Nova
        intent.getStringExtra("setup_url")?.let { url ->
            val s = com.past9.phoneaos.App.graph(context).settings
            s.setMode(com.past9.phoneaos.data.PowerMode.API_KEY); s.setProvider(com.past9.phoneaos.data.Provider.CUSTOM)
            s.setBaseUrl(url); s.setAllowHttp(true); s.setModel("scripted-stand-in"); s.setHelperModel("scripted-stand-in")
            s.setApiKey(com.past9.phoneaos.data.Provider.CUSTOM, "stand-in-key-for-the-test-emulator")
            intent.getStringExtra("setup_name")?.let { s.setAgentName(it) }
            s.setOnboarded(true)
            Log.i(AssistService.TAG, "probe setup: stand-in model at $url")
        }
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
