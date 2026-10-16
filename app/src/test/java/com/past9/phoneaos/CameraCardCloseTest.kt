package com.past9.phoneaos

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.camera.CameraBinder
import com.past9.phoneaos.camera.CameraCard
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** A camera with no hardware: records what the card asks of it. */
private class FakeBinder : CameraBinder {
    var binds = 0; var unbinds = 0; var bound = false; var owner: LifecycleOwner? = null
    override suspend fun bind(owner: LifecycleOwner, front: Boolean, surface: Preview.SurfaceProvider, capture: ImageCapture): Camera? {
        binds++; bound = true; this.owner = owner; return null
    }
    override fun unbind() { unbinds++; bound = false }
}

/** Every way out of the camera card, with the camera live (bound) and in review (a photo taken). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class CameraCardCloseTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val fake = FakeBinder()
    private var closed = 0
    private var sent = 0
    private var open by mutableStateOf(true)

    @Before fun grantCamera() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
    }

    private fun show(review: Boolean = false) {
        val photo = if (review) File.createTempFile("shot", ".jpg").apply { writeBytes(byteArrayOf(1)); deleteOnExit() }.path else null
        rule.setContent {
            AppTheme(dark = false) {
                Box(Modifier.fillMaxSize()) {
                    if (open) CameraCard(Rect(300f, 2000f, 380f, 2080f), onClosed = { closed++; open = false }, onSend = { _, _ -> sent++ }, onGallery = {}, binder = fake, startWith = photo)
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("camera-card").fetchSemanticsNodes().isNotEmpty() }
        if (!review) rule.waitUntil(5_000) { fake.bound }
        rule.waitForIdle()
    }

    private fun assertClosedCleanly() {
        rule.waitUntil(5_000) { closed > 0 }
        rule.waitForIdle()
        assertEquals("onClosed exactly once", 1, closed)
        assertFalse("camera released", fake.bound)
        assertTrue("unbind ran", fake.unbinds >= 1)
        fake.owner?.let { assertEquals("the card's own lifecycle is destroyed", Lifecycle.State.DESTROYED, it.lifecycle.currentState) }
        rule.onAllNodesWithTag("camera-card").assertCountEquals(0)
    }

    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    @Test fun liveChevron() { show(); rule.onNodeWithContentDescription("Close camera").performClick(); assertClosedCleanly() }
    @Test fun liveChevronTwiceStillClosesOnce() {
        show(); rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Close camera").performClick(); rule.onNodeWithContentDescription("Close camera").performClick()
        rule.mainClock.autoAdvance = true
        assertClosedCleanly()
    }
    @Test fun liveBack() { show(); back(); assertClosedCleanly() }
    @Test fun liveSwipeDownOnThePreview() { show(); rule.onNodeWithTag("camera-preview").performTouchInput { swipeDown() }; assertClosedCleanly() }
    @Test fun liveSwipeDownOnTheCard() { show(); rule.onNodeWithTag("camera-card").performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.9f) }; assertClosedCleanly() }
    @Test fun liveTapOnTheDimmedChat() { show(); rule.onRoot().performTouchInput { click(Offset(width / 2f, 40f)) }; assertClosedCleanly() }

    @Test fun twoFingerPinchDoesNotClose() {
        show()
        rule.onNodeWithTag("camera-preview").performTouchInput { pinch(center + Offset(-40f, 0f), center + Offset(-120f, 0f), center + Offset(40f, 0f), center + Offset(120f, 0f)) }
        rule.waitForIdle()
        assertEquals(0, closed); assertTrue(fake.bound)
        rule.onNodeWithContentDescription("Close camera").performClick(); assertClosedCleanly()
    }

    @Test fun reviewBackRetakesThenBackCloses() {
        show(review = true)
        rule.onNodeWithText("Retake").assertExists()
        back(); rule.waitForIdle()
        assertEquals("first back only retakes", 0, closed)
        rule.onNodeWithText("Retake").assertDoesNotExist()
        rule.onNodeWithContentDescription("Close camera").assertExists()
        back(); assertClosedCleanly()
    }
    @Test fun reviewSwipeDown() { show(review = true); rule.onNodeWithTag("camera-card").performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.9f) }; assertClosedCleanly() }
    @Test fun reviewTapOnScrimDoesNothingButBackStillWorks() {
        show(review = true); rule.onRoot().performTouchInput { click(Offset(width / 2f, 40f)) }; rule.waitForIdle(); assertEquals(0, closed)
        back(); rule.waitForIdle(); back(); assertClosedCleanly()
    }

    @Test fun sendSendsAndCloses() {
        show(review = true); rule.onNodeWithText("Send").performClick()
        assertClosedCleanly(); assertEquals(1, sent)
    }
    @Test fun retakeThenEveryPathStillCloses() {
        show(review = true); rule.onNodeWithText("Retake").performClick(); rule.waitForIdle()
        rule.onNodeWithContentDescription("Close camera").performClick(); assertClosedCleanly()
    }
    @Test fun retakeThenBack() { show(review = true); rule.onNodeWithText("Retake").performClick(); rule.waitForIdle(); back(); assertClosedCleanly() }

    @Test fun cardRemovedMidCloseStillReleasesTheCamera() {
        show(); rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Close camera").performClick()
        rule.runOnUiThread { open = false }; rule.mainClock.autoAdvance = true; rule.waitForIdle()
        assertFalse("camera released", fake.bound)
    }
    @Test fun cardRemovedByTheHostReleasesTheCamera() {
        show(); rule.runOnUiThread { open = false }; rule.waitForIdle()
        assertFalse("camera released", fake.bound); assertTrue(fake.unbinds >= 1)
    }
}
