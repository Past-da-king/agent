package com.past9.phoneaos.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.text.InputType
import android.view.View
import java.io.File

/** The two switches behind screen context. Both are off until the person turns them on. */
object AssistPrefs {
    private const val TEXT = "x_assist_read_screen"
    private const val SHOT = "x_assist_read_screenshot"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "Let the assistant read the screen": the words on screen go to the agent with the request. */
    fun readScreen(context: Context): Boolean = prefs(context).getBoolean(TEXT, false)
    /** "Also send a picture of the screen". Only counts while [readScreen] is on. */
    fun readScreenshot(context: Context): Boolean = readScreen(context) && prefs(context).getBoolean(SHOT, false)

    /** "Open over the lock screen". Off: the sheet only opens on an unlocked phone. */
    fun overLockScreen(context: Context): Boolean = prefs(context).getBoolean("x_assist_over_lock", false)
    /** "Live voice in the assistant". Off by default: the sheet opens its normal listening input. On: it opens a Gemini Live session. */
    fun liveVoice(context: Context): Boolean = prefs(context).getBoolean("x_assist_live_voice", false)
    fun setLiveVoice(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("x_assist_live_voice", on).apply()
        AssistService.live?.let { runCatching { it.applyShowContext() } }
    }

    /** "Live sees your screen": while live voice is on, frames of the screen go into the session so it can answer "what is this". Needs [readScreen]. */
    fun liveSees(context: Context): Boolean = readScreen(context) && prefs(context).getBoolean("x_assist_live_sees", true)
    fun setLiveSees(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("x_assist_live_sees", on).apply()
        AssistService.live?.let { runCatching { it.applyShowContext() } }
    }

    /** How patient Live is before it answers: quick, patient (default) or very. See LiveTuning.Patience. */
    fun livePatience(context: Context): String = prefs(context).getString("x_live_patience", com.past9.phoneaos.voice.LiveTuning.Patience.PATIENT.key) ?: com.past9.phoneaos.voice.LiveTuning.Patience.PATIENT.key
    fun setLivePatience(context: Context, key: String) { prefs(context).edit().putString("x_live_patience", key).apply() }

    /** "Wait for me": Live only listens between "Tap to talk" and "I'm done". Off by default. */
    fun liveWaitForMe(context: Context): Boolean = prefs(context).getBoolean("x_live_wait_for_me", false)
    fun setLiveWaitForMe(context: Context, on: Boolean) { prefs(context).edit().putBoolean("x_live_wait_for_me", on).apply() }

    /** "Follow my language": no language is pinned, so several languages in one sentence are understood. Off by default (English is pinned). */
    fun liveAutoLanguage(context: Context): Boolean = prefs(context).getBoolean("x_live_auto_language", false)
    fun setLiveAutoLanguage(context: Context, on: Boolean) { prefs(context).edit().putBoolean("x_live_auto_language", on).apply() }

    /** The turn-taking and language choices as one value for a session. [vocabulary] is what the transcriber should know (names). */
    fun liveTuning(context: Context, vocabulary: List<String> = emptyList()): com.past9.phoneaos.voice.LiveTuning = com.past9.phoneaos.voice.LiveTuning(
        patience = com.past9.phoneaos.voice.LiveTuning.Patience.of(livePatience(context)),
        waitForMe = liveWaitForMe(context),
        autoLanguage = liveAutoLanguage(context),
        vocabulary = vocabulary,
    )

    /** Whether the phone should gather a picture at all: the sheet's own picture switch, or Live's frames. */
    fun screenshotAllowed(context: Context): Boolean = readScreenshot(context) || (liveVoice(context) && liveSees(context))

    fun setOverLockScreen(context: Context, on: Boolean) { prefs(context).edit().putBoolean("x_assist_over_lock", on).apply() }

    fun setReadScreen(context: Context, on: Boolean) {
        val e = prefs(context).edit().putBoolean(TEXT, on)
        if (!on) e.putBoolean(SHOT, false)
        e.apply()
        AssistService.live?.let { runCatching { it.applyShowContext() } }
    }
    fun setReadScreenshot(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(SHOT, on).apply()
        AssistService.live?.let { runCatching { it.applyShowContext() } }
    }
}

/**
 * Banking, payment and money apps are never read: not their words, not a picture. Android has no "finance" app
 * category to ask for, so this goes by the app's package name and its label: makers of banking and payment apps the
 * list knows, and words that give a money app away. It errs on the side of not reading.
 */
object ScreenGuard {
    /** Package prefixes of banking, payment, wallet, trading and tax apps. */
    val FINANCE_PREFIXES = listOf(
        // Wallets and payments
        "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa", "com.samsung.android.spay", "com.samsung.android.samsungpay",
        "com.paypal.", "com.venmo", "com.squareup.cash", "com.zellepay", "com.stripe.", "com.klarna.", "com.afterpay.", "com.payoneer.", "com.skrill.",
        "com.transferwise.", "com.wise.", "com.revolut.", "com.westernunion.", "com.moneygram.", "com.remitly.", "com.worldremit.", "net.one97.paytm",
        "com.phonepe.", "in.org.npci.", "com.mpesa.", "com.safaricom.mpesa", "com.mtn.", "com.eg.android.alipaygphone", "com.snapscan.", "com.zapper.",
        "za.co.yoco.", "com.ozow.", "com.payfast.", "com.paystack.", "com.flutterwave.",
        // Banks
        "za.co.fnb.", "com.fnb.", "za.co.absa.", "com.barclays.", "com.sbg.", "com.standardbank.", "za.co.standardbank.", "capitecbank.", "za.co.capitec",
        "za.co.nedbank", "com.nedbank.", "za.co.investec", "com.investec.", "com.discovery.bank", "za.co.discovery.bank", "za.co.tymebank", "com.tymebank",
        "za.co.africanbank", "com.bankzero", "za.co.bankzero", "com.chase.", "com.wf.wellsfargo", "com.infonow.bofa", "com.citi.", "com.konylabs.capitalone",
        "com.usaa.", "com.americanexpress.", "com.discoverfinancial.", "com.hsbc.", "uk.co.hsbc.", "com.rbs.", "com.natwest.", "com.lloydsbank.", "uk.co.santander.",
        "com.grppl.android.shell.", "co.uk.getmondo", "com.starlingbank.", "com.monzo.", "de.number26.", "com.n26.", "com.ing.", "com.db.", "com.bbva.", "com.santander.",
        "com.sbi.", "com.icicibank.", "com.hdfc.", "com.axis.", "com.kotak.", "com.scb.", "com.dbs.", "com.ocbc.", "com.anz.", "com.commbank.", "org.westpac.", "au.com.nab.",
        "com.td.", "com.rbc.", "com.bmo.", "com.scotiabank.", "com.cibc.", "com.nu.production", "com.chime.", "com.sofi.", "com.varo", "com.current.",
        // Trading, crypto, tax
        "com.coinbase.", "com.binance.", "com.kraken.", "com.luno.", "co.mona.android", "piuk.blockchain", "io.metamask", "com.wallet.crypto.", "com.robinhood.",
        "com.etoro.", "com.easyequities.", "com.interactivebrokers.", "com.schwab.", "com.fidelity.", "com.vanguard.", "com.intuit.", "za.gov.sars.",
    )

    /** Words in a package segment or a label that mean money. Whole words only, so "display" never matches "pay". */
    private val WORDS = setOf("bank", "banking", "banque", "banco", "wallet", "pay", "payments", "payment", "money", "finance", "financial", "invest", "investing",
        "trading", "broker", "crypto", "bitcoin", "loan", "loans", "credit", "mortgage", "insurance", "tax", "sars", "netbanking", "mobilebanking", "ebanking", "upi")
    /** Fragments that mean money wherever they sit in a word ("capitecbank", "paypal"). */
    private val FRAGMENTS = listOf("bank", "wallet", "paypal", "crypto", "finance")

    fun blocked(pkg: String?, label: String? = null): Boolean {
        val p = pkg.orEmpty().lowercase().trim()
        if (p.isEmpty()) return false
        if (FINANCE_PREFIXES.any { p.startsWith(it) || p == it.trimEnd('.') }) return true
        val parts = p.split('.', '_').filter { it.isNotEmpty() } + label.orEmpty().lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        return parts.any { w -> w in WORDS || FRAGMENTS.any { f -> w.contains(f) } }
    }

    const val SAYS = "I don't read banking apps"
}

/** One thing on screen, as the extractor reads it: free of Android so the rules can be tested. */
data class ScreenNode(
    val text: String = "",
    val description: String = "",
    val visible: Boolean = true,
    /** A password or card field, or anything the app marked sensitive: never read. */
    val secret: Boolean = false,
    val children: List<ScreenNode> = emptyList(),
)

/** What the assistant knows about the screen for ONE turn. It is never kept after the sheet closes. */
data class ScreenContext(
    val pkg: String = "",
    val label: String = "",
    val text: String = "",
    val link: String = "",
    /** A picture of the screen, as a file, or "" when there is none. */
    val screenshot: String = "",
    /** A banking or money app: nothing was read. */
    val blocked: Boolean = false,
    /** The phone gave nothing (a protected window, or the lock screen). */
    val protectedWindow: Boolean = false,
) {
    val hasAnything get() = text.isNotBlank() || link.isNotBlank() || screenshot.isNotBlank()

    companion object {
        const val MAX_CHARS = 6_000

        /** The words on screen, top to bottom, each once, without anything secret or hidden. */
        fun words(roots: List<ScreenNode>, max: Int = MAX_CHARS): String {
            val out = LinkedHashSet<String>()
            fun walk(n: ScreenNode) {
                if (!n.visible) return
                if (!n.secret) listOf(n.text, n.description).forEach { s -> s.trim().replace(Regex("\\s+"), " ").takeIf { it.length > 1 }?.let { out += it } }
                n.children.forEach(::walk)
            }
            roots.forEach(::walk)
            val b = StringBuilder()
            for (line in out) { if (b.length + line.length + 1 > max) { b.append("\n(more on screen, cut here)"); break }; if (b.isNotEmpty()) b.append('\n'); b.append(line) }
            return b.toString()
        }

        /**
         * What a turn may know: nothing with the switch off, nothing from a money app, the words with the first
         * switch, the picture only with the second.
         */
        fun gate(readScreen: Boolean, readScreenshot: Boolean, pkg: String, label: String, text: String, link: String, screenshot: String, secureWindow: Boolean): ScreenContext? {
            if (!readScreen) return null
            if (ScreenGuard.blocked(pkg, label)) return ScreenContext(pkg = pkg, label = label, blocked = true)
            // A window the app protected hands over no words and no picture; the phone already refused, so nothing is passed on.
            val shot = if (readScreenshot) screenshot else ""
            if (secureWindow && text.isBlank() && link.isBlank() && shot.isBlank()) return ScreenContext(pkg = pkg, label = label, protectedWindow = true)
            return ScreenContext(pkg, label, text, link, shot)
        }

        // ---- Android side -----------------------------------------------------------------------

        fun nodes(structure: AssistStructure): List<ScreenNode> = (0 until structure.windowNodeCount).mapNotNull { i ->
            runCatching { node(structure.getWindowNodeAt(i).rootViewNode) }.getOrNull()
        }

        private fun node(v: AssistStructure.ViewNode): ScreenNode {
            val type = v.inputType
            val variation = type and InputType.TYPE_MASK_VARIATION
            val password = (type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
                (type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            val hints = runCatching { v.autofillHints?.joinToString(" ")?.lowercase().orEmpty() }.getOrDefault("")
            val secret = password || listOf("password", "creditcard", "cc-", "cardnumber", "cvc", "cvv", "otp", "smsotp").any { hints.contains(it) }
            return ScreenNode(v.text?.toString().orEmpty(), v.contentDescription?.toString().orEmpty(), v.visibility == View.VISIBLE, secret,
                (0 until v.childCount).mapNotNull { i -> runCatching { node(v.getChildAt(i)) }.getOrNull() })
        }

        /** Everything the phone handed the assistant, through the gate. */
        fun from(context: Context, structure: AssistStructure?, content: AssistContent?, shot: String = ""): ScreenContext? {
            val pkg = structure?.activityComponent?.packageName.orEmpty()
            val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("")
            // A window the app protected (FLAG_SECURE) hands over no view tree at all.
            val roots = structure?.let { runCatching { nodes(it) }.getOrDefault(emptyList()) }.orEmpty()
            val text = words(roots)
            val link = runCatching { content?.webUri?.toString() }.getOrNull().orEmpty()
            val secure = structure == null || roots.isEmpty() || text.isBlank()
            return gate(AssistPrefs.readScreen(context), AssistPrefs.readScreenshot(context), pkg, label, text, link, shot, secure)
        }

        /** Keep the picture as a file the agent can be shown, small enough to send. Returns "" when it cannot. */
        fun save(context: Context, bitmap: Bitmap?): String {
            bitmap ?: return ""
            return runCatching {
                val dir = File(context.filesDir, "assist").apply { mkdirs() }
                // Old pictures go: the chat keeps the newest few so a message's picture still opens.
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
                val scale = 1280f / maxOf(bitmap.width, bitmap.height)
                val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
                val small = if (scale < 1f) Bitmap.createScaledBitmap(soft, (soft.width * scale).toInt(), (soft.height * scale).toInt(), true) else soft
                val f = File(dir, "screen-${System.currentTimeMillis()}.jpg")
                f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                f.absolutePath
            }.getOrDefault("")
        }
    }
}

/** What goes out as the turn: his words, then a note only the agent reads (the bubble in the chat hides it). */
object AssistMessage {
    /** Where the note starts. The chat cuts a user bubble here, like the note under something shared. */
    const val MARK = "\n\n[Assistant sheet"

    /**
     * The standing line in the agent's system prompt (main agent, helpers and routines alike). The note on each sheet
     * message says the same in full; this is so the agent knows what the note is before it sees one. It is also the
     * seam for the background screen: no tool is named as existing, the agent is told to use those tools when they do.
     */
    const val PROMPT = "- A message with a note in [Assistant sheet] was said to the phone's assistant sheet, over another app. Answer in one or two short lines " +
        "that could be read out, act at once on a clear command, and take \"this\" or \"that\" to mean what the note says is on screen. You work out how with the tools " +
        "you have: when tools that drive the phone's own apps on the background screen exist (their names start with screen_), use them to do the job inside the app, " +
        "and when a learned app or a saved skill fits, use that first; save a way that worked so it is faster next time. Banking, payment and money apps are never " +
        "opened, read or acted in, whoever asks."

    /** The background-screen tools another part of the app adds (real apps driven on a hidden display). Named here only so the note can point at them. */
    const val SCREEN_TOOLS = "screen_open_app, screen_look, screen_tap, screen_type, screen_swipe, screen_key, screen_wait, screen_close"

    fun compose(words: String, source: String, screen: ScreenContext?, typed: Boolean = false): String {
        val b = StringBuilder(words.trim())
        b.append(MARK).append(": the user ").append(if (typed) "typed" else "said").append(" this to the phone's assistant sheet, over another app")
        b.append(if (source == AssistEntry.WAKE_WORD) " (wake word)" else "").append(". ")
        b.append("Answer in one or two short lines they could hear read out. Act straight away on a clear command; ask one short question only when it is truly unclear. ")
        b.append("\"This\", \"that\" and \"here\" mean what is on their screen, described below. ")
        b.append("You decide how to do it with whatever tools you have. When tools that drive the phone's own apps on the background screen exist (")
        b.append(SCREEN_TOOLS).append("), use them to do things inside other apps; use a learned app or a saved skill when one fits, and save a way that worked so it is faster next time. ")
        b.append("Never open, read or act in a banking, payment or money app. Anything sent in the user's name still needs their approval first.]")
        when {
            screen == null -> b.append("\nScreen: not read (the user has not turned that on).")
            screen.blocked -> b.append("\nScreen: not read. The app on screen is a banking or money app, which the assistant never reads. If they asked about it, say so in a line.")
            screen.protectedWindow && !screen.hasAnything -> b.append("\nScreen: ").append(app(screen)).append(" is on screen but gave nothing to read (a protected window or the lock screen).")
            else -> {
                b.append("\nOn screen: ").append(app(screen))
                if (screen.link.isNotBlank()) b.append("\nPage: ").append(screen.link)
                if (screen.text.isNotBlank()) b.append("\nWords on screen:\n").append(screen.text)
                if (screen.screenshot.isNotBlank()) b.append("\nA picture of the screen is attached.")
            }
        }
        return b.toString()
    }

    private fun app(s: ScreenContext) = when {
        s.label.isNotBlank() && s.pkg.isNotBlank() -> "${s.label} (${s.pkg})"
        s.pkg.isNotBlank() -> s.pkg
        else -> "an app"
    }

    /** His words alone, as the chat shows them. */
    fun visible(text: String): String = text.substringBefore(MARK)
}
