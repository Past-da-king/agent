package com.past9.phoneaos

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.system.Background
import com.past9.phoneaos.system.ExactAlarms
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val am = ctx.getSystemService(AlarmManager::class.java)

    @Test fun exactAlarmIsArmedAndReplacedNotDuplicated() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val t = System.currentTimeMillis() + 3_600_000
        ExactAlarms.set(ctx, ExactAlarms.ACTION_ROUTINE, 44, t)
        ExactAlarms.set(ctx, ExactAlarms.ACTION_ROUTINE, 44, t + 60_000)   // re-armed (boot, update, edit)
        ExactAlarms.set(ctx, ExactAlarms.ACTION_ROUTINE, 47, t)
        val alarms = shadowOf(am).scheduledAlarms
        assertEquals(2, alarms.size)
        assertEquals(t + 60_000, alarms.single { it.operation == ExactAlarms.intent(ctx, ExactAlarms.ACTION_ROUTINE, 44, 0) }.triggerAtMs)
        assertTrue(alarms.all { it.isAllowWhileIdle })
        ExactAlarms.cancel(ctx, ExactAlarms.ACTION_ROUTINE, 44)
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
    }
    @Test fun aReminderIsAnAlarmClockAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val t = System.currentTimeMillis() + 7_200_000
        ExactAlarms.set(ctx, ExactAlarms.ACTION_REMINDER, 48, t, clock = true)
        assertEquals(t, am.nextAlarmClock?.triggerTime)
    }
    @Test fun withoutExactPermissionItStillArmsAnIdleAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        ExactAlarms.set(ctx, ExactAlarms.ACTION_WAKE, 0, System.currentTimeMillis() + 60_000)
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
        assertTrue(shadowOf(am).scheduledAlarms.single().isAllowWhileIdle)
    }
    @Test fun samsungSleepingIsReported() {
        val h = Background.Health(batteryUnrestricted = false, restricted = false, exactAlarms = true, notifications = true, calendar = false, bucket = 45, samsung = true)
        val p = Background.problems(h)
        assertTrue(p.first().contains("Samsung puts it to sleep"))
        assertTrue(p.any { it.contains("most restricted") })
        assertTrue(Background.problems(h.copy(batteryUnrestricted = true, bucket = 10)).isEmpty())
    }
}
