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

    /** agent://miniapp/<id> (a chat card, or the computer's miniapp_open) -> the app's id; anything else -> null. */
    fun idFromLink(link: String?): String? {
        val m = Regex("^agent://miniapp/([A-Za-z0-9_-]{2,40})/?$").find(link?.trim().orEmpty()) ?: return null
        return m.groupValues[1]
    }

    /** Every mini-app a reply links to (agent://miniapp/<id>), once each, in order: each becomes a card under it. */
    fun linksIn(text: String): List<String> = Regex("agent://miniapp/([A-Za-z0-9_-]{2,40})").findAll(text).map { it.groupValues[1] }.distinct().toList()

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

    /**
     * agent.openUrl (and a plain link click) on the phone: only an https address with a host and no user name or
     * password, checked again here whatever the computer said. Android then opens it in the app it picks, so a Teams
     * or Meet link opens that app. Null: it does not open.
     */
    fun openableUrl(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > 4096) return null
        val m = Regex("^https://([^/?#\\s]+)(?:[/?#]\\S*)?$", RegexOption.IGNORE_CASE).matchEntire(text) ?: return null
        val authority = m.groupValues[1]
        if ('@' in authority || authority.startsWith(":") || authority.startsWith(".")) return null
        return text
    }

    /**
     * The link an "open" request should open once the computer answered: the checked address it handed back (it
     * checked, rate-limited and logged it). A computer from before agent.openUrl answers "Unknown request open": then
     * the phone's own check and limit decide. Null: nothing opens, and the computer's answer goes to the app as it is.
     */
    fun linkToOpen(request: JSONObject, reply: JSONObject?): String? {
        if (request.optString("op") != "open" || reply == null) return null
        if (reply.optBoolean("ok")) return openableUrl(reply.optJSONObject("result")?.optString("open"))
        return if (reply.optString("error").startsWith("Unknown request open")) openableUrl(request.optString("url")) else null
    }

    /** The answer the app gets once the phone tried to open the link: null error = opened. */
    fun openedReply(reply: JSONObject, error: String?): JSONObject {
        val out = JSONObject().put("op", "reply").put("id", reply.optInt("id")).put("ok", error == null)
        return if (error == null) out.put("result", "Opened.") else out.put("error", error)
    }
}

/** At most 4 links in 30 s from the open apps on this phone (the computer has its own limit per app). */
class OpenLimiter(private val now: () -> Long = { System.currentTimeMillis() }, private val max: Int = 4, private val perMs: Long = 30_000) {
    private val times = ArrayDeque<Long>()
    @Synchronized fun take(): Boolean {
        val t = now()
        while (times.isNotEmpty() && t - times.first() >= perMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(t)
        return true
    }
}

object MiniAppsHub {
    private val _apps = MutableStateFlow<List<MiniAppInfo>>(emptyList())
    val apps: StateFlow<List<MiniAppInfo>> = _apps
    /** Messages main sends an open frame: (session, message). */
    private val _pushes = MutableSharedFlow<Pair<String, JSONObject>>(extraBufferCapacity = 64)
    val pushes: SharedFlow<Pair<String, JSONObject>> = _pushes
    /** A home-screen shortcut asked to open this app. */
    val pendingOpen = MutableStateFlow<String?>(null)

    /** Files of mini-apps already served, by app, version and path (about 2 MB at most), so opening one again is instant. */
    private val files = object : LinkedHashMap<String, JSONObject>(32, 0.75f, true) {
        var bytes = 0
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean =
            (bytes > 2_000_000).also { if (it && eldest != null) bytes -= eldest.value.optString("body").length }
    }
    @Synchronized fun served(key: String): JSONObject? = files[key]
    @Synchronized fun keep(key: String, file: JSONObject) { files[key] = file; files.bytes += file.optString("body").length }
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
