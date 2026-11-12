package com.past9.phoneaos.wear

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.AppScaffold
import com.past9.phoneaos.wear.ui.AgentWatchTheme
import com.past9.phoneaos.wear.ui.HomeScreen
import com.past9.phoneaos.wearproto.AppEntry
import com.past9.phoneaos.wearproto.AppGrid
import com.past9.phoneaos.wearproto.FaceData
import com.past9.phoneaos.wearproto.HomeGuide
import com.past9.phoneaos.wearproto.LaunchLog

/**
 * The agent as the watch's home screen: optional, off until he asks, never forced (docs/wear.md, "Home screen"). The app ships no home
 * handler at all: [ALIAS] declares the HOME category but is disabled in the manifest, so installing the app changes nothing about how
 * the watch starts. "Use as home screen" switches that one component on and then asks the system to make it the home app, by the
 * HOME role where the watch has it, else by the system's own home or default-apps screen. "Back to the stock home" switches the
 * component off again, which is by itself enough for the system to fall back to the launcher the watch shipped with.
 */
object HomeLauncher {
    const val ALIAS = "com.past9.phoneaos.wear.HomeAlias"
    private const val PREFS = "watch_home"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- the app list ---------------------------------------------------------------------------------------------

    /** Every app with a launcher entry, through the package manager (the manifest declares the query), in the order [AppGrid] gives. */
    fun apps(c: Context): List<AppEntry> {
        val pm = c.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.activityInfo.name) }
        return AppGrid.order(found, c.packageName, hidden(c))
    }

    fun hidden(c: Context): Set<String> = prefs(c).getStringSet("hidden", emptySet()).orEmpty()
    fun log(c: Context): LaunchLog = LaunchLog.fromJson(prefs(c).getString("log", null))

    fun launch(c: Context, e: AppEntry): Boolean {
        val ok = runCatching { c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(e.pkg, e.activity)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        if (ok) { val l = log(c); l.record(e.pkg); prefs(c).edit().putString("log", l.toJson()).apply() }
        return ok
    }

    // ---- turning it on and off --------------------------------------------------------------------------------------

    private fun alias(c: Context) = ComponentName(c.packageName, ALIAS)

    fun componentOn(c: Context): Boolean = c.packageManager.getComponentEnabledSetting(alias(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setComponent(c: Context, on: Boolean) {
        c.packageManager.setComponentEnabledSetting(alias(c), if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }

    private fun roleManager(c: Context): RoleManager? = if (Build.VERSION.SDK_INT >= 29) c.getSystemService(RoleManager::class.java) else null
    fun roleAvailable(c: Context): Boolean = runCatching { roleManager(c)?.isRoleAvailable(RoleManager.ROLE_HOME) == true }.getOrDefault(false)
    fun roleHeld(c: Context): Boolean = runCatching { roleManager(c)?.isRoleHeld(RoleManager.ROLE_HOME) == true }.getOrDefault(false)
    fun requestRoleIntent(c: Context): Intent? = runCatching { roleManager(c)?.createRequestRoleIntent(RoleManager.ROLE_HOME) }.getOrNull()

    /** The package that answers Home now. The system's chooser (package "android") means no default is set. */
    fun defaultHome(c: Context): String {
        val p = runCatching { c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull().orEmpty()
        return if (p == "android") "" else p
    }

    private fun resolvable(c: Context, i: Intent): Intent? = i.takeIf { c.packageManager.resolveActivity(it, 0) != null }

    /** The system's own screen for choosing the home app, where this watch has one. */
    fun homeSettings(c: Context): Intent? = resolvable(c, Intent(Settings.ACTION_HOME_SETTINGS))
    fun defaultApps(c: Context): Intent? = resolvable(c, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))

    fun state(c: Context): HomeGuide.State = HomeGuide.stateOf(componentOn(c), roleAvailable(c), roleHeld(c), defaultHome(c), c.packageName)
}

/** The home screen itself: the agent first, then the apps. Home pressed again comes back to the top; Back does nothing here (it is home). */
class HomeActivity : ComponentActivity() {
    private var top by androidx.compose.runtime.mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WatchApp
        app.controller.opened()
        setContent {
            AgentWatchTheme {
                AppScaffold {
                    BackHandler { /* home has nowhere to go back to */ }
                    val state by app.controller.state.collectAsState()
                    val call by app.call.state.collectAsState()
                    val apps = remember(top) { HomeLauncher.apps(this@HomeActivity) }
                    val recents = remember(top) { AppGrid.recents(apps, HomeLauncher.log(this@HomeActivity)) }
                    HomeScreen(
                        agent = state?.agent.orEmpty(), status = FaceData.status(state, call.active), waiting = FaceData.waiting(state),
                        apps = apps, recents = recents, resetKey = top,
                        onTalk = { startActivity(Intent(this@HomeActivity, MainActivity::class.java).putExtra("go", "talk")) },
                        onOpenAgent = { startActivity(Intent(this@HomeActivity, MainActivity::class.java)) },
                        onApp = { HomeLauncher.launch(this@HomeActivity, it); top++ },
                        onSettings = { startActivity(Intent(this@HomeActivity, MainActivity::class.java).putExtra("go", "home_settings")) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); top++ }
}
