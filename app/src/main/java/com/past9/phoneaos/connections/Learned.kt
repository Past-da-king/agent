package com.past9.phoneaos.connections

import com.past9.phoneaos.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One thing a learned web app can do, written by the agent after it mapped the site: a name, what it is for, the
 * inputs it takes, and the JavaScript that does it inside the signed-in page (an async function body that sees `args`).
 * `readOnly` tools just read; the rest change things and ask the user first unless the app is trusted.
 */
data class LearnedTool(
    val name: String,
    val description: String,
    /** Input name -> {type, description}. */
    val params: JSONObject = JSONObject(),
    val required: List<String> = emptyList(),
    val js: String,
    val readOnly: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("description", description).put("params", params).put("required", JSONArray(required)).put("js", js).put("readOnly", readOnly)

    companion object {
        fun fromJson(o: JSONObject) = LearnedTool(o.getString("name"), o.optString("description"), o.optJSONObject("params") ?: JSONObject(),
            o.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), o.optString("js"), o.optBoolean("readOnly", true))
    }
}

/**
 * A web app the agent learned (WhatsApp Web, Reddit, a site with no API): where it lives, which browser profile is
 * signed in to it, and the tools the agent wrote for it. The tools run in that signed-in browser, so no password is stored.
 */
data class LearnedApp(
    val id: String,
    val name: String,
    val startUrl: String,
    val about: String = "",
    val profile: String = "",
    /** Load the site as a desktop browser would (WhatsApp Web and others refuse a phone). */
    val desktop: Boolean = false,
    val tools: List<LearnedTool> = emptyList(),
    /** Let its tools that change things run without asking each time. Only the user can turn this on. */
    val trusted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    /** An https image of the app's icon; blank means the site's own favicon. */
    val logo: String = "",
) {
    /** What to draw as the app's logo: the one the agent gave, else the site's favicon (an initial letter if neither loads). */
    val logoUrl: String get() = logo.ifBlank { if (host.isBlank()) "" else "https://$host/favicon.ico" }
    val host: String get() = runCatching { java.net.URI(startUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("")
    val writes: Int get() = tools.count { !it.readOnly }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("startUrl", startUrl).put("about", about).put("profile", profile).put("desktop", desktop)
        .put("tools", JSONArray().apply { tools.forEach { put(it.toJson()) } }).put("trusted", trusted).put("updatedAt", updatedAt).put("logo", logo)

    companion object {
        fun fromJson(o: JSONObject) = LearnedApp(o.getString("id"), o.optString("name"), o.optString("startUrl"), o.optString("about"), o.optString("profile"), o.optBoolean("desktop"),
            o.optJSONArray("tools")?.let { a -> (0 until a.length()).map { LearnedTool.fromJson(a.getJSONObject(it)) } } ?: emptyList(), o.optBoolean("trusted"), o.optLong("updatedAt"), o.optString("logo"))

        /** a-z, 0-9 and underscores: what a tool name may be made of. */
        fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
    }
}

/** The apps the agent has learned. */
class LearnedStore(private val settings: SettingsStore) {
    private val _apps = MutableStateFlow(load())
    val apps: StateFlow<List<LearnedApp>> = _apps

    private fun load(): List<LearnedApp> = runCatching {
        val a = JSONArray(settings.extra("learned_apps") ?: "[]"); (0 until a.length()).map { LearnedApp.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun persist(list: List<LearnedApp>) { settings.setExtra("learned_apps", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()); _apps.value = list }

    fun find(ref: String): LearnedApp? = _apps.value.firstOrNull { it.id == ref } ?: _apps.value.firstOrNull { it.name.equals(ref.trim(), true) || it.id == LearnedApp.slug(ref) }
    /** Saving over an app of the same id replaces its tools but keeps what the user decided about trust. */
    fun save(a: LearnedApp) { val old = find(a.id); persist(_apps.value.filter { it.id != a.id } + a.copy(trusted = old?.trusted ?: false, updatedAt = System.currentTimeMillis())) }
    fun setTrusted(id: String, on: Boolean) = persist(_apps.value.map { if (it.id == id) it.copy(trusted = on) else it })
    fun remove(id: String) = persist(_apps.value.filter { it.id != id })

    companion object {
        const val MAX_APPS = 30
        const val MAX_TOOLS = 40
        const val MAX_JS = 12_000
    }
}
