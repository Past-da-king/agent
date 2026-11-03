package com.past9.phoneaos.paypal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

/**
 * The user's own PayPal REST app. Sandbox unless [live]: the hackathon build only ever runs sandbox,
 * and live mode is not offered in the app yet (see docs/paypal-hackathon.md).
 */
data class PayPalCreds(val clientId: String, val secret: String, val live: Boolean = false) {
    val base: String get() = if (live) "https://api-m.paypal.com" else "https://api-m.sandbox.paypal.com"
    /** Shown on every approval card, so a test payment is never mistaken for a real one (or the other way round). */
    val label: String get() = if (live) "LIVE PayPal: real money" else "PayPal sandbox: test money"
}

class PayPalException(message: String, val status: Int = 0) : Exception(message)

/** One HTTP exchange, blocking. Swappable so the tool layer is tested without the network. */
fun interface PayPalTransport {
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String>
}

object OkHttpPayPalTransport : PayPalTransport {
    private val http by lazy { OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build() }
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val type = (headers["Content-Type"] ?: "application/json").toMediaType()
        val rb = body?.toRequestBody(type) ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(type) else null
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.method(method, rb).build()
        return http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
    }
}

/**
 * PayPal's REST API, the parts an agent needs: invoicing, checkout orders, captures, refunds, transaction search.
 * Token cached until a minute before it expires; a 401 clears it and retries once.
 */
class PayPalApi(
    private val creds: () -> PayPalCreds?,
    private val transport: PayPalTransport = OkHttpPayPalTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var token: String? = null
    private var tokenKey: String? = null
    private var expiresAt = 0L

    val configured: Boolean get() = creds() != null
    val label: String get() = creds()?.label ?: "PayPal"

    private fun need(): PayPalCreds = creds() ?: throw PayPalException(
        "PayPal isn't set up on this phone. The user adds their PayPal app's client ID and secret under Connections, PayPal.")

    @Synchronized
    fun accessToken(): String {
        val c = need()
        val key = c.clientId + "|" + c.live
        token?.let { if (tokenKey == key && clock() < expiresAt - 60_000) return it }
        val basic = java.util.Base64.getEncoder().encodeToString("${c.clientId}:${c.secret}".toByteArray())
        val (code, body) = transport.send("POST", c.base + "/v1/oauth2/token",
            mapOf("Authorization" to "Basic $basic", "Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            "grant_type=client_credentials")
        if (code != 200) throw PayPalException("PayPal refused this app's client ID and secret (HTTP $code). Check them under Connections, PayPal.", code)
        val j = JSONObject(body)
        token = j.getString("access_token"); tokenKey = key
        expiresAt = clock() + j.optLong("expires_in", 3000) * 1000
        return token!!
    }

    /** End the current token at PayPal so the next one carries the app's current features. */
    @Synchronized
    fun refreshScopes() {
        val c = need()
        val t = token ?: runCatching { accessToken() }.getOrNull() ?: return
        val basic = java.util.Base64.getEncoder().encodeToString("${c.clientId}:${c.secret}".toByteArray())
        runCatching {
            transport.send("POST", c.base + "/v1/oauth2/token/terminate",
                mapOf("Authorization" to "Basic $basic", "Content-Type" to "application/x-www-form-urlencoded"),
                "token=" + java.net.URLEncoder.encode(t, "UTF-8") + "&token_type_hint=ACCESS_TOKEN")
        }
        token = null
    }

    suspend fun call(method: String, path: String, body: JSONObject? = null, requestId: String? = null, representation: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) { callBlocking(method, path, body, requestId, representation) }

    fun callBlocking(method: String, path: String, body: JSONObject? = null, requestId: String? = null, representation: Boolean = false): JSONObject {
        val c = need()
        fun once(): Pair<Int, String> {
            val h = linkedMapOf("Authorization" to "Bearer ${accessToken()}", "Content-Type" to "application/json", "Accept" to "application/json")
            requestId?.let { h["PayPal-Request-Id"] = it }
            if (representation) h["Prefer"] = "return=representation"
            return transport.send(method, c.base + path, h, body?.toString() ?: if (method == "POST") "{}" else null)
        }
        var (code, text) = once()
        if (code == 401) { synchronized(this) { token = null }; once().let { code = it.first; text = it.second } }
        // A feature switched on after the token was issued (Invoicing, Transaction search) only shows up in a NEW token,
        // and PayPal hands back the same cached token for hours. So on NOT_AUTHORIZED: end that token, get a fresh one, try once more.
        if (code == 403 && text.contains("NOT_AUTHORIZED")) {
            refreshScopes()
            once().let { code = it.first; text = it.second }
        }
        if (code !in 200..299) throw PayPalException(describeError(code, text), code)
        val t = text.trim()
        return when {
            t.isEmpty() -> JSONObject()
            t.startsWith("[") -> JSONObject().put("items", JSONArray(t))
            else -> JSONObject(t)
        }
    }

    companion object {
        /** PayPal's error in one readable line: name, message and each issue with its field. */
        fun describeError(code: Int, text: String): String {
            val j = runCatching { JSONObject(text) }.getOrNull() ?: return "PayPal error (HTTP $code): ${text.take(300)}"
            val issues = j.optJSONArray("details")?.let { d -> (0 until d.length()).map { d.getJSONObject(it) }.joinToString("; ") { i ->
                listOf(i.optString("issue"), i.optString("description"), i.optString("field")).filter { it.isNotBlank() }.joinToString(": ")
            } }.orEmpty()
            val head = listOf(j.optString("name"), j.optString("message"), j.optString("error"), j.optString("error_description")).filter { it.isNotBlank() }.joinToString(": ")
            val hint = when {
                code == 403 && text.contains("NOT_AUTHORIZED") -> " This PayPal app isn't allowed to do that: switch the feature on for the app in the PayPal developer dashboard (Apps & Credentials, the app, Features)."
                else -> ""
            }
            return "PayPal said no (HTTP $code): $head${if (issues.isNotBlank()) " [$issues]" else ""}$hint"
        }

        private val ZERO_DECIMAL = setOf("JPY", "HUF", "TWD")
        /** PayPal's supported currencies. The rand is not one of them. */
        val CURRENCIES = setOf("AUD", "BRL", "CAD", "CNY", "CZK", "DKK", "EUR", "HKD", "HUF", "ILS", "JPY", "MYR", "MXN", "TWD", "NZD", "NOK", "PHP", "PLN", "GBP", "SGD", "SEK", "CHF", "THB", "USD")

        /** "450" -> "450.00" (or "450" for zero-decimal currencies). Throws on anything that isn't a positive amount. */
        fun amount(value: String, currency: String): String {
            val cleaned = value.trim().replace(Regex("[,\\s]"), "").removePrefix("$").removePrefix("R")
            val n = cleaned.toBigDecimalOrNull() ?: throw PayPalException("\"$value\" isn't an amount.")
            if (n <= BigDecimal.ZERO) throw PayPalException("The amount must be more than zero.")
            return n.setScale(if (currency in ZERO_DECIMAL) 0 else 2, RoundingMode.HALF_UP).toPlainString()
        }

        fun currency(code: String?): String {
            val c = code?.trim()?.uppercase()?.ifEmpty { null } ?: "USD"
            if (c == "ZAR") throw PayPalException("PayPal doesn't take rand (ZAR). Use USD (or the user's PayPal currency) and tell the user the amount you converted to.")
            if (c !in CURRENCIES) throw PayPalException("PayPal doesn't support $c. Supported: ${CURRENCIES.sorted().joinToString(", ")}.")
            return c
        }

        fun money(o: JSONObject?): String = if (o == null) "?" else "${o.optString("currency_code")} ${o.optString("value")}"

        /** The last part of an href like ".../invoices/INV2-ABCD". */
        fun idFromHref(href: String): String = href.substringAfterLast('/').substringBefore('?')

        fun link(o: JSONObject, vararg rels: String): String? = o.optJSONArray("links")?.let { l ->
            (0 until l.length()).map { l.getJSONObject(it) }.firstOrNull { it.optString("rel") in rels }?.optString("href")
        }
    }
}
