package com.past9.phoneaos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.screens.UserBubble
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class AgentSenderShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()
    @Test fun senderIsLeftCardAndHisWordsAreRightBubble() {
        rule.setContent {
            AppTheme(dark = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    UserBubble(ChatItem(id = 1, kind = "user", text = "My own words"))
                    UserBubble(ChatItem(id = 2, kind = "user", text = "The playback fix is ready.", meta = """{"via":{"from":"helper","name":"Phone player","chat":"main"}}"""))
                }
            }
        }
        rule.onNodeWithText("My own words").assertIsDisplayed()
        rule.onNodeWithText("From the helper Phone player").assertIsDisplayed()
        val card = rule.onNodeWithTag("agent-sender-card").fetchSemanticsNode().boundsInRoot
        val own = rule.onNodeWithTag("own-message-bubble").fetchSemanticsNode().boundsInRoot
        assertTrue("Agent card starts left of his bubble", card.left < own.left)
        val out = File("/tmp/agent-sender-phone-shots/${if (dark) "dark" else "light"}.png").also { it.parentFile?.mkdirs() }
        rule.onRoot().captureRoboImage(out.path)
    }
}
