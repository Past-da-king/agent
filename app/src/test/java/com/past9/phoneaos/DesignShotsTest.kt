package com.past9.phoneaos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.miniapps.MiniAppInfo
import com.past9.phoneaos.remote.MeshDevice
import com.past9.phoneaos.tools.Connection
import com.past9.phoneaos.tools.Toolkit
import com.past9.phoneaos.ui.ChoiceSheet
import com.past9.phoneaos.ui.ComposerActions
import com.past9.phoneaos.ui.ConfirmSheet
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.screens.ConnectionsActions
import com.past9.phoneaos.ui.screens.ConnectionsScreen
import com.past9.phoneaos.ui.screens.ConnectionsState
import com.past9.phoneaos.ui.screens.MessageComposer
import com.past9.phoneaos.ui.screens.profileChoices
import com.past9.phoneaos.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The screens Ayanda's 29 Oct 2026 feedback named, at his A35's size, light and dark, in his accent and the default
 * one. PNGs land in app/build/design/ (copied into the proof folder).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DesignShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = dark) { CompositionLocalProvider(LocalSheetPreview provides true) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() } } } }
        rule.mainClock.advanceTimeBy(1200)
        rule.onRoot().captureRoboImage(File("build/design/$name-${if (dark) "dark" else "light"}.png").path)
    }

    private val profiles = listOf("" to "General", "p1" to "Strat Edge Works", "p2" to "Guavas", "p3" to "Personal-", "p4" to "Meetings", "p5" to "impetix")

    @Test fun profileSheet() = shot("01-connections-add-which-profile") {
        ChoiceSheet(Icons.Rounded.Apps, "Fathom", "Which profile is it for?", profileChoices(profiles), onPick = {}, onDismiss = {}, line = "Its account goes into that part of your life. You can change it later.")
    }

    @Test fun confirmSheet() = shot("02-confirm-sheet") {
        ConfirmSheet(Icons.Rounded.LinkOff, "Linked computer", "Unlink this phone?", "This phone forgets DESKTOP-EXAMPLE and the copy of its data. Nothing on the computer changes.",
            confirm = "Unlink", onConfirm = {}, onDismiss = {}, cancel = "Keep it linked", destructive = true)
    }

    private val now = System.currentTimeMillis()
    private fun dev(id: String, name: String, kind: String, roles: List<String>, seen: Long) =
        JSONObject().put("id", id).put("name", name).put("kind", kind).put("roles", JSONArray(roles)).put("lastSeen", seen)
    private val devices = MeshDevice.parse(JSONArray().put(dev("dev_pc", "DESKTOP-EXAMPLE", "desktop", listOf("brain", "hands"), now))
        .put(dev("dev_srv", "impetix", "server", listOf("brain", "hands"), now - 30_000)).put(dev("dev_pc2", "Second PC", "desktop", listOf("brain"), now - 7_200_000))
        .put(dev("dev_me", "Ayanda's A35", "phone", listOf("screen", "brain", "hands"), now)), "dev_me", "dev_pc", emptySet(), now)
    private val learned = MeshDevice.learned(JSONArray()
        .put(JSONObject().put("id", "l1").put("name", "Takealot").put("startUrl", "https://www.takealot.com").put("tools", JSONArray().put(JSONObject().put("name", "search_products").put("readOnly", true))))
        .put(JSONObject().put("id", "l2").put("name", "TikTok Studio").put("startUrl", "https://www.tiktok.com/tiktokstudio").put("tools", JSONArray().put(JSONObject().put("name", "post_video").put("readOnly", false))))
        .put(JSONObject().put("id", "l3").put("name", "YouTube Studio").put("startUrl", "https://studio.youtube.com")))
    private val minis = listOf(MiniAppInfo("man3rfqy4e5c92", "Week calendar", "Every calendar, this week", "calendar", 1, 3, true, 0), MiniAppInfo("ma2", "Cosmiprint score", "Views and follows", "chart", 0, 1, true, 0))
    private val fathom = Connection("ca_f", "fathom", "ACTIVE", "fathom")

    @Test fun connections() = shot("03-connections") {
        ConnectionsScreen(ConnectionsState(hasKey = true, remote = true, connected = listOf(fathom, fathom, fathom, Connection("ca_g", "gmail", "ACTIVE", "you@example.com")),
            toolkits = listOf(Toolkit("fathom", "Fathom", "", "", 0, false, true), Toolkit("gmail", "Gmail", "", "", 0, false, true)), devices = devices, learned = learned, miniApps = minis),
            ConnectionsActions(), initialAppsOpen = true)
    }

    @Test fun composer() = shot("04-project-chat-composer") {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.BottomCenter)) {
                MessageComposer("Here is the screenshot", {}, false, ComposerActions(onSend = {}), emptyList(), listOf("Invoice-Oct.pdf"), placeholder = "Message Agent Desktop App")
            }
        }
    }

    @Test fun pinnedRow() = shot("05-home-pinned-apps-row") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.past9.phoneaos.miniapps.PinnedAppsRow(minis, {}, {})
            com.past9.phoneaos.ui.SectionHeader("Your to-dos", "2 open, soonest first")
        }
    }
}
