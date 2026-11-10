package com.past9.phoneaos.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.past9.phoneaos.ui.AppSheet
import com.past9.phoneaos.ui.ChoiceChips
import com.past9.phoneaos.ui.SheetField
import com.past9.phoneaos.wakeword.Learned
import com.past9.phoneaos.wakeword.MicUse
import com.past9.phoneaos.wakeword.Sensitivity
import com.past9.phoneaos.wakeword.SpellingLearner
import com.past9.phoneaos.wakeword.KeywordModelFiles
import com.past9.phoneaos.wakeword.WakeEar
import com.past9.phoneaos.wakeword.WakeKeywords
import com.past9.phoneaos.wakeword.WakeLive
import com.past9.phoneaos.wakeword.WakePhrase
import com.past9.phoneaos.wakeword.WakeState
import com.past9.phoneaos.wakeword.WakeWordSettings
import com.past9.phoneaos.wakeword.WordPieces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the sheet shows, so it can be drawn (and photographed) without a phone behind it. */
data class WakeWordView(
    val settings: WakeWordSettings = WakeWordSettings(),
    /** "Hey" and the agent's name: what is listened for when the person typed nothing. */
    val defaultPhrase: String = "Hey Agent",
    /** The phrase in force as the model spells it, lower case; empty when it cannot be spelled. */
    val latin: String = "hey agent",
    val micAllowed: Boolean = true,
    val onTop: Boolean = true,
    val live: WakeLive = WakeLive(),
    /** Try it: listening for a test hit right now, how many it heard, and whether a sound is coming in. */
    val trying: Boolean = false,
    val tryHits: Int = 0,
    val hearing: Boolean = false,
    /** Say it three times: 0 when not teaching, else which saying it waits for (1 to 3), and a line about the last one. */
    val teachStep: Int = 0,
    val teachSaid: String = "",
) {
    val phrase: String get() = settings.customPhrase.ifBlank { defaultPhrase }
}

class WakeWordActions(
    val onEnabled: (Boolean) -> Unit = {},
    val onPhrase: (String) -> Unit = {},
    val onSensitivity: (Sensitivity) -> Unit = {},
    val onOnlyCharging: (Boolean) -> Unit = {},
    val onPauseOnSaver: (Boolean) -> Unit = {},
    val onPauseLowBattery: (Boolean) -> Unit = {},
    val onAskMic: () -> Unit = {},
    val onAskOnTop: () -> Unit = {},
    val onTry: (Boolean) -> Unit = {},
    val onTeach: () -> Unit = {},
    val onForget: () -> Unit = {},
)

/**
 * Settings > Wake word, wired to the phone: the switch, the phrase, sensitivity, the battery options, teaching it the
 * person's voice, and a live test. A slide-up sheet in the app's own pieces.
 */
@Composable
fun WakeWordSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val store = remember { com.past9.phoneaos.wakeword.WakeWord.store(app) }
    val settings by store.state.collectAsState()
    val agent by com.past9.phoneaos.App.graph(app).settings.state.collectAsState()
    val live by com.past9.phoneaos.wakeword.WakeWord.live.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val micAllowed = remember(tick) { com.past9.phoneaos.wakeword.WakeWord.micAllowed(app) }
    val onTop = remember(tick) { Settings.canDrawOverlays(app) }
    val defaultPhrase = WakePhrase.defaultFor(agent.agentName)
    val phrase = settings.customPhrase.ifBlank { defaultPhrase }
    val latin = remember(phrase) { com.past9.phoneaos.wakeword.WakeWord.latin(phrase) }

    var trying by remember { mutableStateOf(false) }
    var tryHits by remember { mutableIntStateOf(0) }
    var hearing by remember { mutableStateOf(false) }
    var teachStep by remember { mutableIntStateOf(0) }
    var teachSaid by remember { mutableStateOf("") }
    var wantOn by remember { mutableStateOf(false) }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tick++
        if (ok && wantOn) { store.setEnabled(true); com.past9.phoneaos.wakeword.WakeWord.sync(app) }
        wantOn = false
    }

    // Try it: the sheet's own ear, so a test never opens the assistant. The always-on listener stands aside meanwhile.
    val tryEar = remember { java.util.concurrent.atomic.AtomicReference<WakeEar?>(null) }
    val open = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(trying, phrase, settings.sensitivity, settings.learned) {
        var ear: WakeEar? = null
        if (trying && micAllowed) {
            MicUse.hold(TRY)
            ear = WakeEar(app, "try").apply {
                onHit = { tryHits++ }
                onFailed = { trying = false }
                start(com.past9.phoneaos.wakeword.WakeWord.keywords(app, settings, phrase))
            }
            tryEar.set(ear)
        }
        onDispose { tryEar.set(null); ear?.stop(); if (trying) MicUse.release(TRY) }
    }
    LaunchedEffect(trying) { while (trying) { delay(120); hearing = tryEar.get()?.hearing == true }; hearing = false }
    DisposableEffect(Unit) { onDispose { open.set(false); MicUse.release(TRY); MicUse.release(TEACH) } }

    fun teach() {
        if (teachStep != 0) return
        trying = false
        scope.launch {
            MicUse.hold(TEACH)
            val heard = ArrayList<List<String>>()
            try {
                val model = withContext(Dispatchers.IO) { KeywordModelFiles.ensure(app) }
                val learner = SpellingLearner(model)
                val pieces = com.past9.phoneaos.wakeword.WakeWord.pieces(app)
                for (step in 1..3) {
                    teachStep = step
                    val clip = withContext(Dispatchers.IO) { WakeEar.hearOnce(keepGoing = { open.get() }) }
                    val got = if (clip == null) emptyList() else withContext(Dispatchers.Default) { learner.hear(FloatArray(clip.size) { clip[it] / 32768f }) }
                    if (got.size >= WakeKeywords.FEWEST_PIECES) { heard.add(got); teachSaid = "Heard \"${spoken(got)}\"" }
                    else teachSaid = "Didn't catch that one. Say it a little louder."
                    delay(700)
                }
                learner.close()
                val learned = Learned.merge(emptyList(), heard, pieces)
                if (learned.isNotEmpty()) { store.setLearned(phrase, learned); teachSaid = "Learned it. Try it now." }
                else teachSaid = "It didn't hear the phrase. Find a quieter spot and try again."
            } finally { teachStep = 0; MicUse.release(TEACH) }
        }
    }

    WakeWordSheetContent(
        WakeWordView(settings, defaultPhrase, latin.lowercase(), micAllowed, onTop, live, trying, tryHits, hearing, teachStep, teachSaid),
        WakeWordActions(
            onEnabled = { on ->
                if (on && !micAllowed) { wantOn = true; askMic.launch(Manifest.permission.RECORD_AUDIO) }
                else { store.setEnabled(on); com.past9.phoneaos.wakeword.WakeWord.sync(app) }
            },
            onPhrase = { store.setPhrase(it) },
            onSensitivity = { store.setSensitivity(it) },
            onOnlyCharging = { store.setOnlyCharging(it) },
            onPauseOnSaver = { store.setPauseOnSaver(it) },
            onPauseLowBattery = { store.setPauseLowBattery(it) },
            onAskMic = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
            onAskOnTop = { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            onTry = { on -> if (on && !micAllowed) askMic.launch(Manifest.permission.RECORD_AUDIO) else { if (on) tryHits = 0; trying = on } },
            onTeach = { if (!micAllowed) askMic.launch(Manifest.permission.RECORD_AUDIO) else teach() },
            onForget = { store.setLearned(phrase, emptyList()); teachSaid = "" },
        ),
        onDismiss,
    )
}

private const val TRY = "wake_try"
private const val TEACH = "wake_teach"

/** Word pieces as something a person can read: "▁HE Y ▁TA ND Y" is "hey tandy". */
internal fun spoken(pieces: List<String>): String = pieces.joinToString("").replace(WordPieces.WORD, " ").trim().lowercase()

/** The sheet itself, from plain values. */
@Composable
fun WakeWordSheetContent(v: WakeWordView, a: WakeWordActions, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val s = v.settings
    val fit = WakePhrase.fit(v.latin.uppercase())
    AppSheet(Icons.Rounded.RecordVoiceOver, "Settings", "Wake word", onDismiss = onDismiss, primary = "Done", primaryEnabled = true, onPrimary = onDismiss) {
        Text("Say \"${v.phrase}\" and your agent opens, from any screen, with the app closed or the screen off.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Text("Early: may miss or mishear.", style = MaterialTheme.typography.bodySmall, color = cs.tertiary, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        SwitchRow(Icons.Rounded.Hearing, "Listen for it", when {
            !s.enabled -> "Off"
            v.live.state is WakeState.Paused -> (v.live.state as WakeState.Paused).why.line
            v.live.state == WakeState.Listening -> "Listening now"
            else -> "Starting"
        }, s.enabled, a.onEnabled)
        if (!v.micAllowed) FixRow(Icons.Rounded.Mic, "Microphone", "Tap to allow it. It is only used to hear the phrase.", a.onAskMic)
        if (!v.onTop) FixRow(Icons.Rounded.Layers, "Appear on top", "Tap and allow, so it can open while you are in another app. Without it you get a notification to tap.", a.onAskOnTop)

        Spacer(Modifier.height(20.dp))
        SheetField(s.customPhrase, a.onPhrase, "Phrase", v.defaultPhrase, singleLine = true, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        Text(
            (if (s.customPhrase.isBlank()) "Follows your agent's name. Rename your agent and this changes with it. " else "") + WakePhrase.advice(v.phrase, v.latin.uppercase()),
            style = MaterialTheme.typography.bodySmall, color = if (fit == WakePhrase.Fit.GOOD) cs.onSurfaceVariant else cs.error, modifier = Modifier.padding(start = 4.dp, top = 6.dp))

        Spacer(Modifier.height(20.dp))
        ChoiceChips(Sensitivity.entries.map { Triple(it.name, it.label, null as ImageVector?) }, s.sensitivity.name, { a.onSensitivity(Sensitivity.of(it)) }, label = "How easily it wakes")
        Text(s.sensitivity.line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp))

        Spacer(Modifier.height(20.dp))
        Text("Try it", style = MaterialTheme.typography.titleSmall)
        Text("Say the phrase and watch it land. A test never opens your agent.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { a.onTry(!v.trying) }) { Text(if (v.trying) "Stop" else "Try it") }
            Spacer(Modifier.width(16.dp))
            if (v.trying) {
                Surface(color = if (v.tryHits > 0) cs.primary else if (v.hearing) cs.tertiary else cs.surfaceContainerHighest, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.size(16.dp)) {}
                Spacer(Modifier.width(8.dp))
                Text(when { v.tryHits == 1 -> "Heard it"; v.tryHits > 1 -> "Heard it ${v.tryHits} times"; v.hearing -> "Hearing you"; else -> "Say \"${v.phrase}\"" },
                    style = MaterialTheme.typography.bodyMedium, color = if (v.tryHits > 0) cs.primary else cs.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Teach it your voice", style = MaterialTheme.typography.titleSmall)
        Text("If it misses you, or the name isn't said the way English spells it, say the phrase three times. It learns the sound from you, in any language.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = a.onTeach, enabled = v.teachStep == 0) { Text(if (v.teachStep == 0) "Say it three times" else "Say it now: ${v.teachStep} of 3") }
            if (s.learnedFor(v.phrase).isNotEmpty() && v.teachStep == 0) { Spacer(Modifier.width(8.dp)); TextButton(onClick = a.onForget) { Text("Forget") } }
        }
        val learned = s.learnedFor(v.phrase)
        val teachLine = v.teachSaid.ifBlank { if (learned.isNotEmpty()) "Learned from your voice: " + learned.joinToString(", ") { "\"${spoken(it)}\"" } else "" }
        if (teachLine.isNotBlank()) Text(teachLine, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp))

        Spacer(Modifier.height(20.dp))
        Text("Battery", style = MaterialTheme.typography.titleSmall)
        Text("Listening keeps a small part of the phone awake. These pause it when power matters more.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        SwitchRow(Icons.Rounded.BatterySaver, "Pause on battery saver", "On by default", s.pauseOnSaver, a.onPauseOnSaver)
        SwitchRow(Icons.Rounded.BatteryAlert, "Pause when battery is under 20%", "Listens again on the charger", s.pauseLowBattery, a.onPauseLowBattery)
        SwitchRow(Icons.Rounded.Power, "Only while charging", "Never listens on battery", s.onlyCharging, a.onOnlyCharging)

        Spacer(Modifier.height(16.dp))
        Text("The phrase is recognised on this phone. Sound never leaves it, nothing is recorded, and no account or key is involved. While it listens, Android shows the microphone dot and a notification with a Pause button.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("Typed names work best when they read naturally in English letters. Any other name, or any other language, works once you teach it your voice.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(on, onChange)
    }
}

@Composable
private fun FixRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
        }
    }
}
