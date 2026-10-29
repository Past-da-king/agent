package com.past9.phoneaos.miniapps

import com.past9.phoneaos.remote.RemoteHub
import com.past9.phoneaos.remote.RemoteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mini-apps on the phone (desktop docs/plans/mini-apps.md): the small apps his agents made, listed from the computer
 * without their files (miniapps.list), pinned ones in the same order as the computer's sidebar. An open app runs in a
 * locked-down WebView whose every file and every bridge call goes to the computer (miniapps.serve, miniapps.call):
 * the same checks, permissions and approval cards as the window there. Nothing runs for an app that is not open.
 */
data class MiniAppInfo(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val pinned: Int,
    val version: Int,
    val checked: Boolean,
    val openedAt: Long,
)

object MiniAppRules {
    /** The virtual address an app is served from in the WebView. Nothing else loads. */
    fun hostOf(id: String) = "$id.miniapp.invalid"

    fun isAppId(id: String) = Regex("^ma[a-z0-9]{6,30}$").matches(id)

    /** May the WebView showing [appId] load this address? Only its own virtual host over https. */
    fun mayLoad(appId: String, scheme: String?, host: String?): Boolean = scheme == "https" && host == hostOf(appId)

    fun parseList(raw: Any?): List<MiniAppInfo> {
        val a = raw as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id")
            if (!isAppId(id)) return@mapNotNull null
            MiniAppInfo(id, o.optString("name"), o.optString("description"), o.optString("icon"), o.optInt("pinned"), o.optInt("version", 1), o.optBoolean("checked"), o.optLong("openedAt"))
        }.sortedBy { it.name.lowercase() }
    }

    fun pinned(apps: List<MiniAppInfo>): List<MiniAppInfo> = apps.filter { it.pinned > 0 }.sortedWith(compareBy({ it.pinned }, { it.name.lowercase() }))

    /** A message from main for one open session, or null when it is for another. */
    fun pushFor(session: String, payload: Any?): JSONObject? {
        val o = payload as? JSONObject ?: return null
        if (o.optString("session") != session) return null
        return o.optJSONObject("message")
    }

    /** The theme message the bridge understands (shared/miniapp-kit.ts agent.js). */
    fun themeMessage(dark: Boolean, accent: String): JSONObject = JSONObject().put("op", "theme").put("dark", dark).put("accent", accent)
}

object MiniAppsHub {
    private val _apps = MutableStateFlow<List<MiniAppInfo>>(emptyList())
    val apps: StateFlow<List<MiniAppInfo>> = _apps
    /** Messages main sends an open frame: (session, message). */
    private val _pushes = MutableSharedFlow<Pair<String, JSONObject>>(extraBufferCapacity = 64)
    val pushes: SharedFlow<Pair<String, JSONObject>> = _pushes
    /** A home-screen shortcut asked to open this app. */
    val pendingOpen = MutableStateFlow<String?>(null)
    private var started = false
    private var reload: Job? = null
    private var stateJob: Job? = null

    fun session(): RemoteSession? = RemoteHub.session.value

    /** Follows the linked computer: reads the list on every connection and whenever an app changes there. */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            RemoteHub.session.collectLatest { s ->
                stateJob?.cancel()
                if (s == null) { _apps.value = emptyList(); return@collectLatest }
                // A call while the link is still connecting fails: read the list each time it is up (first time and every reconnect).
                stateJob = scope.launch { s.state.collect { if (it is com.past9.phoneaos.remote.LinkState.Connected) refreshSoon(scope, 0) } }
                s.events.collect { (name, payload) ->
                    when (name) {
                        RemoteSession.EVT_DATA -> if ((payload as? JSONObject)?.optString("entity") == "miniapp" || (payload as? JSONObject)?.optBoolean("reset") == true) refreshSoon(scope, 400)
                        "miniapps.push" -> (payload as? JSONObject)?.let { p -> p.optJSONObject("message")?.let { _pushes.tryEmit(p.optString("session") to it) } }
                    }
                }
            }
        }
    }

    private fun refreshSoon(scope: CoroutineScope, waitMs: Long) {
        reload?.cancel()
        reload = scope.launch { delay(waitMs); runCatching { refresh() } }
    }

    suspend fun refresh() {
        val s = session() ?: return
        _apps.value = MiniAppRules.parseList(s.call("miniapps.list", listOf(), 20_000))
    }

    suspend fun pin(id: String, on: Boolean) {
        val s = session() ?: return
        s.call(if (on) "miniapps.pin" else "miniapps.unpin", listOf(id), 15_000)
        refresh()
    }
}
