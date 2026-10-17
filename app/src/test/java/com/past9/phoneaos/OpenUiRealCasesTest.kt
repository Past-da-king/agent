package com.past9.phoneaos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.MdPart
import com.past9.phoneaos.ui.MdTable
import com.past9.phoneaos.ui.openui.LocalOpenUiImages
import com.past9.phoneaos.ui.openui.OpenUiLang
import com.past9.phoneaos.ui.screens.ChatActions
import com.past9.phoneaos.ui.screens.ChatScreen
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Everyday answers written by a real model (opencode/deepseek-v4.1-flash-free, given the app's OpenUI prompt section),
 * with every name, place, id and amount then swapped for made-up demo data. Each is drawn in the chat at phone width,
 * dark. PNGs in app/build/screens-openui/openui-<case>.png, and what the parser reported in report-<case>.txt.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OpenUiRealCasesTest {
    @get:Rule val rule = createComposeRule()

    private fun res(name: String) = javaClass.classLoader!!.getResource("openui/$name")!!.readText()
    private fun bytes(name: String) = javaClass.classLoader!!.getResource("openui/$name")!!.readBytes()
    /** Made-up demo photos for the made-up listings, so the screenshot doesn't depend on the network. */
    private val photos by lazy {
        mapOf("photos/1.jpg" to "demo-photo-1.png", "photos/2.jpg" to "demo-photo-2.png", "photos/3.jpg" to "demo-photo-3.png").mapValues { (_, f) ->
            bytes(f).let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap() } }
    }

    private fun case(name: String, heightDp: Int) {
        RuntimeEnvironment.setQualifiers("w390dp-h${heightDp}dp-port-night-xxhdpi")
        val ask = res("$name.ask.txt").lineSequence().first().trim()
        val answer = res("$name.md")
        // What the parser made of each block, for the write-up.
        val blocks = MdTable.split(answer).filterIsInstance<MdPart.OpenUi>()
        val report = blocks.joinToString("\n") { b -> val r = OpenUiLang.render(OpenUiLang.parse(b.source))
            "$name: root=${r.root?.type} errors=${r.errors} unresolved=${r.unresolved}" }
        File("build/screens-openui").mkdirs(); File("build/screens-openui/report-$name.txt").writeText(report.ifEmpty { "$name: no openui block" } + "\n")
        assertNotNull("$name rendered nothing", blocks.firstOrNull()?.let { OpenUiLang.render(OpenUiLang.parse(it.source)).root })
        val now = System.currentTimeMillis()
        val items = listOf(ChatItem(id = 1, kind = "user", text = ask, meta = "{}", createdAt = now), ChatItem(id = 2, kind = "agent", text = answer, meta = "{}", createdAt = now))
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(dark = true) { Surface(color = MaterialTheme.colorScheme.surface) {
            CompositionLocalProvider(LocalOpenUiImages provides { url -> photos.entries.firstOrNull { url.contains(it.key) }?.value }) {
                ChatScreen(items, AgentStatus(), "Nova", "Sam", false, "", {}, ChatActions())
            } } } }
        rule.mainClock.advanceTimeBy(900)
        rule.onRoot().captureRoboImage(File("build/screens-openui/openui-$name.png").path)
    }

    @Test fun flats() = case("flats", 1250)
    @Test fun flight() = case("flight", 800)
    @Test fun approval() = case("approval", 1000)
    @Test fun helpers() = case("helpers", 1250)
    @Test fun money() = case("money", 1150)
    @Test fun products() = case("products", 900)
}
