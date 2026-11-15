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
    /** One language the app can show: its tag, its name in its own language, and whether its text is machine translated. */
    data class AppLanguage(val tag: String, val name: String?, val machine: Boolean = false)

    /**
     * Languages the app has text for; "" follows the phone. Names are in their own language. After the phone's own
     * language come English and Spanish (written by people), then the rest in the order of their English names.
     * Every tag here needs a values-* folder with the same keys: scripts/check-translations.py and LanguageGuardTest hold that.
     */
    val choices = listOf(
        AppLanguage("", null),
        AppLanguage("en", "English"),
        AppLanguage("es", "Español"),
        AppLanguage("af", "Afrikaans", machine = true), // Afrikaans
        AppLanguage("am", "አማርኛ", machine = true), // Amharic
        AppLanguage("ar", "العربية", machine = true), // Arabic
        AppLanguage("bn", "বাংলা", machine = true), // Bengali
        AppLanguage("ca", "Català", machine = true), // Catalan
        AppLanguage("zh-CN", "中文 (简体)", machine = true), // Chinese Simplified
        AppLanguage("zh-TW", "中文 (繁體)", machine = true), // Chinese Traditional
        AppLanguage("cs", "Čeština", machine = true), // Czech
        AppLanguage("da", "Dansk", machine = true), // Danish
        AppLanguage("nl", "Nederlands", machine = true), // Dutch
        AppLanguage("fil", "Filipino", machine = true), // Filipino
        AppLanguage("fi", "Suomi", machine = true), // Finnish
        AppLanguage("fr", "Français", machine = true), // French
        AppLanguage("de", "Deutsch", machine = true), // German
        AppLanguage("el", "Ελληνικά", machine = true), // Greek
        AppLanguage("gu", "ગુજરાતી", machine = true), // Gujarati
        AppLanguage("ha", "Hausa", machine = true), // Hausa
        AppLanguage("he", "עברית", machine = true), // Hebrew
        AppLanguage("hi", "हिन्दी", machine = true), // Hindi
        AppLanguage("hu", "Magyar", machine = true), // Hungarian
        AppLanguage("ig", "Igbo", machine = true), // Igbo
        AppLanguage("id", "Bahasa Indonesia", machine = true), // Indonesian
        AppLanguage("it", "Italiano", machine = true), // Italian
        AppLanguage("ja", "日本語", machine = true), // Japanese
        AppLanguage("kn", "ಕನ್ನಡ", machine = true), // Kannada
        AppLanguage("km", "ខ្មែរ", machine = true), // Khmer
        AppLanguage("ko", "한국어", machine = true), // Korean
        AppLanguage("ms", "Bahasa Melayu", machine = true), // Malay
        AppLanguage("ml", "മലയാളം", machine = true), // Malayalam
        AppLanguage("mr", "मराठी", machine = true), // Marathi
        AppLanguage("ne", "नेपाली", machine = true), // Nepali
        AppLanguage("nb", "Norsk bokmål", machine = true), // Norwegian
        AppLanguage("fa", "فارسی", machine = true), // Persian
        AppLanguage("pl", "Polski", machine = true), // Polish
        AppLanguage("pt-BR", "Português (Brasil)", machine = true), // Portuguese Brazil
        AppLanguage("pt-PT", "Português (Portugal)", machine = true), // Portuguese Portugal
        AppLanguage("pa", "ਪੰਜਾਬੀ", machine = true), // Punjabi
        AppLanguage("ro", "Română", machine = true), // Romanian
        AppLanguage("ru", "Русский", machine = true), // Russian
        AppLanguage("st", "Sesotho", machine = true), // Sesotho
        AppLanguage("sw", "Kiswahili", machine = true), // Swahili
        AppLanguage("sv", "Svenska", machine = true), // Swedish
        AppLanguage("ta", "தமிழ்", machine = true), // Tamil
        AppLanguage("te", "తెలుగు", machine = true), // Telugu
        AppLanguage("th", "ไทย", machine = true), // Thai
        AppLanguage("tn", "Setswana", machine = true), // Tswana
        AppLanguage("tr", "Türkçe", machine = true), // Turkish
        AppLanguage("uk", "Українська", machine = true), // Ukrainian
        AppLanguage("ur", "اردو", machine = true), // Urdu
        AppLanguage("vi", "Tiếng Việt", machine = true), // Vietnamese
        AppLanguage("xh", "isiXhosa", machine = true), // Xhosa
        AppLanguage("yo", "Yorùbá", machine = true), // Yoruba
        AppLanguage("zu", "isiZulu", machine = true), // Zulu
    )

    /** The "machine translated" line, in the language it sits under. */
    fun machineLine(context: Context, tag: String): String {
        val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config).getString(com.past9.phoneaos.R.string.settings_language_machine)
    }

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
