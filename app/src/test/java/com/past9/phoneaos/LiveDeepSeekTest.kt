package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The real API-key path against a real OpenAI-compatible provider (DeepSeek): the production
 * runtime, tools and Room, with nothing faked except the phone's notifications.
 * Skipped unless DEEPSEEK_KEY is set. Writes the transcript to build/live/deepseek.txt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveDeepSeekTest {
    @Test fun openCodeGoWithTools() = live(Provider.OPENCODE_GO, System.getenv("OPENCODE_KEY").orEmpty(), "opencode")

    @Test fun realAgentUsesToolsAndRemembers() = live(Provider.DEEPSEEK, System.getenv("DEEPSEEK_KEY").orEmpty(), "deepseek")

    private fun live(provider: Provider, key: String, tag: String) = runBlocking {
        assumeTrue("no key for $tag", key.isNotBlank())
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = AppDb.inMemory(app); val s = SettingsStore(app)
        s.setMode(PowerMode.API_KEY); s.setProvider(provider); s.setApiKey(provider, key); s.setUserName("Sam")
        val rt = AgentRuntime(app, db, s, CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone())

        rt.send("Remember that my favourite colour is teal. Also add a task: renew my driver's licence by Friday.")
        withTimeout(120_000) { rt.awaitIdle() }
        rt.send("What's my favourite colour, and what's on my task list?")
        withTimeout(120_000) { rt.awaitIdle() }

        val chat = db.chat().all().first()
        File("build/live").mkdirs()
        File("build/live/$tag.txt").writeText(chat.joinToString("\n") { "[${it.kind}] ${it.text}" })
        assertTrue("memory saved", db.memory().list().any { it.body.contains("teal", true) })
        assertTrue("task added", db.tasks().openTasks().any { it.title.contains("licence", true) || it.title.contains("license", true) })
        val lastAgent = chat.last { it.kind == "agent" }.text
        assertTrue("recalls teal: $lastAgent", lastAgent.contains("teal", true))
        assertFalse("no errors", chat.any { it.kind == "notice" })
    }
}
