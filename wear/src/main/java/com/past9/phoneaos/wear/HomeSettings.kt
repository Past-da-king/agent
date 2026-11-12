package com.past9.phoneaos.wear

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.past9.phoneaos.wear.ui.HomeSettingsScreen
import com.past9.phoneaos.wearproto.HomeGuide

/**
 * The settings for using the agent as the watch's home screen (HomeLauncher.kt). Turning it on switches the one disabled component on and
 * then asks the system, by the HOME role where the watch has it, else by the system's own screen; turning it off switches the component
 * off, which hands Home back to the stock launcher whatever the watch's settings say.
 */
@Composable
internal fun HomeSettings(context: Context) {
    var tick by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    val state = remember(tick) { HomeLauncher.state(context) }
    val roleAvailable = remember { HomeLauncher.roleAvailable(context) }
    val homeSettings = remember { HomeLauncher.homeSettings(context) }
    val defaultApps = remember { HomeLauncher.defaultApps(context) }
    val asked = stringResource(R.string.home_state_asked)
    val back = stringResource(R.string.home_state_off)
    val noPicker = stringResource(R.string.home_no_picker)
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    fun open(i: Intent?): Boolean = i != null && runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

    HomeSettingsScreen(
        state = state, hasPicker = roleAvailable || homeSettings != null || defaultApps != null, note = note,
        onTurnOn = {
            HomeLauncher.setComponent(context, true)
            val done = when (HomeGuide.turnOn(roleAvailable, homeSettings != null, defaultApps != null).last()) {
                HomeGuide.Step.REQUEST_ROLE -> HomeLauncher.requestRoleIntent(context)?.let { runCatching { role.launch(it); true }.getOrDefault(false) } ?: false
                HomeGuide.Step.OPEN_HOME_SETTINGS -> open(homeSettings)
                HomeGuide.Step.OPEN_DEFAULT_APPS -> open(defaultApps)
                else -> false
            }
            // A watch that would not open any system screen is told what to do instead of being left guessing.
            note = if (done) asked else noPicker
            tick++
        },
        onTurnOff = {
            HomeLauncher.setComponent(context, false)
            note = back
            tick++
        },
        onOpenSystem = if (homeSettings != null || defaultApps != null) ({ open(homeSettings ?: defaultApps) }) else null,
    )
}
