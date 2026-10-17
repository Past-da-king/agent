package com.past9.phoneaos.ui.openui

import android.content.Context

/**
 * The system prompt section that teaches the agent to answer with cards, lists, photos and buttons (OpenUI Lang).
 * Generated with OpenUI's own generator from the components the app draws (tools/gen-agent-prompt.mjs, then
 * tools/post-agent-prompt.py), stored in assets/openui-prompt.txt.
 */
object OpenUiPrompt {
    @Volatile private var cached: String? = null
    fun section(context: Context): String = cached ?: runCatching {
        context.assets.open("openui-prompt.txt").bufferedReader().use { it.readText() }.trim()
    }.getOrDefault("").also { cached = it }
}
