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

/** Look at the user's latest photos, with their yes. The model sees them as images. */
class PhotosTool(private val context: Context) : Tool {
    override val spec = ToolSpec("photos_recent", "Look at the user's most recent photos or screenshots from their gallery (asks first). Use when they say 'my last photo', 'the screenshot I just took'.",
        schema(emptyList(), "count" to com.past9.phoneaos.agent.int("How many, 1 to 4 (default 1)"), "screenshots_only" to com.past9.phoneaos.agent.bool("Only screenshots")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val perm = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (!PermissionBroker.granted(context, perm)) {
            val a = ctx.ask("Can I look at your latest photos?", listOf("Allow", "No"))
            if (a != "Allow") return "The user said no. Ask them to send the photo with the photo button instead."
            if (!PermissionBroker.request(context, perm) { ctx.notifyBlocking("Allow photos?", "Open the app to let your agent see your photos") }) return "Photo access wasn't granted."
        }
        val n = input.optInt("count", 1).coerceIn(1, 4)
        val shotsOnly = input.optBoolean("screenshots_only")
        val uris = withContext(Dispatchers.IO) {
            val col = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val sel = if (shotsOnly) "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" else null
            val args = if (shotsOnly) arrayOf("%Screenshots%") else null
            context.contentResolver.query(col, arrayOf(android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DATE_ADDED, android.provider.MediaStore.Images.Media.DISPLAY_NAME),
                sel, args, "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                val out = mutableListOf<Triple<android.net.Uri, Long, String>>()
                while (c.moveToNext() && out.size < n) out += Triple(android.content.ContentUris.withAppendedId(col, c.getLong(0)), c.getLong(1), c.getString(2))
                out
            }.orEmpty()
        }
        if (uris.isEmpty()) return "No photos found."
        val lines = uris.mapNotNull { (uri, added, name) ->
            val path = withContext(Dispatchers.IO) { runCatching {
                val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                    val sc = minOf(1f, 1568f / maxOf(info.size.width, info.size.height)); d.setTargetSize((info.size.width * sc).toInt().coerceAtLeast(1), (info.size.height * sc).toInt().coerceAtLeast(1))
                    d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }
                java.io.File(context.filesDir, "uploads/gallery-${System.nanoTime()}.jpg").apply { parentFile?.mkdirs(); outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }.path
            }.getOrNull() } ?: return@mapNotNull null
            ctx.activity("Looked at $name", JSONObject().put("tool", "photos").put("image", path))
            val words = Ocr.text(path).take(4000)
            "$name, added ${java.time.Instant.ofEpochSecond(added).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}: [[image:$path]]" + if (words.isNotBlank()) "\nText in it: $words" else ""
        }
        return lines.joinToString("\n")
    }
}

/**
 * Sign-in codes: when a site sends a one-time code, the agent waits for it to land in the
 * notifications (SMS app, authenticator, the site's own app) and carries on by itself.
 */
class WaitForCodeTool(private val db: com.past9.phoneaos.data.AppDb, private val settings: SettingsStore, private val allow: NotificationsAllowTool) : Tool {
    override val spec = ToolSpec("notifications_wait_code", "After triggering a one-time code (SMS, app, email notification) during a sign-in, wait for it to arrive in the phone's notifications and get the code. Name the app it comes through (e.g. Messages, Uber, Gmail).",
        schema(listOf("app"), "app" to str("App the code arrives through"), "seconds" to com.past9.phoneaos.agent.int("How long to wait, default 90")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val start = System.currentTimeMillis() - 30_000
        val app = input.optString("app")
        if (settings.state.value.notifApps.isEmpty() || db.notifications().since(0).none { it.app.contains(app, true) }) {
            val r = allow.run(JSONObject().put("app", app).put("reason", "catch the sign-in code and finish logging in"), ctx)
            if (r.startsWith("The user said no") || r.startsWith("No app")) return r
        }
        val id = ctx.activity("Waiting for the code from $app", JSONObject().put("tool", "notifications"))
        val deadline = System.currentTimeMillis() + input.optInt("seconds", 90).coerceIn(10, 300) * 1000L
        val code = Regex("""(?<![\d-])(\d{4,8})(?![\d-])""")
        while (System.currentTimeMillis() < deadline) {
            db.notifications().since(start).filter { app.isBlank() || it.app.contains(app, true) || it.text.contains(app, true) || it.title.contains(app, true) }
                .forEach { n ->
                    val text = n.title + " " + n.text
                    if (Regex("(?i)code|otp|verif|pin|one-time|login|sign").containsMatchIn(text)) code.find(text)?.let {
                        ctx.updateActivity(id, "Got the code from ${n.app}"); return "Code: ${it.groupValues[1]} (from ${n.app}: \"${text.take(160)}\")"
                    }
                }
            kotlinx.coroutines.delay(2000)
        }
        ctx.updateActivity(id, "No code arrived")
        return "No code arrived in time. Ask the user for it (ask_user), or use browser_handoff."
    }
}
