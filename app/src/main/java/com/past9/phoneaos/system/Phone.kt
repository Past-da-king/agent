package com.past9.phoneaos.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.past9.phoneaos.App
import com.past9.phoneaos.MainActivity
import com.past9.phoneaos.R
import com.past9.phoneaos.agent.PhoneBridge
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** The runtime's hands on the phone: notifications, opening links, keeping work alive. */
class AndroidPhone(private val context: Context) : PhoneBridge {
    private val active = AtomicInteger(0)

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, context.getString(R.string.svc_messages_channel), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WORK, context.getString(R.string.svc_work_channel), NotificationManager.IMPORTANCE_MIN))
    }

    override fun notify(title: String, body: String, questionItemId: Long?, options: List<String>) {
        // A notification can't draw cards: show what they are about instead of their code.
        val shown = com.past9.phoneaos.ui.openui.OpenUiLang.plainText(body)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val st = App.graph(context).settings.state.value
        val agent = Identity.publishAgentShortcut(context, st.agentName, st.mascot, st.accent)
        val me = androidx.core.app.Person.Builder().setName(st.userName.ifBlank { context.getString(R.string.svc_you) }).setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(null).addMessage("$title\n$shown", System.currentTimeMillis(), agent)
        val b = NotificationCompat.Builder(context, CH_MESSAGES).setSmallIcon(Identity.statIcon(context, st.mascot)).setContentTitle(title)
            .setColor(tint(st.accent))
            .setContentText(shown).setStyle(style).setShortcutId("agent").setLargeIcon(Identity.avatar(context, st.mascot, st.accent, 192))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setAutoCancel(true).setContentIntent(open)
        val nid = questionItemId?.toInt() ?: (System.currentTimeMillis() % 100000).toInt()
        if (questionItemId != null) com.past9.phoneaos.agent.AskOptions.fit(options, 3).forEachIndexed { i, opt ->
            val pi = PendingIntent.getBroadcast(context, nid * 10 + i,
                Intent(context, AnswerReceiver::class.java).putExtra("item", questionItemId).putExtra("option", opt).putExtra("nid", nid), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, opt, pi)
        }
        runCatching { NotificationManagerCompat.from(context).notify(nid, b.build()) } // no permission yet: chat still has it
    }

    /**
     * A turn in the chat has ended and its last word was [reply]: tell the user, unless the chat is already in front of
     * them. Issue #15 (5 Nov 2026): the "working" notification used to be the last thing the phone said. When a
     * background turn finished, the work service stopped and took that notification with it, and the reply sat in a
     * chat nobody had been told about. This is a notification of its own, on the messages channel and under its own id,
     * so the working notification coming and going cannot take it; and it is always the same id, so an afternoon of
     * work leaves one line behind (the newest reply), not twenty.
     */
    override fun replyPosted(reply: String) {
        // The chat is open in front of them, or the agent chose to say nothing: there is nothing to tell.
        val text = reply.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || ChatOnScreen.showing()) return
        // Android 13+ can refuse notifications at the permission, and older versions in the settings: either way the
        // reply is in the chat, so nothing is posted and nothing is said about it.
        val allowed = (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!allowed) return
        val st = App.graph(context).settings.state.value
        // Tapping it opens the conversation itself; reading it there takes it out of the shade.
        val open = PendingIntent.getActivity(context, NID_REPLY,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open", "chat"), PendingIntent.FLAG_IMMUTABLE)
        val shown = short(text)
        val b = NotificationCompat.Builder(context, CH_MESSAGES).setSmallIcon(Identity.statIcon(context, st.mascot))
            .setColor(tint(st.accent)).setContentTitle(st.agentName.ifBlank { "Your agent" }).setContentText(shown)
            .setAutoCancel(true).setContentIntent(open)
        Identity.asAgent(context, b, "", shown)
        runCatching { NotificationManagerCompat.from(context).notify(NID_REPLY, b.build()) }
    }

    /** How much of a reply the shade shows: one paragraph's worth. The whole reply stays in the chat. */
    private fun short(text: String, max: Int = 400) = if (text.length <= max) text else text.take(max).trimEnd() + "\u2026"

    /** The user's accent colour, outside Compose. */
    private fun tint(accent: String): Int = com.past9.phoneaos.ui.theme.accentOf(accent).lP
        .let { android.graphics.Color.rgb((it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }

    override fun openLink(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun workStarted() {
        activeCount = active.incrementAndGet()
        if (activeCount == 1) ForegroundGuard.start(context, WorkService::class.java)
    }
    override fun workFinished() {
        // A quick turn can finish before the service is up: the guard stops it once it is, never before (that crashes).
        if (active.decrementAndGet() <= 0) { active.set(0); activeCount = 0; ForegroundGuard.stop(context, WorkService::class.java) } else activeCount = active.get()
    }

    /** The "Agent is working: N helpers" line follows the count, and a safety-net check runs while any helper is working. */
    override fun helpersChanged(count: Int) {
        helperCount = count
        runCatching { WorkService.refresh(context) }
        runCatching { if (count > 0) HelperWatchdog.ensure(context) else HelperWatchdog.cancel(context) }
    }

    companion object {
        const val CH_MESSAGES = "messages"; const val CH_WORK = "work"
        /** The one notification a finished turn leaves behind. Far above every other id here, so it can never land on a
         *  question card's (a chat row's id) or a reminder's (200_000 + id). */
        const val NID_REPLY = 1_500_000_042
        /** The chat is open: whatever waited in the shade for it has been read. */
        fun clearReply(context: Context) { runCatching { NotificationManagerCompat.from(context).cancel(NID_REPLY) } }
        /** Work in this process right now (helpers, a chat turn, a routine) and how many of it is helpers. */
        @Volatile var activeCount = 0
        @Volatile var helperCount = 0
    }
}

/** Answers a question card straight from the notification shade. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val item = intent.getLongExtra("item", -1); val option = intent.getStringExtra("option") ?: return
        if (option == "Open browser") {
            context.startActivity(Intent(context, MainActivity::class.java).putExtra("open", "browser").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        }
        App.graph(context).runtime.answer(item, option)
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra("nid", 0))
    }
}

/**
 * Keeps the process alive while the agent is mid-task and the user has left the app. It is sticky: if Android
 * still kills the app, the system starts the service (and so the app) again, and the helpers that were cut off
 * pick up from their last checkpoint. It stops itself when nothing is left to do.
 */
class WorkService : Service() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        // Foreground first, through the guard: a throw while building the notification still reaches startForeground.
        ForegroundGuard.enter(this, 42, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) { build(this) }
    }
    override fun onDestroy() { ForegroundGuard.left(this); handler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null: Android restarted us after killing the app. Creating the graph (in build) resumes the cut-off helpers;
        // if there turns out to be nothing to resume, don't hang around.
        if (intent == null) handler.postDelayed({ if (AndroidPhone.activeCount <= 0) stopSelf() }, 45_000)
        // Started by an alarm to hold the process for the work it queues: let go if that work never starts.
        if (intent?.getBooleanExtra(EXTRA_KEEPALIVE, false) == true) handler.postDelayed({ if (AndroidPhone.activeCount <= 0) stopSelf() }, 120_000)
        return START_STICKY
    }

    companion object {
        const val EXTRA_KEEPALIVE = "keepalive"
        fun text(context: Context, helpers: Int) = if (helpers > 0) context.resources.getQuantityString(R.plurals.svc_work_helpers, helpers, helpers) else context.getString(R.string.svc_work_on_it)

        fun build(context: Context): android.app.Notification {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val st = App.graph(context).settings.state.value
            val who = when (st.agentName) { "" -> "Agent"; "Your agent" -> context.getString(R.string.svc_your_agent); else -> st.agentName }
            val h = AndroidPhone.helperCount
            return Identity.asAgent(context, NotificationCompat.Builder(context, AndroidPhone.CH_WORK).setSmallIcon(Identity.statIcon(context, st.mascot))
                .setContentTitle(if (h > 0) context.resources.getQuantityString(R.plurals.svc_work_title_helpers, h, who, h) else context.getString(R.string.svc_work_title, who))
                .setContentText(context.getString(R.string.svc_work_text)).setOngoing(true).setContentIntent(open),
                "", text(context, h)).build()
        }

        /** Update the ongoing notification in place (the helper count changed). */
        fun refresh(context: Context) {
            if (AndroidPhone.activeCount <= 0) return
            NotificationManagerCompat.from(context).notify(42, build(context))
        }
    }
}

/**
 * The safety net: while helpers are working this wakes every 15 minutes. If the app died and nothing brought it
 * back (a force-stop-free kill the service restart missed), starting the process here resumes the cut-off helpers.
 */
object HelperWatchdog {
    private const val NAME = "helper-watchdog"
    fun ensure(context: Context) {
        androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<WatchdogWorker>(15, java.util.concurrent.TimeUnit.MINUTES).build())
    }
    fun cancel(context: Context) { androidx.work.WorkManager.getInstance(context).cancelUniqueWork(NAME) }
}

class WatchdogWorker(context: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = App.graph(applicationContext) // a fresh process resumes cut-off helpers as it starts
        runCatching { g.runtime.resumeCutOffHelpers() } // a live process: pick up any helper that has lost its coroutine
        if (g.runtime.status.value.helpers == 0 && g.runtime.workingHelperRows() == 0) HelperWatchdog.cancel(applicationContext)
        return Result.success()
    }
}

class BootReceiver : BroadcastReceiver() {
    /** After a restart, an app update or a clock change: bring helpers, routines, reminders and the check-in back, run what was missed once. */
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // Starting the process resumes helpers that were cut off; then every clock is armed again
                // (alarms do not survive a reboot, an update, or a clock change).
                Rearm.all(context)
            } finally { pending.finish() }
        }
    }
}

/** Speaks replies when the user turned voice replies on. */
class Speaker(context: Context) : android.speech.tts.TextToSpeech.OnInitListener {
    private val tts = android.speech.tts.TextToSpeech(context.applicationContext, this)
    private var ready = false
    override fun onInit(status: Int) { ready = status == android.speech.tts.TextToSpeech.SUCCESS }
    fun say(text: String) { if (ready) tts.speak(text.replace(Regex("[*_#`>]"), ""), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "reply") }
    fun stop() = tts.stop()
}
