package com.past9.phoneaos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.screens.WatchPairSheetView
import com.past9.phoneaos.ui.screens.WatchPairState
import com.past9.phoneaos.ui.theme.AppTheme
import com.past9.phoneaos.ui.theme.Media
import com.past9.phoneaos.wearproto.PairProblem
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Settings > Watch, every state, at 390 and 360 dp wide, light and dark. PNGs land in app/build/design/watch-pair/. The camera cannot run on a
 * headless JVM, so its window shows a stand-in; the scan box, torch and hint around it are the real ones.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class WatchPairShotsTest(private val dark: Boolean, private val width: Int) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} w={1}")
        fun params() = listOf(false, true).flatMap { d -> listOf(390, 360).map { w -> arrayOf<Any>(d, w) } }
    }
    @get:Rule val rule = createComposeRule()
    @Before fun size() { RuntimeEnvironment.setQualifiers("+w${width}dp-h${if (width == 390) 844 else 800}dp-xxhdpi") }

    @Test fun everyState() {
        var state by mutableStateOf<WatchPairState>(WatchPairState.Ready)
        var code by mutableStateOf("")
        var torch by mutableStateOf(false)
        var cam by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalSheetPreview provides true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        WatchPairSheetView(state, code, { code = it }, torch, { torch = it }, cam, { cam = true },
                            camera = { m -> Box(m.background(Media.stageRaised)) }, onPair = {}, onUnpair = {}, onRetry = {}, onDismiss = {})
                    }
                }
            }
        }
        val tag = "${width}-${if (dark) "dark" else "light"}"
        fun shot(name: String) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeBy(1500); rule.onRoot().captureRoboImage(File("build/design/watch-pair/$name-$tag.png").path) }
        shot("1-scanning")
        torch = true; shot("2-scanning-torch"); torch = false
        cam = false; shot("3-camera-off"); cam = true
        code = "9H4T"; shot("4-typing")
        code = "9H4T-5PBS"; shot("5-code-ready")
        state = WatchPairState.Pairing; shot("6-pairing")
        state = WatchPairState.Paired("Galaxy Watch4 Classic"); shot("7-paired")
        state = WatchPairState.Problem(PairProblem.NOT_MAIN_PROFILE); shot("8-error-secure-folder")
        state = WatchPairState.Problem(PairProblem.WATCH_NOT_CONNECTED); shot("9-error-not-connected")
        state = WatchPairState.Problem(PairProblem.WATCH_DID_NOT_ANSWER); shot("10-error-silent")
        state = WatchPairState.Problem(PairProblem.WIFI_UNREACHABLE); shot("11-error-wifi")
        state = WatchPairState.Problem(PairProblem.NO_PLAY_SERVICES); shot("12-error-play-services")
        state = WatchPairState.Problem(PairProblem.TOLD_NO, "That code is not right."); shot("13-error-wrong-code")
    }
}
