package com.past9.phoneaos

import android.Manifest
import android.location.Address
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.webkit.PermissionRequest
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.remote.DeviceTools
import com.past9.phoneaos.tools.PhoneLocation
import com.past9.phoneaos.tools.PhoneLocationTool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * 29 Oct 2026 (Ayanda): agents must be able to get his phone's location when needed. The phone lends phone_location to
 * the linked computer (its own group, so it can be switched off), answers from a fresh known position or one fix,
 * names the suburb with the phone's own Geocoder, and a web page's navigator.geolocation always gets an answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneLocationTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private object Ctx : ToolContext {
        override val agentLabel = "test"
        override suspend fun activity(text: String, meta: JSONObject): Long = 0
        override suspend fun updateActivity(id: Long, text: String, meta: JSONObject) {}
        override suspend fun ask(question: String, options: List<String>): String? = null
        override suspend fun notify(title: String, body: String) {}
    }

    private fun place(): Address = Address(Locale.US).apply {
        subLocality = "Umhlanga"; locality = "Durban"; adminArea = "KwaZulu-Natal"; countryName = "South Africa"
        setAddressLine(0, "Lighthouse Rd, Umhlanga, Durban, 4319, South Africa"); latitude = -29.7276; longitude = 31.0844
    }

    private fun located(provider: String, ageMs: Long) = Location(provider).apply {
        latitude = -29.7276; longitude = 31.0844; accuracy = 12f; time = System.currentTimeMillis() - ageMs
        elapsedRealtimeNanos = (SystemClock.elapsedRealtime() - ageMs).coerceAtLeast(1) * 1_000_000
    }

    @Test
    fun theLinkedComputerSeesPhoneLocationInItsOwnGroup() {
        assertEquals("location", DeviceTools.groupOf("phone_location"))
        val cat = DeviceTools.catalogue(app)
        val tool = (0 until cat.length()).map { cat.getJSONObject(it) }.firstOrNull { it.getString("name") == "phone_location" }
        assertTrue("phone_location is lent to the computer", tool != null)
        assertEquals("location", tool!!.getString("group"))
        assertTrue(tool.getBoolean("readOnly"))
        assertTrue(tool.getJSONObject("params").getJSONObject("properties").has("max_age"))
    }

    @Test
    fun aFreshKnownPositionAnswersWithTheSuburbAndNoNewFix() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val lm = app.getSystemService(LocationManager::class.java)
        val slm = shadowOf(lm)
        slm.setLocationEnabled(true)
        slm.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        slm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        slm.simulateLocation(located(LocationManager.NETWORK_PROVIDER, 30_000))
        val out = JSONObject(PhoneLocationTool(app, lookup = { _, _ -> place() }).run(JSONObject().put("max_age", 120), Ctx))
        assertEquals(-29.7276, out.getDouble("lat"), 1e-4)
        assertEquals(31.0844, out.getDouble("lng"), 1e-4)
        assertEquals(12, out.getInt("accuracy_m"))
        assertEquals("last_known", out.getString("source"))
        assertEquals("Umhlanga", out.getString("suburb"))
        assertEquals("Durban", out.getString("city"))
        assertTrue(out.getString("timestamp").startsWith("20"))
        assertEquals("1", App.graph(app).settings.extra(PhoneLocation.KEEP_KEY))
    }

    @Test
    fun closedAppWithoutAllTheTimeSaysSoAtOnceInsteadOfWaiting() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        org.junit.Assume.assumeFalse(PhoneLocation.onScreen())
        val t0 = System.currentTimeMillis()
        val e = runCatching { PhoneLocationTool(app).run(JSONObject().put("max_age", 0), Ctx) }.exceptionOrNull()
        assertTrue("$e", e?.message?.startsWith("BACKGROUND:") == true)
        assertTrue(System.currentTimeMillis() - t0 < 5_000)
    }

    @Test
    fun theFirstCallSaysItIsAskingInsteadOfOutlivingTheLinkDeadline() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val t0 = System.currentTimeMillis()
        val e = runCatching { PhoneLocationTool(app, askMs = 800).run(JSONObject(), Ctx) }.exceptionOrNull()
        assertTrue("$e", e?.message?.startsWith("ASKING:") == true)
        assertTrue(System.currentTimeMillis() - t0 < 10_000)
        com.past9.phoneaos.system.PermissionBroker.pending.value?.let { com.past9.phoneaos.system.PermissionBroker.answer(it, false) }
        Unit
    }

    @Test
    fun allTheTimeAndOnScreenComeFromWhatAndroidReallyEnforces() {
        // His A35, 29 Oct: permission ACCESS_BACKGROUND_LOCATION said nothing useful; the app-op said "foreground".
        assertEquals(false, PhoneLocation.backgroundFrom(android.app.AppOpsManager.MODE_FOREGROUND, true))
        assertEquals(false, PhoneLocation.backgroundFrom(android.app.AppOpsManager.MODE_ALLOWED, false))
        assertEquals(true, PhoneLocation.backgroundFrom(android.app.AppOpsManager.MODE_ALLOWED, true))
        assertEquals(true, PhoneLocation.backgroundFrom(null, true))
        // The wake-up foreground service keeps the process at 125: that is not on screen.
        assertEquals(false, PhoneLocation.onScreenImportance(android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertEquals(true, PhoneLocation.onScreenImportance(android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        // Nothing shown (answered in 40 ms) and still while-in-use: open settings. He saw it and kept while-in-use: leave it.
        assertEquals(true, PhoneLocation.settingsInstead(40, false))
        assertEquals(false, PhoneLocation.settingsInstead(9_000, false))
        assertEquals(false, PhoneLocation.settingsInstead(40, true))
        assertEquals("location_bg_asked_v2", PhoneLocation.BG_ASKED_KEY)
    }

    @Test
    fun theAskNeverOverwritesTheWakeUpServicesNotification() {
        assertTrue(PhoneLocation.NOTE_ID != com.past9.phoneaos.wakeup.WakeChannelService.NOTE_ID)
        assertTrue(PhoneLocation.NOTE_ID != com.past9.phoneaos.wakeup.WakeUpService.NOTE_ID)
    }

    @Test
    fun locationSwitchedOffSaysSo() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val slm = shadowOf(app.getSystemService(LocationManager::class.java))
        slm.setLocationEnabled(false)
        val e = runCatching { PhoneLocationTool(app).run(JSONObject(), Ctx) }.exceptionOrNull()
        assertTrue("$e", e?.message?.startsWith("OFF:") == true)
    }

    @Test
    fun choosingTheFixAndTheProviders() {
        val fresh = PhoneLocation.Fix(1.0, 2.0, 5f, 0, 10_000, "network")
        val old = PhoneLocation.Fix(1.0, 2.0, 5f, 0, 600_000, "gps")
        assertEquals(fresh, PhoneLocation.freshest(listOf(old, fresh), 120))
        assertEquals(null, PhoneLocation.freshest(listOf(old), 120))
        assertEquals(null, PhoneLocation.freshest(listOf(fresh), 0))
        assertEquals(listOf("fused", "network", "gps"), PhoneLocation.providers({ true }, 34))
        assertEquals(listOf("network", "gps"), PhoneLocation.providers({ true }, 30))
        assertEquals(listOf("gps"), PhoneLocation.providers({ it == "gps" }, 30))
        // No place name: coordinates only, and it says so.
        val bare = PhoneLocation.result(fresh, "fix", null)
        assertTrue(bare.isNull("suburb")); assertTrue(bare.has("note"))
    }

    @Test
    fun aPageAskingForLocationGetsAnAnswerAtOnceWhenTheAppHoldsIt() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(PhoneLocation.webAllowed(app, "https://example.com", 1_000))
    }

    @Test
    fun aPageAskingWithoutPermissionIsAnsweredNoInsteadOfHanging() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val t0 = System.currentTimeMillis()
        assertEquals(false, PhoneLocation.webAllowed(app, "https://example.com", 1_500))
        assertTrue(System.currentTimeMillis() - t0 < 10_000)
    }

    @Test
    fun pageCameraAndMicFollowTheAppsOwnPermissions() {
        val asked = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_MIDI_SYSEX)
        assertEquals(listOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE), PhoneLocation.webResources(asked) { it == Manifest.permission.RECORD_AUDIO }.toList())
        assertEquals(emptyList<String>(), PhoneLocation.webResources(asked) { false }.toList())
    }
}
