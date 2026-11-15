package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.*
import com.past9.phoneaos.data.AgentSettings
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.ModelRoute
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.RouteChoice
import com.past9.phoneaos.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Records the model of every call it gets, and answers (or refuses) as told. */
class RecordingProvider(override val name: String, private val reply: String = "done", private val refuse: ProviderException? = null) : LlmProvider {
    val models = CopyOnWriteArrayList<String>()
    override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
        models += model
        refuse?.let { throw it }
        return Completion(say(reply), "end_turn")
    }
}

/**
 * Per-task model routing (GitHub issue #64): the resolver, what is saved, and that chat, routine and watcher turns really
 * run on their route's account and model, while an empty route changes nothing and a broken one fails that turn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelRoutingTest {
    private lateinit var db: AppDb
    private lateinit var settings: SettingsStore
    private val phone = FakePhone()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDb.inMemory(ctx); settings = SettingsStore(ctx)
        settings.resetAll()
        settings.setMode(PowerMode.API_KEY); settings.setProvider(Provider.ANTHROPIC); settings.setApiKey(Provider.ANTHROPIC, "sk-main"); settings.setModel("main-model"); settings.setUserName("Sam")
        settings.setApiKey(Provider.OPENAI, "sk-openai"); settings.setApiKey(Provider.DEEPSEEK, "sk-deepseek")
    }

    // ---- the resolver -----------------------------------------------------------------------------------------------------

    private fun pick(route: ModelRoute, st: AgentSettings = settings.state.value) = ModelRouting.resolve(route, st) { settings.apiKey(it) }

    @Test fun noRouteMeansTheDefaultForEveryKindOfTurn() {
        ModelRoute.entries.forEach { assertEquals(ModelRouting.Pick.Default, pick(it)) }
    }

    @Test fun eachRouteResolvesToItsOwnAccountAndModel() {
        settings.setRoute(ModelRoute.CHAT, Provider.OPENAI, "chat-model")
        settings.setRoute(ModelRoute.ROUTINE, Provider.DEEPSEEK, "routine-model")
        val chat = pick(ModelRoute.CHAT) as ModelRouting.Pick.Routed
        assertEquals(Provider.OPENAI, chat.provider); assertEquals("chat-model", chat.model); assertEquals("sk-openai", chat.key); assertEquals(Provider.OPENAI.baseUrl, chat.baseUrl)
        val routine = pick(ModelRoute.ROUTINE) as ModelRouting.Pick.Routed
        assertEquals(Provider.DEEPSEEK, routine.provider); assertEquals("routine-model", routine.model)
        // A kind with no route of its own stays on the default, whatever the others are set to.
        assertEquals(ModelRouting.Pick.Default, pick(ModelRoute.WATCHER))
    }

    @Test fun aRouteToTheMainProviderKeepsItsServerAddress() {
        settings.setProvider(Provider.CUSTOM); settings.setApiKey(Provider.CUSTOM, "sk-custom"); settings.setBaseUrl("https://my.server/v1")
        settings.setRoute(ModelRoute.CHAT, Provider.CUSTOM, "local-model")
        assertEquals("https://my.server/v1", (pick(ModelRoute.CHAT) as ModelRouting.Pick.Routed).baseUrl)
    }

    @Test fun aRouteWhoseAccountIsGoneIsBrokenAndNamesTheRoute() {
        settings.setRoute(ModelRoute.WATCHER, Provider.OPENAI, "x")
        settings.clearApiKey(Provider.OPENAI)
        val b = pick(ModelRoute.WATCHER) as ModelRouting.Pick.Broken
        assertTrue(b.message, b.message.contains("Watchers route") && b.message.contains("OpenAI") && b.message.contains("Model routing"))
    }

    @Test fun aBadServerAddressIsBrokenUnlessTheUserAllowedPlainHttp() {
        settings.setProvider(Provider.CUSTOM); settings.setApiKey(Provider.CUSTOM, "k"); settings.setBaseUrl("http://10.0.0.5/v1")
        settings.setRoute(ModelRoute.CHAT, Provider.CUSTOM, "m")
        assertTrue(pick(ModelRoute.CHAT) is ModelRouting.Pick.Broken)
        settings.setAllowHttp(true)
        assertTrue(pick(ModelRoute.CHAT) is ModelRouting.Pick.Routed)
    }

    @Test fun aCustomRouteWithNoSavedAddressIsBroken() {
        settings.setApiKey(Provider.CUSTOM, "k")      // main provider is still Anthropic: the custom address was never saved
        settings.setRoute(ModelRoute.ROUTINE, Provider.CUSTOM, "m")
        assertTrue(pick(ModelRoute.ROUTINE) is ModelRouting.Pick.Broken)
    }

    @Test fun routesAreIgnoredOnASubscription() {
        settings.setRoute(ModelRoute.CHAT, Provider.OPENAI, "m")
        settings.setMode(PowerMode.SUBSCRIPTION)
        assertEquals(ModelRouting.Pick.Default, pick(ModelRoute.CHAT))
    }

    @Test fun savedJunkIsNotARoute() {
        assertNull(RouteChoice.fromJson(null)); assertNull(RouteChoice.fromJson("nonsense"))
        assertNull(RouteChoice.fromJson("""{"provider":"NO_SUCH_PROVIDER","model":"m"}"""))
        assertNull(RouteChoice.fromJson("""{"provider":"OPENAI","model":"  "}"""))
        settings.setExtra("route_CHAT", null)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putString("x_route_CHAT", """{"provider":"GONE","model":"m"}""").commit()
        assertTrue(SettingsStore(ctx).state.value.routes.isEmpty())
    }

    // ---- what is saved ----------------------------------------------------------------------------------------------------

    @Test fun nothingIsPreFilledAndAccountsAreTheProvidersWithAKey() {
        assertTrue(settings.state.value.routes.isEmpty())
        assertEquals(setOf(Provider.ANTHROPIC, Provider.OPENAI, Provider.DEEPSEEK), settings.accounts().toSet())
    }

    @Test fun aRouteIsSavedReadBackAndResetToTheDefault() {
        settings.setRoute(ModelRoute.ROUTINE, Provider.OPENAI, " cheap-model ")
        assertEquals(RouteChoice(Provider.OPENAI, "cheap-model"), settings.state.value.routes[ModelRoute.ROUTINE])
        assertNull(settings.state.value.routes[ModelRoute.CHAT])
        settings.clearRoute(ModelRoute.ROUTINE)
        assertTrue(settings.state.value.routes.isEmpty())
        settings.setRoute(ModelRoute.CHAT, Provider.OPENAI, "m"); settings.setRoute(ModelRoute.CHAT, Provider.OPENAI, "")
        assertTrue("a blank model clears the route", settings.state.value.routes.isEmpty())
    }

    @Test fun routesSurviveANewStoreAndAPowerSourceChange() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        settings.setRoute(ModelRoute.CHAT, Provider.OPENAI, "chat-model"); settings.setRoute(ModelRoute.WATCHER, Provider.DEEPSEEK, "watch-model")
        settings.setProvider(Provider.GEMINI); settings.setMode(PowerMode.SUBSCRIPTION); settings.setMode(PowerMode.API_KEY); settings.setModel("another")
        val fresh = SettingsStore(ctx).state.value.routes
        assertEquals(RouteChoice(Provider.OPENAI, "chat-model"), fresh[ModelRoute.CHAT])
        assertEquals(RouteChoice(Provider.DEEPSEEK, "watch-model"), fresh[ModelRoute.WATCHER])
        assertEquals(2, fresh.size)
    }

    @Test fun resettingTheAppClearsTheRoutes() {
        settings.setRoute(ModelRoute.CHAT, Provider.OPENAI, "m"); settings.resetAll()
        assertTrue(settings.state.value.routes.isEmpty())
    }

    // ---- turns on the runtime ---------------------------------------------------------------------------------------------

    private class Rig(val main: RecordingProvider, val routed: RecordingProvider) {
        val built = CopyOnWriteArrayList<Pair<Provider, String>>()
    }

    private fun rig(main: RecordingProvider = RecordingProvider("main", "from main"), routed: RecordingProvider = RecordingProvider("routed", "from route")) = Rig(main, routed)

    private fun runtime(r: Rig) = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone,
        providerFactory = { r.main }, routeProviderFactory = { p, k, _, _, _ -> r.built += p to k; r.routed })

    private suspend fun notices() = db.chat().all().first().filter { it.kind == "notice" }.map { it.text }

    @Test fun aRoutineTurnRunsOnTheRoutinesRoute() = runBlocking {
        settings.setRoute(ModelRoute.ROUTINE, Provider.OPENAI, "routine-model")
        settings.setRoute(ModelRoute.WATCHER, Provider.DEEPSEEK, "watcher-model")
        val r = rig(); val out = runtime(r).runBackground("Morning", "check the weather")
        assertEquals("from route", out)
        assertEquals(listOf("routine-model"), r.routed.models.toList())
        assertEquals(listOf(Provider.OPENAI to "sk-openai"), r.built.toList())
        assertTrue("the default provider must not be called", r.main.models.isEmpty())
    }

    @Test fun aWatcherTurnRunsOnTheWatchersRoute() = runBlocking {
        settings.setRoute(ModelRoute.ROUTINE, Provider.OPENAI, "routine-model")
        settings.setRoute(ModelRoute.WATCHER, Provider.DEEPSEEK, "watcher-model")
        val r = rig(); runtime(r).runBackground("Price watch", "it fired", route = ModelRoute.WATCHER)
        assertEquals(listOf("watcher-model"), r.routed.models.toList())
        assertEquals(listOf(Provider.DEEPSEEK to "sk-deepseek"), r.built.toList())
        assertTrue(r.main.models.isEmpty())
    }

    @Test fun theWatcherCodePathsAskForTheWatchersRoute() {
        // Watchers.kt cannot run in a unit test without a browser; this pins that both of its runs ask for the watchers route.
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }.resolve("com/past9/phoneaos/triggers/Watchers.kt").readText()
        assertEquals("both the fire and the repair run use the watchers route", 2, Regex("""runBackground\([^\n]*route = com\.past9\.phoneaos\.data\.ModelRoute\.WATCHER\)""").findAll(src).count())
    }

    @Test fun aChatTurnRunsOnTheChatRoute() = runBlocking {
        settings.setRoute(ModelRoute.CHAT, Provider.OPENAI, "chat-model"); settings.setRoute(ModelRoute.ROUTINE, Provider.DEEPSEEK, "routine-model")
        val r = rig(); val rt = runtime(r)
        rt.send("hello there"); withTimeout(15_000) { rt.awaitIdle() }
        assertEquals(listOf("chat-model"), r.routed.models.toList())
        assertTrue(r.main.models.isEmpty())
        assertEquals("from route", db.chat().all().first().last { it.kind == "agent" }.text)
    }

    @Test fun anInstructionFromAnotherAppRunsAsChatAndUsesTheChatRoute() = runBlocking {
        settings.setRoute(ModelRoute.CHAT, Provider.OPENAI, "chat-model"); settings.setRoute(ModelRoute.ROUTINE, Provider.DEEPSEEK, "routine-model")
        val r = rig(); runtime(r).runBackground("Instruction", "do it", role = PromptRole.MAIN)
        assertEquals(listOf("chat-model"), r.routed.models.toList())
    }

    @Test fun aChatRouteLeavesRoutinesOnTheDefault() = runBlocking {
        settings.setRoute(ModelRoute.CHAT, Provider.OPENAI, "chat-model")
        val r = rig(); runtime(r).runBackground("Morning", "go")
        assertEquals(listOf("main-model"), r.main.models.toList())
        assertTrue(r.routed.models.isEmpty()); assertTrue(r.built.isEmpty())
    }

    @Test fun withNoRoutesEveryTurnRunsExactlyAsBefore() = runBlocking {
        val r = rig(); val rt = runtime(r)
        rt.send("hello"); withTimeout(15_000) { rt.awaitIdle() }
        rt.runBackground("Morning", "go"); rt.runBackground("Watch", "fired", route = ModelRoute.WATCHER)
        // The default provider took all three, on the default model; the route provider was never even built.
        assertEquals(listOf("main-model", "main-model", "main-model"), r.main.models.toList())
        assertTrue(r.routed.models.isEmpty()); assertTrue(r.built.isEmpty())
        assertEquals("from main", db.chat().all().first().last { it.kind == "agent" && it.meta.contains("routine").not() }.text)
    }

    @Test fun aChatRouteWhoseAccountIsGoneFailsThatTurnAndNeverFallsBack() = runBlocking {
        settings.setRoute(ModelRoute.CHAT, Provider.OPENAI, "chat-model"); settings.clearApiKey(Provider.OPENAI)
        val r = rig(); val rt = runtime(r)
        rt.send("hello"); withTimeout(15_000) { rt.awaitIdle() }
        val n = notices().joinToString("\n")
        assertTrue(n, n.contains("The Chat route can't run") && n.contains("OpenAI"))
        assertTrue("no model was called at all", r.main.models.isEmpty() && r.routed.models.isEmpty())
        assertTrue(db.chat().all().first().none { it.kind == "agent" })
    }

    @Test fun aRoutineRouteWithABadAddressFailsThatRunAndNamesTheRoute() = runBlocking {
        settings.setProvider(Provider.CUSTOM); settings.setApiKey(Provider.CUSTOM, "k"); settings.setBaseUrl("http://10.0.0.5/v1"); settings.setModel("m")
        settings.setRoute(ModelRoute.ROUTINE, Provider.CUSTOM, "local")
        val r = rig(); val out = runtime(r).runBackground("Morning", "go")
        assertTrue(out, out.contains("The Routines route can't run") && out.contains("server address"))
        assertTrue(r.main.models.isEmpty() && r.routed.models.isEmpty())
        assertTrue(notices().any { it.contains("Routines route") })
    }

    @Test fun whenTheProviderRefusesARoutedTurnTheErrorNamesTheRouteAndTheDefaultIsNotTried() = runBlocking {
        settings.setRoute(ModelRoute.ROUTINE, Provider.OPENAI, "routine-model")
        val r = rig(routed = RecordingProvider("routed", refuse = ProviderException("HTTP 401: bad key", 401)))
        val out = runtime(r).runBackground("Morning", "go")
        assertTrue(out, out.contains("Routines route (OpenAI · routine-model) failed") && out.contains("API key was rejected"))
        assertEquals(listOf("routine-model"), r.routed.models.toList())
        assertTrue("no silent fallback", r.main.models.isEmpty())
    }

    @Test fun aRefusedChatTurnOnTheDefaultStillReadsAsItAlwaysDid() = runBlocking {
        val r = rig(main = RecordingProvider("main", refuse = ProviderException("HTTP 401: bad key", 401)))
        val rt = runtime(r); rt.send("hi"); withTimeout(15_000) { rt.awaitIdle() }
        assertTrue(notices().any { it == "Your API key was rejected. Check it in Settings." })
    }
}
