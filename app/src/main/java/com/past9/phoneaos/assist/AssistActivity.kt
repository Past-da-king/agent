package com.past9.phoneaos.assist

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.past9.phoneaos.App
import com.past9.phoneaos.Graph
import com.past9.phoneaos.MainActivity
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.system.ChatOnScreen
import com.past9.phoneaos.ui.SheetShape
import com.past9.phoneaos.ui.theme.AppTheme
import com.past9.phoneaos.voice.SystemDictation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * The assist sheet: a compact slide-up sheet over whatever app is on screen (and over the lock screen), listening
 * the moment it opens. It is the phone's assistant when the app holds that role, and the same sheet for a wake word,
 * a shortcut, the quick settings tile and the plain "assist" intent.
 *
 * It decides nothing about what a request means. Every request goes whole to the same agent as the main chat (the
 * one on this phone, or the linked computer's), as a normal message that stays in the chat; the sheet then follows
 * that turn through the same chat rows: thinking, what it is doing, the answer, an approval that waits for him.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AssistActivity : ComponentActivity() {
    private lateinit var g: Graph
    private lateinit var core: AssistCore
    private var source = AssistEntry.SHORTCUT
    private var view by mutableStateOf(AssistView(AssistPhase.LISTENING))
    private var rows: List<ChatItem> = emptyList()
    private var working = false
    private var dictation: SystemDictation? = null
    private var askingPermission = false
    private var follow: Job? = null
    private var closing by mutableStateOf(false)
    /** Live voice in the assistant: a Gemini Live session instead of the one-request listening input. */
    private var liveMode = false
    private var liveView by mutableStateOf(LiveView())
    /** Minimise needs "display over other apps": the slide-up sheet that asks for it. */
    private var overlaySheet by mutableStateOf(false)
    private var pendingMinimise = false

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askingPermission = false
        if (granted && liveMode) startLive()
        else if (granted) startListening() else { liveMode = false; core.listen(); core.micFailed("The microphone is off for this app, so type it here. You can allow it in the phone's Settings."); refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Over the lock screen only when he switched that on; otherwise the phone asks for the unlock first.
        if (Build.VERSION.SDK_INT >= 27 && AssistPrefs.overLockScreen(this)) { setShowWhenLocked(true); setTurnScreenOn(true) }
        com.past9.phoneaos.voice.LiveTiming.mark("sheet_open")
        g = App.graph(this)
        RemoteHub.init(this)
        source = sourceOf(intent)
        core = AssistCore(micAvailable = SystemDictation.available(this))
        AssistHub.keepIfFresh()
        AssistEntry.setOpen(true)
        AssistEntry.clearNotification(this)
        Log.i(AssistService.TAG, "sheet open source=$source held=${AssistRole.held(this)}")
        val st = g.settings.state.value
        // The same chat the app shows: this phone's own, or the linked computer's.
        follow = lifecycleScope.launch {
            launch { RemoteHub.session.flatMapLatest { (it?.db ?: g.db).chat().all() }.collectLatest { rows = it; refresh() } }
            launch { RemoteHub.session.flatMapLatest { it?.status ?: g.runtime.status }.collectLatest { working = it.working; refresh() } }
            // Test builds: words put in by hand, as if spoken.
            // Live voice: the session is the host's; this sheet only shows it.
            launch { LiveHost.view.collect { liveView = it } }
            launch { LiveHost.ended.collect { why -> liveEnded(why) } }
            launch { AssistHub.said.collect { words -> stopListening(); if (!core.listening) core.listen(); heard(words) } }
        }
        setContent {
            val settings by g.settings.state.collectAsState()
            AppTheme(accent = settings.accent, mascot = settings.mascot) { Sheet(settings.agentName.takeIf { it.isNotBlank() && it != "Your agent" } ?: "Your agent", settings.onboarded) }
        }
        liveMode = st.onboarded && AssistPrefs.liveVoice(this)
        if (st.onboarded) beginListening()
    }

    /** Called again while it is up (a second press of the side key, the wake word again): start over, listening. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        source = sourceOf(intent)
        // A live session is already open: a second press changes nothing, it is already listening.
        if (liveMode && LiveHost.active) { LiveHost.attached(); return }
        AssistHub.keepIfFresh()
        AssistEntry.clearNotification(this)
        g.stopSpeaking()
        core.listen(); refresh()
        beginListening()
    }

    /** The system's own "assist" and "voice command" intents carry no source: they come from a key or a gesture. */
    private fun sourceOf(i: Intent?): String =
        if (i?.action == AssistEntry.ACTION) AssistEntry.source(i.getStringExtra(AssistEntry.EXTRA_SOURCE)) else AssistEntry.SIDE_KEY

    override fun onResume() {
        super.onResume(); ChatOnScreen.assistShowing = true
        // Back from the phone's overlay page: if he allowed it, carry on with the minimise he asked for.
        if (pendingMinimise) { askingPermission = false; pendingMinimise = false; if (android.provider.Settings.canDrawOverlays(this)) minimise() }
    }
    override fun onPause() { super.onPause(); ChatOnScreen.assistShowing = false }

    override fun onStop() {
        super.onStop()
        // He went somewhere else: the sheet does not linger behind other apps. The turn carries on in the chat.
        if (!isChangingConfigurations && !askingPermission && !core.waitingForUnlock) finish()
    }

    override fun onDestroy() {
        // Leaving the sheet ends a live session, unless it was minimised to the bubble on purpose.
        if (liveMode && !LiveHost.minimised.value) LiveHost.end("")
        stopListening()
        follow?.cancel()
        runCatching { g.stopSpeaking() }
        AssistEntry.setOpen(false)
        // A minimised session still holds the microphone: the wake word keeps out of its way.
        if (LiveHost.active) AssistEntry.setListening(true)
        AssistHub.clear()
        super.onDestroy()
    }

    private fun refresh() {
        view = core.view(rows, working)
    }

    // ---- live voice -----------------------------------------------------------------------------

    /** Joins the live session if one is running (opened again from the bubble, a second press), else starts one. */
    private fun startLive() {
        if (LiveHost.active) { LiveHost.attached(); liveView = LiveHost.view.value; return }
        LiveHost.start(this, source)
    }

    /** Shrinks the sheet to the floating bubble. Asks for "display over other apps" first, in a slide-up sheet, when it is not allowed yet. */
    private fun minimise() {
        if (!android.provider.Settings.canDrawOverlays(this)) { overlaySheet = true; return }
        LiveHost.minimise()
        finish()
    }

    private fun openOverlaySettings() {
        overlaySheet = false
        pendingMinimise = true
        askingPermission = true
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
            .onFailure { askingPermission = false; pendingMinimise = false }
    }

    /** The live session is over. Ended on purpose (goodbye, quiet, a tap): the sheet closes. It could not start or broke: the normal sheet takes over, with the reason. */
    private fun liveEnded(why: String) {
        if (why.isBlank()) { finish(); return }
        liveMode = false
        core.listen()
        core.micFailed("Live voice stopped: $why You can type here, or tap the mic.")
        refresh()
    }

    // ---- listening ------------------------------------------------------------------------------

    private fun beginListening() {
        if (!core.listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { if (liveMode) startLive() else startListening(); return }
        // The phone's own permission question cannot be drawn over the lock screen: there the keyboard takes over.
        if (locked()) { core.micFailed("Unlock the phone once and allow the microphone, then this listens straight away."); refresh(); return }
        askingPermission = true
        askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        stopListening()
        if (!core.listening) return
        AssistEntry.setListening(true)
        dictation = SystemDictation(this, onText = { t -> core.partial(t); refresh() }, onEnd = { error ->
            dictation = null
            AssistEntry.setListening(false)
            if (error != null && core.words.isBlank()) { core.micFailed(error); refresh() } else heard(core.words)
        }, oneShot = true).also { it.start() }
        refresh()
    }

    private fun stopListening() {
        dictation?.cancel(); dictation = null
        AssistEntry.setListening(false)
    }

    // ---- the turn -------------------------------------------------------------------------------

    private fun locked(): Boolean = runCatching { getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(false)

    /** His words are final (spoken or typed): send them, or keep them until the phone is unlocked. */
    private fun heard(words: String, byKeyboard: Boolean = false) {
        val now = core.finished(words, locked = locked(), byKeyboard = byKeyboard)
        refresh()
        if (now != null) send(now) else if (core.waitingForUnlock) unlock()
    }

    private fun unlock() {
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { core.unlocked()?.let { send(it) }; refresh() }
        })
    }

    private fun send(words: String) {
        val before = rows.lastOrNull()?.id ?: 0L
        val screen = AssistHub.screen.value
        val text = AssistMessage.compose(words, source, screen, typed = core.wasTyped)
        val images = listOfNotNull(screen?.screenshot?.takeIf { it.isNotBlank() && !screen.blocked })
        lifecycleScope.launch {
            runCatching { g.runtime.sendAccepted(text, images) }
                .onSuccess { core.sent(before) }
                .onFailure { e -> core.sendFailed("That did not send: " + e.message.orEmpty().ifBlank { "the link to your computer dropped" } + ". Your words are still here.") }
            refresh()
        }
    }

    private fun openApp() {
        runCatching { startActivity(Intent(this, MainActivity::class.java).putExtra("open", "chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        finish()
    }

    // ---- UI -------------------------------------------------------------------------------------

    @Composable
    private fun Sheet(agentName: String, onboarded: Boolean) {
        val cs = MaterialTheme.colorScheme
        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
        var dragY by remember { mutableFloatStateOf(0f) }
        val screen by AssistHub.screen.collectAsState()
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
        fun dismiss() { closing = true; finish() }
        BackHandler { dismiss() }
        // An answer he asked for out loud is read out, once, when a voice is set up.
        LaunchedEffect(view.answerId, view.phase) {
            val id = view.answerId
            if (view.phase == AssistPhase.ANSWERING && id != null && !core.wasTyped) g.speak(view.answer, id)
        }
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
                Box(Modifier.fillMaxSize().background(cs.scrim.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() })
            }
            AnimatedVisibility(visible, modifier = Modifier.align(Alignment.BottomCenter), enter = slideInVertically(tween(240)) { it } + fadeIn(tween(100)),
                exit = slideOutVertically(tween(160)) { it }) {
                Surface(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight).offset { IntOffset(0, dragY.toInt()) }
                        .draggable(orientation = Orientation.Vertical, state = rememberDraggableState { dy -> dragY = (dragY + dy).coerceAtLeast(0f) },
                            onDragStopped = { if (dragY > 200f) dismiss() else dragY = 0f }),
                    shape = SheetShape, color = cs.surfaceContainerLow, contentColor = cs.onSurface, tonalElevation = 3.dp,
                ) {
                    Column(Modifier.navigationBarsPadding().imePadding()) {
                        if (!onboarded) NotSetUp(agentName)
                        else if (liveMode) LiveSheetBody(liveView, agentName, AssistWords.screenLine(screen, AssistPrefs.readScreen(this@AssistActivity)), LiveActions(
                            onEnd = { if (LiveHost.active) LiveHost.end("") else finish() },
                            onMute = { m -> LiveHost.mute(m) },
                            onMinimise = { minimise() },
                            onOpenApp = { openApp() },
                            onAnswer = { id, option -> g.runtime.answer(id, option) },
                        ))
                        else AssistSheetBody(view, agentName, AssistWords.screenLine(screen, AssistPrefs.readScreen(this@AssistActivity)), AssistActions(
                            onMic = { g.stopSpeaking(); core.listen(); refresh(); beginListening() },
                            onDoneSpeaking = { dictation?.finish() ?: heard(core.words) },
                            onStopAgent = { g.runtime.stop() },
                            onKeyboard = { stopListening(); g.stopSpeaking(); core.type(); refresh() },
                            onSendTyped = { t -> heard(t, byKeyboard = true) },
                            onOpenApp = { openApp() },
                            onAnswer = { id, option -> g.runtime.answer(id, option) },
                            onUnlock = { unlock() },
                        ))
                    }
                }
            }
            if (overlaySheet) OverlayPermissionSheet(agentName, onOpenSettings = { openOverlaySettings() }, onDismiss = { overlaySheet = false })
        }
    }

    @Composable
    private fun NotSetUp(agentName: String) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text("Finish setting up first", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("Open the app once and choose what powers $agentName. Then this sheet listens the moment it opens.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { openApp() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Open the app", style = MaterialTheme.typography.titleMedium) }
        }
    }
}
