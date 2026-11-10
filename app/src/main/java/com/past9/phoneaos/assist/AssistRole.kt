package com.past9.phoneaos.assist

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Whether this app is the phone's digital assistant, and how the person makes it so. Android gives no dialog for it:
 * the assistant is picked in the phone's own Settings, so the app can only say where and open that page.
 */
object AssistRole {
    /** True when the phone's assistant is this app. */
    fun held(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull()
            if (runCatching { rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)) return true
        }
        val cr = context.contentResolver
        return heldBy(context.packageName,
            runCatching { Settings.Secure.getString(cr, "assistant") }.getOrNull(),
            runCatching { Settings.Secure.getString(cr, "voice_interaction_service") }.getOrNull())
    }

    /** The same answer from the two settings the phone keeps (a flattened component each), apart from Android. */
    fun heldBy(pkg: String, assistant: String?, interactor: String?): Boolean =
        listOf(assistant, interactor).any { v -> v?.substringBefore('/')?.trim() == pkg }

    /** The Settings pages where the assistant is picked, best first. The first one this phone has is opened. */
    fun settingsIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    fun openSettings(context: Context): Boolean = settingsIntents(context).any { i -> runCatching { context.startActivity(i); true }.getOrDefault(false) }

    /** What the Settings row says. */
    fun line(held: Boolean): String = if (held) "On: the assistant shortcut opens it over any app" else "Off: tap to make it the phone's assistant"

    /** The two taps, and how the assistant is then called on THIS phone. Only what is true for the maker is said. */
    data class Guide(val steps: List<String>, val triggers: List<String>)

    fun guide(appName: String, manufacturer: String = Build.MANUFACTURER.orEmpty(), sdk: Int = Build.VERSION.SDK_INT): Guide {
        val maker = manufacturer.trim().lowercase()
        val steps = listOf(
            "Tap Open Settings below, then tap Digital assistant app (some phones call it Device assistance app).",
            "Choose $appName and confirm.",
        )
        val triggers = when {
            maker == "samsung" -> listOf(
                "Press and hold the side button. If it opens something else, set it in Settings, Advanced features, Side button, Long press: Digital assistant.",
            )
            maker == "google" -> listOf(
                "Press and hold the power button. If it shows the power menu, set it in Settings, System, Gestures, Press and hold power button: Digital assistant.",
                "With gesture navigation, swipe up from a bottom corner.",
            )
            else -> listOf(
                "Use your phone's assistant shortcut: on most phones, holding the power or home button, or a swipe up from a bottom corner.",
            )
        } + if (sdk >= 25) listOf("Or press and hold the app's icon and tap Talk.") else emptyList()
        return Guide(steps, triggers)
    }

    /** This app's own assistant service, as the phone's settings name it. */
    fun component(context: Context) = ComponentName(context, AssistService::class.java)
}
