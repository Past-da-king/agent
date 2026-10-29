package com.past9.phoneaos.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.past9.phoneaos.App
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.system.PermissionBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/**
 * Where the phone is, for the agent on a linked computer (29 Oct 2026: "agents must be able to get my phone's
 * location when needed"). One answer, never tracking: a recent known position when it is fresh enough, otherwise
 * one fix. The suburb and city come from the phone's own Geocoder, no other service. Android asks the person once;
 * after that the permission stays (it is not handed back like the phone agent's one-off location_get).
 */
object PhoneLocation {
    val FOREGROUND = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    const val DEFAULT_MAX_AGE_S = 120
    const val DEFAULT_TIMEOUT_S = 20
    /** Set once he granted it for the linked computer: location_get then leaves the permission in place. */
    const val KEEP_KEY = "location_keep"
    /** Its own id: 7301 is the wake-up channel's foreground notification, which the ask used to overwrite. */
    const val NOTE_ID = 9_431
    /** Background: an agent asks while the phone is locked in his pocket, and Android gives a backgrounded app no
     *  location at all (not even the last known one) without "Allow all the time". Asked once, after the first yes. */
    const val BACKGROUND = Manifest.permission.ACCESS_BACKGROUND_LOCATION
    const val BG_ASKED_KEY = "location_bg_asked"

    /** On screen (the app is open and visible): Android's "while using the app" covers it. */
    fun onScreen(): Boolean = runCatching {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        // FOREGROUND only: the wake-up channel's foreground service keeps the process at 125, which is not on screen.
        onScreenImportance(info.importance)
    }.getOrDefault(false)

    fun onScreenImportance(importance: Int) = importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    /** The app-op is the truth: "foreground" means while-in-use only, "allow" means all the time (seen on his A35, 29 Oct). */
    fun hasBackground(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        val mode = runCatching {
            context.getSystemService(android.app.AppOpsManager::class.java)
                .unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_FINE_LOCATION, android.os.Process.myUid(), context.packageName)
        }.getOrNull()
        return backgroundFrom(mode, androidx.core.content.ContextCompat.checkSelfPermission(context, BACKGROUND) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    fun backgroundFrom(opMode: Int?, permGranted: Boolean): Boolean = if (opMode == null) permGranted else opMode == android.app.AppOpsManager.MODE_ALLOWED

    /** Android's own page for this app, where Location > Allow all the time is one tap. */
    fun openAppSettings(context: Context) = runCatching {
        context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * A web page asked for navigator.geolocation (protocol: WebChromeClient.onGeolocationPermissionsShowPrompt).
     * Held already: yes at once. Not held: Android's prompt through the app (a notification opens it when the app is in
     * the background), and the page always gets an answer, at worst a no after [timeoutMs]. Before 0.15.3 a page waited
     * three minutes with nothing on screen, so navigator.geolocation looked hung.
     */
    suspend fun webAllowed(context: Context, origin: String, timeoutMs: Long = 90_000): Boolean {
        if (PermissionBroker.granted(context, FOREGROUND)) return true
        val host = runCatching { android.net.Uri.parse(origin).host }.getOrNull() ?: origin
        val ok = withTimeoutOrNull(timeoutMs) { PermissionBroker.request(context, FOREGROUND) { askNote(context, "share it with $host") } } ?: false
        NotificationManagerCompat.from(context).cancel(NOTE_ID)
        if (ok) runCatching { App.graph(context).settings.setExtra(KEEP_KEY, "1") }
        return ok
    }

    /** onPermissionRequest: which page resources to grant, given the Android permissions the app holds. Never location here (that is the prompt above). */
    fun webResources(asked: Array<String>, holds: (String) -> Boolean): Array<String> = asked.filter { r ->
        when (r) {
            android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> holds(Manifest.permission.CAMERA)
            android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> holds(Manifest.permission.RECORD_AUDIO)
            android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> true
            else -> false
        }
    }.toTypedArray()

    /** The app is in his pocket: a notification that opens it, where Android's own prompt comes up. */
    fun askNote(context: Context, why: String, background: Boolean = false) {
        val open = PendingIntent.getActivity(context, PhoneLocation.NOTE_ID, Intent(context, com.past9.phoneaos.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val st = App.graph(context).settings.state.value
        val n = NotificationCompat.Builder(context, com.past9.phoneaos.system.AndroidPhone.CH_MESSAGES)
            .setSmallIcon(com.past9.phoneaos.system.Identity.statIcon(context, st.mascot))
            .setContentTitle(if (background) "Location while the phone is in your pocket?" else "Allow location?")
            .setContentText(if (background) "Pick 'Allow all the time' so your agent can check where you are when the app is closed. Asked once." else "Your agent wants your location so it can $why. Tap, then Allow (asked once).")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (background) "Your agent usually asks while the phone is locked in your pocket, and Android gives a closed app no location unless it may use it all the time. Tap, then pick 'Allow all the time'. Asked once; it only ever takes one position when an agent asks, never tracks."
                else "Your agent wants your location so it can $why. Tap, then Allow. Android asks once."))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setAutoCancel(true).setContentIntent(open).build()
        runCatching { NotificationManagerCompat.from(context).notify(PhoneLocation.NOTE_ID, n) }
    }

    /** A position from LocationManager, kept simple so the choice below is testable. */
    data class Fix(val lat: Double, val lng: Double, val accuracyM: Float?, val timeMs: Long, val ageMs: Long, val provider: String)

    /** Providers to ask, best first: the fused one (Android 12+), then network (cheap), then GPS. */
    fun providers(enabled: (String) -> Boolean, sdk: Int = Build.VERSION.SDK_INT): List<String> {
        val all = buildList {
            if (sdk >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER); add(LocationManager.GPS_PROVIDER)
        }
        return all.filter { runCatching { enabled(it) }.getOrDefault(false) }
    }

    /** The newest last-known fix, if it is no older than maxAgeS. */
    fun freshest(fixes: List<Fix>, maxAgeS: Int): Fix? = fixes.filter { it.ageMs <= maxAgeS * 1000L }.minByOrNull { it.ageMs }

    fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(ms))

    fun result(f: Fix, source: String, place: Address?): JSONObject = JSONObject()
        .put("lat", Math.round(f.lat * 1e6) / 1e6).put("lng", Math.round(f.lng * 1e6) / 1e6)
        .put("accuracy_m", f.accuracyM?.let { Math.round(it).toInt() } ?: JSONObject.NULL)
        .put("timestamp", iso(f.timeMs)).put("age_s", f.ageMs / 1000).put("provider", f.provider).put("source", source)
        .apply {
            val suburb = place?.subLocality ?: place?.featureName?.takeIf { place.locality != null && it != place.locality && !it.any(Char::isDigit) }
            put("suburb", suburb ?: JSONObject.NULL)
            put("city", place?.locality ?: place?.subAdminArea ?: JSONObject.NULL)
            put("province", place?.adminArea ?: JSONObject.NULL)
            put("country", place?.countryName ?: JSONObject.NULL)
            put("address", place?.getAddressLine(0) ?: JSONObject.NULL)
            if (place == null) put("note", "No place name (the phone's geocoder had no answer); coordinates only.")
            put("map", "https://maps.google.com/?q=${f.lat},${f.lng}")
        }
}

/**
 * [askMs]: how long the first call waits for him to answer Android's prompts. The computer gives a lent tool 120 s, so
 * the ask stops well before that and says so (ASKING:), instead of the call dying as "didn't finish in time".
 */
class PhoneLocationTool(private val context: Context, private val askMs: Long = 75_000, private val lookup: (suspend (Double, Double) -> Address?)? = null) : Tool {
    override val spec = ToolSpec("phone_location",
        "Where the person's phone is right now: lat, lng, accuracy in metres, when, provider, and the suburb and city (looked up on the phone). " +
            "Uses a recent known position if it is no older than max_age seconds (default ${PhoneLocation.DEFAULT_MAX_AGE_S}), otherwise takes one fix; never tracks. " +
            "The first time, the phone asks the person to allow location once.",
        schema(emptyList(),
            "max_age" to int("Seconds: a known position this recent is good enough (default ${PhoneLocation.DEFAULT_MAX_AGE_S}; 0 = always take a new fix)"),
            "timeout" to int("Seconds to wait for a new fix (default ${PhoneLocation.DEFAULT_TIMEOUT_S}, 5 to 60)"),
            "reason" to str("Why you need it, shown to the person if the phone has to ask (finishing 'so I can...')")))

    @SuppressLint("MissingPermission")
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val maxAge = input.optInt("max_age", PhoneLocation.DEFAULT_MAX_AGE_S).coerceIn(0, 7 * 24 * 3600)
        val timeoutS = input.optInt("timeout", PhoneLocation.DEFAULT_TIMEOUT_S).coerceIn(5, 60)
        if (!PermissionBroker.granted(context, PhoneLocation.FOREGROUND)) {
            val why = input.optString("reason").removePrefix("so I can ").ifBlank { "answer you" }
            val ok = withTimeoutOrNull(askMs) { PermissionBroker.request(context, PhoneLocation.FOREGROUND) { PhoneLocation.askNote(context, why) } }
            if (ok == null) error("ASKING: the phone is asking him to allow location now (a notification, then Android's prompt). Call phone_location again in a minute, once he has answered.")
            NotificationManagerCompat.from(context).cancel(PhoneLocation.NOTE_ID)
            if (!ok) error("DENIED: location permission is not allowed on the phone (he was asked and said no, or did not answer within 3 minutes). Ask him to allow Location for the Agent app, or ask for the address.")
        }
        runCatching { App.graph(context).settings.setExtra(PhoneLocation.KEEP_KEY, "1") }
        // Once, right after the first yes (the app is open then): the same for when the app is closed.
        val settings = runCatching { App.graph(context).settings }.getOrNull()
        if (!PhoneLocation.hasBackground(context) && settings?.extra(PhoneLocation.BG_ASKED_KEY) != "1" && PhoneLocation.onScreen()) {
            settings?.setExtra(PhoneLocation.BG_ASKED_KEY, "1")
            android.util.Log.i("PhoneLocation", "asking for all-the-time location")
            withTimeoutOrNull(30_000) { PermissionBroker.request(context, arrayOf(PhoneLocation.BACKGROUND)) { PhoneLocation.askNote(context, "", background = true) } }
            NotificationManagerCompat.from(context).cancel(PhoneLocation.NOTE_ID)
            // Android said yes without showing anything, yet the app-op is still while-in-use: open the app's settings page.
            if (!PhoneLocation.hasBackground(context)) { android.util.Log.i("PhoneLocation", "opening app settings for all-the-time"); PhoneLocation.openAppSettings(context) }
        }
        android.util.Log.i("PhoneLocation", "background=${PhoneLocation.hasBackground(context)} onScreen=${PhoneLocation.onScreen()}")
        if (!PhoneLocation.hasBackground(context) && !PhoneLocation.onScreen())
            error("BACKGROUND: the Agent app on the phone may use location only while it is open, and it is closed now. Ask him to open the app (then call again), or to set Location to 'Allow all the time' for Agent (Settings, Apps, Agent, Permissions, Location).")
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = PhoneLocation.providers({ lm.isProviderEnabled(it) })
        if (providers.isEmpty() || (Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled))
            error("OFF: location is switched off on the phone (quick settings, Location). Ask him to switch it on, or ask for the address.")
        val now = System.currentTimeMillis()
        val known = (providers + LocationManager.PASSIVE_PROVIDER).distinct().mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { fixOf(it, now) }
        }
        var source = "last_known"
        var fix = PhoneLocation.freshest(known, maxAge)
        if (fix == null) {
            fix = withTimeoutOrNull(timeoutS * 1000L) { oneFix(lm, providers) }?.let { fixOf(it, System.currentTimeMillis()) }
            source = "fix"
        }
        if (fix == null) {
            val stale = known.minByOrNull { it.ageMs }
                ?: error("TIMEOUT: no location fix within $timeoutS s and the phone knows no earlier position (indoors, or GPS still searching). Try again with a longer timeout, or ask for the address.")
            fix = stale; source = "last_known_stale"
        }
        val place = withContext(Dispatchers.IO) { withTimeoutOrNull(8_000) { (lookup ?: ::geocode)(fix.lat, fix.lng) } }
        val out = PhoneLocation.result(fix, source, place)
        if (source == "last_known_stale") out.put("note", "No new fix within $timeoutS s: this is the last known position, ${fix.ageMs / 60_000} min old.")
        android.util.Log.i("PhoneLocation", "source=$source provider=${fix.provider} acc=${fix.accuracyM} age=${fix.ageMs}ms place=${place?.locality}")
        return out.toString()
    }

    private fun fixOf(l: Location, now: Long): PhoneLocation.Fix {
        val age = if (l.elapsedRealtimeNanos > 0) (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000 else now - l.time
        return PhoneLocation.Fix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, l.time, age.coerceAtLeast(0), l.provider ?: "unknown")
    }

    /** One position from the best provider that answers, then every listener is removed. */
    @SuppressLint("MissingPermission")
    private suspend fun oneFix(lm: LocationManager, providers: List<String>): Location? = suspendCancellableCoroutine { cont ->
        val listeners = mutableListOf<LocationListener>()
        fun done(l: Location?) {
            if (l == null || !cont.isActive) return
            listeners.forEach { runCatching { lm.removeUpdates(it) } }
            cont.resume(l)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val cancel = android.os.CancellationSignal()
            providers.forEach { p -> runCatching { lm.getCurrentLocation(p, cancel, context.mainExecutor) { done(it) } } }
            cont.invokeOnCancellation { cancel.cancel() }
        } else {
            providers.forEach { p ->
                val l = object : LocationListener {
                    override fun onLocationChanged(location: Location) = done(location)
                    @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                listeners += l
                @Suppress("DEPRECATION") runCatching { lm.requestSingleUpdate(p, l, Looper.getMainLooper()) }
            }
            cont.invokeOnCancellation { listeners.forEach { runCatching { lm.removeUpdates(it) } } }
        }
    }

    private suspend fun geocode(lat: Double, lng: Double): Address? {
        if (!Geocoder.isPresent()) return null
        val g = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { cont ->
            runCatching {
                g.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(list: MutableList<Address>) { if (cont.isActive) cont.resume(list.firstOrNull()) }
                    override fun onError(message: String?) { if (cont.isActive) cont.resume(null) }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        } else runCatching { @Suppress("DEPRECATION") g.getFromLocation(lat, lng, 1)?.firstOrNull() }.getOrNull()
    }
}
