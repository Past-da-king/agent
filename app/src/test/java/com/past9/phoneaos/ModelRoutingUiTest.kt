package com.past9.phoneaos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.agent.ModelInfo
import com.past9.phoneaos.data.AgentSettings
import com.past9.phoneaos.data.ModelRoute
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.RouteChoice
import com.past9.phoneaos.ui.LocalSheetPreview
import com.past9.phoneaos.ui.screens.ModelRoutingSheet
import com.past9.phoneaos.ui.screens.RoutingActions
import com.past9.phoneaos.ui.screens.RuntimeInfo
import com.past9.phoneaos.ui.screens.SettingsActions
import com.past9.phoneaos.ui.screens.SettingsScreen
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Settings > Model routing (issue #64): a row each for Chat, Routines and Watchers, a slide-up sheet (never a pop-up) with
 * the provider and model pickers built from the user's own accounts, and a way back to the default. Pictures at 390 wide
 * here and at 360 in [ModelRoutingUi360Test], light and dark, land in app/build/design/.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
open class ModelRoutingUiTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()

    protected open val width = "390"

    private val catalogue = mapOf(
        Provider.OPENAI to listOf(ModelInfo("gpt-big", "GPT Big", true, "2026-09-01"), ModelInfo("gpt-small", "GPT Small", true, "2026-08-01"), ModelInfo("gpt-mini", "GPT Mini", false, "2026-06-01")),
        Provider.DEEPSEEK to listOf(ModelInfo("deepseek-flash", "DeepSeek Flash", true, "2026-10-01"), ModelInfo("deepseek-pro", "DeepSeek Pro", false, "2026-10-01")),
        Provider.ANTHROPIC to listOf(ModelInfo("claude-big", "Claude Big", true, "2026-10-01"), ModelInfo("claude-fast", "Claude Fast", true, "2026-10-01")),
    )

    private class Calls { val set = mutableListOf<Triple<ModelRoute, Provider, String>>(); val cleared = mutableListOf<ModelRoute>() }

    private fun actions(c: Calls, accounts: List<Provider> = listOf(Provider.ANTHROPIC, Provider.OPENAI, Provider.DEEPSEEK)) = RoutingActions(
        accounts = { accounts }, models = { p -> catalogue[p].orEmpty() }, set = { r, p, m -> c.set += Triple(r, p, m) }, clear = { c.cleared += it })

    private fun settings(routes: Map<ModelRoute, RouteChoice> = emptyMap(), mode: PowerMode = PowerMode.API_KEY) = AgentSettings(mode = mode, provider = Provider.ANTHROPIC, model = "claude-big",
        hasKey = true, userName = "Sam", agentName = "Nova", onboarded = true, routes = routes)

    private fun showSettings(s: AgentSettings, a: RoutingActions) {
        rule.setContent {
            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalSheetPreview provides true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        SettingsScreen(s, RuntimeInfo(false, 0, false, "Runtime pack not in this build"), "0.1.0", SettingsActions(routing = a))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
    }

    private fun tap(tag: String, scroll: Boolean = false) { rule.onNodeWithTag(tag).apply { if (scroll) performScrollTo() }.performClick(); rule.waitForIdle() }

    private fun shot(name: String) = rule.onRoot().captureRoboImage(File("build/design/model-routing-$name-$width-${if (dark) "dark" else "light"}.png").path)

    @Test fun nothingIsChosenUntilTheUserChoosesSoEveryRowSaysSameAsDefault() {
        showSettings(settings(), actions(Calls()))
        rule.onNodeWithTag("routing-row-CHAT").performScrollTo().assertIsDisplayed()
        assertEquals(3, rule.onAllNodesWithText("Same as default").fetchSemanticsNodes().size)
        rule.onNodeWithText("Model routing", ignoreCase = true).assertExists()
        rule.onNodeWithText("Watchers").assertExists()
    }

    @Test fun aRowShowsTheChosenAccountAndModel() {
        showSettings(settings(mapOf(ModelRoute.CHAT to RouteChoice(Provider.OPENAI, "gpt-small"), ModelRoute.WATCHER to RouteChoice(Provider.OPENROUTER, "vendor/some-model"))), actions(Calls()))
        rule.onNodeWithTag("routing-row-CHAT").performScrollTo()
        rule.onNodeWithText("OpenAI · gpt-small").assertExists()
        rule.onNodeWithText("OpenRouter · some-model").assertExists()
        assertEquals(1, rule.onAllNodesWithText("Same as default").fetchSemanticsNodes().size)
        rule.onNodeWithTag("routing-row-WATCHER").performScrollTo()
        shot("section")
    }

    @Test fun theSectionIsNotShownOnASubscription() {
        showSettings(settings(mode = PowerMode.SUBSCRIPTION), actions(Calls()))
        rule.onAllNodesWithText("Model routing", ignoreCase = true).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test fun tappingARowOpensASheetAndPickingAProviderAndModelSavesItAndCloses() {
        val c = Calls(); showSettings(settings(), actions(c))
        tap("routing-row-ROUTINE", scroll = true)
        rule.onNodeWithTag("routing-sheet").assertExists()
        shot("sheet-empty")
        tap("routing-provider-DEEPSEEK")
        rule.onNodeWithTag("routing-model-deepseek-flash").assertExists()
        tap("routing-model-deepseek-flash")
        assertEquals(listOf(Triple(ModelRoute.ROUTINE, Provider.DEEPSEEK, "deepseek-flash")), c.set)
        rule.onNodeWithTag("routing-sheet").assertDoesNotExist()
    }

    @Test fun theSheetForASetRouteOffersAWayBackToTheDefault() {
        val c = Calls(); showSettings(settings(mapOf(ModelRoute.CHAT to RouteChoice(Provider.OPENAI, "gpt-small"))), actions(c))
        tap("routing-row-CHAT", scroll = true)
        rule.onNodeWithTag("routing-model-gpt-small").assertExists()
        shot("sheet-set")
        tap("routing-reset")
        assertEquals(listOf(ModelRoute.CHAT), c.cleared); assertTrue(c.set.isEmpty())
        rule.onNodeWithTag("routing-sheet").assertDoesNotExist()
    }

    @Test fun anUnsetRouteHasNoResetButton() {
        showSettings(settings(), actions(Calls()))
        tap("routing-row-WATCHER", scroll = true)
        rule.onNodeWithTag("routing-reset").assertDoesNotExist()
    }

    @Test fun withNoSavedAccountsTheSheetSaysSoInsteadOfOfferingNothing() {
        showSettings(settings(), actions(Calls(), accounts = emptyList()))
        tap("routing-row-CHAT", scroll = true)
        rule.onNodeWithText("Save an API key for a provider first, then it shows up here.").assertExists()
    }

    @Test fun theSheetOnItsOwnAtThisWidth() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                CompositionLocalProvider(LocalSheetPreview provides true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        ModelRoutingSheet(ModelRoute.CHAT, RouteChoice(Provider.OPENAI, "gpt-small"), actions(Calls())) {}
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        shot("sheet")
    }
}

/** The same screens on a narrow phone. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class ModelRoutingUi360Test(dark: Boolean) : ModelRoutingUiTest(dark) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    override val width = "360"
}
