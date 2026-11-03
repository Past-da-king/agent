package com.past9.phoneaos.remote

import org.json.JSONObject

/**
 * Who really sent a message row (kind "user"). No meta.via means the person wrote it; anything else (a project chat
 * writing to the main thread, the main agent writing into a project chat, a voice call handing over its work, a
 * helper, a routine, a worker, the app) carries meta.via, written by the computer (desktop shared/sender.ts, which
 * this mirrors word for word) or by this phone. The chat draws those as not his (22 Oct 2026).
 */
data class Sender(val from: String, val name: String, val chat: String, val title: String) {
    /** The line above the message: "From your call", "From Hina, the main agent", "From Agent Desktop App". */
    val line: String get() = when (from) {
        "main" -> "From $name, the main agent"
        "project" -> "From $name" + if (title.isBlank()) "" else " · $title"
        "call" -> if (name.isBlank()) "From your call" else "From your call on $name"
        "helper" -> "From the helper $name"
        "routine" -> "From the routine $name"
        "system" -> if (name.isBlank()) "From the app" else "From $name"
        else -> "From $name"
    }

    companion object {
        val KINDS = setOf("main", "project", "call", "helper", "routine", "worker", "system")

        /** The sender of a row's meta (JSON text), or null when the person wrote it himself. */
        fun of(meta: String): Sender? = runCatching { of(JSONObject(meta.ifBlank { "{}" })) }.getOrNull()

        fun of(meta: JSONObject): Sender? {
            val v = meta.optJSONObject("via") ?: return null
            val from = v.opt("from") as? String ?: return null
            if (from !in KINDS) return null
            val name = v.opt("name") as? String ?: ""
            if (name.isEmpty() && from != "call" && from != "system") return null
            return Sender(from, name, v.opt("chat") as? String ?: "", v.opt("title") as? String ?: "")
        }

        /** Whether a chat row is the person's own words: a "user" row nobody else is marked as having sent. */
        fun isHis(kind: String, meta: String): Boolean = kind == "user" && of(meta) == null

        /** The meta a call's hand-off carries when this phone writes it itself. */
        fun callVia(device: String = ""): JSONObject = JSONObject().put("from", "call").put("name", device).put("chat", "")
    }
}
