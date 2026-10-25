package com.past9.phoneaos.system

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own language, separate from the phone's. On Android 13+ it is the system per-app
 * language (also in the phone's Settings > Apps > Language); before that we keep it ourselves and
 * apply it to every context we start from. An empty tag means "follow the phone".
 */
object AppLocale {
    /** Languages the app has text for; "" follows the phone. Labels are in their own language. */
    val choices = listOf("" to null, "en" to "English", "es" to "Español")

    private const val PREFS = "app_locale"
    private const val KEY = "tag"

    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(',')
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system saves it and recreates the activity itself.
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
            activity.recreate()
        }
    }

    /** Before Android 13: the context with the chosen language, for attachBaseContext. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(config)
    }
}
