package com.past9.phoneaos

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.system.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Every language in the picker must make Android pick its own values-* folder when the app is started with
 * that tag, the way AppLocale.wrap and the system per-app language do it. A folder named wrongly
 * (values-he against values-iw, values-id against values-in, values-fil against values-tl) falls back to English
 * silently, so this reads one string in every language and fails on English.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class LocaleResolutionTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(tag: String, id: Int): String {
        val config = Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return ctx.createConfigurationContext(config).getString(id)
    }

    @Test fun everyLanguageInThePickerResolvesToItsOwnText() {
        val english = text("en", R.string.settings_language)
        assertEquals("Language", english)
        val same = mutableListOf<String>()
        for (l in AppLocale.choices) {
            if (l.tag == "" || l.tag == "en") continue
            if (text(l.tag, R.string.settings_language) == english) same += l.tag
        }
        assertTrue("these languages fall back to English (wrong folder name?): $same", same.isEmpty())
    }

    @Test fun theOtherNamesAndroidMayHandOverResolveToo() {
        // The system may give the old codes: iw for Hebrew, in for Indonesian, tl for Filipino.
        for (tag in listOf("iw", "he", "in", "id", "fil", "tl", "zh-Hans-CN", "zh-Hant-TW", "nb-NO", "pt-BR", "pt-PT")) {
            assertNotEquals("$tag resolved to English", "Language", text(tag, R.string.settings_language))
        }
    }

    @Test fun theMachineTranslatedLineIsInEachLanguage() {
        val en = text("en", R.string.settings_language_machine)
        for (l in AppLocale.choices.filter { it.machine }) assertNotEquals("${l.tag} line is English", en, AppLocale.machineLine(ctx, l.tag))
    }
}
