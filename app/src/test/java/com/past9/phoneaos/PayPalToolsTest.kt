package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.paypal.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The PayPal tool layer with PayPal faked at the HTTP edge: what each tool sends, and above all that
 * nothing that moves money or sends in the user's name runs without the user's Approve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PayPalToolsTest {
    /** Scripted PayPal: answers by "METHOD path-prefix", records every request. */
    class FakePayPal : PayPalTransport {
        val calls = mutableListOf<Triple<String, String, String?>>()
        val headers = mutableListOf<Map<String, String>>()
        val routes = linkedMapOf<String, (String?) -> Pair<Int, String>>()
        var tokens = 0
        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
            val path = url.substringAfter(".paypal.com")
            calls += Triple(method, path, body); this.headers += headers
            if (path == "/v1/oauth2/token") { tokens++; return 200 to """{"access_token":"fake-test-token-$tokens","expires_in":32400}""" }
            val hit = routes.entries.firstOrNull { (k, _) -> "$method $path".startsWith(k) } ?: return 404 to """{"name":"RESOURCE_NOT_FOUND","message":"no route for $method $path"}"""
            return hit.value(body)
        }
        fun posted(prefix: String) = calls.any { it.first == "POST" && it.second.startsWith(prefix) }
    }

    class Ctx(private val answer: String?) : ToolContext {
        override val agentLabel = "main"
        val asked = mutableListOf<String>()
        override suspend fun activity(text: String, meta: JSONObject) = 0L
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? { asked += question; return answer }
        override suspend fun notify(title: String, body: String) {}
    }

    private val fake = FakePayPal()
    private val api = PayPalApi({ PayPalCreds("test-client-id", "test-client-secret") }, fake)
    private val ledger = PayPalLedger(File.createTempFile("ledger", ".json").also { it.delete() })
    private val tools = paypalTools(api, ledger).associateBy { it.spec.name }
    private fun run(name: String, input: JSONObject, ctx: ToolContext) = runBlocking { tools.getValue(name).run(input, ctx) }

    private val invoice = JSONObject("""{"id":"INV2-AAAA","status":"DRAFT","detail":{"invoice_number":"0007","currency_code":"USD","payment_term":{"due_date":"2026-10-16"}},
        "primary_recipients":[{"billing_info":{"email_address":"thabo@example.com","name":{"given_name":"Thabo","surname":"Nkosi"}}}],
        "items":[{"name":"Logo work","quantity":"1","unit_amount":{"currency_code":"USD","value":"25.00"}}],"amount":{"currency_code":"USD","value":"25.00"}}""")
    private val approvedOrder = """{"id":"ORD-1","status":"APPROVED","payer":{"email_address":"sipho@example.com","name":{"given_name":"Sipho","surname":"Dlamini"}},
        "purchase_units":[{"description":"Braai pack","amount":{"currency_code":"USD","value":"30.00"}}]}"""
    private val completedOrder = """{"id":"ORD-1","status":"COMPLETED","payer":{"email_address":"sipho@example.com","name":{"given_name":"Sipho"}},
        "purchase_units":[{"description":"Braai pack","amount":{"currency_code":"USD","value":"30.00"},"payments":{"captures":[{"id":"CAP-9","status":"COMPLETED","amount":{"currency_code":"USD","value":"30.00"}}]}}]}"""

    @Test fun draftInvoiceBuildsPayPalsShapeAndNeedsNoApproval() {
        fake.routes["POST /v2/invoicing/invoices"] = { _ -> 201 to invoice.toString() }
        val ctx = Ctx("Decline")
        val out = run("paypal_create_invoice", JSONObject().put("recipient_email", "thabo@example.com").put("recipient_name", "Thabo Nkosi")
            .put("items", JSONArray().put(JSONObject().put("name", "Logo work").put("unit_amount", "25"))).put("due_date", "2026-10-16"), ctx)
        assertTrue(out, out.contains("Draft saved") && out.contains("INV2-AAAA") && out.contains("USD 25.00"))
        assertTrue("a draft is not a money action", ctx.asked.isEmpty())
        val body = JSONObject(fake.calls.last { it.second == "/v2/invoicing/invoices" }.third!!)
        assertEquals("25.00", body.getJSONArray("items").getJSONObject(0).getJSONObject("unit_amount").getString("value"))
        assertEquals("Thabo", body.getJSONArray("primary_recipients").getJSONObject(0).getJSONObject("billing_info").getJSONObject("name").getString("given_name"))
        assertEquals("2026-10-16", body.getJSONObject("detail").getJSONObject("payment_term").getString("due_date"))
        assertEquals("return=representation", fake.headers.last()["Prefer"])
        assertTrue(fake.headers.last()["PayPal-Request-Id"]!!.startsWith("agent-inv-"))
        assertEquals("INV2-AAAA", ledger.recent(1).first().getString("id"))
    }

    @Test fun randIsRefusedWithAWayForward() {
        val out = run("paypal_create_invoice", JSONObject().put("recipient_email", "a@b.co").put("currency", "ZAR")
            .put("items", JSONArray().put(JSONObject().put("name", "x").put("unit_amount", "450"))), Ctx("Approve"))
        assertTrue(out, out.contains("rand") && out.contains("USD"))
        assertFalse(fake.posted("/v2/invoicing"))
    }

    @Test fun sendingAnInvoiceShowsPayPalsOwnInvoiceAndWaitsForApprove() {
        fake.routes["GET /v2/invoicing/invoices/INV2-AAAA"] = { _ -> 200 to invoice.toString() }
        fake.routes["POST /v2/invoicing/invoices/INV2-AAAA/send"] = { _ -> 200 to """{"rel":"payer-view","href":"https://www.sandbox.paypal.com/invoice/p/#INV2-AAAA"}""" }
        val declined = Ctx("Decline")
        assertTrue(run("paypal_send_invoice", JSONObject().put("invoice_id", "INV2-AAAA"), declined).contains("declined"))
        assertFalse("declined: nothing sent", fake.posted("/v2/invoicing/invoices/INV2-AAAA/send"))
        val card = declined.asked.single()
        assertTrue(card, card.startsWith("APPROVAL|Send PayPal invoice to Thabo Nkosi for USD 25.00|"))
        assertTrue(card, card.contains("thabo@example.com") && card.contains("Logo work") && card.contains("Due: 2026-10-16") && card.contains("sandbox: test money"))

        assertTrue(run("paypal_send_invoice", JSONObject().put("invoice_id", "INV2-AAAA"), Ctx(null)).contains("Not done"))
        assertFalse("nobody around: nothing sent", fake.posted("/v2/invoicing/invoices/INV2-AAAA/send"))

        val out = run("paypal_send_invoice", JSONObject().put("invoice_id", "INV2-AAAA"), Ctx("Approve"))
        assertTrue(out, out.startsWith("Sent.") && out.contains("invoice/p/#INV2-AAAA"))
        assertTrue(fake.posted("/v2/invoicing/invoices/INV2-AAAA/send"))
    }

    @Test fun captureWaitsForTheBuyerThenForTheUser() {
        fake.routes["GET /v2/checkout/orders/ORD-0"] = { _ -> 200 to """{"id":"ORD-0","status":"CREATED","links":[{"rel":"approve","href":"https://www.sandbox.paypal.com/checkoutnow?token=ORD-0"}],"purchase_units":[{"amount":{"currency_code":"USD","value":"30.00"}}]}""" }
        val early = Ctx("Approve")
        val notYet = run("paypal_capture_order", JSONObject().put("order_id", "ORD-0"), early)
        assertTrue(notYet, notYet.contains("hasn't paid") && notYet.contains("checkoutnow"))
        assertTrue("no card when there's nothing to collect", early.asked.isEmpty())

        fake.routes["GET /v2/checkout/orders/ORD-1"] = { _ -> 200 to approvedOrder }
        fake.routes["POST /v2/checkout/orders/ORD-1/capture"] = { _ -> 201 to completedOrder }
        val no = Ctx("Decline")
        run("paypal_capture_order", JSONObject().put("order_id", "ORD-1"), no)
        assertFalse(fake.posted("/v2/checkout/orders/ORD-1/capture"))
        assertTrue(no.asked.single(), no.asked.single().startsWith("APPROVAL|Collect USD 30.00 from Sipho Dlamini (sipho@example.com)|"))

        val out = run("paypal_capture_order", JSONObject().put("order_id", "ORD-1"), Ctx("Approve"))
        assertTrue(out, out.contains("CAP-9") && out.contains("COMPLETED"))
        assertEquals("agent-capture-ORD-1", fake.headers.last()["PayPal-Request-Id"])
    }

    @Test fun refundResolvesTheCaptureFromTheOrderAndShowsBothAmounts() {
        fake.routes["GET /v2/checkout/orders/ORD-1"] = { _ -> 200 to completedOrder }
        fake.routes["GET /v2/payments/captures/CAP-9"] = { _ -> 200 to """{"id":"CAP-9","status":"COMPLETED","amount":{"currency_code":"USD","value":"30.00"}}""" }
        fake.routes["POST /v2/payments/captures/CAP-9/refund"] = { _ -> 201 to """{"id":"REF-5","status":"COMPLETED","amount":{"currency_code":"USD","value":"10.00"}}""" }
        val no = Ctx("Decline")
        run("paypal_refund", JSONObject().put("order_id", "ORD-1").put("amount", "10"), no)
        assertFalse(fake.posted("/v2/payments/captures/CAP-9/refund"))
        val card = no.asked.single()
        assertTrue(card, card.startsWith("APPROVAL|Refund USD 10.00 (part) to Sipho") && card.contains("Original payment: USD 30.00"))
        val out = run("paypal_refund", JSONObject().put("order_id", "ORD-1").put("amount", "10"), Ctx("Approve"))
        assertTrue(out, out.contains("REF-5") && out.contains("USD 10.00"))
        assertEquals("10.00", JSONObject(fake.calls.last().third!!).getJSONObject("amount").getString("value"))
    }

    @Test fun refundingTheRestShowsWhatIsReallyLeft() {
        // Found against the real sandbox: after a part refund, the card for "the rest" said the full amount.
        val partlyRefunded = completedOrder.replace("\"captures\":[{\"id\":\"CAP-9\",\"status\":\"COMPLETED\"", "\"refunds\":[{\"id\":\"REF-5\",\"status\":\"COMPLETED\",\"amount\":{\"currency_code\":\"USD\",\"value\":\"10.00\"}}],\"captures\":[{\"id\":\"CAP-9\",\"status\":\"PARTIALLY_REFUNDED\"")
        fake.routes["GET /v2/checkout/orders/ORD-1"] = { _ -> 200 to partlyRefunded }
        fake.routes["GET /v2/payments/captures/CAP-9"] = { _ -> 200 to """{"id":"CAP-9","status":"PARTIALLY_REFUNDED","amount":{"currency_code":"USD","value":"30.00"}}""" }
        val no = Ctx("Decline")
        run("paypal_refund", JSONObject().put("order_id", "ORD-1"), no)
        val card = no.asked.single()
        assertTrue(card, card.startsWith("APPROVAL|Refund USD 20.00 (the rest)") && card.contains("Already refunded: USD 10.00"))
        val tooMuch = run("paypal_refund", JSONObject().put("order_id", "ORD-1").put("amount", "25"), Ctx("Approve"))
        assertTrue(tooMuch, tooMuch.contains("Only USD 20.00 is left"))
        assertFalse(fake.posted("/v2/payments/captures/CAP-9/refund"))
    }

    @Test fun everyMoneyToolStopsAtACardBeforeAnyWrite() {
        fake.routes["GET /v2/invoicing/invoices/"] = { _ -> 200 to invoice.toString() }
        fake.routes["GET /v2/checkout/orders/"] = { _ -> 200 to approvedOrder.replace("APPROVED", "APPROVED") }
        fake.routes["GET /v2/payments/captures/"] = { _ -> 200 to """{"id":"CAP-9","status":"COMPLETED","amount":{"currency_code":"USD","value":"30.00"}}""" }
        val input = JSONObject().put("invoice_id", "INV2-AAAA").put("order_id", "ORD-1").put("capture_id", "CAP-9")
        for (name in PAYPAL_MONEY_TOOLS) {
            val ctx = Ctx("Decline"); val before = fake.calls.size
            run(name, input, ctx)
            assertEquals("$name must ask once", 1, ctx.asked.size)
            assertTrue("$name: approval card", ctx.asked.single().startsWith("APPROVAL|"))
            assertTrue("$name wrote after a Decline", fake.calls.drop(before).none { it.first == "POST" && it.second != "/v1/oauth2/token" })
        }
    }

    @Test fun tokenIsReusedAndA401RetriesOnce() {
        var first = true
        fake.routes["GET /v2/checkout/orders/ORD-1"] = { _ -> if (first) { first = false; 401 to """{"error":"invalid_token"}""" } else 200 to approvedOrder }
        val out = run("paypal_get_order", JSONObject().put("order_id", "ORD-1"), Ctx(null))
        assertTrue(out, out.contains("APPROVED"))
        assertEquals(2, fake.tokens)
        run("paypal_get_order", JSONObject().put("order_id", "ORD-1"), Ctx(null))
        assertEquals("cached", 2, fake.tokens)
    }

    @Test fun aFeatureSwitchedOnLaterIsPickedUpWithAFreshToken() {
        // PayPal keeps handing back the cached token, which lacks a newly enabled feature, until it's terminated.
        fake.routes["POST /v1/oauth2/token/terminate"] = { _ -> 200 to "" }
        fake.routes["GET /v2/invoicing/invoices"] = { _ ->
            if (fake.calls.any { it.second == "/v1/oauth2/token/terminate" }) 200 to """{"items":[]}"""
            else 403 to """{"name":"NOT_AUTHORIZED","message":"Authorization failed due to insufficient permissions."}"""
        }
        val out = run("paypal_list_invoices", JSONObject(), Ctx(null))
        assertTrue(out, out.startsWith("No invoices"))
        assertEquals(2, fake.tokens)
    }

    @Test fun permissionErrorsSayWhatToSwitchOn() {
        fake.routes["GET /v2/invoicing/invoices"] = { _ -> 403 to """{"name":"NOT_AUTHORIZED","message":"Authorization failed due to insufficient permissions.","details":[{"issue":"NOT_AUTHORIZED"}]}""" }
        val out = run("paypal_list_invoices", JSONObject(), Ctx(null))
        assertTrue(out, out.contains("HTTP 403") && out.contains("Features"))
    }

    @Test fun amountsAndCurrencies() {
        assertEquals("450.00", PayPalApi.amount("450", "USD"))
        assertEquals("1234.50", PayPalApi.amount("1,234.5", "USD"))
        assertEquals("500", PayPalApi.amount("499.6", "JPY"))
        assertThrows(PayPalException::class.java) { PayPalApi.amount("-3", "USD") }
        assertThrows(PayPalException::class.java) { PayPalApi.currency("ZAR") }
        assertEquals("USD", PayPalApi.currency(""))
    }

    @Test fun recentAnswersTheLastOrder() {
        fake.routes["POST /v2/checkout/orders"] = { _ -> 201 to """{"id":"ORD-7","status":"CREATED","links":[{"rel":"approve","href":"https://www.sandbox.paypal.com/checkoutnow?token=ORD-7"}]}""" }
        val made = run("paypal_create_order", JSONObject().put("amount", "12").put("description", "Coffee beans").put("buyer", "Lerato"), Ctx(null))
        assertTrue(made, made.contains("checkoutnow?token=ORD-7"))
        val recent = run("paypal_recent", JSONObject().put("kind", "order"), Ctx(null))
        assertTrue(recent, recent.contains("ORD-7") && recent.contains("USD 12.00") && recent.contains("Lerato"))
    }

    @Test fun theAgentOnlyHasPayPalOnceTheUserAddedIt() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = SettingsStore(app); s.setPayPal(null, null)
        val rt = AgentRuntime(app, AppDb.inMemory(app), s, CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone())
        assertTrue(rt.tools().none { it.spec.name.startsWith("paypal_") })
        s.setPayPal("test-client-id", "test-client-secret")
        val names = rt.tools().map(Tool::spec).map { it.name }
        assertTrue(names.toString(), names.containsAll(listOf("paypal_create_invoice", "paypal_send_invoice", "paypal_capture_order", "paypal_refund", "paypal_list_transactions", "paypal_recent")))
        assertTrue(rt.systemPrompt("invoice Thabo").contains("PayPal is connected (PayPal sandbox: test money)"))
        s.setPayPal(null, null)
    }
}
