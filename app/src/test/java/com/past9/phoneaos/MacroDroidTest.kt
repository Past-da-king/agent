package com.past9.phoneaos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.automation.MacroCall
import com.past9.phoneaos.automation.MacroDroidRunTool
import com.past9.phoneaos.automation.MacroRules
import com.past9.phoneaos.automation.MacroSender
import com.past9.phoneaos.automation.MacroSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The agent runs a MacroDroid or Tasker macro (the other direction of issue #18). Pinned here:
 * 1. the intent shape MacroDroid's Intent Received trigger matches: the exact action, and EVERY extra always present as
 *    a string (the trigger fires only when all its configured extras are there);
 * 2. the allowlist: by name, ignoring case and outer spaces, blanks and repeats dropped;
 * 3. the gate: allowed runs at once, anything else asks; no answer or a decline sends nothing;
 * 4. the broadcast really goes out with those extras, and a receiver for the action gets it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MacroDroidTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private class Ctx(private val answer: String?) : ToolContext {
        val asked = mutableListOf<String>()
        override val agentLabel = "helper-1"
        override suspend fun activity(text: String, meta: JSONObject): Long = 0
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? { asked += question; return answer }
        override suspend fun notify(title: String, body: String) {}
    }

    private class Prefs(val list: List<String> = emptyList(), val hookOn: Boolean = false, val hookId: String = "") : MacroSettings {
        override fun macroAllowlist() = list
        override fun macroWebhookOn() = hookOn
        override fun macroWebhookId() = hookId
    }

    private fun tool(prefs: Prefs, sent: MutableList<Pair<MacroCall, String>>) =
        MacroDroidRunTool(prefs) { call, via, _ -> sent += call to via; "sent" }

    // ---- 1. the shape -----------------------------------------------------------------------------------------------

    @Test fun everyExtraIsAlwaysThereAsAString() {
        val e = MacroCall("Wifi off").extras()
        assertEquals(listOf("macro", "v1", "v2", "v3", "params", "from"), e.keys.toList())
        assertEquals("Wifi off", e["macro"])
        assertEquals("", e["v1"]); assertEquals("", e["v2"]); assertEquals("", e["v3"])
        assertEquals("{}", e["params"])
        assertEquals("agent", e["from"])
    }

    @Test fun theIntentCarriesTheActionAndExtras() {
        val i = MacroCall("Open Spotify", v1 = "Discover Weekly", params = JSONObject().put("volume", 7)).intent()
        assertEquals("com.past9.phoneaos.MACRO", i.action)
        assertEquals("Open Spotify", i.getStringExtra("macro"))
        assertEquals("Discover Weekly", i.getStringExtra("v1"))
        assertEquals("", i.getStringExtra("v3"))
        assertEquals(7, JSONObject(i.getStringExtra("params")!!).getInt("volume"))
        assertNull("implicit, so MacroDroid and Tasker both get it", i.component)
        assertNull(i.`package`)
        // MacroDroid matches an extra's NAME case-sensitively, and a phone keyboard types "Macro".
        assertEquals("Open Spotify", i.getStringExtra("Macro"))
        assertEquals("Open Spotify", i.getStringExtra("MACRO"))
    }

    @Test fun theWebhookAddressUsesTheMacroAsIdentifier() {
        val url = MacroCall("Wifi off", v1 = "a b&c").webhookUrl(" dev123 ")
        assertTrue(url, url.startsWith("https://trigger.macrodroid.com/dev123/wifi-off?"))
        assertTrue(url, url.contains("v1=a%20b%26c"))
        assertTrue(url, url.contains("params=%7B%7D"))
    }

    @Test fun badCallsAreRefusedBeforeSending() {
        assertEquals("No macro named.", MacroRules.problem(MacroCall("  ")))
        assertTrue(MacroRules.problem(MacroCall("a\nb"))!!.contains("line break"))
        assertTrue(MacroRules.problem(MacroCall("x".repeat(81)))!!.contains("too long"))
        assertNull(MacroRules.problem(MacroCall("Wifi off")))
    }

    // ---- 2. the allowlist -------------------------------------------------------------------------------------------

    @Test fun allowlistParsesLinesAndCommas() {
        assertEquals(listOf("Wifi off", "Torch", "open maps"), MacroRules.parseAllowlist(" Wifi off\n\nTorch, open maps ,Open Maps\nwifi OFF"))
        assertTrue(MacroRules.parseAllowlist(null).isEmpty())
    }

    @Test fun allowedIsByNameIgnoringCase() {
        val list = listOf("Wifi off", "Torch")
        assertTrue(MacroRules.allowed(" wifi OFF ", list))
        assertFalse(MacroRules.allowed("Wifi", list))
        assertFalse(MacroRules.allowed("", list))
        assertFalse(MacroRules.allowed("Torch", emptyList()))
    }

    // ---- 3. the gate ------------------------------------------------------------------------------------------------

    @Test fun anAllowedMacroRunsWithoutAsking() = runBlocking {
        val sent = mutableListOf<Pair<MacroCall, String>>()
        val ctx = Ctx(answer = null)
        assertEquals("sent", tool(Prefs(listOf("Wifi off")), sent).run(JSONObject().put("macro", "wifi off").put("v1", "now"), ctx))
        assertTrue(ctx.asked.isEmpty())
        assertEquals("now", sent.single().first.v1)
        assertEquals(MacroRules.VIA_INTENT, sent.single().second)
    }

    @Test fun anyOtherMacroAsksAndRunsOnApprove() = runBlocking {
        val sent = mutableListOf<Pair<MacroCall, String>>()
        val ctx = Ctx(answer = "Approve")
        assertEquals("sent", tool(Prefs(), sent).run(JSONObject().put("macro", "Torch").put("v1", "on"), ctx))
        val q = ctx.asked.single()
        assertTrue(q, q.startsWith("APPROVAL|Run the macro \"Torch\"|"))
        assertTrue(q, q.contains("v1: on") && q.contains("this phone only"))
        assertEquals(1, sent.size)
    }

    @Test fun noAnswerOrDeclineSendsNothing() = runBlocking {
        val sent = mutableListOf<Pair<MacroCall, String>>()
        val nobody = tool(Prefs(), sent).run(JSONObject().put("macro", "Torch"), Ctx(answer = null))
        assertTrue(nobody, nobody.startsWith("failed:") && nobody.contains("Allowed macros"))
        val no = tool(Prefs(), sent).run(JSONObject().put("macro", "Torch"), Ctx(answer = "Decline"))
        assertTrue(no, no.startsWith("failed:") && no.contains("declined"))
        assertTrue(sent.isEmpty())
    }

    @Test fun theWebhookIsRefusedWhileOff() = runBlocking {
        val sent = mutableListOf<Pair<MacroCall, String>>()
        val ctx = Ctx(answer = "Approve")
        val out = tool(Prefs(listOf("Torch")), sent).run(JSONObject().put("macro", "Torch").put("via", "webhook"), ctx)
        assertTrue(out, out.startsWith("failed:") && out.contains("webhook is off"))
        assertTrue(sent.isEmpty())
        assertEquals("sent", tool(Prefs(listOf("Torch"), hookOn = true, hookId = "dev1"), sent).run(JSONObject().put("macro", "Torch").put("via", "webhook"), ctx))
        assertEquals(MacroRules.VIA_WEBHOOK, sent.single().second)
    }

    @Test fun theApprovalSaysWhenItGoesThroughMacroDroidsServer() = runBlocking {
        val ctx = Ctx(answer = "Decline")
        tool(Prefs(hookOn = true, hookId = "d"), mutableListOf()).run(JSONObject().put("macro", "Torch").put("via", "webhook"), ctx)
        assertTrue(ctx.asked.single().contains("MacroDroid's webhook server"))
    }

    // ---- 4. the broadcast really fires ------------------------------------------------------------------------------

    @Test fun theBroadcastGoesOutWithTheRightExtras() {
        val got = mutableListOf<Intent>()
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { got += i } }
        androidx.core.content.ContextCompat.registerReceiver(app, receiver, IntentFilter(MacroRules.ACTION), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        try {
            assertEquals("sent", MacroSender(app).broadcast(MacroCall("Wifi off", v2 = "x", params = JSONObject().put("k", "v"))))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val sentOut = shadowOf(app).broadcastIntents.last()
            assertEquals(MacroRules.ACTION, sentOut.action)
            val i = got.single()
            assertEquals("Wifi off", i.getStringExtra("macro"))
            assertEquals("", i.getStringExtra("v1"))
            assertEquals("x", i.getStringExtra("v2"))
            assertEquals("v", JSONObject(i.getStringExtra("params")!!).getString("k"))
            assertEquals("agent", i.getStringExtra("from"))
        } finally { app.unregisterReceiver(receiver) }
    }

    @Test fun theToolSendsTheRealBroadcastForAnAllowedMacro() = runBlocking {
        val before = shadowOf(app).broadcastIntents.size
        val out = MacroDroidRunTool(app, Prefs(listOf("Torch"))).run(JSONObject().put("macro", "Torch").put("v1", "on"), Ctx(answer = null))
        assertEquals("sent", out)
        val i = shadowOf(app).broadcastIntents.drop(before).single()
        assertEquals(MacroRules.ACTION, i.action)
        assertEquals("Torch", i.getStringExtra("macro"))
        assertEquals("on", i.getStringExtra("v1"))
    }

    @Test fun itIsLentToLinkedComputersAsItsOwnGroup() {
        assertEquals("automation", com.past9.phoneaos.remote.DeviceTools.groupOf("macrodroid_run"))
        assertTrue(com.past9.phoneaos.remote.DeviceTools.GROUPS.containsKey("automation"))
    }

    @Test fun theRecipeNamesTheActionAndMacro() {
        val r = MacroRules.recipe("Wifi off")
        assertTrue(r.contains("com.past9.phoneaos.MACRO") && r.contains("Name: macro (small letters). Value: Wifi off") && r.contains("wifi-off"))
        assertTrue("it says what makes MacroDroid stay silent", r.contains("not even a space") && r.contains("action alone"))
    }
}
