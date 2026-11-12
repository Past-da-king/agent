package com.past9.phoneaos.wear.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.wear.ui.AgentWatchTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Watch v2 design mocks: every screen of the premium redesign, rendered from the real Compose code (so the mock IS the
 * build) on round 192 and 227 dp, square 192 and rectangular 180 x 220, plus watch faces at 450 px and the motion clips
 * as numbered frames (ffmpeg turns them into mp4/gif). Output: wear/build/design/v2/.
 */
abstract class WatchV2Shots(private val size: String, private val round: Boolean = true) {
    @get:Rule val rule = createComposeRule()
    protected var cur by mutableStateOf<@Composable () -> Unit>({})
    private var started = false

    protected fun host() {
        if (started) return
        started = true
        rule.setContent {
            AgentWatchTheme { Box(Modifier.fillMaxSize().background(Color.Black).clip(if (round) CircleShape else RectangleShape)) { cur() } }
        }
    }

    protected fun shot(name: String, dir: String = size, content: @Composable () -> Unit) {
        host(); cur = content; rule.waitForIdle()
        rule.onRoot().captureRoboImage(File("build/design/v2/$dir/$name.png").path)
    }

    protected fun frames(name: String, n: Int, fps: Int, content: @Composable (Float) -> Unit) {
        host()
        var tt by mutableFloatStateOf(0f)
        cur = { content(tt) }
        for (i in 0 until n) {
            tt = i / fps.toFloat(); rule.waitForIdle()
            rule.onRoot().captureRoboImage(File("build/design/v2/clips/$name/f%03d.png".format(i)).path)
        }
    }

    /** The phone's own AgentAvatar code (top row) next to the watch mascot (bottom row): rest, working, needs you, and four of the nine. */
    @Test fun mascotMatchesPhone() {
        host(); cur = {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Color(0xFF131218)), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    PhoneAgentAvatar(false, 48.dp); PhoneAgentAvatar(true, 48.dp); PhoneAgentAvatar(false, 48.dp, needsYou = true); PhoneAgentAvatar(false, 48.dp, mascot = "bloom")
                }
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    for ((i, m) in listOf(Mood.Idle, Mood.Thinking, Mood.Needs, Mood.Idle).withIndex()) Stage(Modifier.size(48.dp)) { Orb(24.dp, 24.dp, 23.dp, m, if (i == 1) 0.7f else 0f, 0f, if (i == 3) MascotKind.Bloom else MascotKind.Scout, glow = 0f) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        rule.onRoot().captureRoboImage(File("build/design/v2/mascot-compare-$size.png").path)
    }

    @Test fun screens() {
        shot("01-home-idle") { HomeScreen(1.3f) }
        shot("02-home-needs-you") { HomeScreen(1.5f, pending = 1) }
        shot("03-listening") { ListeningScreen(2.0f, 0.62f) }
        shot("04-thinking") { ThinkingScreen(1.2f) }
        shot("05-answer-speaking") { AnswerScreen(1.0f, 0f, true, 0.55f) }
        shot("06-answer-scrolled") { AnswerScreen(1.0f, 52f, false) }
        shot("07-approval") { ApprovalScreen(1.0f) }
        shot("08-approval-holding") { ApprovalScreen(1.0f, hold = 0.62f) }
        shot("09-approval-approved") { ApprovalScreen(1.0f, hold = 1f, done = 0.75f) }
        shot("10-question") { QuestionScreen(1.0f) }
        shot("11-call-active") { CallScreen(1.6f, 0.62f) }
        shot("12-call-muted-earbuds") { CallScreen(1.6f, 0f, muted = true, route = "Earbuds", routeIcon = Ic.headphones, secs = 312) }
        shot("13-call-audio-sheet") { CallScreen(1.6f, 0.4f, sheet = 1f) }
        shot("14-ring-wakeup") { RingScreen(0.55f, RingKind.Wake) }
        shot("15-ring-call") { RingScreen(0.55f, RingKind.Call) }
        shot("16-ring-approval") { RingScreen(0.9f, RingKind.Approval) }
        shot("17-gestures") { GesturesScreen(0.4f) }
        shot("18-gesture-picker") { GesturesScreen(0.4f, picker = 1f) }
        shot("19-gesture-not-on-this-watch") { GestureUnsupportedScreen(1f) }
        shot("20-tile-talk") { TileTalk(1.3f) }
        shot("21-tile-glance") { TileGlance(1.3f) }
        shot("22-complications") { ComplicationGallery(1.0f) }
        shot("23-launcher-home") { LauncherHome(1.3f) }
        shot("24-launcher-home-needs-you") { LauncherHome(1.5f, pending = 2) }
        shot("25-app-grid") { AppGridScreen(1.3f) }
        shot("26-app-grid-scrolled") { AppGridScreen(1.3f, pan = Offset(-22f, -44f), focus = 3) }
        shot("27-recent-apps") { RecentsScreen(1.3f) }
        shot("28-home-setting-standard") { HomeAppSetting(1.0f, selected = 1) }
        shot("29-home-choose-sheet") { HomeChooseSheet(1.0f) }
        shot("30-home-setting-agent-switch-back") { SwitchBackScreen(1.0f) }
        shot("31-switch-back-sheet") { SwitchBackScreen(1.0f, sheet = 1f) }
        shot("32-switched-back") { SwitchedBackDone(1.0f) }
        shot("33-mascots") { MascotStrip(1.0f) }
        shot("34-pair-scan") { PairScan(1.0f) }
        shot("35-pair-busy") { PairBusy(1.0f, 1) }
        shot("36-pair-done") { PairDone(1.0f, "Nova", MascotKind.Bloom) }
    }
}

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round-xhdpi")
class WatchV2Shots192 : WatchV2Shots("round-192")

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-notround-xhdpi")
class WatchV2ShotsSquare192 : WatchV2Shots("square-192", round = false)

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w180dp-h220dp-notround-xhdpi")
class WatchV2ShotsRect : WatchV2Shots("rect-180x220", round = false)

/** 227 dp round: the stills, and every motion clip as frames. */
@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round-xhdpi")
class WatchV2Shots227 : WatchV2Shots("round-227") {
    @Test fun clips() {
        frames("talk-flow", 135, 15) { TalkFlow(it) }
        frames("approve-hold", 54, 15) { t ->
            val hold = smooth(t / 1.6f); val done = smooth((t - 1.7f) / 0.9f)
            ApprovalScreen(t, hold = if (t > 1.6f) 1f else hold, done = done * 1f)
        }
        frames("ring", 45, 15) { RingScreen(it, RingKind.Wake) }
        frames("call", 75, 15) { t ->
            val mine = (t % 4f) < 2f
            CallScreen(t, if (mine) voice(t) else voice(t, 3f) * 0.8f, secs = 134 + t.toInt())
        }
        frames("gesture-picker-rise", 30, 15) { GesturesScreen(it, picker = smooth(it / 0.7f)) }
        frames("launcher-to-grid", 60, 15) { t ->
            val a = smooth((t - 0.35f) / 0.45f); val fan = smooth((t - 0.45f) / 0.9f)
            val pan = Offset(0f, -46f * smooth((t - 2.3f) / 1.5f))
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().alpha(1f - a)) { LauncherHome(t) }
                Box(Modifier.fillMaxSize().alpha(a)) { AppGridScreen(t, fan, pan) }
            }
        }
    }
}

/** The watch faces at 450 x 450 px (225 dp at xhdpi): three directions, each with its low-power ambient look. */
@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w225dp-h225dp-round-xhdpi")
class WatchV2Faces : WatchV2Shots("face-450") {
    @Test fun faces() {
        val names = listOf("a-still", "b-orb", "c-data")
        for (i in 0..2) {
            shot("face-${names[i]}-active", "faces") { WatchFace(i, 1.9f) }
            shot("face-${names[i]}-ambient", "faces") { WatchFace(i, 1.9f, ambient = true) }
        }
    }
    @Test fun faceClips() {
        frames("face-orb-to-ambient", 75, 15) { t -> val a = smooth((t - 3.6f) / 0.6f); Box(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) { WatchFace(1, t) }; Box(Modifier.fillMaxSize().alpha(a)) { WatchFace(1, t, ambient = true) } } }
        frames("face-still-to-ambient", 75, 15) { t -> val a = smooth((t - 3.6f) / 0.6f); Box(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) { WatchFace(0, t) }; Box(Modifier.fillMaxSize().alpha(a)) { WatchFace(0, t, ambient = true) } } }
    }
}


/** Direction B, Material 3 Expressive for Wear, same screens and file names as direction A, in ex-<size>/. */
abstract class WatchExShots(private val dir: String, private val round: Boolean = true) {
    @get:Rule val rule = createComposeRule()
    private var cur by mutableStateOf<@Composable () -> Unit>({})
    private var started = false
    private fun host() {
        if (started) return
        started = true
        rule.setContent { AgentWatchTheme { Box(Modifier.fillMaxSize().background(Color.Black).clip(if (round) CircleShape else RectangleShape)) { cur() } } }
    }
    protected fun shot(name: String, content: @Composable () -> Unit) {
        host(); cur = content; rule.waitForIdle()
        rule.onRoot().captureRoboImage(File("build/design/v2/$dir/$name.png").path)
    }
    protected fun frames(name: String, n: Int, fps: Int, content: @Composable (Float) -> Unit) {
        host()
        var tt by mutableFloatStateOf(0f)
        cur = { content(tt) }
        for (i in 0 until n) { tt = i / fps.toFloat(); rule.waitForIdle(); rule.onRoot().captureRoboImage(File("build/design/v2/clips/$name/f%03d.png".format(i)).path) }
    }
    @Test fun home() {
        shot("01-home-idle") { ExHome(1.3f) }
        shot("02-home-needs-you") { ExHome(1.5f, pending = 1) }
    }
    @Test fun talk() {
        shot("03-listening") { ExListening(2.0f, 0.62f) }
        shot("04-thinking") { ExThinking(1.2f) }
        shot("05-answer-speaking") { ExAnswer(1.0f) }
        shot("07-approval") { ExApproval(1.0f) }
        shot("08-approval-holding") { ExApproval(1.0f, hold = 0.6f) }
        shot("11-call-active") { ExCall(1.6f, 0.6f) }
    }
    @Test fun pairing() {
        shot("34-pair-scan") { ExPairScan(1.0f) }
        shot("35-pair-busy") { ExPairBusy(1.0f, 1) }
        shot("36-pair-done") { ExPairDone(1.0f, "Nova", MascotKind.Bloom) }
    }
}

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round-xhdpi")
class WatchExShots227 : WatchExShots("ex-round-227")

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round-xhdpi")
class WatchExShots192 : WatchExShots("ex-round-192")

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w225dp-h225dp-round-xhdpi")
class WatchExFaces : WatchExShots("ex-faces") {
    @Test fun faces() {
        val names = listOf("a-still", "b-orb", "c-data")
        for (i in 0..2) {
            shot("face-${names[i]}-active") { ExFace(i, 1.9f) }
            shot("face-${names[i]}-ambient") { ExFace(i, 1.9f, ambient = true) }
        }
    }
}
