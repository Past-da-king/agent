package com.past9.phoneaos.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.wear.compose.material3.AppScaffold
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.wear.ui.AgentWatchTheme
import com.past9.phoneaos.wear.ui.AnswerScreen
import com.past9.phoneaos.wear.ui.AnswerUi
import com.past9.phoneaos.wear.ui.ApprovalScreen
import com.past9.phoneaos.wear.ui.DoneScreen
import com.past9.phoneaos.wear.ui.ExchangeScreen
import com.past9.phoneaos.wear.ui.ListeningScreen
import com.past9.phoneaos.wear.ui.RecentScreen
import com.past9.phoneaos.wear.ui.TalkScreen
import com.past9.phoneaos.wearproto.Exchange
import com.past9.phoneaos.wearproto.Pending
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every watch screen on a round 192 dp and 227 dp display, a square 192 dp and a rectangular 180 x 220 dp one, dark. PNGs land in
 * wear/build/design/<size>/. The round clip is drawn here so the mock shows what the glass hides; the screens themselves
 * lay out inside the real round window (the `round` qualifier).
 */
abstract class WatchShots(private val size: String, private val round: Boolean = true) {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AgentWatchTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).clip(if (round) CircleShape else RectangleShape)) { AppScaffold { content() } }
            }
        }
        rule.mainClock.advanceTimeBy(800)
        rule.onRoot().captureRoboImage(File("build/design/$size/$name.png").path)
    }

    private val quick = listOf("Yes", "No", "Not now", "Remind me later")
    private val waiting = Pending(7, Pending.KIND_APPROVAL, "Send the invoice to the client", "Invoice 0042 for the March retainer, to accounts@example.com", Pending.APPROVAL_OPTIONS, "Your agent")
    private val question = Pending(8, Pending.KIND_QUESTION, "Which time works for the call?", "", listOf("Tuesday 10:00", "Wednesday 14:00", "Neither"), "")
    private val recent = listOf(
        Exchange(1, "What is on today", "Two meetings, the first at 10:00. Nothing needs you before then.", 0),
        Exchange(2, "Remind me to call the bank", "Done. I will remind you at 15:00.", 0),
        Exchange(3, "Is the build green", "Yes, all checks passed on the last run.", 0),
    )

    @Test fun talk() = shot("01-talk") {
        TalkScreen("Nova", "pc", 1, true, quick, true, null, {}, {}, {}, {}, {}, {}, {})
    }
    @Test fun talkMicDead() = shot("02-talk-mic-dead") {
        TalkScreen("Nova", "local", 0, false, quick, true, null, {}, {}, {}, {}, {}, {}, {})
    }
    @Test fun listening() = shot("03-listening") { ListeningScreen(level = 0.6f, sending = false) {} }
    @Test fun sending() = shot("04-sending") { ListeningScreen(level = 0f, sending = true) {} }
    @Test fun answerWorking() = shot("05-answer-working") {
        AnswerScreen(AnswerUi("What is on today", "Reading your calendar", "", true, false, ""), {}, {}, {}, {})
    }
    @Test fun answer() = shot("06-answer") {
        AnswerScreen(AnswerUi("What is on today", "", "Two meetings today. The first is at 10:00 with the design team, the second at 14:30. Nothing needs you before then, and I moved the 16:00 call to tomorrow.", false, false, ""), {}, {}, {}, {})
    }
    @Test fun answerNeedsYou() = shot("07-answer-needs-you") {
        AnswerScreen(AnswerUi("Send the invoice", "Needs you", "", false, true, ""), {}, {}, {}, {})
    }
    @Test fun approval() = shot("08-approval") { ApprovalScreen(waiting, " 1/2") {} }
    @Test fun question() = shot("09-question") { ApprovalScreen(question, "") {} }
    @Test fun approved() = shot("10-approved") { DoneScreen("Approved", true) {} }
    @Test fun recentList() = shot("11-recent") { RecentScreen(recent) {} }
    @Test fun exchange() = shot("12-exchange") { ExchangeScreen(recent[0]) {} }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round-xhdpi")
class WatchShots192 : WatchShots("round-192")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round-xhdpi")
class WatchShots227 : WatchShots("round-227")

/** The same screens on a square watch and on a tall rectangular one: the layout must not assume a circle. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-notround-xhdpi")
class WatchShotsSquare192 : WatchShots("square-192", round = false)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w180dp-h220dp-notround-xhdpi")
class WatchShotsRect180x220 : WatchShots("rect-180x220", round = false)
