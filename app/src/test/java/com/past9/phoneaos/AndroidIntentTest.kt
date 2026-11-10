package com.past9.phoneaos

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.automation.AndroidAppsTool
import com.past9.phoneaos.automation.AndroidIntentTool
import com.past9.phoneaos.automation.IntentRules
import com.past9.phoneaos.automation.IntentSender
import com.past9.phoneaos.automation.IntentSettings
import com.past9.phoneaos.automation.IntentSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * android_intent and android_apps: the agent talks to any app on the phone. Pinned here:
 * 1. what the agent writes becomes the right Intent: action, data and type, package, component, categories, typed
 *    extras, named flags;
 * 2. the safety rule: an intent that only opens a screen goes; an allowlisted action and app pair goes; anything else
 *    asks, and no answer or a decline sends nothing;
 * 3. the real thing happens: the broadcast goes out, the activity starts, the service starts;
 * 4. discovery reads the installed apps and what they accept, and never asks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidIntentTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val maps = "com.google.android.apps.maps"

    private class Ctx(private val answer: String?) : ToolContext {
        val asked = mutableListOf<String>()
        override val agentLabel = "helper-1"
        override suspend fun activity(text: String, meta: JSONObject): Long = 0
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? { asked += question; return answer }
        override suspend fun notify(title: String, body: String) {}
    }

    private class Prefs(val text: String = "") : IntentSettings {
        override fun intentAllowlist() = IntentRules.parseAllowlist(text)
    }

    private fun tool(prefs: Prefs, sent: MutableList<IntentSpec>) = AndroidIntentTool(prefs) { sent += it; "sent" }

    private fun json(s: String) = JSONObject(s)

    // ---- 1. the shape -----------------------------------------------------------------------------------------------

    @Test fun aMapsRouteBecomesAViewIntentForThatApp() {
        val s = IntentRules.parse(json("""{"kind":"activity","action":"android.intent.action.VIEW","data":"google.navigation:q=Sandton+City","package":"$maps"}""")).spec!!
        val i = IntentRules.build(s)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(Uri.parse("google.navigation:q=Sandton+City"), i.data)
        assertEquals(maps, i.`package`)
        assertTrue("an activity from outside an activity needs its own task", i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun extrasKeepTheirTypes() {
        val s = IntentRules.parse(json("""{"kind":"broadcast","action":"com.example.DO","extras":{
            "text":"hi","on":true,"count":3,"big":5000000000,"ratio":1.5,"names":["a","b"],"ids":[1,2,3],
            "forced":{"type":"long","value":7},"link":{"type":"uri","value":"content://x/1"},"f":{"type":"float","value":"2.5"},
            "list":{"type":"string_list","value":["x","y"]},"flag":{"type":"boolean","value":"yes"}}}""")).spec!!
        val i = IntentRules.build(s)
        assertEquals("hi", i.getStringExtra("text"))
        assertEquals(true, i.extras!!.get("on"))
        assertEquals(3, i.extras!!.get("count"))
        assertEquals(5000000000L, i.extras!!.get("big"))
        assertEquals(1.5, i.getDoubleExtra("ratio", 0.0), 0.0)
        assertArrayEquals(arrayOf("a", "b"), i.getStringArrayExtra("names"))
        assertArrayEquals(intArrayOf(1, 2, 3), i.getIntArrayExtra("ids"))
        assertEquals(7L, i.extras!!.get("forced"))
        assertEquals(Uri.parse("content://x/1"), i.extras!!.get("link"))
        assertEquals(2.5f, i.getFloatExtra("f", 0f), 0f)
        assertEquals(arrayListOf("x", "y"), i.getStringArrayListExtra("list"))
        assertEquals(true, i.extras!!.get("flag"))
    }

    @Test fun dataTypeComponentCategoriesAndFlags() {
        val s = IntentRules.parse(json("""{"kind":"activity","action":"android.intent.action.SEND","data":"content://media/1","mimeType":"image/png",
            "component":"com.example.app/.ShareActivity","category":["android.intent.category.DEFAULT"],"flags":["grant_read","clear_top"]}""")).spec!!
        val i = IntentRules.build(s)
        assertEquals("image/png", i.type)
        assertEquals(Uri.parse("content://media/1"), i.data)
        assertEquals(ComponentName("com.example.app", "com.example.app.ShareActivity"), i.component)
        assertTrue(i.hasCategory(Intent.CATEGORY_DEFAULT))
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 && i.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertEquals("com.example.app", s.targetPackage)
    }

    @Test fun badIntentsAreRefusedWithTheReason() {
        fun err(s: String) = IntentRules.parse(json(s)).error.orEmpty()
        assertTrue(err("""{"kind":"teleport","action":"x"}""").contains("kind must be"))
        assertTrue(err("""{"kind":"broadcast"}""").contains("Give an action"))
        assertTrue(err("""{"kind":"activity","component":"nonsense"}""").contains("package/class"))
        assertTrue(err("""{"kind":"service","action":"com.example.GO"}""").contains("needs a package"))
        assertTrue(err("""{"kind":"broadcast","action":"a","flags":["warp"]}""").contains("Unknown flag"))
        assertTrue(err("""{"kind":"broadcast","action":"a","extras":{"n":{"type":"int","value":"seven"}}}""").contains("extra \"n\""))
    }

    // ---- 2. the safety rule -----------------------------------------------------------------------------------------

    @Test fun openingAScreenNeverAsks() = runBlocking {
        val sent = mutableListOf<IntentSpec>()
        val ctx = Ctx(answer = null)
        for (body in listOf(
            """{"kind":"activity","action":"android.intent.action.VIEW","data":"geo:0,0?q=Sandton+City"}""",
            """{"kind":"activity","action":"android.intent.action.DIAL","data":"tel:0123456789"}""",
            """{"kind":"activity","action":"android.settings.WIFI_SETTINGS"}""",
            """{"kind":"activity","component":"com.example.app/.MainActivity"}""",
        )) assertEquals(body, "sent", tool(Prefs(), sent).run(json(body), ctx))
        assertTrue(ctx.asked.isEmpty())
        assertEquals(4, sent.size)
    }

    @Test fun anythingElseAsksAndSaysExactlyWhatGoes() = runBlocking {
        val sent = mutableListOf<IntentSpec>()
        val ctx = Ctx(answer = "Approve")
        val body = """{"kind":"activity","action":"android.intent.action.SEND","mimeType":"text/plain","package":"com.whatsapp","extras":{"android.intent.extra.TEXT":"On my way"}}"""
        assertEquals("sent", tool(Prefs(), sent).run(json(body), ctx))
        val q = ctx.asked.single()
        assertTrue(q, q.startsWith("APPROVAL|Open com.whatsapp|"))
        assertTrue(q, q.contains("android.intent.action.SEND") && q.contains("On my way") && q.contains("android.intent.action.SEND | com.whatsapp"))
        assertEquals(1, sent.size)
    }

    @Test fun broadcastsAndServicesAskToo() = runBlocking {
        val sent = mutableListOf<IntentSpec>()
        val ctx = Ctx(answer = "Decline")
        val b = tool(Prefs(), sent).run(json("""{"kind":"broadcast","action":"com.example.WIPE"}"""), ctx)
        val s = tool(Prefs(), sent).run(json("""{"kind":"service","action":"com.example.SYNC","package":"com.example"}"""), ctx)
        assertTrue(b, b.startsWith("failed:") && b.contains("declined"))
        assertTrue(s, s.startsWith("failed:"))
        assertEquals(2, ctx.asked.size)
        assertTrue(ctx.asked[0], ctx.asked[0].startsWith("APPROVAL|Broadcast WIPE to any app|"))
        assertTrue(sent.isEmpty())
    }

    @Test fun nobodyThereSendsNothingAndSaysHowToAllowIt() = runBlocking {
        val sent = mutableListOf<IntentSpec>()
        val out = tool(Prefs(), sent).run(json("""{"kind":"broadcast","action":"com.example.DO","package":"com.example"}"""), Ctx(answer = null))
        assertTrue(out, out.startsWith("failed:") && out.contains("com.example.DO | com.example") && out.contains("Allowed app intents"))
        assertTrue(sent.isEmpty())
    }

    @Test fun anAllowedActionAndAppPairRunsWithoutAsking() = runBlocking {
        val sent = mutableListOf<IntentSpec>()
        val ctx = Ctx(answer = null)
        val prefs = Prefs("# mine\nandroid.intent.action.SEND | com.whatsapp\ncom.example.PING | *\ncom.example.ANY\n")
        assertEquals("sent", tool(prefs, sent).run(json("""{"kind":"activity","action":"android.intent.action.SEND","mimeType":"text/plain","package":"com.whatsapp"}"""), ctx))
        assertEquals("sent", tool(prefs, sent).run(json("""{"kind":"broadcast","action":"com.example.PING","package":"com.other"}"""), ctx))
        assertEquals("sent", tool(prefs, sent).run(json("""{"kind":"broadcast","action":"com.example.ANY"}"""), ctx))
        assertTrue(ctx.asked.isEmpty())
        // The same action to ANOTHER app is not allowed by the WhatsApp line.
        val other = tool(prefs, sent).run(json("""{"kind":"activity","action":"android.intent.action.SEND","mimeType":"text/plain","package":"org.telegram.messenger"}"""), ctx)
        assertTrue(other, other.startsWith("failed:"))
        assertEquals(1, ctx.asked.size)
        assertEquals(3, sent.size)
    }

    @Test fun theAllowlistParsesPairs() {
        val l = IntentRules.parseAllowlist(" a.B | com.x \n\n# note\nc.D\n e.F|*\na.B | com.x")
        assertEquals(listOf(IntentRules.Pair("a.B", "com.x"), IntentRules.Pair("c.D", "*"), IntentRules.Pair("e.F", "*")), l)
        assertFalse("actions are case-sensitive", IntentRules.allowed(IntentSpec("broadcast", "a.b", pkg = "com.x"), l))
        assertTrue(IntentRules.allowed(IntentSpec("broadcast", "a.B", pkg = "COM.X"), l))
    }

    // ---- 3. it really happens ---------------------------------------------------------------------------------------

    @Test fun theBroadcastGoesOutWithTypedExtras() {
        val got = mutableListOf<Intent>()
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { got += i } }
        androidx.core.content.ContextCompat.registerReceiver(app, receiver, IntentFilter("com.example.DO_THING"), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        try {
            val s = IntentRules.parse(json("""{"kind":"broadcast","action":"com.example.DO_THING","extras":{"count":3,"on":true,"name":"x"}}""")).spec!!
            assertEquals("sent", IntentSender(app).send(s))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val i = got.single()
            assertEquals(3, i.getIntExtra("count", -1))
            assertTrue(i.getBooleanExtra("on", false))
            assertEquals("x", i.getStringExtra("name"))
        } finally { app.unregisterReceiver(receiver) }
    }

    private fun installMaps() {
        val pm = shadowOf(app.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = maps; versionName = "11.0"
            applicationInfo = ApplicationInfo().apply { packageName = maps; name = "Maps"; nonLocalizedLabel = "Maps" }
            activities = arrayOf(ActivityInfo().apply { packageName = maps; name = "$maps.MapsActivity"; exported = true })
        })
        val info = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = maps; name = "$maps.MapsActivity"; exported = true; applicationInfo = ApplicationInfo().apply { packageName = maps } } }
        @Suppress("DEPRECATION")
        for (i in listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=Sandton+City")).setPackage(maps),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=Sandton+City")).setPackage(maps),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=Sandton+City")),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        )) pm.addResolveInfoForIntent(i, info)
    }

    @Test fun theToolOpensAMapsRouteForReal() = runBlocking {
        installMaps()
        val t = AndroidIntentTool(Prefs()) { IntentSender(app) { true }.send(it) }
        val out = t.run(json("""{"kind":"activity","action":"android.intent.action.VIEW","data":"google.navigation:q=Sandton+City","package":"$maps"}"""), Ctx(answer = null))
        assertEquals("sent", out)
        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("google.navigation:q=Sandton+City", started.dataString)
        assertEquals(maps, started.`package`)
    }

    @Test fun anIntentNoAppCanOpenFailsInWords() {
        val out = IntentSender(app) { true }.send(IntentSpec("activity", Intent.ACTION_VIEW, data = "nosuchscheme://x", pkg = "com.nobody"))
        assertTrue(out, out.startsWith("failed: no app on this phone can open that") && out.contains("com.nobody"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun fromTheBackgroundItBecomesANotificationToTap() {
        installMaps()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val out = IntentSender(app) { false }.send(IntentSpec("activity", Intent.ACTION_VIEW, data = "geo:0,0?q=Sandton+City"))
        assertTrue(out, out.startsWith("sent: as a notification"))
        assertNull("nothing is started behind the user's back", shadowOf(app).nextStartedActivity)
    }

    @Test fun aServiceIsStarted() {
        val out = IntentSender(app).send(IntentSpec("service", "com.example.SYNC", component = "com.example/.SyncService"))
        // Robolectric answers a start with the component; a real phone answers null when there is no such service.
        assertTrue(out, out == "sent" || out.startsWith("failed: no such service"))
        assertEquals(ComponentName("com.example", "com.example.SyncService"), shadowOf(app).nextStartedService?.component)
    }

    // ---- 4. discovery -----------------------------------------------------------------------------------------------

    @Test fun discoveryListsAppsAndWhatOneAccepts() = runBlocking {
        installMaps()
        val ctx = Ctx(answer = null)
        val apps = AndroidAppsTool(app)
        val list = apps.run(JSONObject().put("query", "maps"), ctx)
        assertTrue(list, list.contains("($maps)"))
        val one = apps.run(JSONObject().put("package", maps), ctx)
        assertTrue(one, one.contains("Turn-by-turn navigation") && one.contains("Map places (geo:)") && one.contains(".MapsActivity"))
        assertFalse(one, one.contains("Shared text"))
        val who = apps.run(JSONObject().put("intent", json("""{"action":"android.intent.action.VIEW","data":"geo:0,0?q=Sandton+City"}""")), ctx)
        assertTrue(who, who.contains("$maps/.MapsActivity"))
        assertTrue(apps.run(JSONObject().put("package", "com.not.here"), ctx).contains("not installed"))
        assertTrue("reading never asks", ctx.asked.isEmpty())
    }

    @Test fun bothToolsAreLentToLinkedComputers() {
        assertEquals("automation", com.past9.phoneaos.remote.DeviceTools.groupOf("android_intent"))
        assertEquals("automation", com.past9.phoneaos.remote.DeviceTools.groupOf("android_apps"))
    }

    @Test fun aJsonListOfMixedThingsIsTextNotACrash() {
        val v = IntentRules.extraValue(JSONArray().put("a").put(1))
        assertArrayEquals(arrayOf("a", "1"), v as Array<*>)
    }
}
