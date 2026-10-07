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

    override fun workStarted() { if (active.incrementAndGet() == 1) runCatching { context.startForegroundService(Intent(context, WorkService::class.java)) } }
    override fun workFinished() { if (active.decrementAndGet() <= 0) { active.set(0); context.stopService(Intent(context, WorkService::class.java)) } }

    companion object { const val CH_MESSAGES = "messages"; const val CH_WORK = "work" }
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

/** Keeps the process alive while the agent is mid-task and the user has left the app. */
class WorkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val st = App.graph(this).settings.state.value
        val n = Identity.asAgent(this, NotificationCompat.Builder(this, AndroidPhone.CH_WORK).setSmallIcon(Identity.statIcon(this, st.mascot))
            .setContentTitle("${st.agentName.ifBlank { "Your agent" }} is working").setContentText("You can leave the app. I will let you know.").setOngoing(true).setContentIntent(open),
            "", "Working on it. You can leave the app, I will let you know.").build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(42, n)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try { com.past9.phoneaos.triggers.Routines.rescheduleAll(context) } finally { pending.finish() }
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
