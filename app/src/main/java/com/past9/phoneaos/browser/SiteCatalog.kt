package com.past9.phoneaos.browser

import com.past9.phoneaos.data.SettingsStore
import org.json.JSONObject

/** What the app has learned about how one site behaves in its browser. */
data class SiteNote(val site: String, val desktop: Boolean, val outcome: String, val reason: String, val at: Long) {
    fun toJson() = JSONObject().put("desktop", desktop).put("outcome", outcome).put("reason", reason).put("at", at)
    /** One line an agent can act on. */
    fun line() = "$site: works as a ${if (desktop) "desktop" else "phone"} browser" + if (outcome == "refused") " (refused in both: $reason)" else if (reason.isNotBlank()) " ($reason)" else ""
}

/**
 * The site catalog: learned, not written by hand. Every time the browser opens a site it records which way of presenting
 * itself got through (phone or desktop) and why the other didn't, so the next visit starts the right way and agents are told
 * what is known. Keyed by host, so each site is learned on its own. Stored on the phone only.
 */
class SiteCatalog(private val settings: SettingsStore) {
    private val key = "site_catalog"
    private fun all(): JSONObject = runCatching { JSONObject(settings.extra(key) ?: "{}") }.getOrElse { JSONObject() }

    fun get(url: String): SiteNote? {
        val s = siteOf(url) ?: return null
        val o = all().optJSONObject(s) ?: return null
        return SiteNote(s, o.optBoolean("desktop"), o.optString("outcome"), o.optString("reason"), o.optLong("at"))
    }

    fun record(url: String, desktop: Boolean, outcome: String, reason: String = "") {
        val s = siteOf(url) ?: return
        val a = all(); a.put(s, SiteNote(s, desktop, outcome, reason.take(200), System.currentTimeMillis()).toJson())
        // Newest 200 sites are plenty.
        if (a.length() > 200) a.keys().asSequence().map { it to a.getJSONObject(it).optLong("at") }.sortedBy { it.second }.take(a.length() - 200).forEach { a.remove(it.first) }
        settings.setExtra(key, a.toString())
    }

    fun forget(url: String) { val s = siteOf(url) ?: return; val a = all(); a.remove(s); settings.setExtra(key, a.toString()) }
    fun list(): List<SiteNote> = all().keys().asSequence().mapNotNull { k -> all().optJSONObject(k)?.let { SiteNote(k, it.optBoolean("desktop"), it.optString("outcome"), it.optString("reason"), it.optLong("at")) } }.sortedByDescending { it.at }.toList()

    companion object {
        /** The site: its host without a leading www. or m. (flow.google.com and mail.google.com are different sites and are learned separately). */
        fun siteOf(url: String): String? =
            runCatching { java.net.URI(if (url.contains("://")) url else "https://$url").host }.getOrNull()?.lowercase()?.removePrefix("www.")?.removePrefix("m.")?.takeIf { it.isNotBlank() }
    }
}
