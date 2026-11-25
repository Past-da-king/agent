package com.past9.phoneaos.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.content.ContextCompat
import com.past9.phoneaos.MainActivity
import com.past9.phoneaos.R

/** A media-session pause action remains available when the screen is locked. */
internal class NoteMediaControls(context: Context, private val pause: () -> Unit) {
    companion object {
        const val NOTIFICATION_ID = 71204
        private const val CHANNEL = "voice_note_playback"
    }
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val action = app.packageName + ".PAUSE_NOTE." + System.identityHashCode(this)
    private var session: MediaSession? = null
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == action) pause()
        }
    }

    fun show(position: Int, duration: Int) {
        if (!registered) {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        val media = session ?: MediaSession(app, "VoiceNote").also {
            it.setCallback(object : MediaSession.Callback() {
                override fun onPause() = pause()
                override fun onStop() = pause()
            })
            it.isActive = true
            session = it
        }
        media.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, app.getString(R.string.app_name))
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, app.getString(R.string.note_pause))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong()).build())
        media.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(PlaybackState.STATE_PLAYING, position.toLong(), 1f).build())
        manager.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        val pauseIntent = PendingIntent.getBroadcast(app, 0, Intent(action).setPackage(app.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(app, 0,
            Intent(app, MainActivity::class.java).putExtra("open", "chat"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(app.getString(R.string.app_name))
            .setContentText(app.getString(R.string.note_pause))
            .setContentIntent(openIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,
                app.getString(R.string.chat_pause), pauseIntent).build())
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun hide() {
        // A paused, never-started cached note must not cancel another note's controls.
        if (session == null && !registered) return
        manager.cancel(NOTIFICATION_ID)
        if (registered) { app.unregisterReceiver(receiver); registered = false }
        session?.release()
        session = null
    }
}
