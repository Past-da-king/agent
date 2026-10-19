package com.past9.phoneaos.browser

/** What a site did when this browser asked for a page. */
enum class Refusal(val label: String) {
    NONE("loaded fine"),
    UNSUPPORTED_BROWSER("the site says this browser or device isn't supported"),
    HTTP_BLOCKED("the site answered with a block (HTTP error)"),
    HUMAN_CHECK("the site wants proof of a human (captcha or browser check)"),
    SIGNIN_REFUSED("the site refused to sign in inside this browser"),
    BLANK("the page loaded empty, which usually means its scripts gave up on this browser"),
    LOAD_ERROR("the page didn't load (network or certificate error)"),
}

data class Verdict(val refusal: Refusal, val evidence: String = "") {
    val ok get() = refusal == Refusal.NONE
    /** Presenting as a different kind of browser (phone <-> desktop) can plausibly change this. A human check or sign-in refusal won't. */
    val identityMayHelp get() = refusal == Refusal.UNSUPPORTED_BROWSER || refusal == Refusal.HTTP_BLOCKED || refusal == Refusal.BLANK
}

/**
 * Reads a loaded page for the signs that the site turned this browser away. Nothing here knows about any one site: it looks
 * at what every site does (an HTTP block, an "unsupported browser" message, a captcha wall, an empty page), so a new site
 * needs no new code.
 */
object SiteHealth {
    private val unsupported = Regex("""(browser|device|platform)\s+(is\s+)?(not|isn.t)\s+(supported|compatible)|unsupported\s+(browser|device)|(use|try|switch to)\s+(a|an|another|the|our)?\s*(different|supported|desktop|modern|newer)?\s*(browser|computer|desktop)|upgrade your browser|update your browser|(only|best)\s+(available|works|viewed)\s+(on|in)\s+(a\s+)?(desktop|computer|chrome|laptop)|not\s+(available|supported)\s+on\s+(mobile|phones?)|open\s+(this\s+)?(page\s+)?(in|on)\s+(a\s+)?(desktop|computer)|please\s+use\s+(a\s+)?(desktop|computer|laptop)|(mobile|phone)\s+(browsers?\s+)?(is|are)\s+not\s+supported|(from|on|using|in)\s+(a\s+)?(web\s+)?(browser\s+)?on\s+your\s+(computer|desktop|laptop|pc)|(from|on|using)\s+(your|a)\s+(computer|desktop|laptop|pc)\b""", RegexOption.IGNORE_CASE)
    private val human = Regex("""verify (that )?you are (a )?human|are you a (robot|human)|captcha|unusual traffic|checking your browser|just a moment|press (and|&) hold|performing security verification|security check to access""", RegexOption.IGNORE_CASE)
    private val signin = Regex("""disallowed_useragent|may not be secure|Couldn.t sign you in|isn.t secure|browser or app may not be secure""", RegexOption.IGNORE_CASE)
    private val netError = Regex("""could not be loaded because:?\s*(net::ERR_[A-Z_]+)""", RegexOption.IGNORE_CASE)
    private val netError2 = Regex("""net::ERR_[A-Z_]+""")
    private val blockedWords = Regex("""access denied|request blocked|you have been blocked|error 1020|forbidden|not authori[sz]ed to view""", RegexOption.IGNORE_CASE)

    /** [status] is the main page's HTTP status if known, [error] a load error from the browser, [text] the visible text. */
    fun assess(status: Int?, error: String?, title: String, text: String): Verdict {
        val t = text.trim()
        // When a site answers with an error and no page, Chrome draws its own "Webpage not available ... net::ERR_..." page.
        // An HTTP error code means the site itself said no; any other net error means the page never arrived.
        val net = netError.find(t)?.groupValues?.get(1) ?: error?.let { netError2.find(it)?.value }
        if (net != null) return if (net.contains("HTTP_RESPONSE_CODE_FAILURE")) Verdict(Refusal.HTTP_BLOCKED, "the site answered with an HTTP error and no page ($net)") else Verdict(Refusal.LOAD_ERROR, net)
        if (error != null && status == null && t.length < 60) return Verdict(Refusal.LOAD_ERROR, error.take(160))
        signin.find(t)?.let { return Verdict(Refusal.SIGNIN_REFUSED, snippet(t, it.range)) }
        human.find(title + "\n" + t)?.let { if (t.length < 1500 || status in setOf(403, 429, 503)) return Verdict(Refusal.HUMAN_CHECK, snippet(t, it.range)) }
        // A wall of text that mentions "unsupported browser" among a lot of content is a footer, not a refusal.
        unsupported.find(t)?.let { if (t.length < 1200) return Verdict(Refusal.UNSUPPORTED_BROWSER, snippet(t, it.range)) }
        if (status != null && status in setOf(403, 406, 429, 451, 503) && t.length < 1500) return Verdict(Refusal.HTTP_BLOCKED, "HTTP $status" + blockedWords.find(t)?.let { ": " + snippet(t, it.range) }.orEmpty())
        if (blockedWords.containsMatchIn(t) && t.length < 400) return Verdict(Refusal.HTTP_BLOCKED, snippet(t, blockedWords.find(t)!!.range))
        if (status != null && status >= 500 && t.length < 200) return Verdict(Refusal.LOAD_ERROR, "HTTP $status")
        if (t.length < 25 && (status == null || status < 400)) return Verdict(Refusal.BLANK, "no visible text")
        return Verdict(Refusal.NONE)
    }

    private fun snippet(t: String, r: IntRange): String = t.substring(maxOf(0, r.first - 40), minOf(t.length, r.last + 60)).replace(Regex("\\s+"), " ").trim()
}
