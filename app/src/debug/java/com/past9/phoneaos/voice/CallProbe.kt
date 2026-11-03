package com.past9.phoneaos.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * DEBUG BUILDS ONLY (src/debug): drives a call on an emulator, which has no microphone to talk into.
 *   adb shell am broadcast -a com.past9.phoneaos.CALL_PROBE -n com.past9.phoneaos/.voice.CallProbe --es say "Email Ben the report"
 *     puts the words to the REAL Live model as if the user said them (it decides which tool to call);
 *   ... --es tool start_work --es args '{"task":"...","target":"main"}'
 *     simulates the model calling a tool, for a run with no Live session.
 * Every tool call and result is logged under the tag CallAgent.
 */
class CallProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val call = com.past9.phoneaos.App.graph(context).call
        intent.getStringExtra("say")?.let { Log.i("CallAgent", "probe say: $it"); call.probeSay(it) }
        intent.getStringExtra("tool")?.let { name ->
            val args = runCatching { JSONObject(intent.getStringExtra("args") ?: "{}") }.getOrDefault(JSONObject())
            Log.i("CallAgent", "probe tool: $name $args")
            call.probeTool(name, args)
        }
    }
}
