package com.past9.phoneaos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.ui.screens.TakeMarkLine
import com.past9.phoneaos.ui.theme.AppTheme
import com.past9.phoneaos.voice.TakeMark
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The marker under an instantly sent dictated message (Refining, and Retry when it failed), at 390 wide, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class InstantSendShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()
    private fun file(name: String) = File("build/instant-send/390/$name-${if (dark) "dark" else "light"}.png").also { it.parentFile?.mkdirs() }.path

    private fun shot(name: String, caption: String, kind: TakeMark.Kind) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.align(Alignment.TopCenter).padding(16.dp, 120.dp, 16.dp, 0.dp)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) {
                                Text(caption, Modifier.padding(14.dp, 10.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        TakeMarkLine(kind) {}
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(300)
        rule.onRoot().captureRoboImage(file(name))
    }

    @Test fun refining() = shot("01-refining", "for example constration head but some other companies start agentility", TakeMark.Kind.REFINING)
    @Test fun failed() = shot("02-failed", "for example constration head but some other companies start agentility", TakeMark.Kind.FAILED)
    @Test fun refined() = shot("03-refined", "For example, the letterhead? Because Strat Edge has its own letterhead.", TakeMark.Kind.NONE)
}
