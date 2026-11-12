package com.past9.phoneaos.wear

import android.Manifest
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import com.past9.phoneaos.wear.ui.AgentWatchTheme
import com.past9.phoneaos.wear.ui.AnswerScreen
import com.past9.phoneaos.wear.ui.AnswerUi
import com.past9.phoneaos.wear.ui.ApprovalScreen
import com.past9.phoneaos.wear.ui.CallScreen
import com.past9.phoneaos.wear.ui.DoneScreen
import com.past9.phoneaos.wear.ui.GesturesScreen
import com.past9.phoneaos.wear.ui.HomeSettingsScreen
import com.past9.phoneaos.wear.ui.ExchangeScreen
import com.past9.phoneaos.wear.ui.ListeningScreen
import com.past9.phoneaos.wear.ui.RecentScreen
import com.past9.phoneaos.wear.ui.TalkScreen
import com.past9.phoneaos.wearproto.Phase
import com.past9.phoneaos.wearproto.Via
import kotlinx.coroutines.launch

/**
 * The watch app. It is opened from the app list, the tile, a complication or the key the watch maps to it (the same
 * activity answers ACTION_ASSIST), and the extra "go" says where to start: "talk" or "approvals".
 */
class MainActivity : ComponentActivity() {
    private var go by androidx.compose.runtime.mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        go = intentGo(intent)
        val controller = (application as WatchApp).controller
        controller.opened()
        askNotificationsOnce()
        setContent { AgentWatchTheme { AppScaffold { PairGate { WatchNav(controller, (application as WatchApp).call, go) } } } }
    }

    // Sensors for the gestures he mapped run while this screen is up (and during a call or a ring), and not otherwise.
    override fun onResume() { super.onResume(); WatchGestures.resumed(this) }
    override fun onPause() { WatchGestures.paused(this); super.onPause() }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Rings and the call's notification need the notification permission (Wear OS 4 and later): asked once, the first time. */
    private fun askNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("watch_main", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); go = intentGo(intent) + "#" + System.nanoTime() }

    private fun intentGo(i: Intent?): String = when {
        i?.getStringExtra("go") != null -> i.getStringExtra("go").orEmpty()
        i?.action == Intent.ACTION_ASSIST || i?.action == "android.intent.action.VOICE_ASSIST" -> "talk"
        else -> ""
    }
}

private object Routes {
    const val HOME = "home"; const val LISTEN = "listen"; const val ANSWER = "answer"
    const val APPROVAL = "approval"; const val RECENT = "recent"; const val EXCHANGE = "exchange"; const val DONE = "done"
    const val CALL = "call"; const val GESTURES = "gestures"; const val HOME_SETTINGS = "home_settings"
}

@Composable
private fun WatchNav(c: WatchController, call: WatchCall, go: String) {
    val nav = rememberSwipeDismissableNavController()
    val state by c.state.collectAsState()
    val asked by c.asked.collectAsState()
    val notice by c.notice.collectAsState()
    val micDeadAt by c.micDeadAt.collectAsState()
    val micPhase by c.micPhase.collectAsState()
    val level by c.level.collectAsState()
    val reach by c.reach.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val agent = state?.agent.orEmpty()
    val pending = state?.pending.orEmpty()
    val micUsable = !MicPolicy.preferFallback(micDeadAt, System.currentTimeMillis())

    fun toAnswer() { nav.navigate(Routes.ANSWER) { popUpTo(Routes.HOME) } }
    fun startListening() {
        nav.navigate(Routes.LISTEN)
        scope.launch {
            when (val r = c.listen()) {
                is WatchController.Listen.Sent -> { nav.popBackStack(Routes.HOME, false); toAnswer() }
                is WatchController.Listen.NoMic -> nav.popBackStack(Routes.HOME, false)
                WatchController.Listen.NoSpeech -> { nav.popBackStack(Routes.HOME, false) }
                is WatchController.Listen.Failed -> nav.popBackStack(Routes.HOME, false)
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startListening() }
    fun onMic() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startListening()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun speakOnPhone() { scope.launch { if (c.speakOnPhone()) toAnswer() } }
    val typing = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence("ask")?.toString() }
        if (!text.isNullOrBlank()) scope.launch { if (c.ask(text, Via.KEYBOARD)) toAnswer() }
    }
    fun type() {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("ask").setLabel(agent.ifBlank { "Agent" }).build()))
        typing.launch(intent)
    }

    val callState by call.state.collectAsState()
    // The watch mic needs its permission before a call can use it; if he says no, the call still goes, on the phone's sound.
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> call.start(false); nav.navigate(Routes.CALL) { launchSingleTop = true } }
    fun placeCall(phoneAudio: Boolean = false) {
        if (!phoneAudio && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            MicRecorder.preflight(context) != MicPolicy.Verdict.NO_HARDWARE) { callPermission.launch(Manifest.permission.RECORD_AUDIO); return }
        call.start(phoneAudio); nav.navigate(Routes.CALL) { launchSingleTop = true }
    }
    val vibrator = remember { context.getSystemService(android.os.Vibrator::class.java) }

    // A gesture he mapped (WatchGestures): the screen does what it means here; anything it does not handle falls to the headless path.
    DisposableEffect(Unit) {
        WatchGestures.uiHandler = { effect ->
            when (effect) {
                com.past9.phoneaos.wearproto.gesture.GestureEffect.StartTalk -> { if (micUsable) onMic() else speakOnPhone(); true }
                com.past9.phoneaos.wearproto.gesture.GestureEffect.StartCall -> { placeCall(); true }
                com.past9.phoneaos.wearproto.gesture.GestureEffect.EndCall -> { call.end(); true }
                com.past9.phoneaos.wearproto.gesture.GestureEffect.DismissRing -> { WatchRing.dismissTop(context); true }
                com.past9.phoneaos.wearproto.gesture.GestureEffect.LeaveScreen -> { nav.popBackStack(); true }
                // First of two: a short double buzz says "again to approve".
                com.past9.phoneaos.wearproto.gesture.GestureEffect.ArmApprove -> {
                    runCatching { vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1)) }; true
                }
                com.past9.phoneaos.wearproto.gesture.GestureEffect.Approve -> {
                    val id = WatchRing.viewingCard
                    if (id != null) scope.launch {
                        if (c.answer(id, "Approve")) nav.navigate(Routes.DONE + "/yes/a") { popUpTo(Routes.HOME) }
                    }
                    true
                }
                com.past9.phoneaos.wearproto.gesture.GestureEffect.Ignore -> true
            }
        }
        onDispose { WatchGestures.uiHandler = null; WatchGestures.approvalOnScreen = { false } }
    }

    // A tile, complication or key press asked for something: go there.
    LaunchedEffect(go) {
        when (go.substringBefore('#')) {
            "talk" -> if (micUsable) onMic() else speakOnPhone()
            "approvals" -> pending.firstOrNull()?.let { nav.navigate(Routes.APPROVAL + "/" + it.id) }
            "call" -> nav.navigate(Routes.CALL) { launchSingleTop = true }
            "gestures" -> nav.navigate(Routes.GESTURES) { launchSingleTop = true }
            "home_settings" -> nav.navigate(Routes.HOME_SETTINGS) { launchSingleTop = true }
        }
    }

    SwipeDismissableNavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val quick = listOf(R.string.quick_yes, R.string.quick_no, R.string.quick_not_now, R.string.quick_later, R.string.quick_next, R.string.quick_more).map { stringResource(it) }
            TalkScreen(
                agent = agent, link = if (reach == "none") "none" else state?.link.orEmpty(), waiting = pending.size, micUsable = micUsable, quick = quick,
                hasRecent = state?.recent?.isNotEmpty() == true, notice = notice,
                onMic = ::onMic, onPhone = ::speakOnPhone, onType = ::type,
                onQuick = { q -> scope.launch { if (c.ask(q, Via.QUICK)) toAnswer() } },
                onWaiting = { pending.firstOrNull()?.let { nav.navigate(Routes.APPROVAL + "/" + it.id) } },
                onRecent = { nav.navigate(Routes.RECENT) }, onTryMic = { c.tryMicAgain(); c.clearNotice() },
                onCall = { placeCall() }, onMore = { nav.navigate(Routes.GESTURES) },
            )
        }
        composable(Routes.CALL) {
            CallScreen(callState, agent, onMute = call::toggleMute, onEnd = call::end, onAgain = { placeCall() }, onPhoneAudio = { placeCall(phoneAudio = true) })
        }
        composable(Routes.GESTURES) {
            var map by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(WatchGestures.map(context)) }
            var mode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(WatchRing.approvalMode(context)) }
            GesturesScreen(
                map = map, profile = WatchGestures.profile(context), approvalMode = mode,
                onMap = { map = it; WatchGestures.save(context, it) },
                onApprovalMode = { mode = it; WatchRing.setApprovalMode(context, it) },
                onHomeScreen = { nav.navigate(Routes.HOME_SETTINGS) }, onUnpair = { scope.launch { WatchPairingState.unpair(context) } },
                onNotificationAccess = { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            )
            // Home screen settings live one step further on, so the talk screen stays small.
        }
        composable(Routes.HOME_SETTINGS) { HomeSettings(context) }
        composable(Routes.LISTEN) { ListeningScreen(level = level, sending = micPhase == WatchController.MicPhase.SENDING, onStop = c::stopListening) }
        composable(Routes.ANSWER) {
            val turn = WatchController.turnFor(state, asked)
            val onPhoneWait = asked?.onPhone == true && (turn?.phase == Phase.SENDING)
            val ui = AnswerUi(
                you = turn?.heard.orEmpty().takeIf { it != "…" }.orEmpty(),
                status = when {
                    onPhoneWait -> stringResource(R.string.speak_on_phone_wait)
                    turn?.phase == Phase.SENDING -> stringResource(R.string.sending)
                    turn?.phase == Phase.THINKING -> stringResource(R.string.thinking)
                    turn?.phase == Phase.ACTING -> turn.label.ifBlank { stringResource(R.string.working) }
                    turn?.phase == Phase.APPROVAL || turn?.phase == Phase.ASKING -> stringResource(R.string.needs_you)
                    else -> ""
                },
                answer = if (turn?.phase == Phase.ERROR) "" else turn?.answer.orEmpty(),
                working = turn?.phase in setOf(Phase.SENDING, Phase.THINKING, Phase.ACTING) && !onPhoneWait,
                needsYou = turn?.phase == Phase.APPROVAL || turn?.phase == Phase.ASKING,
                problem = if (turn?.phase == Phase.ERROR) turn.problem.ifBlank { stringResource(R.string.try_again) } else notice.orEmpty(),
            )
            AnswerScreen(ui, onReply = { nav.popBackStack(Routes.HOME, false); if (micUsable) onMic() else speakOnPhone() }, onStop = c::stop,
                onReview = { pending.firstOrNull()?.let { nav.navigate(Routes.APPROVAL + "/" + it.id) } },
                onRetry = { c.clearNotice(); nav.popBackStack(Routes.HOME, false) })
        }
        composable(Routes.APPROVAL + "/{id}") { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull()
            val p = pending.firstOrNull { it.id == id }
            if (p == null) { LaunchedEffect(Unit) { nav.popBackStack() } } else {
                // He is looking at this card: it does not ring again, and an approval gesture may answer it (twice, see GestureRouter).
                DisposableEffect(p.id) {
                    WatchRing.viewingCard = p.id
                    WatchGestures.approvalOnScreen = { p.isApproval }
                    onDispose { WatchRing.viewingCard = null; WatchGestures.approvalOnScreen = { false } }
                }
                val pos = if (pending.size > 1) " " + (pending.indexOf(p) + 1) + "/" + pending.size else ""
                ApprovalScreen(p, pos) { option ->
                    scope.launch {
                        val ok = c.answer(p.id, option)
                        if (ok) nav.navigate(Routes.DONE + "/" + (if (option == "Decline") "no" else "yes") + "/" + (if (p.isApproval) "a" else "q")) { popUpTo(Routes.HOME) }
                    }
                }
            }
        }
        composable(Routes.DONE + "/{who}/{kind}") { entry ->
            val yes = entry.arguments?.getString("who") == "yes"; val approval = entry.arguments?.getString("kind") == "a"
            val text = if (!approval) stringResource(R.string.answered) else if (yes) stringResource(R.string.approved) else stringResource(R.string.declined)
            DoneScreen(text, positive = yes) {
                // More waiting? Show the next card; otherwise go back to the conversation.
                val next = c.state.value?.pending?.firstOrNull()
                if (next != null) nav.navigate(Routes.APPROVAL + "/" + next.id) { popUpTo(Routes.HOME) } else nav.popBackStack(Routes.HOME, false)
            }
        }
        composable(Routes.RECENT) { RecentScreen(state?.recent.orEmpty()) { nav.navigate(Routes.EXCHANGE + "/" + it.id) } }
        composable(Routes.EXCHANGE + "/{id}") { entry ->
            val e = state?.recent?.firstOrNull { it.id == entry.arguments?.getString("id")?.toLongOrNull() }
            if (e != null) ExchangeScreen(e) { nav.popBackStack(Routes.HOME, false); if (micUsable) onMic() else speakOnPhone() }
        }
    }
}
