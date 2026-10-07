package com.past9.phoneaos.tools

import android.content.Context
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.data.ChatDao
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.voice.Tts
import org.json.JSONObject

/** A voice note: something worth hearing (a summary, a briefing), spoken, with the text one tap away. */
class VoiceNoteTool(private val context: Context, private val settings: SettingsStore, private val chat: ChatDao) : Tool {
    override val spec = ToolSpec("voice_note", "Send the user a short VOICE NOTE: what you would say out loud (natural speech, the answer first, under a minute). Use when they're on the move, ask to hear it, or for a briefing. The text shows under it too.",
        schema(listOf("script"), "script" to str("What to say, as spoken words. No markdown, no URLs")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val script = input.optString("script").trim()
        val f = Tts.synth(context, settings, script)
        chat.insert(ChatItem(kind = "voice", text = script, meta = JSONObject().put("path", f?.path ?: "").toString()))
        return if (f != null) "Voice note sent." else "No voice is set up (Settings, Voice), so it went as text only."
    }
}
