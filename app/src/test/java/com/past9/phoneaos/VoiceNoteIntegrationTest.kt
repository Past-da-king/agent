package com.past9.phoneaos

import android.content.Context
import android.content.res.Configuration
import android.media.MediaMetadataRetriever
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.screens.VoiceBubble
import com.past9.phoneaos.ui.screens.noteSpokenTime
import com.past9.phoneaos.ui.theme.AppTheme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w390dp-h844dp-xxhdpi")
class VoiceNoteIntegrationTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File.createTempFile("voice-note-ui-", ".wav")
    @After fun cleanup() { com.past9.phoneaos.voice.NotePlayback.pauseAll(); file.delete() }

    @Test fun bubbleRetriesMetadataFailureThroughItsPlayButton() {
        val path = file.path
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(path), ShadowMediaPlayer.MediaInfo(118000, 0))
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(path), IllegalArgumentException("Unreadable audio"))
        rule.setContent {
            AppTheme(dark = false) {
                VoiceBubble(ChatItem(id = 902, kind = "voice", text = "Synthetic retry proof", meta = JSONObject().put("path", path).toString()))
            }
        }
        val error = context.getString(R.string.chat_audio_cant_play)
        rule.waitUntil(15000) { rule.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(context.getString(R.string.chat_getting_audio)).assertDoesNotExist()
        ShadowMediaMetadataRetriever.reset()
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_DURATION, "118000")
        rule.onNodeWithContentDescription("Play voice note").performClick()
        rule.waitUntil(15000) { rule.onAllNodesWithContentDescription("Pause voice note").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Pause voice note").assertIsDisplayed()
        rule.onNodeWithText(error).assertDoesNotExist()
    }

    @Test fun completeLocalePhrasesDescribePositionAndProgress() {
        val locales = mapOf(
            "zu" to "Indawo yokudlala enothini lezwi",
            "xh" to "Indawo yokudlala kwinowuthi yelizwi",
            "st" to "Sebaka sa ho bapala molaetseng oa lentsoe",
            "tn" to "Lefelo la go tshameka mo molaetseng wa lentswe",
            "de" to "Position in der Sprachnachricht",
            "ja" to "ボイスメモの再生位置"
        )
        locales.forEach { (tag, expected) ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val resources = context.createConfigurationContext(configuration)
            assertEquals(tag, expected, resources.getString(R.string.note_position))
            val action = resources.getString(R.string.note_play)
            if (action.lowercase(Locale.forLanguageTag(tag)) != action.uppercase(Locale.forLanguageTag(tag))) {
                assertNotEquals(tag, action, action.uppercase(Locale.forLanguageTag(tag)))
            }
            val progress = resources.getString(R.string.note_progress, "42", "118")
            assertTrue(tag, progress.contains("42") && progress.contains("118"))
            assertFalse(tag, progress.contains("/"))
        }
    }

    @Test fun spokenTimesUseLocalePluralRulesAndLabelsReuseTranslations() {
        assertEquals("1 second", noteSpokenTime(1000, Locale.ENGLISH))
        assertEquals("2 seconds", noteSpokenTime(2000, Locale.ENGLISH))
        val french = noteSpokenTime(118000, Locale.FRENCH)
        assertTrue(french, french.contains("minute") && french.contains("secondes"))
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("zu")) }
        val zulu = context.createConfigurationContext(configuration)
        val label = zulu.getString(R.string.note_play)
        assertFalse(label, label.contains("Play") || label.contains("voice note"))
        assertNotEquals(noteSpokenTime(42000, Locale.ENGLISH), noteSpokenTime(42000, Locale.FRENCH))
    }
}
