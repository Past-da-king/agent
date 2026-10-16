package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.tools.AppCatalog
import com.past9.phoneaos.tools.ComposioConnect
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.data.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Apps a Composio Connect account has beyond the popular list (its own custom toolkits too) are learned from replies and kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposioOwnAppsTest {
    // Shapes of real Composio Connect replies (ids shortened).
    private val search = """{"successful":true,"data":{"results":[{"index":1,"primary_tool_slugs":["CUSTOM_RECIPES_LIST_RECIPES","CUSTOM_RECIPES_GET_RECIPE"],"related_tool_slugs":["DISCORD_LIST_MY_CONNECTIONS"],"toolkits":["custom_recipes"]}],
        "toolkit_connection_statuses":[{"toolkit":"custom_recipes","description":"custom_recipes toolkit","has_active_connection":true,"accounts":[]},
        {"toolkit":"discord","description":"Chat for communities","has_active_connection":false}]},"error":null}
No exact fit? Any HTTP or SSE endpoint can be connected as a custom MCP."""
    private val list = """{"successful":true,"data":{"message":"All connections are active","results":{"linear":{"toolkit":"linear","status":"active",
        "accounts":[{"id":"ca_1","status":"active","is_default":true}]},"trello":{"toolkit":"trello","status":"not_connected","accounts":[]}}},"error":null}"""

    @Test fun activeAppsComeFromSearchesAndLists() {
        assertEquals(mapOf("custom_recipes" to "custom_recipes toolkit"), ComposioConnect.activeApps(search))
        assertEquals(mapOf("linear" to ""), ComposioConnect.activeApps(list))
        assertTrue(ComposioConnect.activeApps("Error: timeout").isEmpty())
        // Tools go under the app they belong to; one whose app isn't in that result is left out.
        assertEquals(mapOf("custom_recipes" to listOf("CUSTOM_RECIPES_LIST_RECIPES", "CUSTOM_RECIPES_GET_RECIPE")), ComposioConnect.appTools(search))
    }

    @Test fun learnedAppsAreKeptAndForgottenWithTheAccount() {
        val s = SettingsStore(ApplicationProvider.getApplicationContext())
        s.setComposioKey("ck_one")
        s.rememberComposioApps(ComposioConnect.activeApps(search))
        s.rememberComposioApps(mapOf("custom_recipes" to ""))   // a later list without a description keeps the one we had
        s.rememberComposioApps(ComposioConnect.activeApps(list))
        assertEquals(mapOf("custom_recipes" to "custom_recipes toolkit", "linear" to ""), s.composioApps())

        val shown = AppCatalog.known(s.composioApps()).associateBy { it.slug }
        assertEquals("Recipes", shown.getValue("custom_recipes").name)
        assertEquals("", shown.getValue("custom_recipes").description)
        assertFalse("gmail" in AppCatalog.known(mapOf("gmail" to "")).map { it.slug })

        s.setComposioKey("ck_one")
        assertEquals(2, s.composioApps().size)
        s.setComposioKey("ck_two")
        assertTrue(s.composioApps().isEmpty())
    }

    @Test fun theAgentIsToldWhichAppsTheUserHas() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = SettingsStore(app); s.setComposioKey("ck_one")
        s.rememberComposioApps(mapOf("custom_recipes" to "custom_recipes toolkit", "gmail" to "", "custom_fleet_tracker" to ""))
        s.rememberComposioAppTools(ComposioConnect.appTools(search))
        val rt = AgentRuntime(app, AppDb.inMemory(app), s, CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone(), { null })
        val sys = rt.systemPrompt("what can I cook tonight")
        assertTrue(sys.contains("connected apps (name and Composio toolkit slug): Fleet Tracker (custom_fleet_tracker), Recipes (custom_recipes), Gmail (gmail)."))
        assertTrue(sys.contains("  - Recipes (custom_recipes): CUSTOM_RECIPES_LIST_RECIPES, CUSTOM_RECIPES_GET_RECIPE\n"))
        assertTrue(sys.contains("  - Fleet Tracker (custom_fleet_tracker): find its tools with COMPOSIO_SEARCH_TOOLS\n"))
    }
}
