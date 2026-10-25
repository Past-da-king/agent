package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.tools.ComposioConnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** With no Composio tools loaded, the agent is told why instead of concluding the user's connections are broken. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposioReloadTest {
    @Test fun theAgentIsToldTheAppToolsCouldNotLoad() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = SettingsStore(app); s.setComposioKey("ck_test")
        assertTrue(ComposioConnect.tools.isEmpty())
        val rt = AgentRuntime(app, AppDb.inMemory(app), s, CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone(), { null })
        val sys = rt.systemPrompt("what's in my inbox")
        assertTrue(sys.contains("couldn't load the Composio tools"))
        assertTrue(sys.contains("don't ask them to reconnect or disconnect apps"))

        s.setComposioKey(null)
        assertFalse(AgentRuntime(app, AppDb.inMemory(app), SettingsStore(app), CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone(), { null })
            .systemPrompt("hi").contains("couldn't load the Composio tools"))
    }
}
