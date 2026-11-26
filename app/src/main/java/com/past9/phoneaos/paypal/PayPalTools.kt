package com.past9.phoneaos.paypal

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.paypal.PayPalApi.Companion.amount
import com.past9.phoneaos.paypal.PayPalApi.Companion.currency
import com.past9.phoneaos.paypal.PayPalApi.Companion.idFromHref
import com.past9.phoneaos.paypal.PayPalApi.Companion.link
import com.past9.phoneaos.paypal.PayPalApi.Companion.money
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * PayPal for the agent. Tool names follow PayPal's own Agent Toolkit (create_invoice, send_invoice, create_order,
 * pay_order, create_refund, list_transactions...) with a paypal_ prefix, so the shapes match what PayPal documents.
 *
 * The rule the whole layer is built around: a tool that moves money or sends something in the user's name
 * (send an invoice, cancel one, capture a payment, refund) fetches the real object from PayPal FIRST and shows
 * the user an approval card built from PayPal's own data, never from what the model claims. Nothing runs
 * until they tap Approve. Background runs (routines, nobody around) can't approve, so they never move money.
 * Read-only and draft tools (create a draft invoice, make a payment link, look things up) run straight away.
 */

/** The PayPal things this phone made, newest last, so "refund the last order" and "did Sipho pay?" resolve without a search. */
class PayPalLedger(private val file: File) {
    @Synchronized fun add(kind: String, id: String, who: String, amount: String, note: String = "", extra: JSONObject = JSONObject()) {
        val all = read()
        all.put(JSONObject().put("kind", kind).put("id", id).put("who", who).put("amount", amount).put("note", note).put("at", System.currentTimeMillis()).put("extra", extra))
        val trimmed = JSONArray().apply { val from = maxOf(0, all.length() - 300); for (i in from until all.length()) put(all.get(i)) }
        file.parentFile?.mkdirs(); file.writeText(trimmed.toString())
    }
    @Synchronized fun recent(n: Int = 20, kind: String? = null): List<JSONObject> {
        val all = read()
        return (0 until all.length()).map { all.getJSONObject(it) }.filter { kind == null || it.optString("kind") == kind }.takeLast(n).reversed()
    }
    private fun read(): JSONArray = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
}

internal fun objSchema(desc: String, required: List<String>, vararg props: Pair<String, JSONObject>) =
    schema(required, *props).put("description", desc)
internal fun arrayOf(desc: String, item: JSONObject) = JSONObject().put("type", "array").put("description", desc).put("items", item)

/** Ask the user. Null means approved; anything else is the tool's answer to the model. */
internal suspend fun approveMoney(api: PayPalApi, ctx: ToolContext, action: String, details: String): String? {
    val a = ctx.ask("APPROVAL|$action|$details\n${api.label}", listOf("Approve", "Decline"))
    return when (a) {
        "Approve" -> null
        null -> "Not done. PayPal money actions only run after the user taps Approve, and nobody could answer (background run). Tell the user it's waiting for them."
        else -> "The user declined. Not done."
    }
}

private suspend fun guarded(ctx: ToolContext, title: String, block: suspend () -> String): String = try {
    block()
} catch (e: PayPalException) {
    e.message ?: "PayPal error."
} catch (e: java.io.IOException) {
    "Couldn't reach PayPal ($title): ${e.message}"
}

private fun day(s: String?): String? = s?.takeIf { it.isNotBlank() }?.let {
    runCatching { LocalDate.parse(it.trim()).toString() }.getOrElse { throw PayPalException("Dates go as YYYY-MM-DD, e.g. 2026-10-16 (got \"$s\").") }
}

private fun invoiceSummary(inv: JSONObject): String = buildString {
    val d = inv.optJSONObject("detail") ?: JSONObject()
    val r = inv.optJSONArray("primary_recipients")?.optJSONObject(0)?.optJSONObject("billing_info")
    val name = r?.optJSONObject("name")?.let { listOf(it.optString("given_name"), it.optString("surname")).filter { s -> s.isNotBlank() }.joinToString(" ") }.orEmpty()
    val email = r?.optString("email_address").orEmpty()
    appendLine("To: ${listOf(name, if (email.isNotBlank()) "($email)" else "").filter { it.isNotBlank() }.joinToString(" ").ifBlank { "nobody set" }}")
    appendLine("Invoice #${d.optString("invoice_number")} (${inv.optString("id")})")
    inv.optJSONArray("items")?.let { items -> for (i in 0 until items.length()) { val it = items.getJSONObject(i)
        appendLine("- ${it.optString("quantity", "1")} x ${it.optString("name")} @ ${money(it.optJSONObject("unit_amount"))}") } }
    appendLine("Total: ${money(inv.optJSONObject("amount"))}")
    d.optJSONObject("payment_term")?.optString("due_date")?.takeIf { it.isNotBlank() }?.let { appendLine("Due: $it") }
    d.optString("note").takeIf { it.isNotBlank() }?.let { appendLine("Note: $it") }
}.trim()

private fun recipientOf(inv: JSONObject): Pair<String, String> {
    val r = inv.optJSONArray("primary_recipients")?.optJSONObject(0)?.optJSONObject("billing_info")
    val name = r?.optJSONObject("name")?.let { listOf(it.optString("given_name"), it.optString("surname")).filter { s -> s.isNotBlank() }.joinToString(" ") }.orEmpty()
    return name to r?.optString("email_address").orEmpty()
}

// ---- invoices --------------------------------------------------------------------------------

class PayPalCreateInvoiceTool(private val api: PayPalApi, private val ledger: PayPalLedger) : Tool {
    override val spec = ToolSpec("paypal_create_invoice",
        "Draft a PayPal invoice (a payment request) for someone. This only makes a DRAFT: nothing reaches them until paypal_send_invoice, which shows the user an approval card first. PayPal has no rand (ZAR): use USD unless told otherwise, and say the amount you converted to.",
        schema(listOf("recipient_email", "items"),
            "recipient_email" to str("Who pays: their email"),
            "recipient_name" to str("Their name, e.g. 'Thabo Nkosi'"),
            "items" to arrayOf("What it's for: one or more lines", objSchema("A line", listOf("name", "unit_amount"),
                "name" to str("e.g. 'Logo design'"), "unit_amount" to str("Price of one, e.g. '45' or '45.50'"), "quantity" to str("How many, default 1"))),
            "currency" to str("ISO code, default USD. Not ZAR (PayPal doesn't take rand)."),
            "due_date" to str("YYYY-MM-DD; leave out for due on receipt"),
            "note" to str("A short note to them on the invoice")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String = guarded(ctx, "invoice") {
        val cur = currency(input.optString("currency"))
        val email = input.optString("recipient_email").trim()
        if (!email.contains("@")) throw PayPalException("recipient_email must be an email address.")
        val arr = input.optJSONArray("items") ?: throw PayPalException("items: at least one line, e.g. [{name:'Logo design', unit_amount:'45'}].")
        if (arr.length() == 0) throw PayPalException("items: at least one line.")
        val items = JSONArray()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            items.put(JSONObject().put("name", it.optString("name").ifBlank { "Item" }.take(200))
                .put("quantity", it.optString("quantity").ifBlank { "1" })
                .put("unit_amount", JSONObject().put("currency_code", cur).put("value", amount(it.optString("unit_amount"), cur))))
        }
        val name = input.optString("recipient_name").trim()
        val billing = JSONObject().put("email_address", email)
        if (name.isNotBlank()) billing.put("name", JSONObject().put("given_name", name.substringBefore(' ')).apply {
            name.substringAfter(' ', "").takeIf { it.isNotBlank() }?.let { put("surname", it) } })
        val detail = JSONObject().put("currency_code", cur)
        input.optString("note").takeIf { it.isNotBlank() }?.let { detail.put("note", it.take(4000)) }
        day(input.optString("due_date"))?.let { detail.put("payment_term", JSONObject().put("due_date", it)) }
            ?: detail.put("payment_term", JSONObject().put("term_type", "DUE_ON_RECEIPT"))
        val body = JSONObject().put("detail", detail).put("primary_recipients", JSONArray().put(JSONObject().put("billing_info", billing))).put("items", items)
        val res = api.call("POST", "/v2/invoicing/invoices", body, requestId = "agent-inv-" + UUID.randomUUID(), representation = true)
        val id = res.optString("id").ifBlank { idFromHref(res.optString("href")) }
        val inv = if (res.has("amount")) res else api.call("GET", "/v2/invoicing/invoices/$id")
        ledger.add("invoice", id, listOf(name, email).filter { it.isNotBlank() }.joinToString(" "), money(inv.optJSONObject("amount")), items.getJSONObject(0).optString("name"))
        ctx.activity("Drafted a PayPal invoice for ${name.ifBlank { email }}", JSONObject().put("tool", "paypal"))
        "Draft saved (status ${inv.optString("status", "DRAFT")}), not sent yet.\n${invoiceSummary(inv)}\nNext: paypal_send_invoice with invoice_id $id (the user approves first)."
    }
}

class PayPalSendInvoiceTool(private val api: PayPalApi, private val ledger: PayPalLedger) : Tool {
    override val spec = ToolSpec("paypal_send_invoice",
        "Send a drafted PayPal invoice to the person it's for. Shows the user an approval card with the invoice exactly as PayPal holds it, and only sends on Approve.",
        schema(listOf("invoice_id"), "invoice_id" to str("e.g. INV2-XXXX-XXXX-XXXX-XXXX"),
            "subject" to str("Optional email subject"), "note" to str("Optional short message with it")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String = guarded(ctx, "send invoice") {
        val id = input.optString("invoice_id").trim().ifEmpty { throw PayPalException("invoice_id is needed.") }
        val inv = api.call("GET", "/v2/invoicing/invoices/$id")
        val status = inv.optString("status")
        if (status != "DRAFT" && status != "SCHEDULED") return@guarded "Invoice $id is already $status, so it can't be sent again. ${invoiceSummary(inv)}"
        val (name, email) = recipientOf(inv)
        approveMoney(api, ctx, "Send PayPal invoice to ${name.ifBlank { email }} for ${money(inv.optJSONObject("amount"))}", invoiceSummary(inv))?.let { return@guarded it }
        val body = JSONObject().put("send_to_recipient", true).put("send_to_invoicer", false)
        input.optString("subject").takeIf { it.isNotBlank() }?.let { body.put("subject", it.take(400)) }
        input.optString("note").takeIf { it.isNotBlank() }?.let { body.put("note", it.take(4000)) }
        val res = api.call("POST", "/v2/invoicing/invoices/$id/send", body, requestId = "agent-send-$id")
        val payLink = res.optString("href").ifBlank { inv.optJSONObject("detail")?.optJSONObject("metadata")?.optString("recipient_view_url").orEmpty() }
        ledger.add("invoice_sent", id, listOf(name, email).filter { it.isNotBlank() }.joinToString(" "), money(inv.optJSONObject("amount")))
        ctx.activity("Sent a PayPal invoice to ${name.ifBlank { email }}", JSONObject().put("tool", "paypal"))
        "Sent. PayPal emailed ${email.ifBlank { "them" }} invoice $id for ${money(inv.optJSONObject("amount"))}." + (if (payLink.isNotBlank()) "\nTheir pay link: $payLink" else "")
    }
}

class PayPalGetInvoiceTool(private val api: PayPalApi) : Tool {
    override val spec = ToolSpec("paypal_get_invoice", "Check a PayPal invoice: status (DRAFT, SENT/UNPAID, PAID, PARTIALLY_PAID, CANCELLED...), what's been paid and the pay link.",
        schema(listOf("invoice_id"), "invoice_id" to str("e.g. INV2-XXXX-XXXX-XXXX-XXXX")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = guarded(ctx, "invoice") {
        val inv = api.call("GET", "/v2/invoicing/invoices/${input.optString("invoice_id").trim()}")
        buildString {
            appendLine("Status: ${inv.optString("status")}")
            appendLine(invoiceSummary(inv))
            inv.optJSONObject("payments")?.let { p ->
                appendLine("Paid so far: ${money(p.optJSONObject("paid_amount"))}")
                p.optJSONArray("transactions")?.let { t -> for (i in 0 until t.length()) { val x = t.getJSONObject(i)
                    appendLine("- ${x.optString("payment_date")} ${money(x.optJSONObject("amount"))} via ${x.optString("method")} ${x.optString("payment_id")}") } }
            }
            inv.optJSONObject("due_amount")?.let { appendLine("Still due: ${money(it)}") }
            inv.optJSONObject("detail")?.optJSONObject("metadata")?.optString("recipient_view_url")?.takeIf { it.isNotBlank() }?.let { appendLine("Pay link: $it") }
        }.trim()
    }
}

class PayPalListInvoicesTool(private val api: PayPalApi) : Tool {
    override val spec = ToolSpec("paypal_list_invoices", "List PayPal invoices, newest first, optionally only one person's (name or email) or one status. Use for 'did Sipho pay?' or 'who still owes me?'.",
        schema(emptyList(), "who" to str("Part of a name or email to match"), "status" to str("e.g. UNPAID, PAID, DRAFT, SENT, CANCELLED"), "limit" to int("Default 10, max 50")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = guarded(ctx, "invoices") {
        val res = api.call("GET", "/v2/invoicing/invoices?page=1&page_size=100&total_required=true&fields=amount")
        val items = res.optJSONArray("items") ?: JSONArray()
        val who = input.optString("who").trim().lowercase()
        val status = input.optString("status").trim().uppercase()
        val rows = (0 until items.length()).map { items.getJSONObject(it) }.filter { inv ->
            val (n, e) = recipientOf(inv)
            (who.isEmpty() || n.lowercase().contains(who) || e.lowercase().contains(who)) &&
                (status.isEmpty() || inv.optString("status") == status || (status == "UNPAID" && inv.optString("status") in setOf("SENT", "UNPAID", "PARTIALLY_PAID")))
        }.sortedByDescending { it.optJSONObject("detail")?.optJSONObject("metadata")?.optString("create_time").orEmpty() }
            .take(input.optInt("limit", 10).coerceIn(1, 50))
        if (rows.isEmpty()) return@guarded "No invoices match." + (if (who.isNotEmpty()) " (looked for \"$who\")" else "")
        rows.joinToString("\n") { inv ->
            val (n, e) = recipientOf(inv); val d = inv.optJSONObject("detail")
            "${inv.optString("id")} · #${d?.optString("invoice_number")} · ${inv.optString("status")} · ${money(inv.optJSONObject("amount"))} · ${listOf(n, e).filter { it.isNotBlank() }.joinToString(" ")} · ${d?.optString("invoice_date")}"
        }
    }
}

class PayPalCancelInvoiceTool(private val api: PayPalApi, private val ledger: PayPalLedger) : Tool {
    override val spec = ToolSpec("paypal_cancel_invoice", "Cancel a sent PayPal invoice (they'll be told it's cancelled). Asks the user first.",
        schema(listOf("invoice_id"), "invoice_id" to str("The invoice id"), "note" to str("Optional reason they'll see")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = guarded(ctx, "cancel invoice") {
        val id = input.optString("invoice_id").trim()
        val inv = api.call("GET", "/v2/invoicing/invoices/$id")
        val (name, email) = recipientOf(inv)
        approveMoney(api, ctx, "Cancel PayPal invoice to ${name.ifBlank { email }} (${money(inv.optJSONObject("amount"))})", invoiceSummary(inv))?.let { return@guarded it }
        val body = JSONObject().put("send_to_recipient", true).put("send_to_invoicer", false)
        input.optString("note").takeIf { it.isNotBlank() }?.let { body.put("note", it) }
        api.call("POST", "/v2/invoicing/invoices/$id/cancel", body)
        ledger.add("invoice_cancelled", id, listOf(name, email).filter { it.isNotBlank() }.joinToString(" "), money(inv.optJSONObject("amount")))
        "Cancelled invoice $id."
    }
}

// ---- checkout orders (payment links), captures, refunds ----------------------------------------

private fun orderSummary(o: JSONObject): String = buildString {
    val pu = o.optJSONArray("purchase_units")?.optJSONObject(0)
    appendLine("Order ${o.optString("id")} · ${o.optString("status")}")
    pu?.optString("description")?.takeIf { it.isNotBlank() }?.let { appendLine("For: $it") }
    appendLine("Amount: ${money(pu?.optJSONObject("amount"))}")
    payerOf(o).takeIf { it.isNotBlank() }?.let { appendLine("Buyer: $it") }
}.trim()

private fun payerOf(o: JSONObject): String {
    val p = o.optJSONObject("payer") ?: o.optJSONObject("payment_source")?.optJSONObject("paypal")
    val n = p?.optJSONObject("name")?.let { listOf(it.optString("given_name"), it.optString("surname")).filter { s -> s.isNotBlank() }.joinToString(" ") }.orEmpty()
    val e = p?.optString("email_address").orEmpty()
    return listOf(n, if (e.isNotBlank()) "($e)" else "").filter { it.isNotBlank() }.joinToString(" ")
}

private fun captures(o: JSONObject): List<JSONObject> {
    val out = mutableListOf<JSONObject>()
    o.optJSONArray("purchase_units")?.let { pus -> for (i in 0 until pus.length()) {
        pus.getJSONObject(i).optJSONObject("payments")?.optJSONArray("captures")?.let { c -> for (j in 0 until c.length()) out += c.getJSONObject(j) }
    } }
    return out
}

class PayPalCreateOrderTool(private val api: PayPalApi, private val ledger: PayPalLedger) : Tool {
    override val spec = ToolSpec("paypal_create_order",
        "Make a PayPal checkout order: a pay link for an amount. No money moves until the buyer pays through the link AND the user approves collecting it (paypal_capture_order). Giving the link to someone through another app needs that app's own approval. PayPal has no rand (ZAR): use USD unless told otherwise.",
        schema(listOf("amount", "description"), "amount" to str("e.g. '30' or '30.00'"), "description" to str("What it's for, shown to the buyer"),
            "currency" to str("ISO code, default USD"), "buyer" to str("Who it's for (name or email), only for your own records")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = guarded(ctx, "order") {
        val cur = currency(input.optString("currency"))
        val value = amount(input.optString("amount"), cur)
        val body = JSONObject().put("intent", "CAPTURE").put("purchase_units", JSONArray().put(JSONObject()
            .put("description", input.optString("description").take(127))
            .put("amount", JSONObject().put("currency_code", cur).put("value", value))))
        val o = api.call("POST", "/v2/checkout/orders", body, requestId = "agent-order-" + UUID.randomUUID())
        val pay = link(o, "approve", "payer-action").orEmpty()
        ledger.add("order", o.optString("id"), input.optString("buyer"), "$cur $value", input.optString("description"), JSONObject().put("pay_link", pay))
        ctx.activity("Made a PayPal pay link for $cur $value", JSONObject().put("tool", "paypal"))
        "Order ${o.optString("id")} for $cur $value (${o.optString("status")}).\nPay link for the buyer: $pay\nOnce they've paid, paypal_capture_order collects it (the user approves first)."
    }
}

class PayPalGetOrderTool(private val api: PayPalApi) : Tool {
    override val spec = ToolSpec("paypal_get_order", "Check a PayPal order: CREATED (not paid yet), APPROVED (buyer paid, ready to collect), COMPLETED (collected), plus the buyer and any captures and refunds.",
        schema(listOf("order_id"), "order_id" to str("The order id")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = guarded(ctx, "order") {
        val o = api.call("GET", "/v2/checkout/orders/${input.optString("order_id").trim()}")
        buildString {
            appendLine(orderSummary(o))
            captures(o).forEach { c -> appendLine("Capture ${c.optString("id")}: ${c.optString("status")} ${money(c.optJSONObject("amount"))}") }
            if (o.optString("status") in setOf("CREATED", "PAYER_ACTION_REQUIRED")) link(o, "approve", "payer-action")?.let { appendLine("Not paid yet. Pay link: $it") }
        }.trim()
    }
}

class PayPalCaptureOrderTool(private val api: PayPalApi, private val ledger: PayPalLedger) : Tool {
    override val spec = ToolSpec("paypal_capture_order", "Collect the money for an order the buyer has approved in PayPal (PayPal's pay_order). Shows the user an approval card with PayPal's own amount and buyer, and only collects on Approve.",
        schema(listOf("order_id"), "order_id" to str("The order id")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = guarded(ctx, "capture") {
        val id = input.optString("order_id").trim()
        val o = api.call("GET", "/v2/checkout/orders/$id")
        when (o.optString("status")) {
            "COMPLETED" -> return@guarded "Already collected.\n${orderSummary(o)}"
            "CREATED", "PAYER_ACTION_REQUIRED", "SAVED" -> return@guarded "The buyer hasn't paid yet, so there's nothing to collect. Pay link: ${link(o, "approve", "payer-action").orEmpty()}"
            "VOIDED" -> return@guarded "That order was voided."
        }
        val pu = o.optJSONArray("purchase_units")?.optJSONObject(0)
        approveMoney(api, ctx, "Collect ${money(pu?.optJSONObject("amount"))} from ${payerOf(o).ifBlank { "the buyer" }}", orderSummary(o))?.let { return@guarded it }
        val res = api.call("POST", "/v2/checkout/orders/$id/capture", null, requestId = "agent-capture-$id")
        val cap = captures(res).firstOrNull()
        ledger.add("capture", cap?.optString("id").orEmpty(), payerOf(res).ifBlank { payerOf(o) }, money(cap?.optJSONObject("amount")), pu?.optString("description").orEmpty(), JSONObject().put("order", id))
        ctx.activity("Collected ${money(cap?.optJSONObject("amount"))} on PayPal", JSONObject().put("tool", "paypal"))
        "Collected. Order $id is ${res.optString("status")}. Capture ${cap?.optString("id")}: ${cap?.optString("status")} ${money(cap?.optJSONObject("amount"))}."
    }
}

class PayPalRefundTool(private val api: PayPalApi, private val ledger: PayPalLedger) : Tool {
    override val spec = ToolSpec("paypal_refund", "Refund a PayPal payment, in full or part (PayPal's create_refund). Give the order id or the capture id. Shows the user an approval card with the original payment, what's already been refunded and this refund, and only refunds on Approve.",
        schema(emptyList(), "order_id" to str("The order the payment was for"), "capture_id" to str("Or the capture id directly"),
            "amount" to str("Leave out to refund everything that's left"), "note" to str("Short note the buyer sees")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = guarded(ctx, "refund") {
        var orderId = input.optString("order_id").trim()
        var captureId = input.optString("capture_id").trim()
        if (captureId.isEmpty() && orderId.isEmpty()) throw PayPalException("Give the order_id or the capture_id. paypal_recent lists the latest ones.")
        if (captureId.isEmpty()) {
            val o = api.call("GET", "/v2/checkout/orders/$orderId")
            captureId = captures(o).firstOrNull { it.optString("status") in setOf("COMPLETED", "PARTIALLY_REFUNDED") }?.optString("id")
                ?: return@guarded "Order $orderId has no collected payment left to refund (status ${o.optString("status")})."
        }
        val cap = api.call("GET", "/v2/payments/captures/$captureId")
        if (cap.optString("status") == "REFUNDED") return@guarded "Capture $captureId is already fully refunded."
        if (orderId.isEmpty()) orderId = cap.optJSONObject("supplementary_data")?.optJSONObject("related_ids")?.optString("order_id").orEmpty()
        // The order carries the buyer and every refund so far: the card shows what's really left, from PayPal.
        val order = orderId.takeIf { it.isNotBlank() }?.let { runCatching { api.call("GET", "/v2/checkout/orders/$it") }.getOrNull() }
        val buyer = order?.let { payerOf(it) }.orEmpty()
        val what = order?.optJSONArray("purchase_units")?.optJSONObject(0)?.optString("description").orEmpty()
        val original = cap.optJSONObject("amount")
        val cur = original?.optString("currency_code") ?: "USD"
        val already = refundedSoFar(order, captureId)
        val left = (original?.optString("value")?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) - already
        val partial = input.optString("amount").takeIf { it.isNotBlank() }?.let { amount(it, cur) }
        if (partial != null && partial.toBigDecimal() > left) return@guarded "Only $cur ${left.toPlainString()} is left to refund on that payment (of ${money(original)})."
        val refundText = partial?.let { if (it.toBigDecimal().compareTo(left) == 0) "$cur $it (the rest)" else "$cur $it (part)" }
            ?: if (already.signum() > 0) "$cur ${left.setScale(2).toPlainString()} (the rest)" else "${money(original)} (full)"
        val details = buildString {
            appendLine("Refund: $refundText")
            appendLine("Original payment: ${money(original)} · capture $captureId")
            if (already.signum() > 0) appendLine("Already refunded: $cur ${already.setScale(2).toPlainString()}")
            if (orderId.isNotBlank()) appendLine("Order: $orderId")
            if (what.isNotBlank()) appendLine("For: $what")
            if (buyer.isNotBlank()) appendLine("To: $buyer")
            input.optString("note").takeIf { it.isNotBlank() }?.let { appendLine("Note to buyer: $it") }
        }.trim()
        approveMoney(api, ctx, "Refund $refundText${if (buyer.isNotBlank()) " to $buyer" else ""}", details)?.let { return@guarded it }
        val body = JSONObject()
        partial?.let { body.put("amount", JSONObject().put("currency_code", cur).put("value", it)) }
        input.optString("note").takeIf { it.isNotBlank() }?.let { body.put("note_to_payer", it.take(255)) }
        val r = api.call("POST", "/v2/payments/captures/$captureId/refund", body, requestId = "agent-refund-$captureId-${partial ?: "rest"}-${already.toPlainString()}", representation = true)
        val got = if (r.has("amount")) money(r.optJSONObject("amount")) else refundText
        ledger.add("refund", r.optString("id"), buyer, got, what, JSONObject().put("capture", captureId).put("order", orderId))
        ctx.activity("Refunded $got on PayPal", JSONObject().put("tool", "paypal"))
        "Refunded. Refund ${r.optString("id")}: ${r.optString("status")} $got."
    }

    private fun refundedSoFar(order: JSONObject?, captureId: String): java.math.BigDecimal {
        var sum = java.math.BigDecimal.ZERO
        order?.optJSONArray("purchase_units")?.let { pus -> for (i in 0 until pus.length()) {
            pus.getJSONObject(i).optJSONObject("payments")?.optJSONArray("refunds")?.let { rs -> for (j in 0 until rs.length()) {
                val r = rs.getJSONObject(j)
                if (r.optString("status") in setOf("COMPLETED", "PENDING")) sum += r.optJSONObject("amount")?.optString("value")?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            } }
        } }
        return sum
    }
}

// ---- looking back --------------------------------------------------------------------------------

class PayPalListTransactionsTool(private val api: PayPalApi, private val now: () -> ZonedDateTime = { ZonedDateTime.now(ZoneOffset.UTC) }) : Tool {
    override val spec = ToolSpec("paypal_list_transactions", "Search the PayPal account's transactions (money in and out) between two dates, at most 31 days apart; default the last 30 days. PayPal's search runs up to a few hours behind, so for something from the last hour check the order or invoice itself.",
        schema(emptyList(), "start_date" to str("YYYY-MM-DD"), "end_date" to str("YYYY-MM-DD, default now"), "who" to str("Part of a payer's name or email to match")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = guarded(ctx, "transactions") {
        val n = now()
        val end = day(input.optString("end_date"))?.let { LocalDate.parse(it).atTime(23, 59, 59).atZone(ZoneOffset.UTC) }?.let { if (it.isAfter(n)) n else it } ?: n
        val start = day(input.optString("start_date"))?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC) } ?: end.minusDays(30)
        if (start.plusDays(31).isBefore(end)) throw PayPalException("PayPal searches at most 31 days at a time.")
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        val res = api.call("GET", "/v1/reporting/transactions?start_date=${start.format(f)}&end_date=${end.format(f)}&fields=transaction_info,payer_info&page_size=100")
        val who = input.optString("who").trim().lowercase()
        val rows = res.optJSONArray("transaction_details")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().filter { t ->
            val p = t.optJSONObject("payer_info")
            who.isEmpty() || p?.optString("email_address")?.lowercase()?.contains(who) == true || p?.optJSONObject("payer_name")?.optString("alternate_full_name")?.lowercase()?.contains(who) == true
        }
        if (rows.isEmpty()) return@guarded "No transactions from ${start.toLocalDate()} to ${end.toLocalDate()}${if (who.isNotEmpty()) " for \"$who\"" else ""}. (PayPal's search can lag a few hours behind.)"
        rows.joinToString("\n") { t ->
            val i = t.optJSONObject("transaction_info") ?: JSONObject(); val p = t.optJSONObject("payer_info")
            "${i.optString("transaction_initiation_date").take(16)} · ${i.optString("transaction_id")} · ${i.optString("transaction_event_code")} · ${i.optString("transaction_status")} · ${money(i.optJSONObject("transaction_amount"))} · ${p?.optString("email_address").orEmpty()}"
        }
    }
}

class PayPalRecentTool(private val ledger: PayPalLedger) : Tool {
    override val spec = ToolSpec("paypal_recent", "The PayPal invoices, pay links, payments collected and refunds made from this phone, newest first, with their ids. Use for 'the last order' or 'that invoice to Thabo'.",
        schema(emptyList(), "kind" to str("Only one kind: invoice, invoice_sent, order, capture, refund"), "limit" to int("Default 10")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val rows = ledger.recent(input.optInt("limit", 10).coerceIn(1, 50), input.optString("kind").takeIf { it.isNotBlank() })
        if (rows.isEmpty()) return "Nothing on PayPal from this phone yet."
        val f = DateTimeFormatter.ofPattern("d MMM HH:mm")
        return rows.joinToString("\n") { r ->
            val at = java.time.Instant.ofEpochMilli(r.optLong("at")).atZone(java.time.ZoneId.systemDefault()).format(f)
            val extra = r.optJSONObject("extra")?.let { e -> e.keys().asSequence().joinToString(" ") { k -> "$k ${e.optString(k)}" } }.orEmpty()
            "$at · ${r.optString("kind")} · ${r.optString("id")} · ${r.optString("amount")} · ${r.optString("who")}${if (r.optString("note").isNotBlank()) " · " + r.optString("note") else ""}${if (extra.isNotBlank()) " · $extra" else ""}"
        }
    }
}

fun paypalTools(api: PayPalApi, ledger: PayPalLedger): List<Tool> = listOf(
    PayPalCreateInvoiceTool(api, ledger), PayPalSendInvoiceTool(api, ledger), PayPalGetInvoiceTool(api), PayPalListInvoicesTool(api), PayPalCancelInvoiceTool(api, ledger),
    PayPalCreateOrderTool(api, ledger), PayPalGetOrderTool(api), PayPalCaptureOrderTool(api, ledger), PayPalRefundTool(api, ledger),
    PayPalListTransactionsTool(api), PayPalRecentTool(ledger),
)

/** Tools that move money or send in the user's name. Each one must stop at an approval card (enforced by PayPalToolsTest). */
val PAYPAL_MONEY_TOOLS = setOf("paypal_send_invoice", "paypal_cancel_invoice", "paypal_capture_order", "paypal_refund")
