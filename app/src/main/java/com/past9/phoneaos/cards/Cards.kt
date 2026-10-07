package com.past9.phoneaos.cards

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.past9.phoneaos.ui.theme.appColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * A live card: a small screen the agent wrote in HTML, shown in a slide-up sheet and as a tile on Home.
 * It looks native because it is rendered with the app's own colours (CSS variables from the live theme)
 * and the agent-ui.css kit. Data lives beside the HTML and is refreshed by a script (no AI) or by the agent.
 */
data class Card(
    val id: String,
    val title: String,
    /** A kit icon name, e.g. "mail", "tag", "calendar". */
    val icon: String = "chart",
    val html: String,
    /** JSON the HTML renders (card.data()). */
    val data: String = "null",
    /** One short line for the Home tile, e.g. "R 3,299 cheapest". */
    val headline: String = "",
    /** Where the data comes from, e.g. "takealot.com". */
    val source: String = "",
    /** Node script that refreshes the data with no AI (optional). */
    val script: String = "",
    /** How often the script runs, in minutes (0 = only when asked). */
    val everyMinutes: Int = 0,
    /** The user let this card's script read their connected apps. */
    val appsAllowed: Boolean = false,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Last script problem, shown in the sheet so a broken card isn't silently stale. */
    val error: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("icon", icon).put("html", html).put("data", data).put("headline", headline)
        .put("source", source).put("script", script).put("everyMinutes", everyMinutes).put("appsAllowed", appsAllowed).put("pinned", pinned)
        .put("createdAt", createdAt).put("updatedAt", updatedAt).put("error", error)

    companion object {
        fun fromJson(o: JSONObject) = Card(o.getString("id"), o.optString("title"), o.optString("icon", "chart"), o.optString("html"), o.optString("data", "null"),
            o.optString("headline"), o.optString("source"), o.optString("script"), o.optInt("everyMinutes"), o.optBoolean("appsAllowed"), o.optBoolean("pinned"),
            o.optLong("createdAt"), o.optLong("updatedAt"), o.optString("error"))

        /** "Monitor prices!" -> "monitor-prices" */
        fun slug(title: String) = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "card" }
    }
}

/** Cards as JSON files (no database migration, nothing to lose on an app update). */
class CardStore(context: Context) {
    private val dir = File(context.filesDir, "cards").apply { mkdirs() }
    private val _cards = MutableStateFlow(load())
    val cards: StateFlow<List<Card>> = _cards

    private fun load(): List<Card> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { Card.fromJson(JSONObject(f.readText())) }.getOrNull() }.sortedBy { it.createdAt }

    fun get(id: String): Card? = _cards.value.firstOrNull { it.id == id }

    @Synchronized fun put(card: Card): Card {
        File(dir, "${card.id}.json").writeText(card.toJson().toString())
        _cards.value = (_cards.value.filter { it.id != card.id } + card).sortedBy { it.createdAt }
        return card
    }

    @Synchronized fun delete(id: String) { File(dir, "$id.json").delete(); _cards.value = _cards.value.filter { it.id != id } }

    @Synchronized fun update(id: String, edit: (Card) -> Card): Card? = get(id)?.let { put(edit(it)) }
}

/** Turns a card into the page the WebView shows: theme variables, the kit, then the agent's HTML. */
object CardKit {
    private var cssCache: String? = null
    private var jsCache: String? = null
    private var guideCache: String? = null

    fun guide(context: Context): String = guideCache ?: context.assets.open("cards/GUIDE.md").bufferedReader().readText().also { guideCache = it }
    private fun css(context: Context) = cssCache ?: context.assets.open("cards/agent-ui.css").bufferedReader().readText().also { cssCache = it }
    private fun js(context: Context) = jsCache ?: context.assets.open("cards/kit.js").bufferedReader().readText().also { jsCache = it }

    private fun hex(c: Color): String = String.format("#%06X", 0xFFFFFF and c.toArgb())

    /** The live theme as CSS variables: the same values the native screens use. */
    fun themeVars(dark: Boolean, accent: String): String {
        val (cs, extra) = appColors(dark, accent)
        val v = linkedMapOf(
            "primary" to cs.primary, "on-primary" to cs.onPrimary, "primary-container" to cs.primaryContainer, "on-primary-container" to cs.onPrimaryContainer,
            "secondary" to cs.secondary, "secondary-container" to cs.secondaryContainer, "on-secondary-container" to cs.onSecondaryContainer,
            "surface" to cs.surface, "surface-container-lowest" to cs.surfaceContainerLowest, "surface-container-low" to cs.surfaceContainerLow,
            "surface-container" to cs.surfaceContainer, "surface-container-high" to cs.surfaceContainerHigh, "surface-container-highest" to cs.surfaceContainerHighest,
            "on-surface" to cs.onSurface, "on-surface-variant" to cs.onSurfaceVariant, "outline" to cs.outline, "outline-variant" to cs.outlineVariant,
            "error" to cs.error, "error-container" to cs.errorContainer, "on-error-container" to cs.onErrorContainer,
            "success" to extra.success, "success-container" to extra.successContainer,
            "warn" to cs.tertiary, "warn-container" to cs.tertiaryContainer, "on-warn-container" to cs.onTertiaryContainer,
        )
        return ":root{color-scheme:${if (dark) "dark" else "light"};" + v.entries.joinToString("") { "--${it.key}:${hex(it.value)};" } + "}"
    }

    fun page(context: Context, html: String, dark: Boolean, accent: String): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">")
        // Nothing leaves the card: images may load (product photos), nothing else.
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src https: data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:\">")
        append("<style>").append(themeVars(dark, accent)).append(css(context)).append("</style>")
        append("<script>").append(js(context)).append("</script></head><body>")
        append(html)
        append("</body></html>")
    }
}

/**
 * The page's bridge. Only four things cross it: the card's data, running its script with an input,
 * opening a link in the app, and asking the agent about the card.
 */
class CardBridge(
    private val data: () -> String,
    private val onRun: (callId: String, input: String) -> Unit = { _, _ -> },
    private val onOpen: (String) -> Unit = {},
    private val onAsk: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    @JavascriptInterface fun data(): String = data.invoke()
    @JavascriptInterface fun run(id: String, input: String) = onRun(id, input)
    @JavascriptInterface fun open(url: String) = onOpen(url)
    @JavascriptInterface fun ask(text: String) = onAsk(text)
    @JavascriptInterface fun error(text: String) = onError(text)
}

object CardWeb {
    /** A WebView set up for cards: JS for the kit, no file or content access, no navigation away, transparent. */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(wv: WebView, bridge: CardBridge, onLink: (String) -> Unit) {
        wv.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = false; allowFileAccess = false; allowContentAccess = false
            blockNetworkLoads = false // images only, enforced by the page's CSP
            builtInZoomControls = false; displayZoomControls = false; setSupportZoom(false)
            textZoom = 100
        }
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.addJavascriptInterface(bridge, "AppBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { onLink(request.url.toString()); return true }
        }
    }

    fun load(wv: WebView, page: String) = wv.loadDataWithBaseURL("https://card.local/", page, "text/html", "utf-8", null)

    /**
     * Renders a card offscreen at phone width and saves a PNG: what a helper sees to check its work.
     * Returns null where WebView can't draw (tests).
     */
    suspend fun screenshot(context: Context, card: Card, dark: Boolean, accent: String, out: File): File? = withContext(Dispatchers.Main) {
        runCatching {
            val dm = context.resources.displayMetrics
            val w = (390 * dm.density).toInt()
            val wv = WebView(context)
            val loaded = CompletableDeferred<Unit>()
            configure(wv, CardBridge({ card.data }), {})
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            fun size(h: Int) { wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); wv.layout(0, 0, w, h) }
            size((844 * dm.density).toInt())
            load(wv, CardKit.page(context, card.html, dark, accent))
            withTimeoutOrNull(8000) { loaded.await() }
            delay(700)
            val h = (wv.contentHeight * dm.density).toInt().coerceIn((200 * dm.density).toInt(), (2400 * dm.density).toInt())
            size(h); delay(300)
            val bg = appColors(dark, accent).first.surfaceContainerLow.toArgb()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply { drawColor(bg); wv.draw(this) }
            wv.destroy()
            out.parentFile?.mkdirs()
            // Halve it: plenty for a model to judge layout, a quarter of the bytes.
            val small = Bitmap.createScaledBitmap(bmp, w / 2, h / 2, true)
            out.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out
        }.getOrNull()
    }
}
