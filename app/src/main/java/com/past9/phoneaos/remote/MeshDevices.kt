package com.past9.phoneaos.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * One device on his link (devices.list on the computer): a computer that answers, a server, another phone. What
 * Connections > Your machines shows instead of "Add a machine" when he is linked (29 Oct 2026).
 */
data class MeshDevice(val id: String, val name: String, val kind: String, val roles: List<String>, val lastSeen: Long, val state: State, val line: String) {
    enum class State { ANSWERING, ONLINE, SELF, AWAY }

    companion object {
        /** Seen this recently: online. */
        const val ONLINE_MS = 3 * 60_000L

        /**
         * The rows of devices.list, minus the ones taken out, the computer answering now first, then the other brains,
         * then the rest. [answering] is the brain the phone talks to; [ready] the brains whose health check said ready.
         */
        fun parse(raw: Any?, self: String, answering: String?, ready: Set<String>, now: Long = System.currentTimeMillis()): List<MeshDevice> {
            val a = raw as? JSONArray ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotBlank() && !it.optBoolean("revoked") }
                .distinctBy { it.optString("id") }.map { o -> of(o, self, answering, ready, now) }
                .sortedWith(compareBy({ it.state.ordinal }, { !it.roles.contains("brain") }, { it.name.lowercase() }))
        }

        private fun of(o: JSONObject, self: String, answering: String?, ready: Set<String>, now: Long): MeshDevice {
            val id = o.optString("id")
            val roles = o.optJSONArray("roles")?.let { r -> (0 until r.length()).map { r.optString(it) } }.orEmpty()
            val kind = o.optString("kind").ifBlank { "desktop" }
            val seen = o.optLong("lastSeen")
            val state = when {
                id == self -> State.SELF
                id == answering -> State.ANSWERING
                id in ready || (seen > 0 && now - seen < ONLINE_MS) -> State.ONLINE
                else -> State.AWAY
            }
            val what = when (kind) { "phone" -> "Phone"; "tablet" -> "Tablet"; "server" -> "Server"; else -> "Computer" }
            val does = if ("brain" in roles) "answers for you" else if ("hands" in roles) "its tools can be used" else "a window onto your agent"
            val line = when (state) {
                State.ANSWERING -> "$what · $does"
                State.SELF -> "$what · this one"
                State.ONLINE -> "$what · $does"
                State.AWAY -> "$what · " + (if (seen > 0) "last seen ${com.past9.phoneaos.ui.relativeTime(seen, now)}" else "not seen yet")
            }
            return MeshDevice(id, o.optString("name").ifBlank { what }, kind, roles, seen, state, line)
        }

        /** The computer's learned apps (apps.learned), as the phone's list draws them. Read only: tools run there. */
        fun learned(raw: Any?): List<com.past9.phoneaos.connections.LearnedApp> {
            val a = raw as? JSONArray ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }.distinctBy { it.optString("id") }.map { o ->
                val tools = o.optJSONArray("tools")?.let { t -> (0 until t.length()).mapNotNull { t.optJSONObject(it) }.map { x ->
                    com.past9.phoneaos.connections.LearnedTool(x.optString("name"), x.optString("description"), js = "", readOnly = x.optBoolean("readOnly", true)) } }.orEmpty()
                val icon = o.optString("icon").takeIf { it.startsWith("https://") }.orEmpty()
                com.past9.phoneaos.connections.LearnedApp(o.optString("id"), o.optString("name").ifBlank { o.optString("host") }, o.optString("startUrl"), o.optString("about"),
                    tools = tools, trusted = o.optBoolean("trusted"), updatedAt = o.optLong("updatedAt"), logo = icon)
            }
        }
    }
}
