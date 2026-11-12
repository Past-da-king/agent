package com.past9.phoneaos.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.wear.ui.AgentWatchTheme
import com.past9.phoneaos.wear.ui.AnswerScreen
import com.past9.phoneaos.wear.ui.AnswerUi
import com.past9.phoneaos.wear.ui.ApprovalScreen
import com.past9.phoneaos.wear.ui.CallScreen
import com.past9.phoneaos.wear.ui.DoneScreen
import com.past9.phoneaos.wear.ui.ExchangeScreen
import com.past9.phoneaos.wear.ui.GesturesScreen
import com.past9.phoneaos.wear.ui.HomeScreen
import com.past9.phoneaos.wear.ui.HomeSettingsScreen
import com.past9.phoneaos.wear.ui.ListeningScreen
import com.past9.phoneaos.wear.ui.PairStage
import com.past9.phoneaos.wear.ui.PairingScreen
import com.past9.phoneaos.wear.ui.RecentScreen
import com.past9.phoneaos.wear.ui.RingScreen
import com.past9.phoneaos.wear.ui.TalkScreen
import com.past9.phoneaos.wear.ui.WatchSettingsScreen
import com.past9.phoneaos.wear.ui.ex.AgentLook
import com.past9.phoneaos.wear.ui.ex.LocalLook
import com.past9.phoneaos.wear.ui.ex.MascotKind
import com.past9.phoneaos.wear.ui.ex.Pal
import com.past9.phoneaos.wearproto.AgentStatus
import com.past9.phoneaos.wearproto.AppEntry
import com.past9.phoneaos.wearproto.ApprovalRingMode
import com.past9.phoneaos.wearproto.CallPlan
import com.past9.phoneaos.wearproto.CallView
import com.past9.phoneaos.wearproto.Exchange
import com.past9.phoneaos.wearproto.HomeGuide
import com.past9.phoneaos.wearproto.Pending
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
 * Every watch screen (Material 3 Expressive, docs/wear.md "Look") on a round 192 and 227 dp display, a square 192 dp and a rectangular
 * 180 x 220 dp one. PNGs land in wear/build/design/x/<size>/. Clips are numbered frames in wear/build/design/x/clips/<name>/, one frame
 * every 1/15 s of the test clock, so the real animations (breathing, wavy rings, sheets) run.
 */
abstract class XShots(private val size: String, private val round: Boolean = true) {
    @get:Rule val rule = createComposeRule()
    protected var cur by mutableStateOf<@Composable () -> Unit>({})
    private var started = false
    protected fun host() {
        if (started) return
        started = true
        rule.mainClock.autoAdvance = false
        rule.setContent { AgentWatchTheme { Box(Modifier.fillMaxSize().background(Color.Black).clip(if (round) CircleShape else RectangleShape)) { cur() } } }
    }

    protected fun shot(name: String, dir: String = size, content: @Composable () -> Unit) {
        host(); cur = content; rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeBy(500)
        rule.onRoot().captureRoboImage(File("build/design/x/_warm.png").path)
        rule.mainClock.advanceTimeBy(400); rule.mainClock.advanceTimeByFrame()
        rule.onRoot().captureRoboImage(File("build/design/x/$dir/$name.png").path)
    }

    protected fun frames(name: String, n: Int, content: @Composable (Float) -> Unit) {
        host()
        var tt by mutableFloatStateOf(0f)
        cur = { content(tt) }
        rule.mainClock.advanceTimeByFrame()
        for (i in 0 until n) {
            tt = i / 15f
            rule.mainClock.advanceTimeBy(if (i == 0) 16 else 66)
            rule.onRoot().captureRoboImage(File("build/design/x/clips/$name/f%03d.png".format(i)).path)
        }
    }

    private val plan = CallPlan("c1", Side.WATCH, Side.WATCH, "")
    private fun call(phase: String, sub: String = "", degraded: Boolean = false, line: String = "", error: String = "", muted: Boolean = false) =
        WatchCallCore.State("c1", phase, muted, line, "out", plan, error, degraded, sub)
    private val apps = listOf(AppEntry("me", "Agent", "m.A"), AppEntry("a", "Alarm", "a.A"), AppEntry("b", "Maps", "b.B"), AppEntry("c", "Weather", "c.C"), AppEntry("d", "Fitness", "d.D"), AppEntry("e", "Music", "e.E"),
        AppEntry("f", "Notes", "f.F"), AppEntry("g", "Timer", "g.G"), AppEntry("h", "Wallet", "h.H"), AppEntry("i", "Phone", "i.I"))
    private val profile = DeviceProfile(36, hasAccel = true, hasGyro = true, oem = "samsung")
    private val quick = listOf("Yes", "No", "Not now", "Remind me later")
    private val waiting = Pending(7, Pending.KIND_APPROVAL, "Send the invoice to the client", "Invoice 0042, March retainer, to accounts@example.com", Pending.APPROVAL_OPTIONS, "Your agent")
    private val question = Pending(8, Pending.KIND_QUESTION, "Which time works for the call?", "", listOf("Tuesday 10:00", "Wednesday 14:00", "Neither"), "")
    private val recent = listOf(
        Exchange(1, "What is on today", "Two meetings, the first at 10:00. Nothing needs you before then.", 0),
        Exchange(2, "Remind me to call the bank", "Done. I will remind you at 15:00.", 0),
        Exchange(3, "Is the build green", "Yes, all checks passed on the last run.", 0),
    )
    private val longAnswer = "You have two meetings today. The first is at 10:00 with the design team, the second at 14:30. Nothing needs you before then, and I moved the 16:00 call to tomorrow."

    val map = GestureMap().with(Gesture.FLICK, GestureAction.TALK).with(Gesture.DOUBLE_TAP, GestureAction.APPROVE)

    @Test fun s01() = shot("01-talk") { TalkScreen("Nova", "local", 0, true, quick, true, null, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun s02() = shot("02-talk-needs-you") { TalkScreen("Nova", "local", 1, true, quick, true, null, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun s03() = shot("03-talk-mic-dead") { TalkScreen("Nova", "pc", 0, false, quick, false, null, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun s04() = shot("04-talk-phone-not-reachable") { TalkScreen("Nova", "none", 0, true, quick, false, null, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun s05() = shot("05-listening") { ListeningScreen(0.6f, false) {} }
    @Test fun s06() = shot("06-sending") { ListeningScreen(0f, true) {} }
    @Test fun s07() = shot("07-answer-thinking") { AnswerScreen(AnswerUi("What is on today", "Reading your calendar", "", true, false, ""), {}, {}, {}, {}) }
    @Test fun s08() = shot("08-answer") { AnswerScreen(AnswerUi("What is on today", "", longAnswer, false, false, ""), {}, {}, {}, {}) }
    @Test fun s09() = shot("09-answer-needs-you") { AnswerScreen(AnswerUi("Send the invoice", "Needs you", "", false, true, ""), {}, {}, {}, {}) }
    @Test fun s10() = shot("10-answer-error") { AnswerScreen(AnswerUi("", "", "", false, false, "Phone not reachable"), {}, {}, {}, {}) }
    @Test fun s11() = shot("11-approval") { ApprovalScreen(waiting, " 1/2", onAnswer = {}) }
    @Test fun s12() = shot("12-approval-holding") { ApprovalScreen(waiting, " 1/2", previewHold = 0.6f, onAnswer = {}) }
    @Test fun s13() = shot("13-approval-question") { ApprovalScreen(question, "", onAnswer = {}) }
    @Test fun s14() = shot("14-done-approved") { DoneScreen("Approved", true) {} }
    @Test fun s15() = shot("15-done-declined") { DoneScreen("Declined", false) {} }
    @Test fun s16() = shot("16-recent") { RecentScreen(recent) {} }
    @Test fun s17() = shot("17-exchange") { ExchangeScreen(recent[0]) {} }
    @Test fun s18() = shot("18-call-connecting") { CallScreen(call(WatchCallCore.STARTING), "Nova", {}, {}, {}, {}, previewSecs = 0) }
    @Test fun s19() = shot("19-call-speaking") { CallScreen(call(WatchCallCore.LIVE, CallView.SPEAKING), "Nova", {}, {}, {}, {}, previewSecs = 134) }
    @Test fun s20() = shot("20-call-listening") { CallScreen(call(WatchCallCore.LIVE, CallView.LISTENING), "Nova", {}, {}, {}, {}, previewSecs = 21) }
    @Test fun s21() = shot("21-call-muted") { CallScreen(call(WatchCallCore.LIVE, CallView.LISTENING, muted = true), "Nova", {}, {}, {}, {}, previewSecs = 312) }
    @Test fun s22() = shot("22-call-sound-sheet") { CallScreen(call(WatchCallCore.LIVE, CallView.SPEAKING), "Nova", {}, {}, {}, {}, previewSheet = true, previewSecs = 134) }
    @Test fun s23() = shot("23-call-failed") { CallScreen(call(WatchCallCore.FAILED, error = "Phone not reachable"), "Nova", {}, {}, {}, {}) }
    @Test fun s24() = shot("24-ring-wake") { RingScreen(RingItem("w", RingKind.WAKE, "urgent", "Design review in 5 min", "Thabo is presenting your numbers"), {}, {}, {}) }
    @Test fun s25() = shot("25-ring-critical") { RingScreen(RingItem("w2", RingKind.WAKE, "critical", "Standup starts now", "Join the call, Thabo presents your numbers"), {}, {}, {}) }
    @Test fun s26() = shot("26-ring-approval") { RingScreen(RingItem("card:7", RingKind.APPROVAL, "urgent", "Send the invoice", "To accounts@example.com"), {}, {}, {}) }
    @Test fun s27() = shot("27-gestures") { GesturesScreen(map, profile, ApprovalRingMode.BUZZ, {}, {}, {}, {}) }
    @Test fun s28() = shot("28-gesture-picker") { GesturesScreen(map, profile, ApprovalRingMode.BUZZ, {}, {}, {}, {}, previewPick = Gesture.FLICK) }
    @Test fun s29() = shot("29-gestures-not-on-this-watch") { GesturesScreen(GestureMap(), profile.copy(hasGyro = false), ApprovalRingMode.BUZZ, {}, {}, {}, {}) }
    @Test fun s30() = shot("30-ring-mode-sheet") { GesturesScreen(map, profile, ApprovalRingMode.BUZZ, {}, {}, {}, {}, previewMode = true) }
    @Test fun s31() = shot("31-launcher-home") { HomeScreen("Nova", AgentStatus.IDLE, 0, apps, apps.drop(2).take(3), 0, {}, {}, {}, {}) }
    @Test fun s32() = shot("32-launcher-home-needs-you") { HomeScreen("Nova", AgentStatus.NEEDS_YOU, 2, apps, apps.drop(2).take(3), 0, {}, {}, {}, {}) }
    @Test fun s33() = shot("33-app-grid") { HomeScreen("Nova", AgentStatus.IDLE, 0, apps, apps.drop(2).take(3), 0, {}, {}, {}, {}, previewGrid = true) }
    @Test fun s34() = shot("34-recent-apps") { HomeScreen("Nova", AgentStatus.IDLE, 0, apps, apps.drop(2).take(3), 0, {}, {}, {}, {}, previewRecents = true) }
    @Test fun s35() = shot("35-home-setting-standard") { HomeSettingsScreen(HomeGuide.State.OFF, true, "", {}, {}, {}) }
    @Test fun s36() = shot("36-home-choose-sheet") { HomeSettingsScreen(HomeGuide.State.OFF, true, "", {}, {}, {}, previewSheet = 1) }
    @Test fun s37() = shot("37-home-setting-agent-home") { HomeSettingsScreen(HomeGuide.State.HELD_BY_THIS_APP, true, "", {}, {}, {}) }
    @Test fun s38() = shot("38-switch-back-sheet") { HomeSettingsScreen(HomeGuide.State.HELD_BY_THIS_APP, true, "", {}, {}, {}, previewSheet = 2) }
    @Test fun s39() = shot("39-settings") { WatchSettingsScreen("Nova", {}, {}, {}) }
    @Test fun s40() = shot("40-pair-scan") { PairingScreen(PairStage.SCAN, "agent://watch-pair?code=482913&id=7K2QX", "482 913", 0, "Nova", MascotKind.Scout, {}) }
    @Test fun s41() = shot("41-pair-pulling") { PairingScreen(PairStage.PULLING, "", "", 1, "Nova", MascotKind.Scout, {}) }
    @Test fun s42() = shot("42-pair-done") { PairingScreen(PairStage.DONE, "", "", 3, "Nova", MascotKind.Bloom, {}) }
    @Test fun s43() = shot("43-look-synced-accent") {
            CompositionLocalProvider(LocalLook provides AgentLook(MascotKind.Gem, Pal.of(Color(0xFF7FD6B2)))) { TalkScreen("Nova", "local", 0, true, quick, true, null, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        }
}

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round-xhdpi")
class XShots227 : XShots("round-227") {
    @Test fun clips() {
        val q = listOf("Yes", "No")
        frames("talk-flow", 120) { t ->
            val toListen = ((t - 1.0f) / 0.3f).coerceIn(0f, 1f); val toThink = ((t - 3.4f) / 0.3f).coerceIn(0f, 1f); val toAnswer = ((t - 5.2f) / 0.3f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().alpha(1f - toListen)) { TalkScreen("Nova", "local", 0, true, q, true, null, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
                if (toListen > 0f && toThink < 1f) Box(Modifier.fillMaxSize().alpha(toListen * (1f - toThink))) { ListeningScreen(((kotlin.math.sin(t * 6f) + 1f) / 2f) * 0.8f, false) {} }
                if (toThink > 0f && toAnswer < 1f) Box(Modifier.fillMaxSize().alpha(toThink * (1f - toAnswer))) { AnswerScreen(AnswerUi("What is on today", "Reading your calendar", "", true, false, ""), {}, {}, {}, {}) }
                if (toAnswer > 0f) Box(Modifier.fillMaxSize().alpha(toAnswer)) { AnswerScreen(AnswerUi("What is on today", "", "You have two meetings today. The first is at 10:00 with the design team, the second at 14:30. Nothing needs you before then.", false, false, ""), {}, {}, {}, {}) }
            }
        }
        frames("approve-hold", 54) { t ->
            val hold = (t / 1.6f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxSize()) {
                if (t < 2.0f) ApprovalScreen(Pending(7, Pending.KIND_APPROVAL, "Send the invoice to the client", "Invoice 0042, March retainer", Pending.APPROVAL_OPTIONS, ""), " 1/2", previewHold = hold, onAnswer = {})
                else DoneScreen("Approved", true) {}
            }
        }
        frames("call", 75) { t ->
            CallScreen(WatchCallCore.State("c1", WatchCallCore.LIVE, false, "", "out", CallPlan("c1", Side.WATCH, Side.WATCH, ""), "", false, if ((t % 4f) < 2f) CallView.LISTENING else CallView.SPEAKING), "Nova", {}, {}, {}, {}, previewSecs = 134 + t.toInt())
        }
        frames("ring", 45) { t -> RingScreen(RingItem("w", RingKind.WAKE, "urgent", "Design review in 5 min", "Thabo is presenting your numbers"), {}, {}, {}) }
        frames("pairing", 90) { t ->
            val stage = when { t < 2.0f -> PairStage.SCAN; t < 5.0f -> PairStage.PULLING; else -> PairStage.DONE }
            PairingScreen(stage, "agent://watch-pair?code=482913&id=7K2QX", "482 913", ((t - 2f) / 1f).toInt().coerceIn(0, 2), "Nova", MascotKind.Bloom, {})
        }
        val apps = listOf(AppEntry("me", "Agent", "m.A"), AppEntry("a", "Alarm", "a.A"), AppEntry("b", "Maps", "b.B"), AppEntry("c", "Weather", "c.C"), AppEntry("d", "Fitness", "d.D"), AppEntry("e", "Music", "e.E"), AppEntry("f", "Notes", "f.F"), AppEntry("g", "Timer", "g.G"))
        frames("launcher-grid", 30) { t ->
            Box(Modifier.fillMaxSize()) {
                HomeScreen("Nova", AgentStatus.IDLE, 0, apps, apps.drop(2).take(2), 0, {}, {}, {}, {}, previewGrid = t >= 1.0f)
            }
        }
        frames("sheet-rise", 30) { t ->
            GesturesScreen(GestureMap().with(Gesture.FLICK, GestureAction.TALK), DeviceProfile(36, hasAccel = true, hasGyro = true, oem = "samsung"), ApprovalRingMode.BUZZ, {}, {}, {}, {}, previewPick = if (t >= 0.5f) Gesture.FLICK else null)
        }
    }
}

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round-xhdpi")
class XShots192 : XShots("round-192")

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-notround-xhdpi")
class XShotsSquare192 : XShots("square-192", round = false)

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w180dp-h220dp-notround-xhdpi")
class XShotsRect : XShots("rect-180x220", round = false)
