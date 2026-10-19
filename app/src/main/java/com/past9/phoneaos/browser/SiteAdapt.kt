package com.past9.phoneaos.browser

/** What the adaptive opener needs from a browser; BrowserEngine implements it, tests fake it. */
interface SitePort {
    val desktop: Boolean
    suspend fun setDesktop(on: Boolean)
    /** Load [url] and report what happened. */
    suspend fun load(url: String): Loaded
    suspend fun snapshot(): String
}

data class Loaded(val url: String, val title: String, val status: Int?, val error: String?, val text: String)

/** The result of opening a site, with what was learned. [notes] go to the agent ahead of the page. */
data class Opened(val verdict: Verdict, val desktop: Boolean, val notes: String, val triedBoth: Boolean)

/**
 * Open a site the way that works, without being told how. It starts from what the catalog knows about the site (or what the
 * caller asked for), reads the page for signs of a refusal ([SiteHealth]), and if presenting as the other kind of browser could
 * plausibly help it tries that once. What got through is remembered. When nothing does, it says what was seen and what to try
 * next, so the agent never gets a silent dead end.
 */
object SiteAdapt {
    suspend fun open(port: SitePort, catalog: SiteCatalog, url: String, asked: Boolean?): Opened {
        val known = catalog.get(url)
        // A site nobody has told us about starts as a phone, whatever the last site needed; only what was learned about THIS site counts.
        val start = asked ?: known?.takeIf { it.outcome == "works" }?.desktop ?: false
        if (port.desktop != start) port.setDesktop(start)
        val first = port.load(url).let { it to SiteHealth.assess(it.status, it.error, it.title, it.text) }
        if (first.second.ok) {
            if (known == null || known.desktop != start || known.outcome != "works") catalog.record(url, start, "works")
            val remembered = if (asked == null && known?.outcome == "works") "Known site: opened as a ${mode(start)} browser (learned earlier).\n" else ""
            return Opened(first.second, start, remembered, false)
        }
        // A human check or a refused sign-in is not about what kind of browser this is; don't flail.
        if (asked != null || !first.second.identityMayHelp) {
            if (!first.second.identityMayHelp) catalog.record(url, start, "refused", first.second.refusal.label)
            return Opened(first.second, start, diagnosis(first.second, listOf(mode(start)), url), false)
        }
        port.setDesktop(!start)
        val second = port.load(url).let { it to SiteHealth.assess(it.status, it.error, it.title, it.text) }
        if (second.second.ok) {
            catalog.record(url, !start, "works", "as a ${mode(start)} browser: ${first.second.refusal.label}")
            return Opened(second.second, !start, "The site refused a ${mode(start)} browser (${first.second.refusal.label}: ${first.second.evidence}) and worked as a ${mode(!start)} one. Remembered for next time.\n", true)
        }
        // Neither worked: leave the browser the way it was asked to start, and say so.
        port.setDesktop(start)
        catalog.record(url, start, "refused", "${first.second.refusal.label}; also refused as a ${mode(!start)} browser")
        return Opened(first.second, start, diagnosis(first.second, listOf(mode(start), mode(!start)), url), true)
    }

    private fun mode(desktop: Boolean) = if (desktop) "desktop" else "phone"

    /** What an agent should do next. Kept in one place so every tool that hits a refusal teaches the same thing. */
    fun diagnosis(v: Verdict, tried: List<String>, url: String): String = buildString {
        appendLine("THE SITE REFUSED THIS BROWSER: ${v.refusal.label}${if (v.evidence.isNotBlank()) " (\"${v.evidence}\")" else ""}. Tried: ${tried.joinToString(" and ")}.")
        appendLine("Next, in this order:")
        when (v.refusal) {
            Refusal.HUMAN_CHECK -> appendLine("1. This needs a person: browser_handoff, let the user pass the check, then carry on in the same session.")
            Refusal.SIGNIN_REFUSED -> appendLine("1. Use the site's email or password sign-in instead of its single sign-on button, or browser_handoff so the user signs in.")
            else -> {
                appendLine("1. See why: browser_console for the page's errors, browser_network (start, reload, log) for the calls that failed and their replies. A refused API call there tells you more than the page does.")
                appendLine("2. The site may expect a signed-in session or a step you skipped: look at the outline for a sign-in, consent or \"continue\" step.")
            }
        }
        appendLine("3. If the site really needs a full desktop browser on a computer, hand the whole job to one of the user's machines (machine_handoff); it reports back when done.")
        append("4. Otherwise tell the user plainly what the site refuses and what the options are. Don't retry the same thing.")
    }
}
