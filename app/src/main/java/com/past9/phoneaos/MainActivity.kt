package com.past9.phoneaos

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.past9.phoneaos.agent.AnthropicProvider
import com.past9.phoneaos.agent.Msg
import com.past9.phoneaos.agent.OpenAiCompatProvider
import com.past9.phoneaos.browser.BrowserService
import com.past9.phoneaos.data.MemoryRow
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.system.Speaker
import com.past9.phoneaos.triggers.Routines
import com.past9.phoneaos.ui.AppDrawer
import com.past9.phoneaos.ui.NavCounts
import com.past9.phoneaos.ui.screens.*
import com.past9.phoneaos.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Text shared in from another app, or a route asked for by a notification. */
    private val incoming = MutableStateFlow<Pair<String?, String?>>(null to null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        val g = App.graph(this)
        setContent {
            val st by g.settings.state.collectAsStateWithLifecycle()
            AppTheme(accent = st.accent, mascot = st.mascot) { Root(g) }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        if (i == null) return
        val shared = if (i.action == Intent.ACTION_SEND) listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n").ifBlank { null } else null
        incoming.value = shared to i.getStringExtra("open")
    }

    @Composable
    private fun Root(g: Graph) {
        val settings by g.settings.state.collectAsStateWithLifecycle()
        if (!settings.onboarded) { Onboarding(g); return }

        val items by g.db.chat().all().collectAsStateWithLifecycle(emptyList())
        val status by g.runtime.status.collectAsStateWithLifecycle()
        val goals by g.db.tasks().goals().collectAsStateWithLifecycle(emptyList())
        val tasks by g.db.tasks().tasks().collectAsStateWithLifecycle(emptyList())
        val memories by g.db.memory().all().collectAsStateWithLifecycle(emptyList())
        val routines by g.db.triggers().all().collectAsStateWithLifecycle(emptyList())
        val browserLive by BrowserService.running.collectAsStateWithLifecycle()
        val nav = rememberNavController()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        var draft by rememberSaveable { mutableStateOf("") }
        var conn by remember { mutableStateOf(ConnectionsState(hasKey = settings.composioEnabled)) }
        val sub = remember(settings.subKind) { g.subRuntime() }
        val speaker = remember { Speaker(this) }

        val incomingNow by incoming.collectAsStateWithLifecycle()
        LaunchedEffect(incomingNow) {
            val (text, open) = incomingNow
            if (text != null) { draft = text; nav.navigate("chat") { popUpTo("chat") { inclusive = false } } }
            if (open != null) nav.navigate(open)
            if (text != null || open != null) incoming.value = null to null
        }
        // Read new replies aloud when the user asked for that.
        var lastSpoken by remember { mutableLongStateOf(items.lastOrNull()?.id ?: 0L) }
        LaunchedEffect(items.lastOrNull()?.id) {
            val last = items.lastOrNull() ?: return@LaunchedEffect
            if (last.id > lastSpoken && last.kind == "agent" && settings.speakReplies) speaker.say(last.text)
            lastSpoken = maxOf(lastSpoken, last.id)
        }

        val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { conn = conn.copy(notificationsAllowed = it) }
        val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { draft = if (draft.isBlank()) it else "$draft $it" }
        }
        fun refreshConnections() = scope.launch {
            val has = g.settings.composioKey() != null
            conn = conn.copy(hasKey = has, loading = has, error = null, notificationsAllowed = notificationsAllowed())
            if (!has) return@launch
            try {
                val c = g.runtime.composio.connections(); conn = conn.copy(connected = c)
                val t = g.runtime.composio.toolkits(); conn = conn.copy(toolkits = t, loading = false)
            } catch (e: Exception) { conn = conn.copy(loading = false, error = "Couldn't reach Composio: ${e.message}") }
        }
        fun go(route: String) { scope.launch { drawer.close() }; if (route != "chat") nav.navigate(route) { launchSingleTop = true } else nav.popBackStack("chat", false) }

        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            AppDrawer(settings.agentName, NavCounts(tasks.count { it.status != "done" }, memories.size, routines.count { it.enabled }, conn.connected.count { it.status == "ACTIVE" }), ::go)
        }) {
            NavHost(nav, startDestination = "chat") {
                composable("chat") {
                    ChatScreen(items, status, settings.agentName, settings.userName, browserLive, draft, { draft = it },
                        ChatActions(
                            onSend = { g.runtime.send(it) }, onStop = { g.runtime.stop() },
                            onAnswer = { id, o -> g.runtime.answer(id, o) },
                            onMenu = { scope.launch { drawer.open() } }, onBrowser = { nav.navigate("browser") },
                            onMic = { runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) } },
                        ), openCount = tasks.count { it.status != "done" })
                }
                composable("tasks") {
                    TasksScreen(goals, tasks, TaskActions(
                        onBack = { nav.popBackStack() },
                        onToggle = { t -> scope.launch { g.db.tasks().updateTask(t.copy(status = if (t.status == "done") "todo" else "done", updatedAt = System.currentTimeMillis())) } },
                        onAdd = { text -> scope.launch { g.db.tasks().insertTask(TaskRow(title = text.trim())) } },
                        onDelete = { t -> scope.launch { g.db.tasks().deleteTask(t.id) } },
                        onAsk = { prompt -> g.runtime.send(prompt); nav.popBackStack("chat", false) },
                    ))
                }
                composable("memory") {
                    MemoryScreen(memories, MemoryActions(
                        onBack = { nav.popBackStack() },
                        onSave = { m -> scope.launch { if (m.id == 0L) g.db.memory().insert(m) else g.db.memory().update(m) } },
                        onDelete = { m -> scope.launch { g.db.memory().delete(m.id) } },
                    ))
                }
                composable("routines") {
                    RoutinesScreen(routines, RoutineActions(
                        onBack = { nav.popBackStack() },
                        onToggle = { r -> scope.launch { val n = r.copy(enabled = !r.enabled); g.db.triggers().upsert(n); Routines.schedule(this@MainActivity, n) } },
                        onRunNow = { r -> Routines.runNow(this@MainActivity, r.id) },
                        onDelete = { r -> scope.launch { g.db.triggers().delete(r.id); Routines.cancel(this@MainActivity, r.id) } },
                        onSave = { r -> scope.launch { val id = g.db.triggers().upsert(r); Routines.schedule(this@MainActivity, r.copy(id = id)) } },
                    ))
                }
                composable("connections") {
                    LaunchedEffect(Unit) { refreshConnections() }
                    ConnectionsScreen(conn, ConnectionsActions(
                        onBack = { nav.popBackStack() },
                        onSaveKey = { k -> saveComposio(g, k).also { if (it == null) refreshConnections() } },
                        onRemoveKey = { g.settings.setComposioKey(null); conn = ConnectionsState(hasKey = false) },
                        onConnect = { slug -> scope.launch { runCatching { g.runtime.composio.connect(slug) }.onSuccess { open(it) }.onFailure { conn = conn.copy(error = it.message) } } },
                        onDisconnect = { c -> scope.launch { runCatching { g.runtime.composio.disconnect(c.id) }; refreshConnections() } },
                        onRefresh = { refreshConnections() }, onOpenUrl = ::open,
                        onAllowNotifications = { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    ))
                }
                composable("settings") {
                    var info by remember { mutableStateOf(sub.info()) }
                    SettingsScreen(settings, info, BuildConfig.VERSION_NAME, SettingsActions(
                        onBack = { nav.popBackStack() },
                        setUserName = g.settings::setUserName, setAgentName = g.settings::setAgentName,
                        setMode = g.settings::setMode, setProvider = g.settings::setProvider, setModel = g.settings::setModel, setHelperModel = g.settings::setHelperModel,
                        onChangeKey = { nav.navigate("key") }, setSpeak = g.settings::setSpeakReplies,
                        onClaudeSetup = { nav.navigate("sub") }, onDeleteRuntime = { sub.delete(); info = sub.info() }, onStyle = { nav.navigate("style") },
                        onClearChat = { g.runtime.clearConversation() },
                        onResetAll = { scope.launch { g.runtime.stop(); g.db.clearAllTables(); g.settings.resetAll() } },
                        onBattery = { runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } },
                    ))
                }
                composable("key") {
                    var p by remember { mutableStateOf(settings.provider) }
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.systemBarsPadding()) {
                            KeyStep(p, { p = it }, onboardingActions(g)) { g.settings.setProvider(p); g.settings.setMode(PowerMode.API_KEY); nav.popBackStack() }
                        }
                    }
                }
                composable("sub") {
                    var info by remember { mutableStateOf(sub.info()) }
                    val log = remember { mutableStateListOf<String>() }
                    var installing by remember { mutableStateOf(false) }
                    SubSetupScreen(sub.kind, sub.spec.signInHelp, info, sub.installed, log, installing, onBack = { nav.popBackStack() },
                        onInstall = { scope.launch { installing = true; runCatching { sub.install { l -> scope.launch { log += l } } }.onFailure { log += "Failed: ${it.message}" }; installing = false; info = sub.info() } },
                        onSaveToken = { t -> sub.setToken(t); g.settings.setMode(PowerMode.SUBSCRIPTION); info = sub.info() },
                        onUseKey = { g.settings.setMode(PowerMode.API_KEY); nav.navigate("key") })
                }
                composable("style") {
                    com.past9.phoneaos.ui.SubScreen("Make it yours", "Colour and sidekick", { nav.popBackStack() }) { pad ->
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 48.dp)) {
                            StylePicker(settings.userName, settings.agentName, settings.accent, settings.mascot, g.settings::setAccent, g.settings::setMascot)
                        }
                    }
                }
                composable("browser") {
                    val engine = BrowserService.engine
                    val page by (engine?.page ?: MutableStateFlow(com.past9.phoneaos.browser.PageState())).collectAsStateWithLifecycle()
                    BrowserScreen(engine, page, onClose = { nav.popBackStack() }, onHandBack = {
                        items.lastOrNull { it.kind == "question" && !org.json.JSONObject(it.meta).has("answer") }?.let { g.runtime.answer(it.id, "Done") }
                        nav.popBackStack()
                    })
                }
            }
        }
    }

    @Composable
    private fun Onboarding(g: Graph) {
        val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val st = g.settings.state.value
        OnboardingScreen(onboardingActions(g).copy(
            requestNotifications = { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) },
            finish = { g.settings.setOnboarded(true) },
        ), initialAccent = st.accent, initialMascot = st.mascot, initialUser = st.userName, initialAgent = if (st.agentName == "Your agent") "" else st.agentName)
    }

    private fun onboardingActions(g: Graph) = OnboardingActions(
        saveNames = { u, a -> g.settings.setUserName(u); if (a.isNotBlank()) g.settings.setAgentName(a) },
        choosePower = g.settings::setMode,
        chooseStyle = { a, m -> g.settings.setAccent(a); g.settings.setMascot(m) },
        chooseSub = g.settings::setSubKind,
        subAvailable = { k -> com.past9.phoneaos.runtime.SubscriptionRuntime(this, k).available },
        saveCustom = { url, model -> g.settings.setProvider(Provider.CUSTOM); g.settings.setBaseUrl(url); g.settings.setModel(model) },
        saveKey = { p, k -> if (g.settings.state.value.provider != p) g.settings.setProvider(p); g.settings.setApiKey(p, k) },
        testKey = { p, k -> testKey(p, k) },
        saveComposio = { k -> saveComposio(g, k) },
        openUrl = ::open,
    )

    private suspend fun testKey(p: Provider, key: String): String? = try {
        val st = App.graph(this).settings.state.value
        val model = if (st.provider == p && st.model.isNotBlank()) st.model else p.defaultModel
        com.past9.phoneaos.agent.AgentRuntime.providerFor(p, key, if (p == Provider.CUSTOM) st.baseUrl else p.baseUrl).complete("Reply with OK.", listOf(Msg.user("ping")), emptyList(), model, 16); null
    } catch (e: com.past9.phoneaos.agent.ProviderException) {
        when (e.status) { 401, 403 -> "That key was rejected."; 404 -> "Key works but model ${p.defaultModel} isn't available. You can change the model in Settings."; 429 -> "Key works but has no credit or is rate-limited."; else -> e.message }
    } catch (e: Exception) { "Couldn't check: ${e.message}" }

    private suspend fun saveComposio(g: Graph, key: String): String? {
        g.settings.setComposioKey(key)
        return if (g.runtime.composio.verify()) null else { g.settings.setComposioKey(null); "Composio didn't accept that key." }
    }

    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun open(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
}

