package com.past9.phoneaos

import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.paypal.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The real PayPal sandbox, through the same tools the agent calls. Skipped unless PAYPAL_CLIENT_ID and
 * PAYPAL_SECRET are set. Approvals are answered "Approve" by the test and every card is written to the log,
 * so the log shows exactly what the user would have been asked.
 *
 * The buyer has to approve the order in a browser (that is PayPal's checkout, by design): the test writes the
 * pay link to PAYPAL_LINK_FILE and waits up to PAYPAL_WAIT_SEC for the order to turn APPROVED.
 * Log: build/live/paypal-sandbox.txt
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PayPalSandboxLiveTest {
    private val log = StringBuilder()
    /** Pay links carry the order id as "token=": harmless, but secret scanners flag it, so the log masks it. */
    private fun say(s: String) { val m = s.replace(Regex("token=[A-Z0-9]+"), "token=<order id>"); log.appendLine(m); println(m) }

    private val ctx = object : ToolContext {
        override val agentLabel = "main"
        override suspend fun activity(text: String, meta: JSONObject) = 0L.also { say("  [activity] $text") }
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? {
            say("  [APPROVAL CARD] " + question.removePrefix("APPROVAL|").replace("|", "\n    ").replace("\n", "\n    ")); say("  [user taps] Approve"); return "Approve"
        }
        override suspend fun notify(title: String, body: String) {}
    }

    @Test fun endToEnd(): Unit = runBlocking {
        val id = System.getenv("PAYPAL_CLIENT_ID").orEmpty(); val secret = System.getenv("PAYPAL_SECRET").orEmpty()
        assumeTrue("no PayPal sandbox keys", id.isNotBlank() && secret.isNotBlank())
        val api = PayPalApi({ PayPalCreds(id, secret) })
        val ledger = PayPalLedger(File("build/live/paypal-ledger.json").also { it.delete() })
        val t = paypalTools(api, ledger).associateBy { it.spec.name }
        suspend fun call(name: String, input: JSONObject): String { say("\n> $name $input"); return t.getValue(name).run(input, ctx).also { say(it) } }
        val buyer = System.getenv("PAYPAL_BUYER").orEmpty().ifBlank { "buyer@example.com" }
        try {
            // Invoices
            val draft = call("paypal_create_invoice", JSONObject().put("recipient_email", buyer).put("recipient_name", "Thabo Nkosi")
                .put("items", JSONArray().put(JSONObject().put("name", "Logo work").put("unit_amount", "25"))).put("due_date", java.time.LocalDate.now().plusDays(1).toString()).put("note", "Thanks for the work"))
            Regex("INV2-[A-Z0-9-]+").find(draft)?.value?.let { inv ->
                call("paypal_send_invoice", JSONObject().put("invoice_id", inv))
                call("paypal_get_invoice", JSONObject().put("invoice_id", inv))
                call("paypal_list_invoices", JSONObject().put("who", "thabo"))
            }
            // Orders: pay link, buyer pays, collect, refund
            val made = call("paypal_create_order", JSONObject().put("amount", "30").put("description", "Braai pack (sandbox test)").put("buyer", "Sipho"))
            val orderId = Regex("Order (\\S+) for").find(made)!!.groupValues[1]
            val link = Regex("https://\\S+").find(made)!!.value
            System.getenv("PAYPAL_LINK_FILE")?.let { File(it).writeText(link + "\n") }
            call("paypal_capture_order", JSONObject().put("order_id", orderId))
            val wait = (System.getenv("PAYPAL_WAIT_SEC") ?: "0").toLong()
            val until = System.currentTimeMillis() + wait * 1000
            var status = ""
            while (System.currentTimeMillis() < until) {
                status = api.call("GET", "/v2/checkout/orders/$orderId").optString("status")
                if (status == "APPROVED") break
                Thread.sleep(5000)
            }
            say("\n(buyer status after waiting: $status)")
            if (status == "APPROVED") {
                call("paypal_capture_order", JSONObject().put("order_id", orderId))
                call("paypal_get_order", JSONObject().put("order_id", orderId))
                call("paypal_refund", JSONObject().put("order_id", orderId).put("amount", "10").put("note", "Part refund, sandbox test"))
                call("paypal_refund", JSONObject().put("order_id", orderId).put("note", "Rest of it"))
                call("paypal_get_order", JSONObject().put("order_id", orderId))
            }
            call("paypal_list_transactions", JSONObject())
            call("paypal_recent", JSONObject())
        } finally {
            File("build/live").mkdirs(); File("build/live/paypal-sandbox.txt").writeText(log.toString())
        }
    }
}
