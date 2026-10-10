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
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages from your agent", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "Agent working", NotificationManager.IMPORTANCE_MIN))
    }

    override fun notify(title: String, body: String, questionItemId: Long?, options: List<String>) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val st = App.graph(context).settings.state.value
        val agent = Identity.publishAgentShortcut(context, st.agentName, st.mascot, st.accent)
        val me = androidx.core.app.Person.Builder().setName(st.userName.ifBlank { "You" }).setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(null).addMessage("$title\n$body", System.currentTimeMillis(), agent)
        val b = NotificationCompat.Builder(context, CH_MESSAGES).setSmallIcon(Identity.statIcon(context, st.mascot)).setContentTitle(title)
            .setColor(com.past9.phoneaos.ui.theme.accentOf(st.accent).lP.let { android.graphics.Color.rgb((it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) })
            .setContentText(body).setStyle(style).setShortcutId("agent").setLargeIcon(Identity.avatar(context, st.mascot, st.accent, 192))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setAutoCancel(true).setContentIntent(open)
        val nid = questionItemId?.toInt() ?: (System.currentTimeMillis() % 100000).toInt()
        if (questionItemId != null) options.take(3).forEachIndexed { i, opt ->
            val pi = PendingIntent.getBroadcast(context, nid * 10 + i,
                Intent(context, AnswerReceiver::class.java).putExtra("item", questionItemId).putExtra("option", opt).putExtra("nid", nid), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, opt, pi)
        }
        runCatching { NotificationManagerCompat.from(context).notify(nid, b.build()) } // no permission yet: chat still has it
    }

    override fun openLink(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun workStarted() {
        activeCount = active.incrementAndGet()
        if (activeCount == 1) runCatching { context.startForegroundService(Intent(context, WorkService::class.java)) }
    }
    override fun workFinished() {
        if (active.decrementAndGet() <= 0) { active.set(0); activeCount = 0; context.stopService(Intent(context, WorkService::class.java)) } else activeCount = active.get()
    }

    /** The "Agent is working: N helpers" line follows the count, and a safety-net check runs while any helper is working. */
    override fun helpersChanged(count: Int) {
        helperCount = count
        runCatching { WorkService.refresh(context) }
        runCatching { if (count > 0) HelperWatchdog.ensure(context) else HelperWatchdog.cancel(context) }
    }

    companion object {
        const val CH_MESSAGES = "messages"; const val CH_WORK = "work"
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
        val n = build(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(42, n)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null: Android restarted us after killing the app. Creating the graph (in build) resumes the cut-off helpers;
        // if there turns out to be nothing to resume, don't hang around.
        if (intent == null) handler.postDelayed({ if (AndroidPhone.activeCount <= 0) stopSelf() }, 45_000)
        return START_STICKY
    }

    companion object {
        fun text(helpers: Int) = if (helpers > 0) "Working: $helpers helper${if (helpers > 1) "s" else ""}" else "Working on it"

        fun build(context: Context): android.app.Notification {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val st = App.graph(context).settings.state.value
            val who = st.agentName.ifBlank { "Agent" }
            val h = AndroidPhone.helperCount
            return Identity.asAgent(context, NotificationCompat.Builder(context, AndroidPhone.CH_WORK).setSmallIcon(Identity.statIcon(context, st.mascot))
                .setContentTitle(if (h > 0) "$who is working: $h helper${if (h > 1) "s" else ""}" else "$who is working")
                .setContentText("You can leave the app. I will let you know.").setOngoing(true).setContentIntent(open),
                "", text(h)).build()
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
    /** After a restart or an app update the process is gone: bring helpers and routines back, run what was missed once. */
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                runCatching { App.graph(context) } // starting the process resumes helpers that were cut off
                runCatching { com.past9.phoneaos.triggers.Routines.rescheduleAll(context) }
                runCatching { com.past9.phoneaos.triggers.Routines.catchUp(context) }
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
