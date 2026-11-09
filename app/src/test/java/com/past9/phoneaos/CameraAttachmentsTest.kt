package com.past9.phoneaos

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.camera.CameraBinder
import com.past9.phoneaos.camera.CameraCard
import com.past9.phoneaos.remote.Outgoing
import com.past9.phoneaos.ui.ComposerBox
import com.past9.phoneaos.ui.ComposerBoxes
import com.past9.phoneaos.ui.ComposerTools
import com.past9.phoneaos.ui.composerActionsFor
import com.past9.phoneaos.ui.screens.MessageComposer
import com.past9.phoneaos.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Camera to message box (9 Nov 2026): the photo he takes lands in the message box as a photo he can remove, he can
 * take more, and he can send them with no words. The app never writes words for him and the camera never sends.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class CameraAttachmentsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val sendButton = "Send (swipe up for a voice reply)"

    /** The activity's side of the camera button: records which box asked. */
    private class Tools : ComposerTools {
        var opened = 0; var asked: ComposerBox? = null
        override fun pickPhotos(box: ComposerBox) {}
        override fun pickDocs(box: ComposerBox) {}
        override fun camera(box: ComposerBox, origin: Rect) { opened++; asked = box }
        override fun mic(box: ComposerBox) {}
        override fun listening(box: ComposerBox) = false
        override fun recordings(box: ComposerBox) {}
    }

    private class NoCamera : CameraBinder {
        override suspend fun bind(owner: LifecycleOwner, front: Boolean, surface: Preview.SurfaceProvider, capture: ImageCapture): Camera? = null
        override fun unbind() {}
    }

    /** What a send carried: the text, the pictures, the files. */
    private data class Sent(val text: String, val pics: List<String>, val files: List<String>)
    private val sent = ArrayList<Sent>()

    @Before fun grantCamera() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        ComposerBoxes.dropForTest()
    }

    /** A real JPEG on disk, as the camera leaves one. */
    private fun photo(name: String, top: Int, bottom: Int): String {
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint()
        p.color = top; c.drawRect(0f, 0f, 640f, 260f, p)
        p.color = bottom; c.drawRect(0f, 260f, 640f, 480f, p)
        p.color = 0xFFFFE9A8.toInt(); c.drawCircle(470f, 150f, 60f, p)
        val f = File.createTempFile(name, ".jpg").apply { deleteOnExit() }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f.path
    }
    private fun first() = photo("cam-a", 0xFF4C7FB8.toInt(), 0xFF3F7D4E.toInt())
    private fun second() = photo("cam-b", 0xFFD98A5B.toInt(), 0xFF5B4A3E.toInt())

    private fun actsFor(box: ComposerBox, tools: ComposerTools? = null, fail: () -> Boolean = { false }) =
        composerActionsFor(box, tools, false, scope, send = { t, p, f -> if (fail()) throw java.io.IOException("Not connected to your computer."); sent += Sent(t, p, f) })

    private fun showComposer(box: ComposerBox, tools: ComposerTools? = null, fail: () -> Boolean = { false }) {
        val acts = actsFor(box, tools, fail)
        rule.setContent { AppTheme(dark = false) { MessageComposer(box.draft, { box.draft = it }, false, acts, box.attachments, emptyList(), sending = box.sending, failed = box.failed, onRetry = box.retry) } }
        rule.waitForIdle()
    }
    private fun tapSend() { rule.onNodeWithContentDescription(sendButton, useUnmergedTree = true).performTouchInput { click() }; rule.waitForIdle() }

    // (a) a captured photo lands as an attachment, no text is added and nothing is sent
    @Test fun aCapturedPhotoLandsInTheMessageBoxWithNoWordsAndNothingIsSent() {
        val box = ComposerBox(mutableStateOf(""))
        val tools = Tools()
        val acts = actsFor(box, tools)
        // The camera button opens the camera for THIS box.
        acts.onCamera(Rect(300f, 2000f, 380f, 2080f))
        assertEquals(1, tools.opened); assertSame(box, tools.asked)
        // He takes a photo and presses Add photo on the camera card.
        val shot = first()
        var open by mutableStateOf(true)
        rule.setContent { AppTheme(dark = false) { Box(Modifier.fillMaxSize()) {
            if (open) CameraCard(Rect(300f, 2000f, 380f, 2080f), onClosed = { open = false }, onUse = { box.addPhoto(it) }, onGallery = {}, binder = NoCamera(), startWith = shot)
        } } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("camera-card").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Send").assertDoesNotExist()
        rule.onNodeWithText("Add photo").performClick()
        rule.waitUntil(5_000) { !open }
        assertEquals(listOf(shot), box.attachments.toList())
        assertEquals("no words were written for him", "", box.draft)
        assertTrue("the camera sends nothing by itself", sent.isEmpty())
        assertTrue(box.canSend)
    }

    @Test fun aPhotoNeverTouchesWordsHeAlreadyTyped() {
        val box = ComposerBox(mutableStateOf("is this the right cable"))
        box.addPhoto(first())
        assertEquals("is this the right cable", box.draft)
        assertEquals(1, box.attachments.size)
    }

    // (b) two photos give two attachments; removing one leaves the other
    @Test fun twoPhotosAreTwoAttachmentsAndTheXRemovesOne() {
        val box = ComposerBox(mutableStateOf(""))
        val a = first(); val b = second()
        box.addPhoto(a); box.addPhoto(b)
        box.addPhoto(a) // the same shot twice is still one
        assertEquals(listOf(a, b), box.attachments.toList())
        showComposer(box)
        rule.onNodeWithContentDescription("Photo 1 to send", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Photo 2 to send", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithContentDescription("Remove photo 1", useUnmergedTree = true).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(listOf(b), box.attachments.toList())
        rule.onNodeWithContentDescription("Photo 2 to send", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(sent.isEmpty())
    }

    // (c) photos with no words can be sent, and the outgoing text is empty
    @Test fun photosWithNoWordsAreSentWithEmptyText() {
        val box = ComposerBox(mutableStateOf(""))
        val a = first(); val b = second()
        box.addPhoto(a); box.addPhoto(b)
        showComposer(box)
        tapSend()
        assertEquals(listOf(Sent("", listOf(a, b), emptyList())), sent)
        // Sent: the box is empty again.
        assertTrue(box.attachments.isEmpty()); assertEquals("", box.draft); assertNull(box.failed)
        // Both ways out (this phone's own agent and a paired computer) keep the text empty too.
        assertEquals("", Outgoing.words("", listOf(a, b), emptyList()))
        assertEquals("", Outgoing.words("   ", emptyList(), listOf("/docs/a.pdf")))
        assertEquals("look", Outgoing.words(" look ", listOf(a), emptyList()))
    }

    @Test fun theNoteForThePhonesOwnModelIsNeverHisMessage() {
        val a = first()
        val his = Outgoing.words("", listOf(a), emptyList())
        assertEquals("", his)
        assertNotEquals("", Outgoing.forModel(his!!))
        assertTrue(Outgoing.forModel(his).startsWith("["))
        assertEquals("what is this", Outgoing.forModel("what is this"))
    }

    // (d) nothing to send: Send does nothing
    @Test fun sendWithNoWordsAndNoPhotosIsNotAllowed() {
        val box = ComposerBox(mutableStateOf("   "))
        assertFalse(box.canSend)
        showComposer(box)
        tapSend()
        assertTrue(sent.isEmpty())
        assertNull(Outgoing.words("  ", emptyList(), emptyList()))
        actsFor(box).onSend("")
        assertTrue(sent.isEmpty())
    }

    @Test fun aSendThatFailsKeepsThePhotosAndRetrySendsThem() {
        val box = ComposerBox(mutableStateOf(""))
        val a = first(); val b = second()
        box.addPhoto(a); box.addPhoto(b)
        var down = true
        showComposer(box, fail = { down })
        tapSend()
        assertTrue(sent.isEmpty())
        assertEquals("the photos are not lost", listOf(a, b), box.attachments.toList())
        assertNotNull(box.failed); assertNotNull(box.retry)
        rule.onNodeWithContentDescription("Photo 1 did not send. Try again", useUnmergedTree = true).assertExists()
        down = false
        // A tap on a photo that did not go tries again.
        rule.onNodeWithContentDescription("Photo 1 did not send. Try again", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf(Sent("", listOf(a, b), emptyList())), sent)
        assertTrue(box.attachments.isEmpty()); assertNull(box.failed)
    }

    @Test fun thePhotosStayWhenHeLeavesTheChatAndComesBack() {
        val a = first()
        ComposerBoxes.box("chat:one").addPhoto(a)
        // Leaving the chat throws the screen away; coming back asks for the same box.
        assertEquals(listOf(a), ComposerBoxes.box("chat:one").attachments.toList())
        assertTrue("another chat has none of them", ComposerBoxes.box("chat:two").attachments.isEmpty())
        assertTrue(ComposerBoxes.box("main").attachments.isEmpty())
    }

    // ---- what it looks like: 390 wide, light and dark. PNGs in app/build/audit/fxB/.
    @Test fun shots() {
        val a = first(); val b = second()
        var dark by mutableStateOf(false)
        var words by mutableStateOf("")
        var failed by mutableStateOf<String?>(null)
        val box = ComposerBox(mutableStateOf("")); box.addPhoto(a); box.addPhoto(b)
        val acts = actsFor(box)
        rule.setContent { AppTheme(dark = dark) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                    MessageComposer(words, { words = it }, false, acts, box.attachments, emptyList(), failed = failed, onRetry = if (failed != null) ({}) else null)
                }
            }
        } }
        File("build/audit/fxB").mkdirs()
        fun cap(name: String) { rule.waitForIdle(); rule.onRoot().captureRoboImage(File("build/audit/fxB/$name-${if (dark) "dark" else "light"}.png").path) }
        for (d in listOf(false, true)) {
            dark = d
            words = ""; failed = null; cap("composer-two-photos-no-text")
            words = "Which of these two is the right cable?"; cap("composer-two-photos-with-text")
            words = ""; failed = "Not connected to your computer. Your photos and files are still here."; cap("composer-two-photos-failed")
        }
    }

    @Test fun cameraReviewShot() {
        val shot = first()
        rule.setContent { AppTheme(dark = false) { Box(Modifier.fillMaxSize()) {
            CameraCard(Rect(300f, 2000f, 380f, 2080f), onClosed = {}, onUse = {}, onGallery = {}, binder = NoCamera(), startWith = shot)
        } } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Add photo").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        File("build/audit/fxB").mkdirs()
        rule.onRoot().captureRoboImage(File("build/audit/fxB/camera-review.png").path)
    }
}
