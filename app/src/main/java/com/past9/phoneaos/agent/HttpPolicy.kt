package com.past9.phoneaos.agent

/**
 * HTTPS by default. Plain http:// is only allowed to this phone itself (loopback) or when the
 * user has explicitly opted in for a self-hosted server on a network they trust.
 */
object HttpPolicy {
    const val NEEDS_OPT_IN = "This server uses plain HTTP, which doesn't encrypt your requests or API key. Use an https:// address, or tick \"Allow unencrypted HTTP for this server\"."

    fun isHttp(url: String) = url.trim().startsWith("http://", ignoreCase = true)

    /** True for http(s)://localhost, 127.x.x.x and [::1]. */
    fun isLoopback(url: String): Boolean {
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase()?.removeSurrounding("[", "]") ?: return false
        return host == "localhost" || host == "::1" || host.matches(Regex("""127(\.\d{1,3}){3}"""))
    }

    /** Null if the URL may be used; otherwise a message the user can act on. */
    fun blockReason(url: String, allowHttp: Boolean): String? {
        val u = url.trim()
        return when {
            u.startsWith("https://", ignoreCase = true) -> null
            isHttp(u) -> if (allowHttp || isLoopback(u)) null else NEEDS_OPT_IN
            else -> "The base URL must start with https:// (or http:// with the opt-in)."
        }
    }

    fun check(url: String, allowHttp: Boolean) { blockReason(url, allowHttp)?.let { throw ProviderException(it) } }
}
