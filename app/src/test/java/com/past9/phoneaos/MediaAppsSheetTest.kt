package com.past9.phoneaos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.screens.MediaAppRow
import com.past9.phoneaos.ui.screens.MediaAppsActions
import com.past9.phoneaos.ui.screens.MediaAppsSheet
import com.past9.phoneaos.ui.screens.MediaAppsUi
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Settings > Media apps: each app with what it handles, the user's own default for music and for video, a way back to
 * automatic, and the notification access prompt. Pictures at 390 wide here and at 360 in [MediaAppsSheet360Test], light
 * and dark, land in app/build/design/.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
open class MediaAppsSheetTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()

    protected open val width = "390"

    private val rows = listOf(
        MediaAppRow("test.music.one", "Example Music", playFromSearch = true, audioFiles = true, sessionNow = true),
        MediaAppRow("test.music.two", "Example Player", playFromSearch = true),
        MediaAppRow("test.video.one", "Example Video", videoLinks = true),
        MediaAppRow("test.files.one", "Plain Player", audioFiles = true),
    )

    private class Store(var music: String = "", var video: String = "", var access: Boolean = true) {
        var allowed = 0
        fun actions(rows: List<MediaAppRow>) = MediaAppsActions(rows = { rows }, music = { music }, setMusic = { music = it }, video = { video }, setVideo = { video = it },
            hasSessionAccess = { access }, openSessionAccess = { allowed++ })
    }

    private fun show(a: MediaAppsActions, name: String? = null) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalSheetPreview provides true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { MediaAppsSheet(a) {} }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        if (name != null) rule.onRoot().captureRoboImage(File("build/design/media-apps-$width-$name-${if (dark) "dark" else "light"}.png").path)
    }

    @Test fun picture() {
        show(Store(music = "test.music.one", video = "test.video.one").actions(rows), "default")
    }

    @Test fun pictureWithoutNotificationAccessAndNothingChosen() {
        show(Store(access = false).actions(rows), "no-access")
    }

    @Test fun pictureWithNoApps() {
        show(Store().actions(emptyList()), "empty")
    }

    @Test fun pickingADefaultStoresItAndTappingAgainGoesBackToAutomatic() {
        val s = Store()
        show(s.actions(rows))
        rule.onNodeWithTag("music-test.music.two").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals("test.music.two", s.music)
        rule.onNodeWithTag("music-test.music.two").assertIsSelected()
        rule.onNodeWithTag("music-test.music.one").assertIsNotSelected()
        rule.onNodeWithTag("music-test.music.two").performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals("", s.music)
        rule.onNodeWithTag("video-test.video.one").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals("test.video.one", s.video)
        assertEquals("the music pick is untouched by the video one", "", s.music)
    }

    @Test fun anAppOnlyGetsTheChipsItCanDo() {
        show(Store().actions(rows))
        rule.onNodeWithTag("music-test.music.one").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("video-test.music.one").assertIsNotEnabled()
        rule.onNodeWithTag("video-test.video.one").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("music-test.video.one").assertIsNotEnabled()
        rule.onNodeWithTag("music-test.files.one").assertIsEnabled()
    }

    @Test fun theNotificationAccessPromptShowsOnlyWhenItIsOffAndOpensTheSettings() {
        val s = Store(access = false)
        show(s.actions(rows))
        rule.onNodeWithText("Notification access is off").assertExists()
        rule.onNodeWithText("Allow").performScrollTo().performClick()
        assertEquals(1, s.allowed)
    }

    @Test fun noPromptWhenAccessIsOn() {
        show(Store(access = true).actions(rows))
        assertTrue(rule.onAllNodesWithTextCount("Notification access is off") == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(t: String) = onAllNodesWithText(t).fetchSemanticsNodes().size

    @Test fun abilitiesAndTheSettingsLineAreInPlainWords() {
        assertEquals(listOf("Plays from search", "Local files", "Playing now"), rows[0].abilities())
        assertEquals(listOf("Video links"), rows[2].abilities())
        assertEquals("Which app plays your music and video · automatic", MediaAppsUi.line(rows, "", ""))
        assertEquals("Music: Example Music · Video: Example Video", MediaAppsUi.line(rows, "test.music.one", "test.video.one"))
        assertEquals("Video: Example Video", MediaAppsUi.line(rows, "uninstalled.app", "test.video.one"))
    }
}

/** The same sheet on a narrow phone. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class MediaAppsSheet360Test(dark: Boolean) : MediaAppsSheetTest(dark) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    override val width = "360"
}
