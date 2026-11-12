package com.past9.phoneaos.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.wear.compose.material3.AppScaffold
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.wear.ui.AgentWatchTheme
import com.past9.phoneaos.wear.ui.CallScreen
import com.past9.phoneaos.wear.ui.GesturesScreen
import com.past9.phoneaos.wear.ui.HomeScreen
import com.past9.phoneaos.wear.ui.HomeSettingsScreen
import com.past9.phoneaos.wear.ui.RingScreen
import com.past9.phoneaos.wearproto.AgentStatus
import com.past9.phoneaos.wearproto.AppEntry
import com.past9.phoneaos.wearproto.ApprovalRingMode
import com.past9.phoneaos.wearproto.CallPlan
import com.past9.phoneaos.wearproto.CallView
import com.past9.phoneaos.wearproto.HomeGuide
import com.past9.phoneaos.wearproto.RingItem
import com.past9.phoneaos.wearproto.RingKind
import com.past9.phoneaos.wearproto.Side
import com.past9.phoneaos.wearproto.WatchCallCore
import com.past9.phoneaos.wearproto.gesture.DeviceProfile
import com.past9.phoneaos.wearproto.gesture.Gesture
import com.past9.phoneaos.wearproto.gesture.GestureAction
import com.past9.phoneaos.wearproto.gesture.GestureMap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The call, ring, gestures and home screens, drawn on a round and a square watch. This proves they compose and lay out (including the
 * platform's one-handed-gesture modifier, which does nothing off a Wear OS 7 Pixel Watch); the design pass restyles them.
 * PNGs land in wear/build/design/engine-<shape>/.
 */
abstract class EngineShots(private val size: String, private val round: Boolean) {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AgentWatchTheme { Box(Modifier.fillMaxSize().background(Color.Black).clip(if (round) CircleShape else RectangleShape)) { AppScaffold { content() } } }
        }
        rule.mainClock.advanceTimeBy(800)
        rule.onRoot().captureRoboImage(File("build/design/engine-$size/$name.png").path)
    }

    private val plan = CallPlan("c1", Side.WATCH, Side.PHONE, "")
    private fun call(phase: String, sub: String = "", degraded: Boolean = false, line: String = "", error: String = "", muted: Boolean = false) =
        WatchCallCore.State("c1", phase, muted, line, "out", plan, error, degraded, sub)
    private val apps = listOf(AppEntry("a", "Alarm", "a.A"), AppEntry("b", "Maps", "b.B"), AppEntry("c", "Weather", "c.C"))
    private val profile = DeviceProfile(36, hasAccel = true, hasGyro = true, oem = "samsung")

    @Test fun ringWake() = shot("01-ring-wake") { RingScreen(RingItem("w", RingKind.WAKE, "critical", "Standup starts now", "Join the call, Thabo presents your numbers"), {}, {}, {}) }
    @Test fun ringApproval() = shot("02-ring-approval") { RingScreen(RingItem("card:7", RingKind.APPROVAL, "urgent", "Send the invoice", "To Shey"), {}, {}, {}) }
    @Test fun callConnecting() = shot("03-call-connecting") { CallScreen(call(WatchCallCore.STARTING), "Nova", {}, {}, {}, {}) }
    @Test fun callSpeaking() = shot("04-call-speaking") { CallScreen(call(WatchCallCore.LIVE, CallView.SPEAKING, line = "You have two meetings today, the first at ten."), "Nova", {}, {}, {}, {}) }
    @Test fun callReconnecting() = shot("05-call-reconnecting") { CallScreen(call(WatchCallCore.LIVE, CallView.LISTENING, degraded = true, muted = true), "Nova", {}, {}, {}, {}) }
    @Test fun callFailed() = shot("06-call-failed") { CallScreen(call(WatchCallCore.FAILED, error = "Phone not reachable"), "Nova", {}, {}, {}, {}) }
    @Test fun gestures() = shot("07-gestures") {
        GesturesScreen(GestureMap().with(Gesture.FLICK, GestureAction.TALK), profile, ApprovalRingMode.FULL, {}, {}, {}, {})
    }
    @Test fun gesturesNoGyro() = shot("08-gestures-no-gyro") {
        GesturesScreen(GestureMap(), profile.copy(hasGyro = false), ApprovalRingMode.BUZZ, {}, {}, {}, {})
    }
    @Test fun home() = shot("09-home") { HomeScreen("Nova", AgentStatus.NEEDS_YOU, 2, apps, apps.take(2), 0, {}, {}, {}, {}) }
    @Test fun homeSettingsOff() = shot("10-home-settings-off") { HomeSettingsScreen(HomeGuide.State.OFF, true, "", {}, {}, {}) }
    @Test fun homeSettingsNoPicker() = shot("11-home-settings-no-picker") { HomeSettingsScreen(HomeGuide.State.ON_THIS_APP_ASKED, false, "", {}, {}, null) }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round-xhdpi")
class EngineShotsRound192 : EngineShots("round-192", true)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-notround-xhdpi")
class EngineShotsSquare192 : EngineShots("square-192", false)
