package com.past9.phoneaos.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.wearproto.LaunchHealth
import com.past9.phoneaos.wearproto.LaunchLimit
import com.past9.phoneaos.wearproto.PairBattery

/**
 * What the watch can read about why the app may be slow to open or hold back (docs/wear.md, "When the app will not open"): the battery level
 * and the system's power saving. The rules are [LaunchHealth] and [PairBattery] (pure, tested); this only reads the two facts.
 */
object WatchHealth {
    /** Battery percent, or -1 when it cannot be read. Reading the sticky broadcast costs nothing and needs no permission. */
    fun batteryPercent(c: Context): Int = runCatching {
        val i = c.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        percentOf(i)
    }.getOrDefault(-1)

    fun percentOf(i: Intent?): Int {
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    fun powerSave(c: Context): Boolean = runCatching { (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }.getOrDefault(false)

    fun limit(c: Context): LaunchLimit = LaunchHealth.of(batteryPercent(c), powerSave(c))

    /** The one line to show for [limit], or null when nothing is limiting the app. */
    fun line(c: Context, limit: LaunchLimit): String? = when (limit) {
        LaunchLimit.NONE -> null
        LaunchLimit.POWER_SAVE -> c.getString(R.string.launch_power_save)
        LaunchLimit.VERY_LOW_BATTERY -> c.getString(R.string.launch_very_low)
    }
}

/** The battery percent, kept current while the screen that asks is up. */
@Composable
fun rememberBatteryPercent(): Int {
    val ctx = LocalContext.current
    var pct by remember { mutableIntStateOf(WatchHealth.batteryPercent(ctx)) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { pct = WatchHealth.percentOf(i) } }
        runCatching { ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    return pct
}

/**
 * The one line the pairing screen puts under the code when something may make pairing slow: low battery first (the code is on screen and
 * the screen is kept on, which drains it), then power saving. Null when there is nothing to say.
 */
@Composable
fun pairNotice(percent: Int, powerSave: Boolean): String =
    when {
        PairBattery.low(percent) -> stringResource(R.string.pair_battery_low)
        powerSave -> stringResource(R.string.launch_power_save)
        else -> ""
    }
