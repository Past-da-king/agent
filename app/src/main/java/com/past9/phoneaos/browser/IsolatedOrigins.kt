package com.past9.phoneaos.browser

import java.net.URI

/**
 * Settings > Browser > Sites that need advanced browser features.
 *
 * Android's WebView hides SharedArrayBuffer (threaded WebAssembly, shared memory) from every page unless the app
 * names the site. WhatsApp Web's calling needs it, and so do in-browser editors and models that run in the page.
 * Naming a site relaxes a Spectre guard for that site only, so the list is the user's own, short and exact:
 * https origins, scheme and host (and a port when it is not 443), never a wildcard, never a path.
 */
object IsolatedOrigins {
    /** What a fresh install starts with. The user can remove it, and it does not come back. */
    val DEFAULT = listOf("https://web.whatsapp.com")
    const val MAX = 40

    sealed interface Parsed {
        data class Ok(val origin: String) : Parsed
        data class Bad(val why: String) : Parsed
    }

    private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

    /** What the user typed, as an exact origin ("https://host" or "https://host:port"), or why it can't be one. */
    fun normalise(input: String): Parsed {
        var s = input.trim()
        if (s.isEmpty()) return Parsed.Bad("Type a site address, like https://example.com.")
        if (s.any { it.isWhitespace() }) return Parsed.Bad("A site address has no spaces.")
        if ('*' in s) return Parsed.Bad("No wildcards. Add each site by its exact address.")
        if (!s.contains("://")) s = "https://$s"
        val scheme = s.substringBefore("://").lowercase()
        if (scheme == "http") return Parsed.Bad("Only https sites can be added.")
        if (scheme != "https") return Parsed.Bad("Only https sites can be added.")
        val uri = runCatching { URI(s) }.getOrNull() ?: return Parsed.Bad("That is not a site address.")
        if (uri.rawUserInfo != null) return Parsed.Bad("Leave out the sign-in part before the @.")
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return Parsed.Bad("That is not a site address.")
        if (!HOST.matches(host)) return Parsed.Bad("That is not a site address.")
        val path = uri.rawPath.orEmpty()
        if ((path.isNotEmpty() && path != "/") || uri.rawQuery != null || uri.rawFragment != null)
            return Parsed.Bad("Only the site itself, with nothing after the name. Try https://$host.")
        val port = uri.port
        if (port == 0 || port > 65535) return Parsed.Bad("That port is not valid.")
        return Parsed.Ok("https://$host" + if (port == -1 || port == 443) "" else ":$port")
    }

    /** The stored text (one origin per line) as a clean list. Null means never set: the default. Anything invalid is dropped. */
    fun parse(stored: String?): List<String> {
        if (stored == null) return DEFAULT
        return stored.lineSequence().mapNotNull { (normalise(it) as? Parsed.Ok)?.origin }.distinct().take(MAX).toList()
    }

    fun encode(origins: List<String>): String = origins.joinToString("\n")

    /** [list] with [input] added. The error is null when it was added. */
    fun add(list: List<String>, input: String): Pair<List<String>, String?> = when (val p = normalise(input)) {
        is Parsed.Bad -> list to p.why
        is Parsed.Ok -> when {
            p.origin in list -> list to "${p.origin.removePrefix("https://")} is already on the list."
            list.size >= MAX -> list to "The list is full. Remove a site first."
            else -> (list + p.origin) to null
        }
    }

    fun remove(list: List<String>, origin: String): List<String> = list.filterNot { it.equals(origin.trim(), ignoreCase = true) }

    /**
     * Hands the list to the browser when it can take one. An older WebView ([supported] false) is left alone, and a
     * WebView that refuses is not allowed to take the browser down with it. A wildcard never goes through, whatever
     * the list holds. True when the browser took the list.
     */
    fun apply(supported: Boolean, origins: Collection<String>, set: (Set<String>) -> Unit): Boolean {
        if (!supported) return false
        val clean = origins.mapNotNull { (normalise(it) as? Parsed.Ok)?.origin }.toSet()
        return runCatching { set(clean) }.isSuccess
    }

    /** The Settings line, in words. */
    fun line(origins: List<String>): String = when (origins.size) {
        0 -> "None · tap to add a site"
        1 -> origins[0].removePrefix("https://")
        else -> "${origins.size} sites · " + origins.take(2).joinToString(", ") { it.removePrefix("https://") } + if (origins.size > 2) " and more" else ""
    }
}
