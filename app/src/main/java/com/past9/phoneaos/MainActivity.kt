package com.past9.phoneaos

import androidx.compose.material.icons.rounded.Apps

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
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
import com.past9.phoneaos.ui.CardSheet
import com.past9.phoneaos.ui.CardSheetActions
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
        // A phone linked to a computer picks its link up again (remote/RemoteSession.kt).
        com.past9.phoneaos.remote.RemoteHub.init(this)
        // Mini-apps from the linked computer (miniapps/MiniApps.kt): listed slim, opened in a locked-down WebView.
        com.past9.phoneaos.miniapps.MiniAppsHub.start(g.scope)
        setContent {
            val st by g.settings.state.collectAsStateWithLifecycle()
            AppTheme(accent = st.accent, mascot = st.mascot) {
                androidx.compose.runtime.CompositionLocalProvider(com.past9.phoneaos.ui.screens.LocalStopHelper provides { id: Long -> g.runtime.stopHelper(id); Unit },
                    com.past9.phoneaos.ui.screens.LocalDismissHelper provides { id: Long -> g.runtime.dismissHelper(id) },
                    com.past9.phoneaos.ui.screens.LocalCards provides App.graph(this).cards.cards.collectAsStateWithLifecycle().value) {
                        // A picture or video opened from a card is drawn here, above every screen, never in a window of its own.
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                            Root(g)
                            com.past9.phoneaos.ui.MediaViewerHost()
                        }
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Results of jobs handed to the user's machines: caught up and awaited only while the app is on screen.
        com.past9.phoneaos.handoff.Handoffs.onScreen(this)
        com.past9.phoneaos.remote.RemoteHub.onForeground()
    }

    override fun onResume() {
        super.onResume()
        // A finished turn asks this before it buzzes the phone (system/ChatOnScreen.kt, issue #15).
        com.past9.phoneaos.system.ChatOnScreen.resumed = true
        // Back on the chat with a reply waiting in the shade: it has been read.
        if (com.past9.phoneaos.system.ChatOnScreen.route == "chat") com.past9.phoneaos.system.AndroidPhone.clearReply(this)
        com.past9.phoneaos.triggers.Overnight.onAppOpen(this)
        // A memory pack pushed over adb (Android/data/<pkg>/files/memory-pack.json) is imported once, on open.
        val g = App.graph(this)
        if (com.past9.phoneaos.data.MemoryPack.pending(this).isNotEmpty()) g.scope.launch {
            val n = com.past9.phoneaos.data.MemoryPack.importPending(this@MainActivity, g.db.memory())
            if (n > 0) g.db.chat().insert(com.past9.phoneaos.data.ChatItem(kind = "notice", text = "Added $n memory pages from A.O.S"))
        }
    }

    override fun onPause() {
        super.onPause()
        com.past9.phoneaos.system.ChatOnScreen.resumed = false
        // Leaving the screen: what is in the message boxes and the dictation's words are on disk now.
        com.past9.phoneaos.data.DraftStore.flush()
        com.past9.phoneaos.voice.Dictations.of(this).flush()
    }

    override fun onStop() {
        super.onStop()
        com.past9.phoneaos.handoff.Handoffs.offScreen()
        com.past9.phoneaos.remote.RemoteHub.onBackground()
        // Swap the home-screen icon to the user's character only once they've left the app.
        val st = App.graph(this).settings.state.value
        if (st.onboarded) runCatching { com.past9.phoneaos.system.Identity.applyLauncherIcon(this, st.mascot, st.accent) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        if (i == null) return
        // A home-screen shortcut to one mini-app.
        i.getStringExtra("miniapp")?.let { com.past9.phoneaos.miniapps.MiniAppsHub.pendingOpen.value = it }
        // A link code (agent://pair?...) from the phone's own camera app, a shared QR, or adb.
        i.data?.takeIf { it.scheme == "agent" && it.host == "pair" }?.let { com.past9.phoneaos.remote.RemoteHub.pendingOffer.value = it.toString(); return }
        // A mini-app card in the chat, or the computer opening one here (miniapp_open): agent://miniapp/<id>.
        com.past9.phoneaos.miniapps.MiniAppRules.idFromLink(i.data?.toString())?.let { com.past9.phoneaos.miniapps.MiniAppsHub.pendingOpen.value = it; return }
        // adb hook for checking how notifications look: am start -n <pkg>/.MainActivity --es open testnotify
        // adb hook for checking sites in the agent's browser: am start -n <pkg>/.MainActivity --es open browser --es url https://...
        i.getStringExtra("url")?.takeIf { i.getStringExtra("open") == "browser" }?.let { u ->
            App.graph(this).scope.launch { runCatching { BrowserService.await(this@MainActivity, "main", App.graph(this@MainActivity).settings.state.value.browserProfiles.first()).goto(u) } }
        }
        // adb hook for checking how a site behaves in the browser (what it does by itself when a site refuses it):
        //   am start -n <pkg>/.MainActivity --es open probe --es url https://...   then read: logcat -s SiteProbe
        // It opens the site twice in its own throwaway browser (the second visit shows what was learned), logs both, and forgets the site again.
        i.getStringExtra("url")?.takeIf { i.getStringExtra("open") == "probe" }?.let { u ->
            val g = App.graph(this)
            g.scope.launch {
                runCatching {
                    val e = BrowserService.await(this@MainActivity, "probe", g.settings.state.value.browserProfiles.first())
                    val c = com.past9.phoneaos.browser.SiteCatalog(g.settings)
                    c.forget(u)
                    for (visit in 1..2) {
                        val o = com.past9.phoneaos.browser.SiteAdapt.open(e.port, c, u, null)
                        android.util.Log.i("SiteProbe", "visit $visit $u -> ok=${o.verdict.ok} refusal=${o.verdict.refusal} desktop=${o.desktop} triedBoth=${o.triedBoth} status=${e.lastStatus} final=${e.page.value.url.take(60)} title=${e.page.value.title.take(40)} evidence=${o.verdict.evidence.take(80)} | textNow=[${e.visibleText().let { t -> "${t.length}:" + t.replace(Regex("\\s+"), " ").take(110) }}] navSeq=${e.navSeq} | catalog=${c.get(u)?.line()} | notes=${o.notes.lines().firstOrNull().orEmpty().take(160)}")
                    }
                    c.forget(u)
                }.onFailure { android.util.Log.e("SiteProbe", "failed", it) }
            }
            return
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
        val offer by com.past9.phoneaos.remote.RemoteHub.pendingOffer.collectAsStateWithLifecycle()
        offer?.let { o ->
            com.past9.phoneaos.remote.LinkScreen(onBack = { com.past9.phoneaos.remote.RemoteHub.pendingOffer.value = null },
                onLinked = { com.past9.phoneaos.remote.RemoteHub.pendingOffer.value = null }, offerText = o)
            return
        }
        // Linked to a computer: the same screens show the computer's data, and edits and messages go there.
        val remote by com.past9.phoneaos.remote.RemoteHub.session.collectAsStateWithLifecycle()
        val dataDb = remote?.db ?: g.db

        // Once: if the phone would hold back reminders (battery optimisation, a sleeping Samsung app), say so with the fix.
        var offerReliability by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (g.settings.extra("reliability_offered") == null && com.past9.phoneaos.system.Background.problems(com.past9.phoneaos.system.Background.check(this@MainActivity)).isNotEmpty()) {
                g.settings.setExtra("reliability_offered", "1"); offerReliability = true
            }
        }
        if (offerReliability) com.past9.phoneaos.ui.screens.ReliabilitySheet { offerReliability = false }
        val items by remember(dataDb) { dataDb.chat().all() }.collectAsStateWithLifecycle(emptyList())
        val remoteStatus by (remote?.status ?: g.runtime.status).collectAsStateWithLifecycle()
        val liveCards = remote?.cards?.collectAsStateWithLifecycle()?.value
        // ONE working number for the whole app: the Home header, the agents pill and the Working list all count the same
        // card list, so the header can never say Free while helpers are listed (30 Oct 2026: "8 working" beside "Free").
        // Cards are only "working" while the computer that published them is answering: no brain, nothing at work.
        val alive = remote?.brainAlive() ?: false
        val status = if (remote != null && liveCards != null)
            remoteStatus.copy(
                working = com.past9.phoneaos.remote.LiveCards.anyWorking(liveCards, alive),
                helpers = com.past9.phoneaos.remote.LiveCards.workingCount(liveCards, alive),
            )
        else remoteStatus
        // Opening a chat from a card: a project chat goes on top of the main thread (Back always lands there), "main"
        // is the main thread itself.
        var openFromCard by remember { mutableStateOf<Pair<String, String>?>(null) }
        // New project, asked for from the project switcher on a chat title: the same sheet as on the profile, then its first chat.
        var newProject by remember { mutableStateOf(false) }
        val projectsApi = com.past9.phoneaos.ui.rememberProjectsApi()
        val newProjectScope = rememberCoroutineScope()
        if (newProject) com.past9.phoneaos.ui.NewProjectSheet(onDismiss = { newProject = false }, onCreated = { id ->
            newProject = false
            newProjectScope.launch {
                runCatching {
                    val api = projectsApi ?: return@runCatching
                    val p = api.list().firstOrNull { it.id == id } ?: return@runCatching
                    openFromCard = api.startChat(p).chat to p.name
                }
            }
        })
        val liveUi = remote?.let { r -> liveCards?.let { list -> com.past9.phoneaos.remote.LiveCardsUi(list, r::cardView, r::stopCard, r::dismissCard) { chat, name -> openFromCard = chat to name } } }
        val goals by remember(dataDb) { dataDb.tasks().goals() }.collectAsStateWithLifecycle(emptyList())
        val tasks by remember(dataDb) { dataDb.tasks().tasks() }.collectAsStateWithLifecycle(emptyList())
        val memories by remember(dataDb) { dataDb.memory().all() }.collectAsStateWithLifecycle(emptyList())
        val allRoutines by remember(dataDb) { dataDb.triggers().all() }.collectAsStateWithLifecycle(emptyList())
        // A card's refresh schedule is part of the card, and a wake is a one-off follow-up (see wake_list), not a routine of its own.
        val routines = allRoutines.filter { it.kind != com.past9.phoneaos.cards.CardScripts.KIND && it.kind != com.past9.phoneaos.triggers.Wakes.KIND }
        val browserLive by BrowserService.running.collectAsStateWithLifecycle()
        val nav = rememberNavController()
        LaunchedEffect(openFromCard) {
            val (chat, name) = openFromCard ?: return@LaunchedEffect
            openFromCard = null
            if (chat == "main" || chat.isBlank()) nav.navigate("chat") { popUpTo("chat") { inclusive = false }; launchSingleTop = true }
            else nav.navigate("pchat/" + Uri.encode(chat) + "?name=" + Uri.encode(name)) {
                // One project chat on top of the main thread at a time: Back from it is the main thread.
                if (nav.currentDestination?.route?.startsWith("pchat/") == true) nav.currentBackStackEntry?.destination?.id?.let { popUpTo(it) { inclusive = true } }
                launchSingleTop = true
            }
        }
        val notifRows by g.db.notifications().recent(200).collectAsStateWithLifecycle(emptyList())
        val backEntry by nav.currentBackStackEntryAsState()
        val route = backEntry?.destination?.route ?: "home"
        // Is the chat itself on screen? A finished turn reads that before it notifies (issue #15), and a chat opened
        // under a reply notification takes it away: it has been read.
        LaunchedEffect(route) {
            com.past9.phoneaos.system.ChatOnScreen.route = route
            if (route == "chat") com.past9.phoneaos.system.AndroidPhone.clearReply(this@MainActivity)
        }
        val scope = rememberCoroutineScope()
        val ops = remember(remote) { com.past9.phoneaos.remote.DataOps(applicationContext, remote, g.scope) }
        // The main chat's message box; a project or helper chat has its own (ui/Composer.kt). Its words live on disk, not
        // in this screen. The pickers below fill the box that asked ([boxFor]), so a photo picked in a project chat goes
        // with that chat's message.
        val mainBox = remember { com.past9.phoneaos.ui.ComposerBoxes.box("main") }
        var draft by mainBox.state
        var boxFor by remember { mutableStateOf<com.past9.phoneaos.ui.ComposerBox?>(null) }
        val attachments = mainBox.attachments
        /** Where the camera button is, while the in-place camera card is open (it grows out of there). */
        var cameraOrigin by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        // Documents attached in chat: name -> (extracted text, page images)
        val docs = mainBox.docs
        val docPaths = mainBox.docPaths
        val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val reader = com.past9.phoneaos.tools.FilesPickTool(this@MainActivity)
            val box = boxFor ?: mainBox
            scope.launch { uris.forEach { u -> val name = reader.displayName(u); reader.keepCopy(u, name)?.let { box.docPaths[name] = it.path }; box.docs[name] = reader.readDocument(u) } }
        }
        var modelSheet by remember { mutableStateOf(false) }
        /** The picker is choosing the helpers' model rather than the main agent's. */
        var pickingHelper by remember { mutableStateOf(false) }
        var modelList by remember { mutableStateOf<List<com.past9.phoneaos.agent.ModelInfo>?>(null) }
        var modelErr by remember { mutableStateOf<String?>(null) }
        val onSub = settings.mode == PowerMode.SUBSCRIPTION
        fun openModels(forHelpers: Boolean = false) {
            // Linked: the computer picks its own model.
            if (remote != null) return
            pickingHelper = forHelpers
            modelSheet = true; modelList = null; modelErr = null
            scope.launch {
                runCatching {
                    modelList = if (onSub) com.past9.phoneaos.agent.ModelCatalog.forSubscription(this@MainActivity, settings.subKind)
                    else com.past9.phoneaos.agent.ModelCatalog.forKey(this@MainActivity, settings.provider, g.settings.apiKey(settings.provider).orEmpty(), settings.baseUrl, settings.allowHttp)
                }.onFailure { modelErr = "Couldn't load models: ${it.message}" }
            }
        }
        val linkState = remote?.state?.collectAsStateWithLifecycle()?.value
        // Linked: no model pill and no machine name in the chat; the computer picks its own model.
        val modelLabel = if (remote != null) ""
            else if (onSub) prettyModel(settings.subModel) else settings.model.substringAfterLast('/')
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
            val box = boxFor ?: mainBox
            scope.launch { uris.forEach { u -> importImage(u)?.let { box.attachments += it } } }
        }
        var conn by remember { mutableStateOf(ConnectionsState(hasKey = settings.composioEnabled)) }
        // Linked to a computer: an app being added, waiting for which of the computer's profiles it goes into.
        var pendingRemote by remember { mutableStateOf<Pair<String, List<Pair<String, String>>>?>(null) }
        val profileList by g.runtime.profiles.profiles.collectAsStateWithLifecycle()
        val accountRules by g.runtime.profiles.rules.collectAsStateWithLifecycle()
        val ps = g.runtime.profiles
        val profileActions = com.past9.phoneaos.ui.screens.AccountProfileActions(
            onCreate = { n, a, b, c -> ps.create(n, a, c).let { p -> if (b.isNotBlank()) p.copy(browser = b).also { ps.update(it) } else p } },
            onSave = { ps.update(it) }, onDelete = { ps.delete(it) },
            onMember = { pid, a, on -> ps.setMember(pid, a, on) }, onRules = { id, r -> ps.setRules(id, r) })
        /** An account that was just connected (here or by a helper): ask which profiles it belongs to. */
        var justConnected by remember { mutableStateOf<com.past9.phoneaos.data.AccountRef?>(null) }
        LaunchedEffect(Unit) {
            com.past9.phoneaos.system.UiBus.addToProfile.collect { a -> com.past9.phoneaos.system.UiBus.addToProfile.resetReplayCache(); justConnected = a }
        }
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
            if (text != null) { draft = com.past9.phoneaos.voice.Words.join(draft, text); nav.navigate("chat") { launchSingleTop = true } }
            // An unknown screen name (from a shortcut, an adb call or another app) is ignored; it must never take the app down.
            if (open != null) runCatching { nav.navigate(open) }
            if (text != null || open != null) incoming.value = null to null
        }
        // Read new replies aloud when the user asked for that.
        // Only replies that arrive from now on are read out; the history never is.
        var primed by remember { mutableStateOf(false) }
        LaunchedEffect(items.lastOrNull()?.id) {
            if (!primed) { g.markHeard(dataDb.chat().all().first().map { it.id }); primed = true; return@LaunchedEffect }
            val last = items.lastOrNull() ?: return@LaunchedEffect
            if (org.json.JSONObject(last.meta).optBoolean("call")) return@LaunchedEffect // already heard it on the call
            val ears = settings.autoPlayEarphones && g.earphonesIn()
            // Text replies are never read out (his ruling 5 Oct 17:49): voice comes as voice notes only.
            if (last.kind == "voice" && ears) g.autoPlayVoice(org.json.JSONObject(last.meta).optString("path"), last.id)
        }

        // Start CameraX early (no camera is opened, no permission is asked) so the camera card opens fast.
        LaunchedEffect(route) { if (route == "chat") com.past9.phoneaos.camera.CameraWarm.warm(this@MainActivity) }
        val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { nav.navigate("call") { launchSingleTop = true } } // refused: the call screen says why and offers to ask again
        // The call button always shows. When this phone can't place the call, a tap says why instead of hiding it.
        var callWhy by remember { mutableStateOf<String?>(null) }
        fun startCall() { g.call.unavailableReason(remote != null)?.let { callWhy = it; return }
            if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) nav.navigate("call") else micPerm.launch(Manifest.permission.RECORD_AUDIO) }
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
        // Dictation: the phone's own speech recogniser (the keyboard's and the assistant's, Google's on most phones), live
        // into the message box in the phone's language. Only a phone with no recogniser at all uses the Gemini
        // transcription stream instead (with a Gemini key), and the person is told so.
        var dictation by remember { mutableStateOf<com.past9.phoneaos.voice.Dictating?>(null) }
        var dictating by remember { mutableStateOf(false) }
        /** The message box the mic is writing into. */
        var dictBox by remember { mutableStateOf<com.past9.phoneaos.ui.ComposerBox?>(null) }
        // The app's own long dictation (voice/DictationHub) lives outside this activity: leaving the app for good stops it
        // (its words still land), a rotation does not.
        DisposableEffect(Unit) { onDispose { dictation?.cancel(); if (!isChangingConfigurations) com.past9.phoneaos.voice.DictationHub.stop() } }
        val hubUi by com.past9.phoneaos.voice.DictationHub.ui.collectAsStateWithLifecycle()
        // A dictation the last run of the app was cut off in: its audio and words are safe, say where.
        LaunchedEffect(Unit) { if (com.past9.phoneaos.voice.DictationHub.takeInterrupted()) com.past9.phoneaos.ui.AppNotice.say("Your last dictation was cut off. It's saved in Settings, Recordings.") }
        val hubActive = hubUi?.view?.ended == false
        // The screen stays on while he dictates (the mic service also keeps it going if he locks the phone).
        LaunchedEffect(hubActive) {
            if (hubActive) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        fun say(text: String) = com.past9.phoneaos.ui.AppNotice.say(text)
        fun online(): Boolean = runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(false)
        fun startDictation() {
            val box = dictBox ?: mainBox
            // Only ever adds: a shorter result is ignored, and what he typed or sent since is left alone (voice/Words.kt).
            val writer = com.past9.phoneaos.voice.BoxWriter(box.draft) { android.util.Log.i("AosDictation", it) }
            fun write(t: String) { writer.next(box.draft, t)?.let { box.draft = it } }
            val done: (String?) -> Unit = { err -> runOnUiThread { dictating = false; dictation = null; if (err != null) say(err) } }
            // Since 0.15.17 the app records the mic itself, saves it as it goes, and only his tap ends it: the phone's
            // recogniser is fed from that audio, and whatever it drops (mobile data, silence) is written down from the
            // saved audio (voice/DictationEngine.kt). The older paths below are for phones that can do neither.
            if (com.past9.phoneaos.voice.DictationHub.supported(this@MainActivity)) {
                val owner: Any = if (box === mainBox) "main" else box
                if (com.past9.phoneaos.voice.DictationHub.start(this@MainActivity, owner, box.key) { t -> write(t) }) return
            }
            if (com.past9.phoneaos.voice.SystemDictation.available(this@MainActivity)) {
                val d = com.past9.phoneaos.voice.SystemDictation(this@MainActivity, onText = { t -> runOnUiThread { write(t) } }, onEnd = done)
                dictation = d; dictating = true; d.start(); return
            }
            val key = g.settings.voiceKey("gemini")
            if (key == null || !online()) { say("This phone has no speech recogniser, so the mic can't listen. Install Google's Speech Services, or add a Gemini key in Settings, Voice."); return }
            say("This phone has no speech recogniser, so your words go to Gemini to be written down.")
            val vocab = listOf(settings.agentName, settings.userName).filter { it.isNotBlank() && it != "Your agent" }
            val d = com.past9.phoneaos.voice.Dictation(key, vocab, onText = { t -> runOnUiThread { write(t) } }, onEnd = done)
            dictation = d; dictating = true; d.start()
        }
        val dictPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startDictation() else say("The mic needs the microphone permission.") }
        fun toggleMic(box: com.past9.phoneaos.ui.ComposerBox = mainBox) {
            if (com.past9.phoneaos.voice.DictationHub.active) { com.past9.phoneaos.voice.DictationHub.stop(); return }
            val d = dictation
            if (d != null) { d.finish(); return }
            // A dictation that kept unsent audio: a new one starts fresh (that audio stays on the phone).
            com.past9.phoneaos.voice.DictationHub.dismiss()
            dictBox = box
            if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startDictation()
            else dictPerm.launch(Manifest.permission.RECORD_AUDIO)
        }
        val composerTools = remember {
            object : com.past9.phoneaos.ui.ComposerTools {
                override fun pickPhotos(box: com.past9.phoneaos.ui.ComposerBox) { boxFor = box; picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                override fun pickDocs(box: com.past9.phoneaos.ui.ComposerBox) { boxFor = box; docPicker.launch(arrayOf("*/*")) }
                override fun camera(box: com.past9.phoneaos.ui.ComposerBox, origin: androidx.compose.ui.geometry.Rect) { boxFor = box; cameraOrigin = origin }
                override fun mic(box: com.past9.phoneaos.ui.ComposerBox) = toggleMic(box)
                override fun listening(box: com.past9.phoneaos.ui.ComposerBox): Boolean =
                    (dictating && (dictBox ?: mainBox) === box) || (hubUi?.view?.ended == false && hubUi?.owner == (if (box === mainBox) "main" else box))
                override fun recordings(box: com.past9.phoneaos.ui.ComposerBox) { nav.navigate("recordings?box=" + Uri.encode(box.key)) }
            }
        }
        fun refreshConnections() = scope.launch {
            // Linked to a computer: its connected apps are the phone's apps. Read when this screen opens, small, and no key is asked for.
            val linked = com.past9.phoneaos.remote.RemoteHub.session.value
            if (linked != null) {
                conn = conn.copy(remote = true, hasKey = true, loading = true, error = null, notificationsAllowed = notificationsAllowed())
                try { val (c, t) = linked.connectedApps(); conn = conn.copy(connected = c, toolkits = t, loading = false) }
                catch (e: Exception) { conn = conn.copy(loading = false, error = "Couldn't read the apps from your computer: ${e.message}") }
                // His machines and the computer's learned apps: small lists, read when the screen opens.
                runCatching { linked.meshDevices() }.onSuccess { conn = conn.copy(devices = it) }
                runCatching { linked.learnedApps() }.onSuccess { conn = conn.copy(learned = it) }
                runCatching { linked.computerProfiles() }.onSuccess { conn = conn.copy(remoteProfiles = it) }
                return@launch
            }
            val has = g.settings.composioKey() != null
            conn = conn.copy(remote = false, hasKey = has, loading = has, error = null, notificationsAllowed = notificationsAllowed())
            if (!has) return@launch
            val ck = g.settings.composioKey()?.trim()
            if (ck != null && com.past9.phoneaos.tools.ComposioConnect.isConsumerKey(ck)) {
                // Composio Connect can't list its catalogue: show the popular apps (plus anything found by search) and ask for their status.
                val cc = com.past9.phoneaos.tools.ComposioConnect
                // Plus the account's own apps and anything connected beyond the popular list, as Composio's replies showed them.
                val catalog = com.past9.phoneaos.tools.AppCatalog
                val apps = (catalog.popular + catalog.known(g.settings.composioApps()) + conn.toolkits).distinctBy { it.slug }
                conn = conn.copy(consumer = true, toolkits = apps)
                fun rows(st: Map<String, List<com.past9.phoneaos.tools.ComposioConnect.Account>>) = st.values.flatten().filter { it.status == "ACTIVE" }
                    .map { a -> com.past9.phoneaos.tools.Connection(a.id, a.slug, a.status, a.label.ifBlank { a.slug }) }
                try {
                    val st = cc.accounts(ck, apps.map { it.slug })
                    // A reconnected app has a new account id: profiles move onto it.
                    st.forEach { (slug, accs) -> g.runtime.profiles.followReconnected(slug, accs.filter { it.status == "ACTIVE" }.map { com.past9.phoneaos.data.AccountRef(it.id, slug, it.label.ifBlank { com.past9.phoneaos.tools.AppCatalog.name(slug) }) }) }
                    // One row per signed-in account: four Gmails show as four lines, each with its email.
                    conn = conn.copy(loading = false, connected = rows(st))
                    // A started-but-unfinished sign-in is just an expired link here: the app stays in the list with Connect.
                } catch (e: Exception) { conn = conn.copy(loading = false, error = "Couldn't reach Composio: ${e.message}") }
                // Then look for apps made for this account (they're in no catalogue) and add any new ones with their accounts.
                runCatching {
                    cc.discoverOwnApps(ck)
                    val found = catalog.known(g.settings.composioApps()).filter { t -> conn.toolkits.none { it.slug == t.slug } }
                    if (found.isNotEmpty()) conn = conn.copy(toolkits = conn.toolkits + found, connected = conn.connected + rows(cc.accounts(ck, found.map { it.slug })))
                }
                return@launch
            }
            try {
                val c = g.runtime.composio.connections(); conn = conn.copy(connected = c)
                val t = g.runtime.composio.toolkits(); conn = conn.copy(toolkits = t, loading = false)
            } catch (e: Exception) { conn = conn.copy(loading = false, error = "Couldn't reach Composio: ${e.message}") }
        }
        fun go(r: String) { if (r == "home") nav.popBackStack("home", false) else nav.navigate(r) { popUpTo("home"); launchSingleTop = true } }
        val cards by g.cards.cards.collectAsStateWithLifecycle()
        val miniApps by com.past9.phoneaos.miniapps.MiniAppsHub.apps.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { com.past9.phoneaos.miniapps.MiniAppsHub.pendingOpen.collect { id -> if (id != null) { nav.navigate("miniapp/$id") { launchSingleTop = true }; com.past9.phoneaos.miniapps.MiniAppsHub.pendingOpen.value = null } } }
        var openCard by remember { mutableStateOf<String?>(null) }
        val needStore = remember { NeedStore.of(applicationContext) }
        var refreshingCard by remember { mutableStateOf<String?>(null) }
        val home = HomeState(settings.agentName, settings.userName, status, items, goals, tasks, routines,
            notifRows.count { it.postedAt > System.currentTimeMillis() - 86_400_000L }, settings.notifApps.size, browserLive, cards = cards.filter { it.pinned }, miniApps = miniApps)
        val barRoutes = setOf("home", "tasks", "memory", "routines", "connections")
        // The floating bar sits above the system navigation inset. Pages built on SubScreen already get that inset from
        // their scaffold; Home draws to the screen edge, so it adds the inset itself (3-button navigation is 48dp).
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        var barHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val barPad = com.past9.phoneaos.ui.barClearance(with(androidx.compose.ui.platform.LocalDensity.current) { barHeightPx.toDp() }, navInset)

        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
        // Linked: no bar at all while something answers (failover is silent); a bar only when nothing can.
        remote?.let { com.past9.phoneaos.remote.RemoteTroubleBar(it, onDevices = { nav.navigate("linked") { launchSingleTop = true } }) }
        callWhy?.let { why ->
            com.past9.phoneaos.ui.ConfirmSheet(androidx.compose.material.icons.Icons.Rounded.Call, "Calls", "Calls need a Google key", why,
                confirm = "Open Settings", onConfirm = { callWhy = null; nav.navigate("settings") { launchSingleTop = true } }, onDismiss = { callWhy = null })
        }
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().weight(1f)
            .then(if (linkState?.let { com.past9.phoneaos.remote.RemoteTrouble.shows(it) } == true) androidx.compose.ui.Modifier.consumeWindowInsets(WindowInsets.statusBars) else androidx.compose.ui.Modifier)) {
            androidx.compose.runtime.CompositionLocalProvider(com.past9.phoneaos.ui.LocalComposerTools provides composerTools,
                com.past9.phoneaos.ui.LocalNewProject provides (if (projectsApi != null) ({ newProject = true }) else null)) {
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                        HomeScreen(home, HomeActions(
                            onProfile = { nav.navigate("profile") }, onChat = { nav.navigate("chat") },
                            onAnswer = { id, o -> g.runtime.answer(id, o) },
                            onToggleTask = { t -> ops.updateTask(t.copy(status = if (t.status == "done") "todo" else "done", updatedAt = System.currentTimeMillis())) },
                            onTasks = { go("tasks") }, onGoal = { nav.navigate("profile") }, onRoutines = { go("routines") },
                            onSettings = { nav.navigate("settings") }, onCall = { startCall() }, onBrowser = { nav.navigate("browser") }, onNotifications = { nav.navigate("notifications") },
                            onCard = { openCard = it.id },
                            onMiniApp = { nav.navigate("miniapp/$it") }, onMiniApps = { nav.navigate("miniapps") },
                            onNeed = { t -> when (t) {
                                is NeedTarget.ChatAt -> { ChatFocus.id.value = t.itemId; nav.navigate("chat") }
                                is NeedTarget.ProjectChat -> openFromCard = t.chat to "Project chat"
                                is NeedTarget.Task -> { TaskFocus.target = t; go("tasks") }
                                is NeedTarget.Gone -> Unit
                            } },
                            needs = needStore,
                        ), bottomPadding = barPad + 24.dp + navInset)
                    }
                }
                composable("profile") {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                        ProfileScreen(home, memories, ProfileActions(onBack = { nav.popBackStack() }, onChat = { nav.navigate("chat") },
                            onMemory = { nav.navigate("memory") }, onRoutines = { go("routines") }, onStyle = { nav.navigate("style") }, onGoals = { go("tasks") },
                            onProjectChat = { chat, name -> nav.navigate("pchat/" + Uri.encode(chat) + "?name=" + Uri.encode(name)) },
                            onNewMainChat = { g.runtime.clearConversation(); nav.navigate("chat") }))
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
                composable("recordings?box={box}") { e ->
                    com.past9.phoneaos.ui.screens.RecordingsScreen(e.arguments?.getString("box")?.ifBlank { null } ?: "main", onBack = { nav.popBackStack() })
                }
                composable("chat") {
                    suspend fun beforeSend() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { g.stopSpeaking(); com.past9.phoneaos.triggers.Overnight.dismissMorning(this@MainActivity) }
                    val mainActs = com.past9.phoneaos.ui.composerActionsFor(mainBox, composerTools, status.working, g.scope, send = { text, pics, files ->
                        beforeSend()
                        g.runtime.sendAccepted(text, pics, files)
                    }, stop = { g.runtime.stop() }, sendVoice = { text, pics, files ->
                        beforeSend()
                        g.runtime.sendAccepted(text, pics, files, voiceReply = true)
                    })
                    ChatScreen(items, status, settings.agentName, settings.userName, browserLive, draft, { draft = it },
                        listening = composerTools.listening(mainBox), live = liveUi,
                        readMark = { g.settings.extra("chatRead")?.toLongOrNull() ?: Long.MAX_VALUE },
                        onRead = { id -> if (id > (g.settings.extra("chatRead")?.toLongOrNull() ?: 0L)) g.settings.setExtra("chatRead", id.toString()) },
                        actions = ChatActions(
                            composerSend = mainActs.onSend, composerVoice = mainActs.onSendVoice, sending = { mainBox.sending }, onRecordings = mainActs.onRecordings,
                            composerFailed = mainBox.failed, onComposerRetry = mainBox.retry,
                            onCard = { openCard = it },
                            onAct = { g.stopSpeaking(); com.past9.phoneaos.triggers.Overnight.dismissMorning(this@MainActivity); g.runtime.sendQuiet(it) },
                            onSend = { g.stopSpeaking(); com.past9.phoneaos.triggers.Overnight.dismissMorning(this@MainActivity)
                                val docText = docs.entries.joinToString("") { (n, d) -> "\n\n[Attached document: $n${docPaths[n]?.let { p -> ", file saved at $p (you can upload it with browser_upload)" }.orEmpty()}]\n${d.first.take(30_000)}" }
                                g.runtime.send(it + docText, attachments.toList() + docs.values.flatMap { d -> d.second }.take(12), docPaths.values.toList())
                                attachments.clear(); docs.clear() },
                            onSendVoice = { g.stopSpeaking(); com.past9.phoneaos.triggers.Overnight.dismissMorning(this@MainActivity)
                                val docText = docs.entries.joinToString("") { (n, d) -> "\n\n[Attached document: $n${docPaths[n]?.let { p -> ", file saved at $p (you can upload it with browser_upload)" }.orEmpty()}]\n${d.first.take(30_000)}" }
                                g.runtime.send(it + docText, attachments.toList() + docs.values.flatMap { d -> d.second }.take(12), docPaths.values.toList(), voiceReply = true)
                                attachments.clear(); docs.clear() }, onStop = { g.runtime.stop() },
                            onAttachDoc = { composerTools.pickDocs(mainBox) }, onCamera = { composerTools.camera(mainBox, it) }, onRemoveDoc = { docs.remove(it); docPaths.remove(it) },
                            onAttach = { composerTools.pickPhotos(mainBox) },
                            onRemoveAttachment = { attachments.remove(it) }, onModel = { openModels() }, onCall = { startCall() }, callReady = settings.let { g.call.unavailableReason(remote != null) == null },onStopSpeaking = if (speakingNow) ({ g.stopSpeaking() }) else null,
                            onAnswer = { id, o -> g.runtime.answer(id, o) },
                            onReport = { path -> nav.navigate("report?path=" + Uri.encode(path)) },
                            onOpenChat = { chat, name -> nav.navigate("pchat/" + Uri.encode(chat) + "?name=" + Uri.encode(name)) },
                            onMenu = { nav.popBackStack() }, onBrowser = { nav.navigate("browser") }, onProfile = { nav.navigate("profile") },
                            onMic = { toggleMic(mainBox) },
                        ), openCount = tasks.count { it.status != "done" }, attachments = attachments, modelLabel = modelLabel, docs = docs.keys.toList(),
                        morning = if (!morningNow) null else (brief?.takeIf { it.ideas.isNotEmpty() }?.let { b ->
                            com.past9.phoneaos.ui.screens.MorningUi(b.greeting, b.line, b.body, b.ideas.map { it.icon to it.text }, preparingBrief, { com.past9.phoneaos.triggers.Overnight.dismissMorning(this@MainActivity) })
                        } ?: com.past9.phoneaos.ui.screens.MorningUi(preparing = preparingBrief, onBackToChat = { com.past9.phoneaos.triggers.Overnight.dismissMorning(this@MainActivity) })))
                }
                composable("pchat/{chat}?name={name}") { e ->
                    com.past9.phoneaos.ui.screens.ProjectChatScreen(e.arguments?.getString("chat").orEmpty(), e.arguments?.getString("name").orEmpty(),
                        onBack = { if (!nav.popBackStack("chat", inclusive = false)) { nav.popBackStack(); nav.navigate("chat") { launchSingleTop = true } } }, live = liveUi)
                }
                composable("report?path={path}") { e ->
                    // A button in a report's cards asks the agent, like in the chat.
                    androidx.compose.runtime.CompositionLocalProvider(com.past9.phoneaos.ui.openui.LocalOpenUiSend provides { msg -> g.runtime.send(msg); nav.navigate("chat") },
                        com.past9.phoneaos.ui.openui.LocalOpenUiAct provides { msg -> g.runtime.sendQuiet(msg); nav.navigate("chat") }) {
                        ReportScreen(e.arguments?.getString("path").orEmpty(), onBack = { nav.popBackStack() })
                    }
                }
                composable("tasks") {
                    LaunchedEffect(Unit) { com.past9.phoneaos.remote.RemoteHub.session.value?.need("task", "goal") }
                    TasksScreen(goals, tasks, bottomPadding = barPad, actions = TaskActions(
                        onBack = { nav.popBackStack() },
                        onToggle = { t -> ops.updateTask(t.copy(status = if (t.status == "done") "todo" else "done", updatedAt = System.currentTimeMillis())) },
                        onAdd = { text, due -> ops.insertTask(TaskRow(title = text.trim(), owner = "user", dueAt = due)) },
                        onDelete = { t -> ops.deleteTask(t.id) },
                        onAsk = { prompt -> g.runtime.send(prompt); nav.navigate("chat") },
                    ))
                }
                composable("memory") {
                    LaunchedEffect(Unit) { com.past9.phoneaos.remote.RemoteHub.session.value?.need("memory") }
                    // A landscape tablet shows the list and a page side by side; a phone opens the page full screen.
                    val twoPane = com.past9.phoneaos.ui.widthClass() == com.past9.phoneaos.ui.WidthClass.EXPANDED
                    var openId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
                    val memoryActions = MemoryActions(
                        onBack = { nav.popBackStack() },
                        onOpen = { m -> if (twoPane) openId = m.id else nav.navigate("page/${m.id}") },
                        onSave = { m -> ops.saveMemory(m) },
                        onDelete = { m -> ops.deleteMemory(m.id) },
                        onExport = { uri, done -> scope.launch {
                            val note = runCatching { this@MainActivity.contentResolver.openOutputStream(uri)!!.use { com.past9.phoneaos.data.MemoryTransfer.export(g.db.memory(), it) } }
                                .fold({ com.past9.phoneaos.ui.screens.TransferNote.exported(it) }, { com.past9.phoneaos.ui.screens.TransferNote.failed("Couldn't save the zip", it.message) })
                            done(note) } },
                        onImport = { uri, done -> scope.launch {
                            val name = runCatching { this@MainActivity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: uri.lastPathSegment ?: "memory.zip"
                            val note = runCatching { this@MainActivity.contentResolver.openInputStream(uri)!!.use { com.past9.phoneaos.data.MemoryTransfer.importFrom(name, it, g.db.memory()) } }
                                .fold({ com.past9.phoneaos.ui.screens.TransferNote.imported(it) }, { com.past9.phoneaos.ui.screens.TransferNote.failed("Couldn't read that file", it.message) })
                            done(note) } },
                    )
                    if (!twoPane) MemoryScreen(memories, bottomPadding = barPad, actions = memoryActions)
                    else MemoryTwoPane(memories, openId, { openId = it }, memoryActions, barPad,
                        onOpenTitle = { t -> val target = memories.firstOrNull { it.title.equals(t, true) }
                            if (target != null) openId = target.id else { g.runtime.send("Start a memory page about $t with what you know."); nav.navigate("chat") } },
                        onAsk = { p -> g.runtime.send(p); nav.navigate("chat") })
                }
                composable("page/{id}") { e ->
                    val id = e.arguments?.getString("id")?.toLongOrNull()
                    WikiPageScreen(memories.firstOrNull { it.id == id }, memories, onBack = { nav.popBackStack() },
                        onOpenTitle = { t -> val target = memories.firstOrNull { it.title.equals(t, true) }
                            if (target != null) nav.navigate("page/${target.id}") else { g.runtime.send("Start a memory page about $t with what you know."); nav.navigate("chat") } },
                        onSave = { m -> ops.saveMemory(m) },
                        onDelete = { m -> ops.deleteMemory(m.id); nav.popBackStack() },
                        onAsk = { p -> g.runtime.send(p); nav.navigate("chat") })
                }
                composable("routines") {
                    LaunchedEffect(Unit) { com.past9.phoneaos.remote.RemoteHub.session.value?.need("trigger") }
                    RoutinesScreen(routines, bottomPadding = barPad, actions = RoutineActions(
                        onBack = { nav.popBackStack() },
                        onToggle = { r -> ops.saveRoutine(r.copy(enabled = !r.enabled)) },
                        onRunNow = { r -> ops.runRoutine(r.id) },
                        onDelete = { r -> ops.deleteRoutine(r.id) },
                        onSave = { r -> ops.saveRoutine(r) },
                    ))
                }
                composable("connections") {
                    // Read when the screen opens and when he comes back from the sign-in in the browser; never in the background.
                    val lifeState = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle().value
                    LaunchedEffect(lifeState) { if (lifeState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) refreshConnections() }
                    pendingRemote?.let { (slug, profs) ->
                        // One slide-up sheet of the computer's profiles. 'No profile' IS General (an account in no profile is
                        // in General), so it is offered once, as General.
                        com.past9.phoneaos.ui.ChoiceSheet(androidx.compose.material.icons.Icons.Rounded.Apps, com.past9.phoneaos.tools.AppCatalog.name(slug), "Which profile is it for?",
                            com.past9.phoneaos.ui.screens.profileChoices(profs),
                            onPick = { c -> pendingRemote = null; com.past9.phoneaos.remote.RemoteHub.session.value?.let { rs -> scope.launch { runCatching { rs.connectApp(slug, c.id) }.onSuccess { open(it) }.onFailure { conn = conn.copy(error = "Couldn't start the sign-in on your computer: ${it.message}") } } } },
                            onDismiss = { pendingRemote = null }, line = "Its account goes into that part of your life. You can change it later.")
                    }
                    val machines by g.runtime.machines.store.machines.collectAsStateWithLifecycle()
                    val learnedApps by g.runtime.learned.apps.collectAsStateWithLifecycle()
                    // Re-read on every return to this screen: the user may have just switched All files access on.
                    val resumed = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle().value
                    val phoneFiles = remember(resumed) { com.past9.phoneaos.tools.phoneFilesAllowed() }
                    ConnectionsScreen(conn.copy(machines = machines, phoneFiles = phoneFiles, profiles = profileList, rules = accountRules, browserProfiles = settings.browserProfiles, learned = if (conn.remote) conn.learned else learnedApps, miniApps = miniApps), bottomPadding = barPad + 24.dp, actions = ConnectionsActions(
                        onMiniApps = { nav.navigate("miniapps") { launchSingleTop = true } }, onLinkedDevices = { nav.navigate("linked") { launchSingleTop = true } },
                        profile = profileActions,
                        onTrustLearned = { id, on -> g.runtime.learned.setTrusted(id, on) },
                        onRemoveLearned = { id -> g.runtime.learned.remove(id) },
                        onTeach = { what -> g.runtime.send("Teach yourself this web app so you can use it without clicking through it every time: $what. Read webapp_guide first, check webapp_list, then map it in the browser and save the tools you write with webapp_save."); nav.navigate("chat") },
                        onKeepOne = { keep, remove -> scope.launch {
                            val ck = g.settings.composioKey()?.trim()
                            remove.forEach { c ->
                                if (ck != null && com.past9.phoneaos.tools.ComposioConnect.isConsumerKey(ck)) runCatching { com.past9.phoneaos.tools.ComposioConnect.disconnect(ck, c.toolkit, c.id) }
                                else runCatching { g.runtime.composio.disconnect(c.id) }
                                // The copy's profiles and rules move to the one that stays.
                                ps.profilesWith(c.id).forEach { p -> ps.setMember(p.id, keep.ref(), true) }
                                ps.forgetAccount(c.id)
                            }
                            refreshConnections() } },
                        onNotifications = { nav.navigate("notifications") },
                        onBack = { nav.popBackStack() },
                        onSaveKey = { k -> saveComposio(g, k).also { if (it == null) refreshConnections() } },
                        onRemoveKey = { g.settings.setComposioKey(null); conn = ConnectionsState(hasKey = false) },
                        onLoadCatalog = { com.past9.phoneaos.remote.RemoteHub.session.value?.let { rs -> scope.launch { runCatching { rs.appCatalog() }.onSuccess { c -> conn = conn.copy(consumer = true, toolkits = (c + conn.toolkits).distinctBy { it.slug }) } } } },
                        onConnect = { slug -> scope.launch {
                            val linkedRs = com.past9.phoneaos.remote.RemoteHub.session.value
                            if (linkedRs != null) {
                                val profs = runCatching { linkedRs.agentProfiles() }.getOrDefault(emptyList())
                                if (profs.isNotEmpty()) pendingRemote = slug to profs
                                else runCatching { linkedRs.connectApp(slug, "") }.onSuccess { open(it) }.onFailure { conn = conn.copy(error = "Couldn't start the sign-in on your computer: ${it.message}") }
                                return@launch
                            }
                            val ck = g.settings.composioKey()?.trim()
                            if (ck != null && com.past9.phoneaos.tools.ComposioConnect.isConsumerKey(ck)) {
                                val beforeIds = conn.connected.filter { it.toolkit == slug && it.status == "ACTIVE" }.map { it.id }.toSet(); val before = beforeIds.size
                                runCatching { com.past9.phoneaos.tools.ComposioConnect.connectLink(ck, slug) }.onSuccess { open(it) }.onFailure { conn = conn.copy(error = it.message) }
                                // Pick up the new account as soon as the sign-in finishes, without a manual refresh.
                                repeat(60) { kotlinx.coroutines.delay(5000)
                                    val now = runCatching { com.past9.phoneaos.tools.ComposioConnect.accounts(ck, listOf(slug))[slug].orEmpty().filter { it.status == "ACTIVE" } }.getOrDefault(emptyList())
                                    if (now.size > before) {
                                        val fresh = now.firstOrNull { it.id !in beforeIds }
                                        // The same account again (same email or name, or the app names nobody): keep the one there was.
                                        if (fresh != null && now.any { it.id in beforeIds && com.past9.phoneaos.tools.ComposioConnect.sameAccount(it, fresh) }) {
                                            runCatching { com.past9.phoneaos.tools.ComposioConnect.disconnect(ck, slug, fresh.id) }
                                            conn = conn.copy(error = "That ${com.past9.phoneaos.tools.AppCatalog.name(slug)} account was already connected, so nothing changed.")
                                            refreshConnections(); return@launch
                                        }
                                        refreshConnections()
                                        // Straight away: which part of their life is this account for?
                                        fresh?.let { a -> justConnected = com.past9.phoneaos.data.AccountRef(a.id, slug, a.label.ifBlank { com.past9.phoneaos.tools.AppCatalog.name(slug) }) }
                                        return@launch
                                    } }
                            } else {
                                val beforeIds = runCatching { g.runtime.composio.connections() }.getOrDefault(emptyList()).filter { it.toolkit == slug && it.status == "ACTIVE" }.map { it.id }.toSet()
                                runCatching { g.runtime.composio.connect(slug) }.onSuccess { open(it) }.onFailure { conn = conn.copy(error = it.message); return@launch }
                                repeat(60) { kotlinx.coroutines.delay(5000)
                                    val fresh = runCatching { g.runtime.composio.connections() }.getOrDefault(emptyList()).firstOrNull { it.toolkit == slug && it.status == "ACTIVE" && it.id !in beforeIds }
                                    if (fresh != null) { refreshConnections(); justConnected = com.past9.phoneaos.data.AccountRef(fresh.id, slug, fresh.label); return@launch } }
                            }
                        } },
                        onDisconnect = { c -> scope.launch {
                            val ck = g.settings.composioKey()?.trim()
                            if (ck != null && com.past9.phoneaos.tools.ComposioConnect.isConsumerKey(ck)) runCatching { com.past9.phoneaos.tools.ComposioConnect.disconnect(ck, c.toolkit, c.id) }.onFailure { conn = conn.copy(error = it.message) }
                            else runCatching { g.runtime.composio.disconnect(c.id) }
                            ps.forgetAccount(c.id)
                            refreshConnections() } },
                        machine = machineActions(g),
                        onAllowPhoneFiles = { com.past9.phoneaos.tools.openAllFilesSettings(this@MainActivity) },
                        onSearchAll = { q -> scope.launch {
                            val linkedRs = com.past9.phoneaos.remote.RemoteHub.session.value
                            if (linkedRs != null) {
                                conn = conn.copy(searching = true)
                                val hits = runCatching { linkedRs.searchApps(q) }.getOrDefault(emptyList())
                                conn = conn.copy(searching = false, searched = q, toolkits = (conn.toolkits + hits).distinctBy { it.slug }, hits = hits.map { it.slug }.toSet())
                                return@launch
                            }
                            val ck = g.settings.composioKey()?.trim() ?: return@launch
                            conn = conn.copy(searching = true, error = null)
                            runCatching { com.past9.phoneaos.tools.ComposioConnect.searchApps(ck, q) }.onSuccess { found ->
                                conn = conn.copy(searching = false, searched = q, hits = found.map { it.first.slug }.toSet(), toolkits = (conn.toolkits + found.map { it.first }).distinctBy { it.slug })
                                if (found.any { it.second }) refreshConnections() // a connected app we didn't know about: fetch its account
                            }.onFailure { conn = conn.copy(searching = false, searched = q, error = "Search failed: ${it.message}") }
                        } },
                        onRefresh = { refreshConnections() }, onOpenUrl = ::open,
                        onAllowNotifications = { if (com.past9.phoneaos.system.Background.canAskNotifications(this@MainActivity)) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else com.past9.phoneaos.system.Background.openNotificationSettings(this@MainActivity) },
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
                        onModel = { openModels() }, onHelperModel = { openModels(forHelpers = true) },
                        voice = VoiceActions(
                            setTts = { p, v -> g.settings.setTts(p, v) }, setKey = { p, k -> g.settings.setVoiceKey(p, k) },
                            hasKey = { p -> g.settings.voiceKey(p) != null }, setSpeak = g.settings::setSpeakReplies, setLive = g.settings::setLive, setAutoPlay = g.settings::setAutoPlayEarphones,
                            preview = { g.speak("Hi ${settings.userName.ifBlank { "there" }}, this is how I sound.") }),
                        onChooseSub = { k ->
                            if (k == com.past9.phoneaos.data.SubKind.OPENCODE) { g.settings.setProvider(Provider.OPENCODE_GO); nav.navigate("key") }
                            else { g.settings.setSubKind(k); g.settings.setMode(PowerMode.SUBSCRIPTION); nav.navigate("sub") } },
                        onPinHome = { com.past9.phoneaos.system.Identity.pinNamedShortcut(this@MainActivity, settings.agentName, settings.mascot, settings.accent) },
                        onClearChat = { g.runtime.clearConversation() },
                        onResetAll = { scope.launch { g.automation.stop(); g.runtime.stop(); g.db.clearAllTables(); g.settings.resetAll() } },
                        onBattery = { runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } },
                        onLinked = { nav.navigate("linked") },
                        onRecordings = { nav.navigate("recordings?box=main") },
                        linkedLine = com.past9.phoneaos.remote.RemoteHub.link.collectAsStateWithLifecycle().value.let { l ->
                            when { l == null -> "Use the agent on your computer from this phone"; l.on -> "Using ${l.nodeName.ifBlank { "your computer" }}"; else -> "Linked to ${l.nodeName.ifBlank { "a computer" }}, using this phone's own agent" } },
                        setAutomationAllowed = { on -> g.settings.setAutomationAllowed(on) },
                        rotateAutomationToken = { g.settings.rotateAutomationToken() },
                        macro = com.past9.phoneaos.ui.screens.MacroActions(
                            allowlist = g.settings::macroAllowlistText, setAllowlist = g.settings::setMacroAllowlistText,
                            webhookOn = g.settings::macroWebhookOn, setWebhookOn = g.settings::setMacroWebhookOn,
                            webhookId = g.settings::macroWebhookId, setWebhookId = g.settings::setMacroWebhookId,
                            intents = g.settings::intentAllowlistText, setIntents = g.settings::setIntentAllowlistText,
                            testMaps = { com.past9.phoneaos.automation.IntentSender(this@MainActivity) { true }.send(com.past9.phoneaos.automation.IntentSpec(
                                com.past9.phoneaos.automation.IntentRules.ACTIVITY, Intent.ACTION_VIEW, data = "geo:0,0?q=Sandton+City")) },
                            test = { name -> com.past9.phoneaos.automation.MacroSender(this@MainActivity).broadcast(com.past9.phoneaos.automation.MacroCall(name, v1 = "hello")) },
                        ),
                        browser = com.past9.phoneaos.ui.screens.BrowserSettingsActions(
                            origins = g.settings::isolatedOrigins,
                            addOrigin = { typed -> g.settings.addIsolatedOrigin(typed).also { if (it == null) com.past9.phoneaos.browser.BrowserService.applyIsolation(g.settings.isolatedOrigins()) } },
                            removeOrigin = { origin -> g.settings.removeIsolatedOrigin(origin); com.past9.phoneaos.browser.BrowserService.applyIsolation(g.settings.isolatedOrigins()) },
                            callVoiceOn = g.settings::callBridgeOn, setCallVoiceOn = g.settings::setCallBridgeOn,
                        ),
                    ),
                        automation = com.past9.phoneaos.ui.screens.AutomationInfo(
                            url = g.automation.url.ifBlank { "http://127.0.0.1:" + g.settings.automationPort() },
                            token = g.settings.automationToken(),
                            allowed = g.settings.automationAllowed(),
                            running = g.automation.running,
                        ),
                    )
                }
                composable("linked") { com.past9.phoneaos.remote.LinkedDevicesScreen(onBack = { nav.popBackStack() }) }
                composable("key") {
                    var p by remember { mutableStateOf(settings.provider) }
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.systemBarsPadding().imePadding()) {
                            // Changing the key of an Other server: its address and model are already filled in.
                            val other = p == Provider.CUSTOM && settings.provider == Provider.CUSTOM
                            KeyStep(p, { p = it }, onboardingActions(g), initialBaseUrl = if (other) settings.baseUrl else "", initialModel = if (other) settings.model else "") {
                                g.settings.setProvider(p); g.settings.setMode(PowerMode.API_KEY); nav.popBackStack() }
                        }
                    }
                }
                composable("sub") {
                    SubSetupFlow(g, settings.subKind, onBack = { nav.popBackStack() }, onDone = { nav.navigate("chat") { popUpTo("home") } },
                        onUseKey = { g.settings.setMode(PowerMode.API_KEY); nav.navigate("key") }, modelLabel = modelLabel, onModel = { openModels() })
                }
                composable("call") {
                    val cst by g.call.state.collectAsStateWithLifecycle()
                    CallScreen(settings.agentName, cst, onMute = { g.call.toggleMute() }, onEnd = { g.call.end(); nav.popBackStack() }, onStart = { g.call.start() }, onRoute = { g.call.nextRoute() },
                        actions = items.filter { it.kind == "activity" && it.createdAt >= g.call.callStart && g.call.callStart > 0 }.map { it.text },
                        // Any card waiting on him (a question or an approval, from the agent or a helper), linked or not.
                        question = com.past9.phoneaos.voice.CallController.openQuestion(items),
                        onAnswer = { id, o -> g.runtime.answer(id, o) }, onBrowser = { nav.navigate("browser") { launchSingleTop = true } })
                }
                composable("style") {
                    com.past9.phoneaos.ui.SubScreen("Make it yours", "Colour and sidekick", { nav.popBackStack() }) { pad ->
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 48.dp)) {
                            StylePicker(settings.userName, settings.agentName, settings.accent, settings.mascot, g.settings::setAccent, g.settings::setMascot)
                        }
                    }
                }
                composable("miniapps") {
                    com.past9.phoneaos.miniapps.MiniAppsScreen(miniApps, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("miniapp/$it") },
                        onPin = { id, on -> g.scope.launch { runCatching { com.past9.phoneaos.miniapps.MiniAppsHub.pin(id, on) } } })
                }
                composable("miniapp/{id}") { e ->
                    com.past9.phoneaos.miniapps.MiniAppFrame(e.arguments?.getString("id").orEmpty(), miniApps, androidx.compose.foundation.isSystemInDarkTheme(), settings.accent, onBack = { nav.popBackStack() })
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
            }
            justConnected?.let { a ->
                com.past9.phoneaos.ui.screens.AddToProfileSheet(a, conn.toolkits.firstOrNull { it.slug == a.slug }?.logo ?: com.past9.phoneaos.tools.AppCatalog.logo(a.slug),
                    accountRules[a.id] ?: com.past9.phoneaos.data.AccountRules.DEFAULT, profileList, profileActions) { justConnected = null }
            }
            cards.firstOrNull { it.id == openCard }?.let { c ->
                CardSheet(c, androidx.compose.foundation.isSystemInDarkTheme(), settings.accent, CardSheetActions(
                    onRefresh = { card -> refreshingCard = card.id; scope.launch {
                        val r = com.past9.phoneaos.cards.CardScripts.run(this@MainActivity, card)
                        g.cards.update(card.id) { x -> if (r.ok) x.withData(r.data!!) else x.copy(error = r.log.takeLast(400)) }
                        refreshingCard = null } },
                    onPin = { card, on -> g.cards.update(card.id) { it.copy(pinned = on) } },
                    onAsk = { card, text -> openCard = null; g.runtime.send("[About the card \"${card.title}\" (id ${card.id})] $text"); nav.navigate("chat") },
                    onAct = { card, text -> openCard = null; g.runtime.sendQuiet("[About the card \"${card.title}\" (id ${card.id})] $text"); nav.navigate("chat") },
                    onOpenLink = { url -> scope.launch { com.past9.phoneaos.system.UiBus.openInBrowser.emit(com.past9.phoneaos.system.UiBus.OpenInBrowser(url)) } },
                    onRun = { card, input -> val r = com.past9.phoneaos.cards.CardScripts.run(this@MainActivity, card, input)
                        if (r.ok) { g.cards.update(card.id) { it.withData(r.data!!) }; true to r.data!! } else false to org.json.JSONObject().put("error", r.log.takeLast(300)).toString() },
                ), onDismiss = { openCard = null }, refreshing = refreshingCard == c.id)
            }
            if (modelSheet && pickingHelper) HelperRosterSheet(
                if (onSub) modelList?.map { if (it.id.isBlank()) it.copy(name = "Same as your agent", note = "Whatever your agent runs on") else it } else modelList,
                modelErr, g.runtime.helperRoster().filter { settings.helperRoster.isNotEmpty() },
                onSave = { g.settings.setHelperRoster(it) }, onDismiss = { modelSheet = false }, kept = g.runtime.pins.agents.collectAsStateWithLifecycle().value)
            else if (modelSheet) ModelPickerSheet(
                if (pickingHelper) "Helper model" else if (onSub) "${settings.subKind.label} model" else "${settings.provider.label} model",
                if (pickingHelper && onSub) modelList?.map { if (it.id.isBlank()) it.copy(name = "Same as your agent", note = "Helpers use whatever your agent uses") else it } else modelList,
                when { pickingHelper && onSub -> settings.subHelperModel; pickingHelper -> settings.helperModel; onSub -> settings.subModel; else -> settings.model }, modelErr,
                onPick = { m ->
                    when { pickingHelper && onSub -> g.settings.setSubHelperModel(m.id); pickingHelper -> g.settings.setHelperModel(m.id); onSub -> g.settings.setSubModel(m.id); else -> g.settings.setModel(m.id) }
                    modelSheet = false
                }, onDismiss = { modelSheet = false },
                subtitle = if (pickingHelper) "Your agent plans and hands the work to helpers. Pick what they run on." else null)
            LaunchedEffect(route) { if (route != "chat" && !route.startsWith("pchat")) { cameraOrigin = null } }
            cameraOrigin?.let { o ->
                com.past9.phoneaos.camera.CameraCard(o, onClosed = { cameraOrigin = null },
                    // The shot lands in the message box of the chat whose camera button opened it, as a photo he can
                    // remove. Nothing is sent and no words are written: he sends it himself, with or without words.
                    onUse = { path -> (boxFor ?: mainBox).addPhoto(path) },
                    onGallery = { cameraOrigin = null; composerTools.pickPhotos(boxFor ?: mainBox) })
            }
            androidx.compose.animation.AnimatedVisibility(route in barRoutes, modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut()) {
                HomeBar(route, ::go, onTalk = { nav.navigate("chat") }, modifier = androidx.compose.ui.Modifier.onSizeChanged { barHeightPx = it.height })
            }
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
            onSignOut = { sub.signOut(); signErr = null; info = sub.info() },
            onUseKey = onUseKey)
    }

    @Composable
    private fun Onboarding(g: Graph) {
        val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val st = g.settings.state.value
        var linking by remember { mutableStateOf(false) }
        val offer by com.past9.phoneaos.remote.RemoteHub.pendingOffer.collectAsStateWithLifecycle()
        if (linking || offer != null) {
            com.past9.phoneaos.remote.LinkScreen(onBack = { linking = false; com.past9.phoneaos.remote.RemoteHub.pendingOffer.value = null },
                onLinked = { com.past9.phoneaos.remote.RemoteHub.pendingOffer.value = null; g.settings.setOnboarded(true) }, offerText = offer)
            return
        }
        OnboardingScreen(onboardingActions(g).copy(
            linkDevice = { linking = true },
            subSetup = { kind, back, next -> SubSetupFlow(g, kind, onBack = back, onDone = next, onUseKey = back) },
            requestNotifications = { if (com.past9.phoneaos.system.Background.canAskNotifications(this@MainActivity)) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) },
            finish = { g.settings.setOnboarded(true) },
        ), initialAccent = st.accent, initialMascot = st.mascot, initialUser = st.userName, initialAgent = if (st.agentName == "Your agent") "" else st.agentName)
    }

    private fun onboardingActions(g: Graph) = OnboardingActions(
        saveNames = { u, a -> g.settings.setUserName(u); if (a.isNotBlank()) g.settings.setAgentName(a) },
        choosePower = g.settings::setMode,
        chooseStyle = { a, m -> g.settings.setAccent(a); g.settings.setMascot(m) },
        chooseSub = g.settings::setSubKind,
        subAvailable = { k -> com.past9.phoneaos.runtime.SubscriptionRuntime(this, k).available },
        saveCustom = { url, model, http -> g.settings.setProvider(Provider.CUSTOM); g.settings.setBaseUrl(url); g.settings.setModel(model); g.settings.setAllowHttp(http) },
        saveKey = { p, k -> if (g.settings.state.value.provider != p) g.settings.setProvider(p); g.settings.setApiKey(p, k) },
        testKey = { p, k -> testKey(p, k) },
        saveComposio = { k -> saveComposio(g, k) },
        listModels = { p, k -> com.past9.phoneaos.agent.ModelCatalog.forKey(this, p, k, if (p == Provider.CUSTOM) g.settings.state.value.baseUrl else p.baseUrl, p == Provider.CUSTOM && g.settings.state.value.allowHttp) },
        saveModel = { m -> g.settings.setModel(m) },
        openUrl = ::open,
    )

    private fun machineActions(g: Graph): MachineActions {
        val store = g.runtime.machines.store
        return MachineActions(
            onSave = { m, pw, key, pass, newKey ->
                store.save(m)
                when (m.auth) {
                    com.past9.phoneaos.machines.MachineAuth.PASSWORD -> if (pw.isNotEmpty()) store.setCredentials(m.id, pw, null, null)
                    com.past9.phoneaos.machines.MachineAuth.KEY -> if (key.isNotBlank()) store.setCredentials(m.id, null, key.trim(), pass.ifEmpty { null })
                    com.past9.phoneaos.machines.MachineAuth.NEW_KEY -> if (newKey) {
                        val (priv, pub) = com.past9.phoneaos.machines.Crypto.newKey("agent-" + m.name.lowercase().replace(Regex("[^a-z0-9]+"), "-"))
                        store.setCredentials(m.id, null, priv, null); store.setPublicKey(m.id, pub)
                    }
                }
                store.get(m.id) ?: m
            },
            onTest = { m -> g.runtime.machines.test(m) },
            onDelete = { id -> store.remove(id) },
            onCopy = { t -> (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Public key", t)) },
            publicKey = { id -> store.publicKey(id) },
            reload = { id -> store.get(id) },
        )
    }

    private suspend fun testKey(p: Provider, key: String): String? {
        val g = App.graph(this)
        val st = g.settings.state.value
        val model = if (st.provider == p && st.model.isNotBlank()) st.model else p.defaultModel
        val provider = com.past9.phoneaos.agent.AgentRuntime.providerFor(p, key, if (p == Provider.CUSTOM) st.baseUrl else p.baseUrl, p == Provider.CUSTOM && st.allowHttp)
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
        val err = if (com.past9.phoneaos.tools.ComposioConnect.isConsumerKey(key)) com.past9.phoneaos.tools.ComposioConnect.load(key.trim()) else g.runtime.composio.verify()
        return err?.also { g.settings.setComposioKey(null) }
    }

    private fun notificationsAllowed() = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()

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

