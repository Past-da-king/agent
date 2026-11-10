package com.past9.phoneaos.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.past9.phoneaos.screen.ScreenState
import com.past9.phoneaos.screen.ScreenStarter
import com.past9.phoneaos.screen.ScreenUi
import com.past9.phoneaos.screen.Screens
import com.past9.phoneaos.screen.op
import com.past9.phoneaos.ui.AgentSheet
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.SheetField
import com.past9.phoneaos.ui.SheetHead
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the person can do from the peek sheet. Their Stop and Take over always win over the agent. */
class ScreenPeekActions(
    val onStop: () -> Unit = {}, val onAllow: () -> Unit = {},
    val onTakeOver: () -> Unit = {}, val onHandBack: () -> Unit = {},
    /** A finger on the picture while they have taken over: MotionEvent action, then x and y in the hidden screen's own pixels. */
    val onTouch: (Int, Float, Float) -> Unit = { _, _, _ -> },
    val onKey: (String) -> Unit = {},
    val onBringToMyScreen: () -> Unit = {},
    /** Type into the app while they have it, and save what was done as a learned app. */
    val onType: () -> Unit = {}, val onSave: () -> Unit = {},
)

/**
 * A peek at the background screen: the live picture of the app the agent is using, what it is doing in one line,
 * and the two buttons that matter at the thumb: take over, and stop. A slide-up sheet, never a pop-up.
 */
@Composable
fun ScreenPeekSheet(state: ScreenState, picture: ImageBitmap?, agentName: String, actions: ScreenPeekActions, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            SheetHead(Icons.Rounded.Layers, "Background screen", when {
                state.stopped -> "Stopped"
                state.takenOver -> "You have the screen"
                state.open -> "$agentName is using ${state.app.ifBlank { "an app" }}"
                else -> "Nothing open"
            }, when {
                state.stopped -> "$agentName can do nothing more here until you allow it again."
                state.takenOver -> "Touch the picture to use the app yourself. Hand it back when you are done."
                state.open -> state.step.ifBlank { "Your own screen is not touched." }
                else -> "When $agentName uses an app in the background, you can watch it here."
            })
            Spacer(Modifier.height(16.dp))
            if (state.open) {
                val ratio = if (state.width > 0 && state.height > 0) state.width.toFloat() / state.height else 0.45f
                // The frame's colour says who has the screen: yours, stopped, or the agent's.
                val edge = when { state.stopped -> cs.error; state.takenOver -> cs.tertiary; else -> cs.primary }
                Box(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
                    Box(Modifier.heightIn(max = 440.dp).aspectRatio(ratio).clip(MaterialTheme.shapes.large).background(cs.surfaceContainerHighest)
                        .border(2.dp, edge, MaterialTheme.shapes.large)
                        .then(if (state.takenOver) Modifier.pointerInput(state.width, state.height) {
                            awaitEachGesture {
                                fun sx(x: Float) = x * state.width / size.width.coerceAtLeast(1)
                                fun sy(y: Float) = y * state.height / size.height.coerceAtLeast(1)
                                val down = awaitFirstDown()
                                down.consume()
                                actions.onTouch(MotionEvent.ACTION_DOWN, sx(down.position.x), sy(down.position.y))
                                while (true) {
                                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    c.consume()
                                    if (!c.pressed) { actions.onTouch(MotionEvent.ACTION_UP, sx(c.position.x), sy(c.position.y)); break }
                                    actions.onTouch(MotionEvent.ACTION_MOVE, sx(c.position.x), sy(c.position.y))
                                }
                            }
                        } else Modifier), contentAlignment = Alignment.Center) {
                        if (picture != null) Image(picture, "The background screen", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        else Text("Waiting for the picture", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
                if (state.takenOver) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { actions.onKey("back") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Back", style = MaterialTheme.typography.labelLarge) }
                        OutlinedButton(onClick = { actions.onKey("enter") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Enter", style = MaterialTheme.typography.labelLarge) }
                        OutlinedButton(onClick = actions.onType, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Type", style = MaterialTheme.typography.labelLarge) }
                    }
                }
                if (state.steps >= 2 || state.takenOver) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.steps >= 2) TextButton(onClick = actions.onSave, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save as a learned app") }
                        Spacer(Modifier.weight(1f))
                        if (state.takenOver) TextButton(onClick = actions.onBringToMyScreen, modifier = Modifier.heightIn(min = 48.dp)) { Text("On my screen") }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (state.open || state.stopped) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.stopped) Button(onClick = actions.onAllow, modifier = Modifier.weight(1f).height(64.dp), shapes = ButtonDefaults.shapes()) { Text("Allow again", style = MaterialTheme.typography.titleMedium) }
                else {
                    Button(onClick = actions.onStop, modifier = Modifier.weight(1f).height(64.dp), shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) {
                        Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop", style = MaterialTheme.typography.titleMedium)
                    }
                    if (state.open) FilledTonalButton(onClick = if (state.takenOver) actions.onHandBack else actions.onTakeOver, modifier = Modifier.weight(1.3f).height(64.dp), shapes = ButtonDefaults.shapes()) {
                        Text(if (state.takenOver) "Hand back" else "Take over", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** What the setup sheet knows about this phone right now. */
data class ScreenSetupInfo(
    val ready: Boolean = false, val tooOld: Boolean = false,
    val shizukuInstalled: Boolean = false, val shizukuRunning: Boolean = false, val shizukuAllowed: Boolean = false,
    val adbCommand: String = "", val reason: String = "", val busy: Boolean = false, val note: String = "",
    val rules: String = "", val words: String = "", val whileLocked: Boolean = false,
)

class ScreenSetupActions(
    val onGetShizuku: () -> Unit = {}, val onOpenShizuku: () -> Unit = {}, val onAllowShizuku: () -> Unit = {}, val onStart: () -> Unit = {},
    val onTry: () -> Unit = {}, val onRules: (String) -> Unit = {}, val onWords: (String) -> Unit = {}, val onWhileLocked: (Boolean) -> Unit = {},
    val onBlocked: () -> Unit = {},
)

@Composable
private fun StepRow(n: Int, done: Boolean, title: String, line: String, button: String?, enabled: Boolean = true, onClick: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(MaterialTheme.shapes.extraLarge).background(if (done) cs.primary else cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            if (done) Icon(Icons.Rounded.Check, "Done", tint = cs.onPrimary, modifier = Modifier.size(18.dp))
            else Text("$n", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (button != null && !done) { Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = onClick, enabled = enabled) { Text(button, style = MaterialTheme.typography.labelLarge) } }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, line: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(on, onChange)
    }
}

/**
 * Settings > Background screen: what it is, the one-time steps to get it going, and the rules for what the agent may
 * do there. A slide-up sheet in the app's own pieces. The steps tick themselves as the phone reports them done.
 */
@Composable
fun ScreenSetupSheet(info: ScreenSetupInfo, actions: ScreenSetupActions, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clip = LocalClipboardManager.current
    var rules by remember { mutableStateOf(info.rules) }
    var words by remember { mutableStateOf(info.words) }
    AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding()) {
            SheetHead(Icons.Rounded.Layers, "Background screen", if (info.ready) "Ready" else "Set it up once",
                "Your agent can use another app on a hidden screen while you keep using yours. Android only lets the phone's own shell do that, so a small helper from this app has to be started. It runs on this phone only and nothing leaves it.")
            if (info.reason.isNotBlank() && !info.ready) {
                Spacer(Modifier.height(16.dp))
                Surface(color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer, shape = MaterialTheme.shapes.large) {
                    Text("Your agent just tried to use it: ${info.reason}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
            }
            if (info.tooOld) {
                Spacer(Modifier.height(16.dp))
                Text("This needs Android 11 or newer, so it cannot be used on this phone.", style = MaterialTheme.typography.bodyMedium, color = cs.error)
            } else if (info.ready) {
                Spacer(Modifier.height(20.dp))
                AppCard(padding = PaddingValues(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Visibility, null, tint = cs.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Try it", style = MaterialTheme.typography.titleSmall)
                            Text(info.note.ifBlank { "Opens your phone's Settings app on the hidden screen and shows you the picture." }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = actions.onTry, enabled = !info.busy) { Text("Open", style = MaterialTheme.typography.labelLarge) }
                    }
                }
                Text("The helper stops when the phone restarts. After a restart, start it again here.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            } else {
                Spacer(Modifier.height(20.dp))
                Text("ON THIS PHONE, WITH SHIZUKU", style = com.past9.phoneaos.ui.theme.Eyebrow, color = cs.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard(padding = PaddingValues(vertical = 4.dp)) {
                    StepRow(1, info.shizukuInstalled, "Get Shizuku", "A free, open app that lends the phone's shell to apps you allow.", "Get it", onClick = actions.onGetShizuku)
                    StepRow(2, info.shizukuRunning, "Start Shizuku", "Open it and start it with Wireless debugging. It walks you through a one-time pairing. Start it again after each restart.",
                        "Open", enabled = info.shizukuInstalled, onClick = actions.onOpenShizuku)
                    StepRow(3, info.shizukuAllowed, "Allow this app", "Shizuku asks once whether this app may use it.", "Allow", enabled = info.shizukuRunning, onClick = actions.onAllowShizuku)
                    StepRow(4, false, "Start the helper", info.note.ifBlank { "This app starts its own helper through Shizuku." }, "Start", enabled = info.shizukuAllowed && !info.busy, onClick = actions.onStart)
                }
                Spacer(Modifier.height(20.dp))
                Text("OR FROM A COMPUTER", style = com.past9.phoneaos.ui.theme.Eyebrow, color = cs.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard(padding = PaddingValues(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Usb, null, tint = cs.primary)
                        Spacer(Modifier.width(16.dp))
                        Text("Plug the phone in with USB debugging on, and run this", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { clip.setText(AnnotatedString(info.adbCommand)); com.past9.phoneaos.ui.AppNotice.say("Command copied") }) { Text("Copy", style = MaterialTheme.typography.labelLarge) }
                    }
                    SelectionContainer {
                        Text(info.adbCommand, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
                    }
                }
            }
            if (!info.tooOld) {
                Spacer(Modifier.height(24.dp))
                Text("WHAT YOUR AGENT MAY DO THERE", style = com.past9.phoneaos.ui.theme.Eyebrow, color = cs.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                Text("It always asks you before a press that sends, pays, deletes or posts. You can stop it or take over at any moment from the notification.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 12.dp))
                AppCard(padding = PaddingValues(vertical = 4.dp)) {
                    Surface(onClick = actions.onBlocked, color = androidx.compose.ui.graphics.Color.Transparent) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Shield, null, tint = cs.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Blocked apps", style = MaterialTheme.typography.titleSmall)
                                Text("Banking and money apps are never used there. That cannot be switched off. Add any other app.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.ChevronRight, "Open", tint = cs.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SheetField(rules, { rules = it; actions.onRules(it) }, "Apps with their own rule", "package | allow, ask or never. One per line", minLines = 2, mono = true,
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None))
                Text("never: your agent may not open that app there at all. allow: it does not ask in that app. Every other app asks.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                SheetField(words, { words = it; actions.onWords(it) }, "More words that mean ask first", "Words on buttons, with commas, in any language", singleLine = true,
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None))
                Spacer(Modifier.height(12.dp))
                AppCard(padding = PaddingValues(vertical = 4.dp)) {
                    SwitchRow(Icons.Rounded.Lock, "Keep working while the phone is locked", if (info.whileLocked) "On: it can carry on behind the lock screen." else "Off: it only works while the phone is unlocked.",
                        info.whileLocked, actions.onWhileLocked)
                }
            }
        }
    }
}

/** Draws whichever background screen sheet was asked for. Placed once at the root of the app. */
@Composable
fun ScreenSheetsHost(agentName: String) {
    val which by ScreenUi.show.collectAsStateWithLifecycle()
    when (which) {
        ScreenUi.PEEK -> PeekHost(agentName) { ScreenUi.show.value = null }
        ScreenUi.SETUP -> SetupHost { ScreenUi.show.value = null }
        ScreenUi.RUNS -> RunsHost { ScreenUi.show.value = null }
        ScreenUi.BLOCKED -> BlockedHost { ScreenUi.show.value = ScreenUi.SETUP }
        ScreenUi.SAVE -> SaveSkillHost { ScreenUi.show.value = ScreenUi.PEEK }
        ScreenUi.TYPE -> {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            ScreenTypeSheet(onType = { text, secret ->
                scope.launch { Screens.session(context).userType(text, secret)?.let { com.past9.phoneaos.ui.AppNotice.say(it.take(90)) }; ScreenUi.show.value = ScreenUi.PEEK }
            }, onDismiss = { ScreenUi.show.value = ScreenUi.PEEK })
        }
    }
}

@Composable
private fun PeekHost(agentName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val session = remember { Screens.session(context) }
    val link = remember { Screens.preview(context) }
    val state by session.state.collectAsStateWithLifecycle()
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()
    // The person's touches go to the hidden screen in the order they were made.
    val touches = remember { Channel<Triple<Int, Float, Float>>(Channel.UNLIMITED) }
    LaunchedEffect(Unit) { for (t in touches) session.userTouch(link, t.first, t.second, t.third) }
    LaunchedEffect(state.open) {
        var seq = -1L
        while (state.open) {
            val m = runCatching { link.call(op("frame").put("max", 540).put("q", 60).put("after", seq), 4_000) }.getOrNull()
            val bin = m?.bin
            if (bin != null) { seq = m.json.optLong("seq"); BitmapFactory.decodeByteArray(bin, 0, bin.size)?.let { picture = it.asImageBitmap() } }
            delay(if (m == null) 1_000 else 220)
        }
        picture = null
    }
    ScreenPeekSheet(state, picture, agentName, ScreenPeekActions(
        onStop = { session.userStop() }, onAllow = { session.userAllow() },
        onTakeOver = { session.userTakeOver() }, onHandBack = { session.userHandBack() },
        onTouch = { a, x, y -> touches.trySend(Triple(a, x, y)) },
        onKey = { k -> scope.launch { session.userKey(k) } },
        onBringToMyScreen = { scope.launch { if (session.userBringToMyScreen()) onDismiss() else com.past9.phoneaos.ui.AppNotice.say("Could not move it to your screen") } },
        onType = { ScreenUi.show.value = ScreenUi.TYPE }, onSave = { ScreenUi.show.value = ScreenUi.SAVE },
    ), onDismiss)
}

@Composable
private fun SetupHost(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { com.past9.phoneaos.App.graph(context).settings }
    val session = remember { Screens.session(context) }
    val scope = rememberCoroutineScope()
    val reason by ScreenUi.reason.collectAsStateWithLifecycle()
    var info by remember {
        mutableStateOf(ScreenSetupInfo(tooOld = android.os.Build.VERSION.SDK_INT < 30, adbCommand = ScreenStarter.adbCommand(context),
            rules = settings.extra(Screens.RULES).orEmpty(), words = settings.extra(Screens.WORDS).orEmpty(), whileLocked = settings.extra(Screens.LOCKED) == "1"))
    }
    // The steps tick themselves: look at the phone again every second while the sheet is open.
    LaunchedEffect(Unit) {
        val link = Screens.preview(context)
        while (true) {
            val ready = runCatching { link.call(op("ping"), 2_000).json.optInt("version") == com.past9.phoneaos.screen.ScreenProtocol.VERSION }.getOrDefault(false)
            info = info.copy(ready = ready, shizukuInstalled = ScreenStarter.shizukuInstalled(context), shizukuRunning = ScreenStarter.shizukuRunning(), shizukuAllowed = ScreenStarter.shizukuGranted())
            delay(1_000)
        }
    }
    fun open(intent: Intent) { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { com.past9.phoneaos.ui.AppNotice.say("Could not open it") } }
    ScreenSetupSheet(info.copy(reason = reason), ScreenSetupActions(
        onGetShizuku = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) },
        onOpenShizuku = { context.packageManager.getLaunchIntentForPackage(ScreenStarter.SHIZUKU_PACKAGE)?.let { open(it) } ?: com.past9.phoneaos.ui.AppNotice.say("Shizuku is not installed") },
        onAllowShizuku = { ScreenStarter.shizukuAsk() },
        onStart = {
            info = info.copy(busy = true, note = "Starting")
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { val why = ScreenStarter.startWithShizuku(context); info = info.copy(busy = false, note = why ?: "Started. Checking") }
        },
        onTry = {
            info = info.copy(busy = true, note = "Opening Settings on the hidden screen")
            scope.launch {
                val out = runCatching { session.open(QuietScreenContext, "com.android.settings") }.getOrElse { it.message.orEmpty() }
                info = info.copy(busy = false, note = if (session.state.value.open) "" else out.lineSequence().firstOrNull().orEmpty())
                if (session.state.value.open) ScreenUi.show.value = ScreenUi.PEEK
            }
        },
        onRules = { settings.setExtra(Screens.RULES, it) }, onWords = { settings.setExtra(Screens.WORDS, it) },
        onWhileLocked = { on -> settings.setExtra(Screens.LOCKED, if (on) "1" else "0"); info = info.copy(whileLocked = on) },
        onBlocked = { ScreenUi.show.value = ScreenUi.BLOCKED },
    ), onDismiss)
}

/** The person pressing "Try it" themselves: nothing to ask, nothing to write in the chat. */
private object QuietScreenContext : com.past9.phoneaos.agent.ToolContext {
    override val agentLabel = "you"
    override suspend fun activity(text: String, meta: org.json.JSONObject): Long = 0
    override suspend fun updateActivity(id: Long, text: String, meta: org.json.JSONObject) {}
    override suspend fun ask(question: String, options: List<String>): String? = "Approve"
    override suspend fun notify(title: String, body: String) {}
}
