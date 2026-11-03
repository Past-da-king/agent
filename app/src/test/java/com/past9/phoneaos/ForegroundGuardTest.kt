package com.past9.phoneaos

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.system.ForegroundGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 22 Oct 2026: the app crashed (ForegroundServiceDidNotStartInTimeException) when a call was refused and CallService
 * was stopped before it had called startForeground. Every foreground service now goes through ForegroundGuard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundGuardTest {
    class GoodService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
        override fun onCreate() { super.onCreate(); ForegroundGuard.enter(this, 5, 0) { NotificationCompat.Builder(this, "x").setSmallIcon(android.R.drawable.stat_notify_sync).build() } }
        override fun onDestroy() { ForegroundGuard.left(this); super.onDestroy() }
    }
    class BrokenNotificationService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
        override fun onCreate() { super.onCreate(); ForegroundGuard.enter(this, 6, 0) { throw IllegalStateException("no avatar") } }
        override fun onDestroy() { ForegroundGuard.left(this); super.onDestroy() }
    }

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun aStopBeforeTheServiceIsUpWaitsUntilItIsForegroundThenStops() {
        assertTrue(ForegroundGuard.start(app, GoodService::class.java))
        assertNotNull(shadowOf(app).nextStartedService)
        // The call was refused at once: stopService now is the crash. The guard must not call it.
        ForegroundGuard.stop(app, GoodService::class.java)
        assertNull(shadowOf(app).nextStoppedService)
        // The service comes up: it reaches startForeground FIRST, and only then stops itself.
        val service = Robolectric.buildService(GoodService::class.java).create().get()
        val shadow = shadowOf(service)
        assertNotNull(shadow.lastForegroundNotification)
        assertEquals(5, shadow.lastForegroundNotificationId)
        assertTrue(shadow.isStoppedBySelf)
        assertFalse(ForegroundGuard.isUp(GoodService::class.java))
    }

    @Test
    fun aNormalStartAndStop() {
        ForegroundGuard.start(app, GoodService::class.java)
        val controller = Robolectric.buildService(GoodService::class.java).create()
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(ForegroundGuard.isUp(GoodService::class.java))
        ForegroundGuard.stop(app, GoodService::class.java)
        assertNotNull(shadowOf(app).nextStoppedService)
        controller.destroy()
        assertFalse(ForegroundGuard.isUp(GoodService::class.java))
    }

    @Test
    fun aNotificationThatThrowsStillReachesTheForeground() {
        ForegroundGuard.start(app, BrokenNotificationService::class.java)
        val service = Robolectric.buildService(BrokenNotificationService::class.java).create().get()
        val n: Notification? = shadowOf(service).lastForegroundNotification
        assertNotNull(n)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }
}
