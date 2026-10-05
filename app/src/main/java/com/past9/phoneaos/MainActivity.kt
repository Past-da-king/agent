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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
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
import com.past9.phoneaos.ui.screens.*
import com.past9.phoneaos.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

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

    override fun onResume() {
        super.onResume()
        com.past9.phoneaos.triggers.Overnight.onAppOpen(this)
    }

    override fun onStop() {
        super.onStop()
        // Swap the home-screen icon to the user's character only once they've left the app.
        val st = App.graph(this).settings.state.value
        if (st.onboarded) runCatching { com.past9.phoneaos.system.Identity.applyLauncherIcon(this, st.mascot, st.accent) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        if (i == null) return
        // adb hook for checking how notifications look: am start -n <pkg>/.MainActivity --es open testnotify
        // adb hook for checking sites in the agent's browser: am start -n <pkg>/.MainActivity --es open browser --es url https://...
        i.getStringExtra("url")?.takeIf { i.getStringExtra("open") == "browser" }?.let { u ->
            App.graph(this).scope.launch { runCatching { BrowserService.await(this@MainActivity, "main", App.graph(this@MainActivity).settings.state.value.browserProfiles.first()).goto(u) } }
        }
        if (i.getStringExtra("open") == "testnotify") {
            App.graph(this).phone.notify("Checking in", "This is how my messages look on your phone.")
            moveTaskToBack(true); return
        }
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
        val notifRows by g.db.notifications().recent(200).collectAsStateWithLifecycle(emptyList())
        val backEntry by nav.currentBackStackEntryAsState()
        val route = backEntry?.destination?.route ?: "home"
        val scope = rememberCoroutineScope()
        var draft by rememberSaveable { mutableStateOf("") }
        val attachments = remember { mutableStateListOf<String>() }
        // Documents attached in chat: name -> (extracted text, page images)
        val docs = remember { mutableStateMapOf<String, Pair<String, List<String>>>() }
        val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val reader = com.past9.phoneaos.tools.FilesPickTool(this@MainActivity)
            scope.launch { uris.forEach { u -> val name = reader.displayName(u); docs[name] = reader.readDocument(u) } }
        }
        var modelSheet by remember { mutableStateOf(false) }
        var modelList by remember { mutableStateOf<List<com.past9.phoneaos.agent.ModelInfo>?>(null) }
        var modelErr by remember { mutableStateOf<String?>(null) }
        val onSub = settings.mode == PowerMode.SUBSCRIPTION
        fun openModels() {
            modelSheet = true; modelList = null; modelErr = null
            scope.launch {
                runCatching {
                    modelList = if (onSub) com.past9.phoneaos.agent.ModelCatalog.forSubscription(this@MainActivity, settings.subKind)
                    else com.past9.phoneaos.agent.ModelCatalog.forKey(this@MainActivity, settings.provider, g.settings.apiKey(settings.provider).orEmpty(), settings.baseUrl)
                }.onFailure { modelErr = "Couldn't load models: ${it.message}" }
            }
        }
        val modelLabel = if (onSub) prettyModel(settings.subModel) else settings.model.substringAfterLast('/')
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
            scope.launch { uris.forEach { u -> importImage(u)?.let { attachments += it } } }
        }
        var conn by remember { mutableStateOf(ConnectionsState(hasKey = settings.composioEnabled)) }
        val sub = remember(settings.subKind) { g.subRuntime() }
        val speaker = remember { Speaker(this) }

        // The agent wants a page (e.g. an app sign-in) shown in the built-in browser.
        LaunchedEffect(Unit) {
            com.past9.phoneaos.system.UiBus.openInBrowser.collect { req ->
                com.past9.phoneaos.system.UiBus.openInBrowser.resetReplayCache()
                runCatching { BrowserService.await(this@MainActivity, "main", req.profile ?: settings.browserProfiles.first()).goto(req.url) }
                nav.navigate("browser") { launchSingleTop = true }
            }
        }
        // First open of a new day: straight to the morning screen the night shift wrote.
        val morningNow by com.past9.phoneaos.triggers.Overnight.morning.collectAsStateWithLifecycle()
        val brief by com.past9.phoneaos.triggers.Overnight.brief.collectAsStateWithLifecycle()
        val preparingBrief by com.past9.phoneaos.triggers.Overnight.working.collectAsStateWithLifecycle()
        LaunchedEffect(morningNow) { if (morningNow && route != "chat") nav.navigate("chat") { launchSingleTop = true } }
        val speakingNow by g.speaking.collectAsStateWithLifecycle()
        val incomingNow by incoming.collectAsStateWithLifecycle()
        LaunchedEffect(incomingNow) {
            val (text, open) = incomingNow
            if (text != null) { draft = text; nav.navigate("chat") { launchSingleTop = true } }
            if (open != null) nav.navigate(open)
            if (text != null || open != null) incoming.value = null to null
        }
        // Read new replies aloud when the user asked for that.
        // Only replies that arrive from now on are read out; the history never is.
        var primed by remember { mutableStateOf(false) }
        LaunchedEffect(items.lastOrNull()?.id) {
            if (!primed) { g.markHeard(g.db.chat().all().first().map { it.id }); primed = true; return@LaunchedEffect }
            val last = items.lastOrNull() ?: return@LaunchedEffect
            if (org.json.JSONObject(last.meta).optBoolean("call")) return@LaunchedEffect // already heard it on the call
            val ears = settings.autoPlayEarphones && g.earphonesIn()
            // Text replies are never read out (his ruling 5 Oct 17:49): voice comes as voice notes only.
            if (last.kind == "voice" && ears) g.autoPlayVoice(org.json.JSONObject(last.meta).optString("path"), last.id)
        }

        val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) nav.navigate("call") }
        fun startCall() { if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) nav.navigate("call") else micPerm.launch(Manifest.permission.RECORD_AUDIO) }
        val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { conn = conn.copy(notificationsAllowed = it) }
        // The agent asked for a permission mid-task: show Android's prompt and hand the answer back.
        val permReq by com.past9.phoneaos.system.PermissionBroker.pending.collectAsStateWithLifecycle()
        var activeReq by remember { mutableStateOf<com.past9.phoneaos.system.PermissionBroker.Request?>(null) }
        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            activeReq?.let { com.past9.phoneaos.system.PermissionBroker.answer(it, res.values.any { v -> v }) }; activeReq = null
        }
        val fileReq by com.past9.phoneaos.tools.FilePickBroker.pending.collectAsStateWithLifecycle()
        var activeFile by remember { mutableStateOf<com.past9.phoneaos.tools.FilePickBroker.Request?>(null) }
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            activeFile?.let { com.past9.phoneaos.tools.FilePickBroker.answer(it, uris) }; activeFile = null
        }
        LaunchedEffect(fileReq) { fileReq?.let { if (activeFile == null) { activeFile = it; filePicker.launch(it.mimeTypes) } } }
        LaunchedEffect(permReq) { permReq?.let { if (activeReq == null) { activeReq = it; permLauncher.launch(it.permissions) } } }
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
        fun go(r: String) { if (r == "home") nav.popBackStack("home", false) else nav.navigate(r) { popUpTo("home"); launchSingleTop = true } }
        val home = HomeState(settings.agentName, settings.userName, status, items, goals, tasks, routines,
            notifRows.count { it.postedAt > System.currentTimeMillis() - 86_400_000L }, settings.notifApps.size, browserLive)
        val barRoutes = setOf("home", "tasks", "memory", "routines", "connections")
        val barPad = 112.dp

        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                        HomeScreen(home, HomeActions(
                            onProfile = { nav.navigate("profile") }, onChat = { nav.navigate("chat") },
                            onAnswer = { id, o -> g.runtime.answer(id, o) },
                            onToggleTask = { t -> scope.launch { g.db.tasks().updateTask(t.copy(status = if (t.status == "done") "todo" else "done", updatedAt = System.currentTimeMillis())) } },
                            onTasks = { go("tasks") }, onGoal = { nav.navigate("profile") }, onRoutines = { go("routines") },
                            onSettings = { nav.navigate("settings") }, onCall = if (settings.liveEnabled) ({ startCall() }) else null, onBrowser = { nav.navigate("browser") }, onNotifications = { nav.navigate("notifications") },
                        ), bottomPadding = barPad + 24.dp)
                    }
                }
                composable("profile") {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                        ProfileScreen(home, memories, ProfileActions(onBack = { nav.popBackStack() }, onChat = { nav.navigate("chat") },
                            onMemory = { nav.navigate("memory") }, onRoutines = { go("routines") }, onStyle = { nav.navigate("style") }))
                    }
                }
                composable("notifications") {
                    val pm = packageManager
                    val apps = remember {
                        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        pm.queryIntentActivities(launch, 0).map { it.activityInfo.packageName }.distinct().filter { it != packageName }.mapNotNull { pkg ->
                            runCatching {
                                val ai = pm.getApplicationInfo(pkg, 0)
                                PhoneApp(pkg, pm.getApplicationLabel(ai).toString(), runCatching { pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap() }.getOrNull())
                            }.getOrNull()
                        }
                    }
                    val access = remember(backEntry) { androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this@MainActivity).contains(packageName) }
                    NotificationsScreen(access, apps, settings.notifApps, notifRows, NotificationsActions(
                        onBack = { nav.popBackStack() },
                        onToggle = { pkg, on -> g.settings.setNotifApp(pkg, on); if (!on) scope.launch { g.db.notifications().forget(pkg) } },
                        onGrantAccess = { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } },
                    ))
                }
                composable("chat") {
                    ChatScreen(items, status, settings.agentName, settings.userName, browserLive, draft, { draft = it },
                        ChatActions(
                            onSend = { g.stopSpeaking(); com.past9.phoneaos.triggers.Overnight.dismissMorning(this@MainActivity)
                                val docText = docs.entries.joinToString("") { (n, d) -> "\n\n[Attached document: $n]\n${d.first.take(30_000)}" }
                                g.runtime.send(it + docText, attachments.toList() + docs.values.flatMap { d -> d.second }.take(6), docs.keys.toList())
                                attachments.clear(); docs.clear() },
                            onSendVoice = { g.stopSpeaking(); com.past9.phoneaos.triggers.Overnight.dismissMorning(this@MainActivity)
                                val docText = docs.entries.joinToString("") { (n, d) -> "\n\n[Attached document: $n]\n${d.first.take(30_000)}" }
                                g.runtime.send(it + docText, attachments.toList() + docs.values.flatMap { d -> d.second }.take(6), docs.keys.toList(), voiceReply = true)
                                attachments.clear(); docs.clear() }, onStop = { g.runtime.stop() },
                            onAttachDoc = { docPicker.launch(arrayOf("*/*")) }, onRemoveDoc = { docs.remove(it) },
                            onAttach = { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onRemoveAttachment = { attachments.remove(it) }, onModel = { openModels() }, onCall = if (settings.liveEnabled) ({ startCall() }) else null, onStopSpeaking = if (speakingNow) ({ g.stopSpeaking() }) else null,
                            onAnswer = { id, o -> g.runtime.answer(id, o) },
                            onMenu = { nav.popBackStack() }, onBrowser = { nav.navigate("browser") }, onProfile = { nav.navigate("profile") },
                            onMic = { runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) } },
                        ), openCount = tasks.count { it.status != "done" }, attachments = attachments, modelLabel = modelLabel, docs = docs.keys.toList(),
                        morning = if (!morningNow) null else (brief?.takeIf { it.ideas.isNotEmpty() }?.let { b ->
                            com.past9.phoneaos.ui.screens.MorningUi(b.greeting, b.line, b.body, b.ideas.map { it.icon to it.text }, preparingBrief, { com.past9.phoneaos.triggers.Overnight.dismissMorning(this@MainActivity) })
                        } ?: com.past9.phoneaos.ui.screens.MorningUi(preparing = preparingBrief, onBackToChat = { com.past9.phoneaos.triggers.Overnight.dismissMorning(this@MainActivity) })))
                }
                composable("tasks") {
                    TasksScreen(goals, tasks, bottomPadding = barPad, actions = TaskActions(
                        onBack = { nav.popBackStack() },
                        onToggle = { t -> scope.launch { g.db.tasks().updateTask(t.copy(status = if (t.status == "done") "todo" else "done", updatedAt = System.currentTimeMillis())) } },
                        onAdd = { text, due -> scope.launch { g.db.tasks().insertTask(TaskRow(title = text.trim(), owner = "user", dueAt = due)) } },
                        onDelete = { t -> scope.launch { g.db.tasks().deleteTask(t.id) } },
                        onAsk = { prompt -> g.runtime.send(prompt); nav.navigate("chat") },
                    ))
                }
                composable("memory") {
                    MemoryScreen(memories, bottomPadding = barPad, actions = MemoryActions(
                        onBack = { nav.popBackStack() },
                        onOpen = { m -> nav.navigate("page/${m.id}") },
                        onSave = { m -> scope.launch { if (m.id == 0L) g.db.memory().insert(m) else g.db.memory().update(m) } },
                        onDelete = { m -> scope.launch { g.db.memory().delete(m.id) } },
                    ))
                }
                composable("page/{id}") { e ->
                    val id = e.arguments?.getString("id")?.toLongOrNull()
                    WikiPageScreen(memories.firstOrNull { it.id == id }, memories, onBack = { nav.popBackStack() },
                        onOpenTitle = { t -> val target = memories.firstOrNull { it.title.equals(t, true) }
                            if (target != null) nav.navigate("page/${target.id}") else { g.runtime.send("Start a memory page about $t with what you know."); nav.navigate("chat") } },
                        onSave = { m -> scope.launch { g.db.memory().update(m) } },
                        onDelete = { m -> scope.launch { g.db.memory().delete(m.id) }; nav.popBackStack() },
                        onAsk = { p -> g.runtime.send(p); nav.navigate("chat") })
                }
                composable("routines") {
                    RoutinesScreen(routines, bottomPadding = barPad, actions = RoutineActions(
                        onBack = { nav.popBackStack() },
                        onToggle = { r -> scope.launch { val n = r.copy(enabled = !r.enabled); g.db.triggers().upsert(n); Routines.schedule(this@MainActivity, n) } },
                        onRunNow = { r -> Routines.runNow(this@MainActivity, r.id) },
                        onDelete = { r -> scope.launch { g.db.triggers().delete(r.id); Routines.cancel(this@MainActivity, r.id) } },
                        onSave = { r -> scope.launch { val id = g.db.triggers().upsert(r); Routines.schedule(this@MainActivity, r.copy(id = id)) } },
                    ))
                }
                composable("connections") {
                    LaunchedEffect(Unit) { refreshConnections() }
                    ConnectionsScreen(conn, bottomPadding = barPad + 24.dp, actions = ConnectionsActions(
                        onNotifications = { nav.navigate("notifications") },
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
                        onModel = { openModels() },
                        voice = VoiceActions(
                            setTts = { p, v -> g.settings.setTts(p, v) }, setKey = { p, k -> g.settings.setVoiceKey(p, k) },
                            hasKey = { p -> g.settings.voiceKey(p) != null }, setSpeak = g.settings::setSpeakReplies, setLive = g.settings::setLive, setAutoPlay = g.settings::setAutoPlayEarphones,
                            preview = { g.speak("Hi ${settings.userName.ifBlank { "there" }}, this is how I sound.") }),
                        onChooseSub = { k ->
                            if (k == com.past9.phoneaos.data.SubKind.OPENCODE) { g.settings.setProvider(Provider.OPENCODE_GO); nav.navigate("key") }
                            else { g.settings.setSubKind(k); g.settings.setMode(PowerMode.SUBSCRIPTION); nav.navigate("sub") } },
                        onPinHome = { com.past9.phoneaos.system.Identity.pinNamedShortcut(this@MainActivity, settings.agentName, settings.mascot, settings.accent) },
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
                    SubSetupFlow(g, settings.subKind, onBack = { nav.popBackStack() }, onDone = { nav.navigate("chat") { popUpTo("home") } },
                        onUseKey = { g.settings.setMode(PowerMode.API_KEY); nav.navigate("key") }, modelLabel = modelLabel, onModel = { openModels() })
                }
                composable("call") {
                    val cst by g.call.state.collectAsStateWithLifecycle()
                    CallScreen(settings.agentName, cst, onMute = { g.call.toggleMute() }, onEnd = { g.call.end(); nav.popBackStack() }, onStart = { g.call.start() },
                        actions = items.filter { it.kind == "activity" && it.createdAt >= g.call.callStart && g.call.callStart > 0 }.map { it.text })
                }
                composable("style") {
                    com.past9.phoneaos.ui.SubScreen("Make it yours", "Colour and sidekick", { nav.popBackStack() }) { pad ->
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 48.dp)) {
                            StylePicker(settings.userName, settings.agentName, settings.accent, settings.mascot, g.settings::setAccent, g.settings::setMascot)
                        }
                    }
                }
                composable("browser") {
                    val engines by BrowserService.live.collectAsStateWithLifecycle()
                    BrowserScreen(engines, settings.browserProfiles, BrowserActions(
                        onClose = { nav.popBackStack() },
                        onHandBack = {
                            items.lastOrNull { it.kind == "question" && !org.json.JSONObject(it.meta).has("answer") }?.let { g.runtime.answer(it.id, "Done") }
                            nav.popBackStack()
                        },
                        onProfile = { p -> scope.launch { runCatching { BrowserService.await(this@MainActivity, "main", p).goto("https://www.google.com") } } },
                        onAddProfile = { n -> g.settings.addBrowserProfile(n); scope.launch { runCatching { BrowserService.await(this@MainActivity, "main", n).goto("https://www.google.com") } } },
                    ))
                }
            }
            if (modelSheet) ModelPickerSheet(if (onSub) "${settings.subKind.label} model" else "${settings.provider.label} model", modelList,
                if (onSub) settings.subModel else settings.model, modelErr,
                onPick = { m -> if (onSub) g.settings.setSubModel(m.id) else g.settings.setModel(m.id); modelSheet = false }, onDismiss = { modelSheet = false })
            androidx.compose.animation.AnimatedVisibility(route in barRoutes, modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut()) {
                HomeBar(route, ::go, onTalk = { nav.navigate("chat") })
            }
        }
    }

    /** The whole subscription setup (install, sign in, done), shared by Settings and onboarding. */
    @Composable
    private fun SubSetupFlow(g: Graph, kind: com.past9.phoneaos.data.SubKind, onBack: () -> Unit, onDone: () -> Unit, onUseKey: () -> Unit, modelLabel: String = "", onModel: () -> Unit = {}) {
        val scope = rememberCoroutineScope()
        val sub = remember(kind) { com.past9.phoneaos.runtime.SubscriptionRuntime(this, kind) }
        var info by remember(kind) { mutableStateOf(sub.info()) }
        val log = remember(kind) { mutableStateListOf<String>() }
        var installing by remember { mutableStateOf(false) }
        var signingIn by remember { mutableStateOf(false) }
        var signErr by remember { mutableStateOf<String?>(null) }
        var needsCode by remember { mutableStateOf(false) }
        var verifying by remember { mutableStateOf(false) }
        SubSetupScreen(sub.kind, sub.spec.signInHelp, info, sub.installed, log, installing, onBack = onBack,
            signingIn = signingIn, signInError = signErr, needsCode = needsCode, verifying = verifying,
            onCode = { c -> verifying = true; needsCode = false; sub.submitCode(c) },
            onDone = onDone, modelLabel = modelLabel, onModel = onModel,
            onSignIn = { scope.launch { signingIn = true; signErr = null
                // While you're in the browser this app is in the background, and Android cuts a
                // background app's network. A foreground service keeps it online for the hand-back.
                g.phone.workStarted()
                val err = try { sub.login(onUrl = { url -> runOnUiThread { open(url) } }, progress = { l -> scope.launch { log += l } }, needsCode = { needsCode = true }) } finally { g.phone.workFinished() }
                signingIn = false; needsCode = false; verifying = false; info = sub.info()
                if (sub.signedIn) { g.settings.setSubKind(kind); g.settings.setMode(PowerMode.SUBSCRIPTION) } else signErr = err ?: "Sign-in didn't finish. Tap Sign in to try again." } },
            onInstall = { scope.launch { installing = true; runCatching { sub.install { l -> scope.launch { log += l } } }.onFailure { log += "Failed: ${it.message}" }; installing = false; info = sub.info() } },
            onSaveToken = { t -> runCatching { sub.setToken(t); g.settings.setSubKind(kind); g.settings.setMode(PowerMode.SUBSCRIPTION) }.onFailure { signErr = it.message }; info = sub.info() },
            onUseKey = onUseKey)
    }

    @Composable
    private fun Onboarding(g: Graph) {
        val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val st = g.settings.state.value
        OnboardingScreen(onboardingActions(g).copy(
            subSetup = { kind, back, next -> SubSetupFlow(g, kind, onBack = back, onDone = next, onUseKey = back) },
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
        listModels = { p, k -> com.past9.phoneaos.agent.ModelCatalog.forKey(this, p, k, if (p == Provider.CUSTOM) g.settings.state.value.baseUrl else p.baseUrl) },
        saveModel = { m -> g.settings.setModel(m) },
        openUrl = ::open,
    )

    private suspend fun testKey(p: Provider, key: String): String? {
        val g = App.graph(this)
        val st = g.settings.state.value
        val model = if (st.provider == p && st.model.isNotBlank()) st.model else p.defaultModel
        val provider = com.past9.phoneaos.agent.AgentRuntime.providerFor(p, key, if (p == Provider.CUSTOM) st.baseUrl else p.baseUrl)
        suspend fun ping(m: String) = provider.complete("Reply with OK.", listOf(Msg.user("ping")), emptyList(), m, 16)
        return try { ping(model); null } catch (e: com.past9.phoneaos.agent.ProviderException) {
            // A retired model (Google refuses Gemini 2.5 to new keys) shouldn't block setup: switch to the provider's default.
            if (e.status == 404 && model != p.defaultModel) {
                try { ping(p.defaultModel); g.settings.setModel(p.defaultModel); return null } catch (_: Exception) {}
            }
            when (e.status) { 401, 403 -> "That key was rejected."; 404 -> "Key works, but the model $model isn't available to this key. Pick another model."; 429 -> "Key works but has no credit or is rate-limited."; else -> e.message }
        } catch (e: Exception) { "Couldn't check: ${e.message}" }
    }

    private suspend fun saveComposio(g: Graph, key: String): String? {
        g.settings.setComposioKey(key)
        return if (g.runtime.composio.verify()) null else { g.settings.setComposioKey(null); "Composio didn't accept that key." }
    }

    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Copy a picked photo into app storage, scaled to at most 1568px (what vision models use) as JPEG. */
    private suspend fun importImage(uri: Uri): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
            val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { d, info, _ ->
                val w = info.size.width; val h = info.size.height; val scale = minOf(1f, 1568f / maxOf(w, h))
                d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)); d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val f = java.io.File(filesDir, "uploads/${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            f.path
        }.getOrNull()
    }

    /** "claude-opus-5-5" -> "Opus 5.5", "sonnet" -> "Sonnet", "" -> "Default". */
    private fun prettyModel(id: String): String {
        if (id.isBlank()) return "Default"
        val m = Regex("claude-([a-z]+)-(\\d+)(?:-(\\d+))?").find(id) ?: return id.replaceFirstChar { it.uppercase() }
        val (tier, a, b) = m.destructured
        return tier.replaceFirstChar { it.uppercase() } + " " + a + (if (b.isNotBlank() && b.length <= 2) ".$b" else "")
    }

    private fun open(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
}

