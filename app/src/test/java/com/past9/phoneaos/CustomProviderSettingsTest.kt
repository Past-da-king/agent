package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.HttpPolicy
import com.past9.phoneaos.agent.ModelCatalog
import com.past9.phoneaos.agent.ProviderException
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Other (OpenAI-compatible)" set up from Settings > Change key: the key check passes, then Continue
 * used to call setProvider again, which wiped the server address and model. Settings > Model then said
 * "Couldn't load models" and chat said "I could not reach the AI provider".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomProviderSettingsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** What the Settings > Change key screen does, in order: Check key, then Continue. */
    private fun changeKeyToOther(s: SettingsStore, url: String, model: String) {
        // Check key: saveCustom
        s.setProvider(Provider.CUSTOM); s.setBaseUrl(url); s.setModel(model); s.setAllowHttp(false)
        // Check key passed: saveKey (same provider, so no reset)
        s.setApiKey(Provider.CUSTOM, "lm-studio-key")
        // Continue: the "key" route's next()
        s.setProvider(Provider.CUSTOM); s.setMode(PowerMode.API_KEY)
    }

    @Test fun continuingKeepsTheServerAddressAndModel() {
        val s = SettingsStore(ctx)
        s.setProvider(Provider.ANTHROPIC)
        changeKeyToOther(s, "https://box.tailnet.ts.net/v1/", "nvidia/nemotron-3-nano-4b")
        assertEquals("https://box.tailnet.ts.net/v1", s.state.value.baseUrl)
        assertEquals("nvidia/nemotron-3-nano-4b", s.state.value.model)
        assertTrue(s.state.value.hasKey)
    }

    @Test fun switchingToAnotherProviderStillStartsClean() {
        val s = SettingsStore(ctx)
        changeKeyToOther(s, "https://box.tailnet.ts.net/v1", "nvidia/nemotron-3-nano-4b")
        s.setProvider(Provider.OPENAI)
        assertEquals(Provider.OPENAI.baseUrl, s.state.value.baseUrl)
        assertEquals(Provider.OPENAI.defaultModel, s.state.value.model)
    }

    /** LM Studio's exact /v1/models reply parses: the JSON was never the problem. */
    @Test fun lmStudioModelListLoads() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":[{"id":"nvidia/nemotron-3-nano-4b","object":"model","owned_by":"organization_owner"}],"object":"list"}"""))
        server.start()
        val s = SettingsStore(ctx)
        changeKeyToOther(s, server.url("/v1/").toString(), "nvidia/nemotron-3-nano-4b")
        val st = s.state.value
        val list = ModelCatalog.forKey(ctx, st.provider, s.apiKey(st.provider).orEmpty(), st.baseUrl, st.allowHttp)
        assertEquals(listOf("nvidia/nemotron-3-nano-4b"), list.map { it.id })
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer lm-studio-key", req.getHeader("Authorization"))
        server.shutdown()
    }

    /** A blank address says so, instead of reading as a network failure. */
    @Test fun blankAddressSaysSo() {
        val reason = HttpPolicy.blockReason("", false)
        assertNotNull(reason); assertTrue(reason!!.contains("address", true))
        val e = runCatching { HttpPolicy.check("", false) }.exceptionOrNull() as ProviderException
        assertNotEquals("I could not reach the AI provider. Check your connection.", com.past9.phoneaos.agent.AgentRuntime.friendlyError(e))
    }
}
