package com.past9.phoneaos

import com.past9.phoneaos.ui.screens.SettingsActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The in-app language setting and the Spanish translation (PR #2 by Magarto) were silently undone once by a merge
 * (dfc9461, 3 Nov 2026) that brought older screens over them. This fails when a later merge drops them again:
 *  - Settings > You > Language exists and opens a slide-up sheet (never a centred dialog);
 *  - the chosen language reaches the app (attachBaseContext in App and MainActivity, locales_config generated);
 *  - values-es exists and has every key that values has;
 *  - the main screens read their text from string resources instead of English literals.
 */
class LanguageGuardTest {
    private val java = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private val main = java.parentFile
    private val res = File(main, "res")
    private val app = main.parentFile.parentFile
    private fun src(rel: String) = File(java, "com/past9/phoneaos/$rel").readText()
    private fun keys(f: File) = Regex("""<(?:string|plurals)\s+name="([^"]+)"[^>]*>""").findAll(f.readText()).map { it.groupValues[1] }.toSet()
    private val translatable = listOf("strings.xml", "strings_accounts.xml", "strings_app.xml", "strings_chat.xml", "strings_onboarding.xml", "strings_screens.xml")

    @Test fun spanishResourcesExistWithTheKeyStrings() {
        val es = File(res, "values-es")
        assertTrue("values-es is gone: the Spanish translation was dropped", es.isDirectory)
        val esKeys = es.listFiles { f -> f.name.startsWith("strings") }!!.flatMap { keys(it) }.toSet()
        for (k in listOf("settings_language", "settings_language_phone", "settings_title", "onb_sub_title", "chat_your_agent")) {
            assertTrue("values-es has no '$k'", k in esKeys)
        }
        val missing = translatable.flatMap { n -> keys(File(res, "values/$n")) }.toSet() - esKeys
        assertTrue("strings with no Spanish: ${missing.take(10)} (${missing.size})", missing.isEmpty())
        assertTrue("Spanish should have the bulk of the app's text", esKeys.size > 900)
    }

    @Test fun settingsHasTheLanguageRowAndASlideUpSheet() {
        val names = SettingsActions::class.java.declaredFields.map { it.name }
        assertTrue("SettingsActions lost 'language'", "language" in names)
        assertTrue("SettingsActions lost 'setLanguage'", "setLanguage" in names)
        val settings = src("ui/screens/Settings.kt")
        assertTrue("the Language row is gone", settings.contains("R.string.settings_language") && settings.contains("pickLanguage = true"))
        assertTrue("the language picker must be a slide-up ChoiceSheet", Regex("""if \(pickLanguage\)\s*com\.past9\.phoneaos\.ui\.ChoiceSheet""").containsMatchIn(settings))
        assertTrue("the picker offers the app's languages", settings.contains("AppLocale.choices"))
        assertTrue("MainActivity no longer passes the language to Settings", src("MainActivity.kt").contains("setLanguage = { com.past9.phoneaos.system.AppLocale.set("))
        assertEquals(listOf("", "en", "es"), com.past9.phoneaos.system.AppLocale.choices.map { it.first })
    }

    @Test fun theChosenLanguageReachesTheApp() {
        assertTrue("App lost attachBaseContext + AppLocale.wrap", src("App.kt").contains("AppLocale.wrap("))
        assertTrue("MainActivity lost attachBaseContext + AppLocale.wrap", src("MainActivity.kt").contains("AppLocale.wrap("))
        assertTrue("locales_config is no longer generated", File(app, "build.gradle.kts").readText().contains("generateLocaleConfig = true"))
        assertTrue("resources.properties lost the default locale", File(res, "resources.properties").readText().contains("unqualifiedResLocale=en"))
    }

    @Test fun theMainScreensReadTheirTextFromResources() {
        val floor = mapOf("ui/screens/Settings.kt" to 60, "ui/screens/Chat.kt" to 100, "ui/screens/Onboarding.kt" to 120,
            "ui/screens/Home.kt" to 60, "ui/screens/Connections.kt" to 70, "ui/screens/Memory.kt" to 70, "ui/screens/Routines.kt" to 90, "ui/screens/Tasks.kt" to 40)
        for ((f, min) in floor) {
            val n = Regex("""stringResource\(|R\.string\.|R\.plurals\.""").findAll(src(f)).count()
            assertTrue("$f reads only $n texts from resources (needs $min+): its screens went back to English-only", n >= min)
        }
    }
}
