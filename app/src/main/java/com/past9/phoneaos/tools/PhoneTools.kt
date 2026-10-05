package com.past9.phoneaos.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.system.PermissionBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Phone permissions stay OFF until the agent actually needs one. It asks in plain words, the
 * user decides, and the permission is handed back when the app next closes (Android 13+).
 */
class LocationTool(private val context: Context) : Tool {
    override val spec = ToolSpec("location_get", "Get where the user is right now (for deliveries, directions, 'near me'). Asks them first; you only get it if they allow it.",
        schema(listOf("reason"), "reason" to str("Why you need it, finishing the sentence 'so I can...'")))

    @SuppressLint("MissingPermission")
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!PermissionBroker.granted(context, perms)) {
            val a = ctx.ask("Can I use your location so I can ${input.optString("reason").removePrefix("so I can ")}?", listOf("Allow this time", "No"))
            if (a != "Allow this time") return "The user said no to location. Ask them for the address instead."
            val ok = PermissionBroker.request(context, perms) { ctx.notifyBlocking("Allow location?", "Open the app to let your agent use your location") }
            if (!ok) return "Location permission wasn't granted. Ask the user for the address instead."
        }
        val id = ctx.activity("Checking your location", JSONObject().put("tool", "location"))
        val lm = context.getSystemService(LocationManager::class.java)
        val loc: Location? = withTimeoutOrNull(15_000) { current(lm) } ?: listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (Build.VERSION.SDK_INT >= 33) context.revokeSelfPermissionsOnKill(perms.toList())
        if (loc == null) { ctx.updateActivity(id, "Couldn't get a location fix"); return "No location fix (GPS off or indoors). Ask the user for the address." }
        val address = withContext(Dispatchers.IO) {
            runCatching { @Suppress("DEPRECATION") Geocoder(context).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.getAddressLine(0) }.getOrNull()
        }
        ctx.updateActivity(id, "Got your location${address?.let { ": ${it.substringBefore(',')}" } ?: ""}")
        return "Lat ${"%.5f".format(loc.latitude)}, lng ${"%.5f".format(loc.longitude)} (±${loc.accuracy.toInt()} m)" + (address?.let { ". Address: $it" } ?: "")
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(lm: LocationManager): Location? = suspendCancellableCoroutine { cont ->
        val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
        if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, context.mainExecutor) { if (cont.isActive) cont.resume(it) }
        else cont.resume(null)
    }
}

/** Start reading notifications from one app, with the user's yes. */
class NotificationsAllowTool(private val context: Context, private val settings: SettingsStore) : Tool {
    override val spec = ToolSpec("notifications_allow", "Ask the user to let you read notifications from one app (e.g. their bank, WhatsApp, a delivery app) so you can keep track of something. Off until they say yes.",
        schema(listOf("app", "reason"), "app" to str("App name as it appears on the phone"), "reason" to str("What you'll watch for, finishing 'so I can...'")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val want = input.optString("app")
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinct()
        val hit = apps.firstOrNull { it.second.equals(want, true) } ?: apps.firstOrNull { it.second.contains(want, true) || it.first.contains(want, true) }
            ?: return "No app called \"$want\" on this phone. Installed apps include: ${apps.take(40).joinToString { it.second }}"
        if (hit.first in settings.state.value.notifApps) return "Already reading ${hit.second}."
        val a = ctx.ask("Can I read your ${hit.second} notifications so I can ${input.optString("reason").removePrefix("so I can ")}?", listOf("Allow", "No"))
        if (a != "Allow") return "The user said no."
        settings.setNotifApp(hit.first, true)
        ctx.activity("Now reading ${hit.second} notifications", JSONObject().put("tool", "notifications"))
        val access = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        return "Allowed ${hit.second}." + if (!access) " BUT notification access is still off for this app in Android settings; tell the user to open Connections, Read my notifications, and switch it on." else ""
    }
}

class NotificationsStopTool(private val settings: SettingsStore) : Tool {
    override val spec = ToolSpec("notifications_stop", "Stop reading an app's notifications once you no longer need them.", schema(listOf("package"), "package" to str("Package name or app name")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val p = input.optString("package")
        val match = settings.state.value.notifApps.filter { it == p || it.contains(p, true) }
        match.forEach { settings.setNotifApp(it, false) }
        return if (match.isEmpty()) "Wasn't reading that app." else "Stopped reading ${match.joinToString()}."
    }
}
