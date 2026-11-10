package com.past9.phoneaos.wakeword

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.past9.phoneaos.App
import com.past9.phoneaos.MainActivity
import com.past9.phoneaos.system.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the listener is doing right now, for the settings sheet. */
data class WakeLive(val state: WakeState = WakeState.Off, val hits: Int = 0, val lastHitAt: Long = 0)

/** The way in for the rest of the app: is the wake word on, what is the phrase, start or stop the listener. */
object WakeWord {
    private const val TAG = "WakeWord"
    private val _live = MutableStateFlow(WakeLive())
    val live: StateFlow<WakeLive> = _live
    internal fun publish(state: WakeState) { _live.value = _live.value.copy(state = state) }
    internal fun hit() { _live.value = _live.value.let { it.copy(hits = it.hits + 1, lastHitAt = System.currentTimeMillis()) } }

    fun store(context: Context): WakeWordStore = WakeWordStore.get(context)

    /** The phrase in force: the person's own, else "Hey" and the agent's name as it is this instant. */
    fun phrase(context: Context): String = WakePhrase.current(store(context).state.value.customPhrase, App.graph(context).settings.state.value.agentName)

    /** The phone's own romaniser for names not written in Latin letters (Android 10 and later). */
    val romanizer: WakePhrase.Romanizer? = if (Build.VERSION.SDK_INT >= 29) WakePhrase.Romanizer { text ->
        android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII").transliterate(text)
    } else null

    @Volatile private var pieces: WordPieces? = null
    fun pieces(context: Context): WordPieces = pieces ?: KeywordModelFiles.pieces(context).also { pieces = it }

    /** The phrase in capital Latin letters, as the model will be asked to hear it. */
    fun latin(phrase: String): String = WakePhrase.latin(phrase, romanizer)

    /** The keyword lines for the phrase and settings in force. Empty when the phrase cannot be listened for. */
    fun keywords(context: Context, s: WakeWordSettings = store(context).state.value, phrase: String = phrase(context)): List<String> {
        val p = pieces(context)
        return WakeKeywords.lines(p.split(latin(phrase)), s.learnedFor(phrase), WakeKeywords.tuning(s.sensitivity))
    }

    fun micAllowed(context: Context): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Bring the listener in line with the settings: running when the wake word is on, gone when it is off. Called
     * when the switch changes, when the app is opened and after the phone restarts. If Android refuses to start a
     * microphone service from the background, a plain notification says so and one tap on it starts the listener.
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (!store(app).state.value.enabled) { WakeWordService.stop(app); publish(WakeState.Off); return }
        if (WakeWordService.start(app)) { cancelNudge(app); WakeWordService.poke() } else nudge(app)
    }

    private const val NUDGE_ID = 83
    internal const val CHANNEL = "wake_word"

    internal fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false); setShowBadge(false) })
    }

    internal fun openApp(context: Context, code: Int): PendingIntent = PendingIntent.getActivity(context, code,
        Intent(context, MainActivity::class.java).putExtra("open", "wakeword").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Android would not let the listener start by itself (after a restart, say): one tap opens the app, which starts it. */
    private fun nudge(context: Context) {
        runCatching {
            ensureChannel(context)
            val st = App.graph(context).settings.state.value
            val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(Identity.statIcon(context, st.mascot))
                .setContentTitle("Tap to listen for \"${phrase(context)}\" again")
                .setContentText("Android stopped the listener. Opening the app starts it.")
                .setContentIntent(openApp(context, 31)).setAutoCancel(true).build()
            context.getSystemService(NotificationManager::class.java)?.notify(NUDGE_ID, n)
        }.onFailure { Log.w(TAG, "could not say the listener is down: ${it.message}") }
    }

    private fun cancelNudge(context: Context) { runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NUDGE_ID) } }
}

/**
 * Where a heard wake phrase goes: the assistant's voice sheet.
 *
 * The contract with the assistant: an explicit intent inside this app, action [ACTION], extra "source" = "wake_word".
 * If nothing in the app answers that action (a build without the assist sheet), the app's voice screen opens instead,
 * so a wake word never opens nothing.
 */
object WakeTarget {
    const val ACTION = "com.past9.phoneaos.action.ASSIST"
    const val SOURCE = "wake_word"
    private const val HEARD_ID = 84

    fun intent(context: Context): Intent {
        val assist = Intent(ACTION).setPackage(context.packageName).putExtra("source", SOURCE)
        val found = context.packageManager.resolveActivity(assist, 0) != null
        val i = if (found) assist else Intent(context, MainActivity::class.java).putExtra("open", "call").putExtra("source", SOURCE)
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    /**
     * Open it. Android only lets an app that is not on screen open a screen when it may appear on top of other apps;
     * without that the person gets a heads-up notification to tap instead. False when neither could be done.
     */
    fun open(context: Context): Boolean {
        val i = intent(context)
        val mayOpen = onScreen(context) || Settings.canDrawOverlays(context)
        val started = runCatching { context.startActivity(i); true }.getOrElse { Log.w("WakeTarget", "could not open: ${it.message}"); false }
        if (started && mayOpen) return true
        return runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            nm.createNotificationChannel(NotificationChannel("wake_word_heard", "Wake word heard", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
            val st = App.graph(context).settings.state.value
            val pi = PendingIntent.getActivity(context, 32, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(HEARD_ID, NotificationCompat.Builder(context, "wake_word_heard").setSmallIcon(Identity.statIcon(context, st.mascot))
                .setContentTitle("I heard you").setContentText("Tap to talk. Allow Appear on top in Settings > Wake word to skip this tap.")
                .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).setTimeoutAfter(15_000)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_CALL).build())
            true
        }.getOrDefault(started)
    }

    private fun onScreen(context: Context): Boolean = runCatching {
        val me = ActivityManager.RunningAppProcessInfo(); ActivityManager.getMyMemoryState(me)
        me.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)
}
