package com.past9.phoneaos.miniapps

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.EmptyState
import com.past9.phoneaos.ui.SubScreen
import com.past9.phoneaos.ui.theme.Eyebrow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** The app's own icon names (desktop shared/miniapps.ts MINIAPP_ICON_NAMES) as this app's icons; a drawn SVG shows as a widget. */
fun miniAppIcon(name: String): ImageVector = when (name.trim()) {
    "home" -> Icons.Rounded.Home; "tasks" -> Icons.Rounded.TaskAlt; "memory" -> Icons.Rounded.Hub; "clock" -> Icons.Rounded.Schedule
    "apps" -> Icons.Rounded.Apps; "grid" -> Icons.Rounded.Dashboard; "chat" -> Icons.Rounded.ChatBubble; "search" -> Icons.Rounded.Search
    "globe" -> Icons.Rounded.Language; "shield" -> Icons.Rounded.Shield; "check" -> Icons.Rounded.Check; "sun" -> Icons.Rounded.LightMode
    "moon" -> Icons.Rounded.DarkMode; "phone" -> Icons.Rounded.PhoneAndroid; "monitor" -> Icons.Rounded.Monitor; "key" -> Icons.Rounded.Key
    "link" -> Icons.Rounded.Link; "folder" -> Icons.Rounded.Folder; "server" -> Icons.Rounded.Dns; "bell" -> Icons.Rounded.Notifications
    "play" -> Icons.Rounded.PlayArrow; "person" -> Icons.Rounded.Person; "building" -> Icons.Rounded.Business; "heart" -> Icons.Rounded.Favorite
    "eye" -> Icons.Rounded.Visibility; "sync" -> Icons.Rounded.Sync; "palette" -> Icons.Rounded.Palette; "lock" -> Icons.Rounded.Lock
    "edit" -> Icons.Rounded.Edit; "cal" -> Icons.Rounded.CalendarMonth; "mail" -> Icons.Rounded.Mail; "shot" -> Icons.Rounded.PhotoCamera
    "branch" -> Icons.Rounded.AccountTree; "file" -> Icons.Rounded.Description; "bolt" -> Icons.Rounded.Bolt; "code" -> Icons.Rounded.Code
    "term" -> Icons.Rounded.Terminal; "doc" -> Icons.AutoMirrored.Rounded.Article; "sound" -> Icons.Rounded.GraphicEq; "pin" -> Icons.Rounded.PushPin
    else -> Icons.Rounded.Widgets
}

/** A home-screen shortcut straight into one app (long-press on its tile). */
fun pinShortcut(context: Context, app: MiniAppInfo): Boolean {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
    val intent = Intent(Intent.ACTION_VIEW).setClassName(context, "com.past9.phoneaos.MainActivity").putExtra("miniapp", app.id)
    val info = ShortcutInfoCompat.Builder(context, "miniapp-${app.id}")
        .setShortLabel(app.name.take(24)).setLongLabel(app.name)
        .setIcon(IconCompat.createWithResource(context, com.past9.phoneaos.R.mipmap.ic_app_scout_iris))
        .setIntent(intent).build()
    return ShortcutManagerCompat.requestPinShortcut(context, info, null)
}

@Composable
private fun AppTile(app: MiniAppInfo, size: Int = 52) {
    Surface(shape = RoundedCornerShape((size * 0.28f).dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(miniAppIcon(app.icon), null, Modifier.size((size / 2).dp)) }
    }
}

/**
 * Home's pinned apps: ONE small row of pills (his ask, 29 Oct 2026: small, right before To-dos), in the sidebar's
 * order, then All apps. Long-press a pill: a home-screen shortcut. Nothing shows when none is pinned.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinnedAppsRow(apps: List<MiniAppInfo>, onOpen: (String) -> Unit, onAll: () -> Unit) {
    val context = LocalContext.current
    val pinned = MiniAppRules.pinned(apps)
    if (pinned.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(pinned, key = { it.id }) { app ->
            Surface(shape = RoundedCornerShape(50), color = cs.surfaceContainerHigh, contentColor = cs.onSurface,
                modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50)).combinedClickable(onClick = { onOpen(app.id) }, onLongClick = { pinShortcut(context, app) })
                    .semantics { contentDescription = "Open ${app.name}" }) {
                Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                        Icon(miniAppIcon(app.icon), null, Modifier.size(16.dp), tint = cs.onSecondaryContainer)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(app.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                }
            }
        }
        item(key = "all") {
            Surface(onClick = onAll, shape = RoundedCornerShape(50), color = androidx.compose.ui.graphics.Color.Transparent, contentColor = cs.primary, modifier = Modifier.heightIn(min = 40.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Dashboard, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (apps.size > pinned.size) "All ${apps.size}" else "All apps", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** A mini-app in a reply: its tile, name and what it shows, and Open (on this phone). */
@Composable
fun MiniAppCard(id: String) {
    val apps by MiniAppsHub.apps.collectAsState()
    val app = apps.firstOrNull { it.id == id }
    val cs = MaterialTheme.colorScheme
    Surface(onClick = { MiniAppsHub.pendingOpen.value = id }, shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh, contentColor = cs.onSurface,
        modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).semantics { contentDescription = "Open ${app?.name ?: "the app"}" }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (app != null) AppTile(app, 44) else Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(cs.secondaryContainer))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app?.name ?: "Your app", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app?.description?.ifBlank { null } ?: "Made for you on your computer", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { MiniAppsHub.pendingOpen.value = id }, shapes = ButtonDefaults.shapes()) { Text("Open") }
        }
    }
}

/** Every app: open it, pin it (same order as the computer's sidebar), or put it on the home screen. */
@Composable
fun MiniAppsScreen(apps: List<MiniAppInfo>, onBack: () -> Unit, onOpen: (String) -> Unit, onPin: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    SubScreen("Apps", if (apps.isEmpty()) "Ask your agent for one" else "${apps.size} made for you", onBack) { pad ->
        if (apps.isEmpty()) {
            Box(Modifier.padding(pad)) { EmptyState("No apps yet", "Ask your agent for a dashboard, a tracker or a view of your calendars. It makes it on your computer and it shows here.") }
            return@SubScreen
        }
        val ordered = MiniAppRules.pinned(apps) + apps.filter { it.pinned == 0 }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(ordered, key = { it.id }) { app ->
                AppCard(onClick = { onOpen(app.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppTile(app, 44)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.name, style = MaterialTheme.typography.titleMedium)
                            Text(app.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { pinShortcut(context, app) }) { Icon(Icons.AutoMirrored.Rounded.AddToHomeScreen, "Add to the home screen") }
                        FilterChip(selected = app.pinned > 0, onClick = { onPin(app.id, app.pinned == 0) }, label = { Text(if (app.pinned > 0) "Pinned" else "Pin") }, leadingIcon = { Icon(Icons.Rounded.PushPin, null, Modifier.size(18.dp)) })
                    }
                }
            }
        }
    }
}

/**
 * One app, in a WebView that can reach nothing: no files, no content, no storage, no other address (every request to
 * anything but its own virtual host is answered 403 here, and every navigation away is refused). Its files and its
 * bridge calls go to the computer, which checks them exactly as for the window there.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MiniAppFrame(id: String, apps: List<MiniAppInfo>, dark: Boolean, accent: String, onBack: () -> Unit) {
    val app = apps.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var sessionId by remember(id) { mutableStateOf<String?>(null) }
    var failed by remember(id) { mutableStateOf<String?>(null) }
    var view by remember(id) { mutableStateOf<WebView?>(null) }
    var version by remember(id) { mutableIntStateOf(app?.version ?: 0) }

    DisposableEffect(id) {
        val job = scope.launch {
            val s = MiniAppsHub.session()
            if (s == null) { failed = "Not linked to a computer right now."; return@launch }
            runCatching { s.call("miniapps.session", listOf(id), 20_000) as? String }
                .onSuccess { sid -> if (sid == null) failed = "This app is not on the computer." else sessionId = sid }
                .onFailure { failed = it.message ?: "Could not open it." }
        }
        onDispose {
            job.cancel()
            val sid = sessionId
            val s = MiniAppsHub.session()
            if (sid != null && s != null) CoroutineScope(Dispatchers.IO).launch { runCatching { s.call("miniapps.close", listOf(sid), 10_000) } }
            view?.destroy()
        }
    }
    // Main's messages for this session (events, an edit or a delete of the app).
    LaunchedEffect(sessionId) {
        val sid = sessionId ?: return@LaunchedEffect
        MiniAppsHub.pushes.collect { (session, message) ->
            if (session != sid) return@collect
            when (message.optString("topic")) {
                "app.deleted" -> onBack()
                "app.changed" -> version += 1
                else -> view?.evaluateJavascript("window.__agentReceive&&window.__agentReceive(${JSONObject.quote(message.toString())})", null)
            }
        }
    }
    LaunchedEffect(dark, accent, view) {
        view?.evaluateJavascript("window.__agentReceive&&window.__agentReceive(${JSONObject.quote(MiniAppRules.themeMessage(dark, accent).toString())})", null)
    }

    // Full screen and native (Ayanda, 29 Oct 2026): no header bar. The app's own surface runs under the status bar,
    // the page sits inside the system bars and gesture insets, Android back leaves, and one small floating control
    // closes it. The WebView is made at once, while the computer opens the session, so it shows as soon as it can.
    val cs = MaterialTheme.colorScheme
    val openedAt = remember(id) { android.os.SystemClock.elapsedRealtime() }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(cs.surface)) {
        val sid = sessionId
        val inset = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        when {
            failed != null -> Box(inset, contentAlignment = Alignment.Center) { EmptyState("This app cannot open", failed ?: "") }
            sid == null -> Box(inset, contentAlignment = Alignment.Center) { LoadingIndicator() }
            else -> key(version) {
                AndroidView(modifier = inset, factory = { context ->
                    WebView(context).also { web ->
                        web.settings.apply {
                            javaScriptEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            domStorageEnabled = false
                            @Suppress("DEPRECATION")
                            databaseEnabled = false
                            setGeolocationEnabled(false)
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            cacheMode = WebSettings.LOAD_NO_CACHE
                        }
                        web.webViewClient = FrameClient(id, "${app?.version ?: 0}.$version", openedAt)
                        web.addJavascriptInterface(Bridge(web, sid), "AgentBridge")
                        web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        web.overScrollMode = android.view.View.OVER_SCROLL_NEVER
                        web.loadUrl("https://${MiniAppRules.hostOf(id)}/index.html?theme=${if (dark) "dark" else "light"}&accent=$accent")
                        view = web
                    }
                })
            }
        }
        // The one control: small, floating, in the corner the app's own content leaves free.
        Surface(onClick = onBack, shape = androidx.compose.foundation.shape.CircleShape, color = cs.surfaceContainerHigh.copy(alpha = 0.92f), contentColor = cs.onSurface, shadowElevation = 2.dp,
            modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 6.dp, end = 8.dp).size(36.dp).semantics { contentDescription = "Close ${app?.name ?: "the app"}" }) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, null, Modifier.size(18.dp)) }
        }
    }
}

/** Serves the app's files from the computer and refuses everything else. */
private class FrameClient(private val id: String, private val version: String, private val openedAt: Long) : WebViewClient() {
    private val blocked get() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val url = request.url
        if (!MiniAppRules.mayLoad(id, url.scheme, url.host) || request.method != "GET") {
            android.util.Log.i("MiniApp", "blocked ${request.method} ${url.scheme}://${url.host}")
            return blocked
        }
        val s = MiniAppsHub.session() ?: return blocked
        val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        // A file of this version seen before is served from the phone (fast to open again); a changed app is a new version.
        val cacheKey = "$id@$version$path"
        // This runs on the WebView's own loader thread: waiting here is what it expects.
        val r = MiniAppsHub.served(cacheKey) ?: runBlocking { withTimeoutOrNull(20_000) { runCatching { s.call("miniapps.serve", listOf(id, path), 20_000) as? JSONObject }.getOrNull() } }
            ?.also { if (it.optInt("status") == 200) MiniAppsHub.keep(cacheKey, it) } ?: return blocked
        val type = r.optString("type", "text/plain").substringBefore(';').trim()
        val headers = mapOf("Content-Security-Policy" to r.optString("csp"), "X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-store", "Referrer-Policy" to "no-referrer")
        val status = r.optInt("status", 404)
        return WebResourceResponse(type, "utf-8", status, if (status == 200) "OK" else "Not found", headers, ByteArrayInputStream(r.optString("body").toByteArray(Charsets.UTF_8)))
    }

    override fun onPageFinished(view: WebView, url: String) {
        android.util.Log.i("MiniApp", "opened $id in ${android.os.SystemClock.elapsedRealtime() - openedAt} ms")
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val ok = MiniAppRules.mayLoad(id, url.scheme, url.host)
        if (!ok) android.util.Log.i("MiniApp", "navigation stopped: ${url.scheme}://${url.host}")
        return !ok
    }
}

/** window.AgentBridge.post(json): one message from the app to the computer; the reply comes back to the page. */
private class Bridge(private val web: WebView, private val session: String) {
    @JavascriptInterface
    fun post(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (text.length > 200_000) return
        val s = MiniAppsHub.session() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val reply = runCatching { s.call("miniapps.call", listOf(session, message), 120_000) }.getOrElse { e ->
                if (message.optInt("id") > 0) JSONObject().put("op", "reply").put("id", message.optInt("id")).put("ok", false).put("error", e.message ?: "Not done") else null
            }
            val answer = (reply as? JSONObject) ?: return@launch
            val link = MiniAppRules.linkToOpen(message, answer)
            web.post {
                // agent.openUrl: the computer checked it; Android opens it in the app it picks (Teams, Meet, the browser).
                val out = if (link == null) answer else MiniAppRules.openedReply(answer, if (opens.take()) openLink(web.context, link) else "Too many links: at most 4 in 30 s.")
                web.evaluateJavascript("window.__agentReceive&&window.__agentReceive(${JSONObject.quote(out.toString())})", null)
            }
        }
    }

    companion object {
        val opens = OpenLimiter()

        /** Null when it opened, else why not. */
        fun openLink(context: Context, url: String): String? = try {
            val uri = android.net.Uri.parse(url)
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            android.util.Log.i("MiniApp", "opened a link on ${uri.host}")
            null
        } catch (e: android.content.ActivityNotFoundException) {
            "No app on this phone opens that link."
        } catch (e: Exception) {
            e.message ?: "It did not open."
        }
    }
}
