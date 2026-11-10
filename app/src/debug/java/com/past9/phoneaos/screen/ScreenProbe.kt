package com.past9.phoneaos.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * DEBUG BUILDS ONLY (src/debug): runs one tool the way a linked computer would (through remote/DeviceTools.kt),
 * so the background screen can be proven on an emulator with no model and no computer attached.
 *   adb shell am broadcast -n <pkg>/com.past9.phoneaos.screen.ScreenProbe --es tool screen_open_app --es args '{"app":"Contacts"}' [--es id 3]
 * The answer is logged under the tag ScreenProbe and written to files/screen-probe/<id>.txt, with how long it took.
 */
class ScreenProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("tool") ?: return
        val args = runCatching { JSONObject(intent.getStringExtra("args") ?: "{}") }.getOrDefault(JSONObject())
        val id = intent.getStringExtra("id") ?: "last"
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            val t0 = System.currentTimeMillis()
            // The published device tools, with the answer to any approval given on the command line (default: nobody answers).
            val answer = intent.getStringExtra("answer")
            val ctx = object : com.past9.phoneaos.agent.ToolContext {
                override val agentLabel = "linked computer"
                override suspend fun activity(text: String, meta: JSONObject): Long = 0
                override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
                override suspend fun ask(question: String, options: List<String>): String? { Log.i("ScreenProbe", "ASKED: " + question.take(600)); return answer }
                override suspend fun notify(title: String, body: String) {}
            }
            val tool = com.past9.phoneaos.remote.DeviceTools.tools(context.applicationContext).firstOrNull { it.first.spec.name == name }?.first
            val (ok, value) = if (tool == null) false to "This phone does not lend $name" else runCatching { true to tool.run(args, ctx) }.getOrElse { false to (it.message ?: it.toString()) }
            val ms = System.currentTimeMillis() - t0
            val text = "tool=$name ok=$ok ms=$ms\n$value"
            Log.i("ScreenProbe", text.take(3500))
            runCatching { File(context.getExternalFilesDir(null), "screen-probe").apply { mkdirs() }.let { File(it, "$id.txt").writeText(text) } }
            pending.finish()
        }
    }
}
