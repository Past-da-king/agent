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
import androidx.compose.runtime.LaunchedEffect
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
import com.past9.phoneaos.wear.ui.DoneScreen
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
        setContent { AgentWatchTheme { AppScaffold { WatchNav(controller, go) } } }
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
}

@Composable
private fun WatchNav(c: WatchController, go: String) {
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

    // A tile, complication or key press asked for something: go there.
    LaunchedEffect(go) {
        when (go.substringBefore('#')) {
            "talk" -> if (micUsable) onMic() else speakOnPhone()
            "approvals" -> pending.firstOrNull()?.let { nav.navigate(Routes.APPROVAL + "/" + it.id) }
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
            )
        }
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
