package com.past9.phoneaos.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

data class PageState(val url: String = "", val title: String = "", val loading: Boolean = false)

/**
 * The agent's browser: ONE WebView that lives as long as the BrowserService, laid out offscreen
 * at phone size so pages load, run JavaScript and can be screenshotted while the user is in
 * another app. When the user opens the Browser screen the same WebView is attached to it, so
 * they can watch, or take over for a sign-in or captcha, and hand it back.
 */
class BrowserEngine(private val context: Context, val profile: String = "Personal") {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var pageDone: CompletableDeferred<Unit>? = null
    private val _page = MutableStateFlow(PageState())
    val page: StateFlow<PageState> = _page

    private val width = 1080
    private val height = 2340

    /** The main page plus any popups it opened (OAuth "Sign in with Google" windows). The top one is live. */
    private val stack = ArrayList<WebView>()
    private var container: ViewGroup? = null
    @Volatile var lastUsed = System.currentTimeMillis(); private set
    /** Something the agent should know about the page that the page itself won't say (a blocked sign-in, a download). */
    @Volatile var notice: String? = null
    /** Files the agent wants to hand to the next file picker a page opens (browser_upload). */
    @Volatile var pendingUpload: List<android.net.Uri>? = null
    @Volatile var uploadsServed = 0
    val attached: Boolean get() = container != null
    fun touch() { lastUsed = System.currentTimeMillis() }

    @SuppressLint("SetJavaScriptEnabled")
    fun create() = main.post {
        if (web != null) return@post
        web = newWebView().also { stack += it; layoutOffscreen(it); it.loadUrl("about:blank") }
    }

    /** Every window, main or popup, gets the same profile, settings and handlers. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(context.applicationContext).apply {
        // Each profile is its own cookie jar: work and personal accounts stay signed in side by side.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) runCatching {
            androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(profile)
            androidx.webkit.WebViewCompat.setProfile(this, profile)
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        // Sign-in popups (window.open + opener.postMessage) need real windows to open into.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setGeolocationEnabled(true)
        // Look like Chrome, not an embedded WebView: Google refuses sign-in to "; wv" user agents.
        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        val xrw = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)
        if (xrw) runCatching { androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet()) }
            .onFailure { android.util.Log.w("AgentBrowser", "X-Requested-With allow-list failed", it) }
        // The UA string alone leaves the client hints saying "Android WebView" (Sec-CH-UA), which
        // Google reads too: present the same Chrome brands Chrome itself sends.
        val uam = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA)
        if (uam) runCatching {
            val cur = androidx.webkit.WebSettingsCompat.getUserAgentMetadata(settings)
            val full = Regex("Chrome/([\\d.]+)").find(settings.userAgentString)?.groupValues?.get(1) ?: cur.fullVersion ?: "143.0.0.0"
            val major = full.substringBefore('.')
            fun brand(b: String, v: String, f: String) = androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand(b).setMajorVersion(v).setFullVersion(f).build()
            androidx.webkit.WebSettingsCompat.setUserAgentMetadata(settings, androidx.webkit.UserAgentMetadata.Builder(cur)
                .setBrandVersionList(listOf(brand("Google Chrome", major, full), brand("Chromium", major, full), brand("Not.A/Brand", "99", "99.0.0.0")))
                .setFullVersion(full).setMobile(true).setPlatform("Android").build())
        }.onFailure { android.util.Log.w("AgentBrowser", "UA metadata failed", it) }
        android.util.Log.i("AgentBrowser", "X-Requested-With removable=$xrw, UA metadata=$uam, UA=${settings.userAgentString}")
        // No WebAuthn: browser-mode passkeys need CREDENTIAL_MANAGER_SET_ORIGIN, which only privileged
        // apps hold. With it on, any site asking for a passkey (Microsoft sign-in) threw a SecurityException
        // on the main thread and killed the app. Left off, sites fall back to password or code sign-in.
        val cookies = if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE))
            runCatching { androidx.webkit.WebViewCompat.getProfile(this).cookieManager }.getOrNull() ?: CookieManager.getInstance() else CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(this, true)
        webChromeClient = object : android.webkit.WebChromeClient() {
            // Sites that ask for the location (store finders, delivery) get the phone's, once the app has it.
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                com.past9.phoneaos.App.graph(context).scope.launch {
                    val ok = com.past9.phoneaos.system.PermissionBroker.request(context, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) {}
                    main.post { callback.invoke(origin, ok, ok) }
                }
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val child = newWebView()
                push(child)
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) { pop(window) }
            // <input type=file>: the phone's own picker, so uploads work like in Chrome.
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
                pendingUpload?.let { files -> pendingUpload = null; uploadsServed++; callback.onReceiveValue(files.toTypedArray()); return true }
                val types = params.acceptTypes.orEmpty().map { it.trim() }.filter { it.isNotEmpty() && it.contains('/') }.ifEmpty { listOf("*/*") }.toTypedArray()
                com.past9.phoneaos.App.graph(context).scope.launch {
                    val r = com.past9.phoneaos.tools.FilePickBroker.Request(types)
                    com.past9.phoneaos.tools.FilePickBroker.pending.value = r
                    val uris = kotlinx.coroutines.withTimeoutOrNull(300_000) { r.result.await() }
                    main.post { callback.onReceiveValue(uris?.takeIf { it.isNotEmpty() }?.toTypedArray()) }
                }
                return true
            }
        }
        // Downloads land in the phone's Downloads folder, with this profile's cookies so signed-in files work.
        setDownloadListener { url, userAgent, disposition, mime, _ ->
            if (!url.startsWith("http")) return@setDownloadListener
            runCatching {
                val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
                val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                    .addRequestHeader("Cookie", cookies.getCookie(url) ?: "").addRequestHeader("User-Agent", userAgent)
                    .setMimeType(mime).setTitle(name)
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
                context.getSystemService(android.app.DownloadManager::class.java).enqueue(req)
                notice = "Downloading $name to the phone's Downloads folder."
            }
        }
        // Web notifications: WebView has none, so give pages one that hands them to the app.
        addJavascriptInterface(WebNotifications(context, profile), "__aosWeb")
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, WebNotifications.SHIM, setOf("*")) }
        webViewClient = object : WebViewClient() {
            // Links into other apps (intent://, market://, mailto:) used to load as a blank error page.
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme?.lowercase() ?: return false
                if (scheme in setOf("http", "https", "about", "data", "blob", "javascript", "file")) return false
                if (scheme == "intent") {
                    val intent = runCatching { android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return true
                    intent.getStringExtra("browser_fallback_url")?.takeIf { it.startsWith("http") }?.let { view.loadUrl(it); return true }
                    if (attached) runCatching {
                        intent.addCategory(android.content.Intent.CATEGORY_BROWSABLE); intent.component = null; intent.selector = null
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent)
                    }
                    else notice = "This link wants to open another app (${intent.`package` ?: "an app"}). Skip it or ask the user."
                    return true
                }
                if (attached) runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                else notice = "This link opens another app ($scheme:). Skip it or ask the user."
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (view === web) { _page.value = PageState(url, view.title ?: "", true); notice = null }
                if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(WebNotifications.SHIM, null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (view === web) { _page.value = PageState(url, view.title ?: "", false); pageDone?.complete(Unit) }
                // Google says so in words when it refuses a sign-in here; surface it instead of a dead end.
                if (url.contains("accounts.google.com")) view.evaluateJavascript("(document.body&&document.body.innerText||'').slice(0,4000)") { t ->
                    if (t != null && Regex("disallowed_useragent|may not be secure|Couldn.t sign you in|isn.t secure", RegexOption.IGNORE_CASE).containsMatchIn(t))
                        notice = "Google refused this sign-in inside this browser. Use the site's email or password option instead, or hand the browser to the user (browser_handoff) to try."
                }
            }
        }
    }

    /** A popup opened: it becomes the live window (for the user watching and for the agent's tools). */
    private fun push(child: WebView) {
        val prev = stack.lastOrNull()
        stack += child; web = child
        container?.let { c -> prev?.let { c.removeView(it) }; c.addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) } ?: layoutOffscreen(child)
        _page.value = PageState(child.url ?: "", child.title ?: "", true)
    }

    /** The popup closed itself (sign-in done): back to the page that opened it. */
    private fun pop(window: WebView) {
        if (stack.size <= 1 || !stack.remove(window)) return
        container?.removeView(window)
        window.destroy()
        val top = stack.last(); web = top
        container?.let { c -> if (top.parent == null) c.addView(top, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        _page.value = PageState(top.url ?: "", top.title ?: "", false)
    }

    private fun layoutOffscreen(v: WebView) {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, width, height)
    }

    fun destroy() = main.post { stack.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }; stack.clear(); web = null; container = null }

    /** For the Browser screen: hand the live WebView to a container, and take it back after. */
    fun attachTo(parent: ViewGroup) {
        val v = web ?: return
        container = parent; touch()
        (v.parent as? ViewGroup)?.removeView(v)
        parent.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    fun detachFrom(parent: ViewGroup) {
        if (container === parent) container = null
        touch()
        web?.let { if (it.parent === parent) { parent.removeView(it); layoutOffscreen(it) } }
    }

    private suspend fun <T> onMain(block: (WebView, (T) -> Unit) -> Unit): T? = withTimeoutOrNull(25_000) {
        suspendCancellableCoroutine { cont ->
            main.post {
                val w = web
                if (w == null) cont.resume(null) else block(w) { if (cont.isActive) cont.resume(it) }
            }
        }
    }

    suspend fun goto(url: String): PageState {
        val target = if (url.startsWith("http") || url.startsWith("about:")) url else "https://$url"
        val done = CompletableDeferred<Unit>().also { pageDone = it }
        onMain<Unit> { w, cb -> w.loadUrl(target); cb(Unit) }
        withTimeoutOrNull(20_000) { done.await() }
        kotlinx.coroutines.delay(600) // let late scripts render
        return _page.value
    }

    suspend fun back(): PageState {
        onMain<Unit> { w, cb -> if (w.canGoBack()) w.goBack(); cb(Unit) }
        kotlinx.coroutines.delay(1200); return _page.value
    }

    /** Evaluate JS that returns a JSON-able value; returns the JSON string. */
    suspend fun js(expr: String): String = onMain<String> { w, cb ->
        w.evaluateJavascript("(function(){try{return JSON.stringify($expr)}catch(e){return JSON.stringify('ERR '+e)}})()") { cb(it ?: "null") }
    }?.let { raw -> runCatching { org.json.JSONTokener(raw).nextValue().toString() }.getOrDefault(raw) } ?: "timeout"

    /** Number every visible interactive element and return a compact, readable outline of the page. */
    suspend fun snapshot(maxChars: Int = 14_000): String {
        val raw = js(SNAPSHOT_JS)
        val note = notice?.let { "NOTE: $it\n\n" }.orEmpty()
        return note + runCatching {
            val o = JSONObject(raw)
            buildString {
                appendLine("URL: ${o.optString("url")}"); appendLine("Title: ${o.optString("title")}")
                appendLine("\nInteractive (use the number as ref):"); appendLine(o.optString("els"))
                appendLine("\nText:"); append(o.optString("text"))
            }.take(maxChars)
        }.getOrDefault(raw.take(maxChars))
    }

    /**
     * Click like a finger does: find the element (by ref, selector or visible text, through shadow
     * roots and iframes), scroll it into view, then send a real touch at its centre. Sites that
     * ignore synthetic JS clicks (React sign-in forms) react to this. JS click is the fallback.
     */
    suspend fun click(ref: String?, selector: String?, text: String?): String {
        val found = js("""(()=>{
            const roots=[document]; for(let i=0;i<roots.length&&i<40;i++) roots[i].querySelectorAll('*').forEach(e=>{ if(e.shadowRoot) roots.push(e.shadowRoot) });
            const qa=s=>roots.flatMap(r=>[...r.querySelectorAll(s)]);
            let e=null; const ref=${q(ref)}, sel=${q(selector)}, txt=${q(text)};
            if(ref) e=qa('[data-agent-ref="'+ref+'"]')[0];
            if(!e && sel) e=qa(sel)[0];
            if(!e && txt){ const all=qa('a,button,[role=button],[role=link],[role=option],input[type=submit],input[type=button],label,summary,[onclick],div,span');
              const t=txt.toLowerCase(); const lab=x=>(x.getAttribute&&x.getAttribute('aria-label')||x.innerText||x.value||'').trim().toLowerCase();
              e=all.find(x=>lab(x)===t) || all.find(x=>lab(x).includes(t)&&lab(x).length<t.length+40); }
            if(!e) return {ok:false};
            e.scrollIntoView({block:'center',inline:'center'});
            const r=e.getBoundingClientRect();
            window.__agentEl=e;
            return {ok:true,x:r.left+r.width/2,y:r.top+r.height/2,dis:!!(e.disabled||e.getAttribute('aria-disabled')==='true'),label:(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().slice(0,60),tag:e.tagName.toLowerCase(),vw:window.innerWidth}
        })()""")
        val o = runCatching { JSONObject(found) }.getOrNull() ?: return "no match"
        if (!o.optBoolean("ok")) return "no match"
        if (o.optBoolean("dis")) return "that ${o.optString("tag")} is disabled; something on the page still needs filling in"
        val tapped = tap(o.getDouble("x"), o.getDouble("y"), o.optDouble("vw", 0.0))
        if (!tapped) js("(()=>{const e=window.__agentEl;if(e){e.focus&&e.focus();e.click();}return 1})()")
        kotlinx.coroutines.delay(1500)
        return "clicked ${o.optString("tag")} ${o.optString("label")}"
    }

    /** A real touch: DOWN then UP at CSS-pixel coordinates, converted to view pixels. */
    private suspend fun tap(cssX: Double, cssY: Double, viewportCss: Double): Boolean = onMain<Boolean> { w, cb ->
        if (w.width <= 0 || viewportCss <= 0) { cb(false); return@onMain }
        val scale = w.width / viewportCss
        val x = (cssX * scale).toFloat(); val y = (cssY * scale).toFloat()
        val t = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(t, t + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
        down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        val ok = w.dispatchTouchEvent(down) and w.dispatchTouchEvent(up)
        down.recycle(); up.recycle(); cb(ok)
    } ?: false

    suspend fun type(ref: String?, selector: String?, value: String, submit: Boolean): String = js("""(()=>{
        const roots=[document]; for(let i=0;i<roots.length&&i<40;i++) roots[i].querySelectorAll('*').forEach(x=>{ if(x.shadowRoot) roots.push(x.shadowRoot) });
        const qa=s=>roots.flatMap(r=>[...r.querySelectorAll(s)]);
        let e=null; const ref=${q(ref)}, sel=${q(selector)};
        if(ref) e=qa('[data-agent-ref="'+ref+'"]')[0];
        if(!e && sel) e=qa(sel)[0];
        if(!e) e=document.activeElement&&/INPUT|TEXTAREA/.test(document.activeElement.tagName)?document.activeElement:null;
        if(!e) return 'no match';
        e.scrollIntoView({block:'center'}); e.focus(); e.dispatchEvent(new FocusEvent('focus',{bubbles:true}));
        const v=${q(value)};
        if(e.isContentEditable){ document.execCommand('selectAll'); document.execCommand('insertText',false,v); }
        else {
          const proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
          const setter=Object.getOwnPropertyDescriptor(proto,'value');
          // React tracks the last value it saw; set through the native setter so it notices the change.
          setter.set.call(e,'');
          for(const ch of v){ e.dispatchEvent(new KeyboardEvent('keydown',{key:ch,bubbles:true})); setter.set.call(e,e.value+ch);
            e.dispatchEvent(new InputEvent('input',{bubbles:true,data:ch,inputType:'insertText'})); e.dispatchEvent(new KeyboardEvent('keyup',{key:ch,bubbles:true})); }
        }
        e.dispatchEvent(new Event('change',{bubbles:true})); e.dispatchEvent(new FocusEvent('blur',{bubbles:true}));
        if(${submit}){ const k={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}; e.dispatchEvent(new KeyboardEvent('keydown',k)); e.dispatchEvent(new KeyboardEvent('keypress',k)); e.dispatchEvent(new KeyboardEvent('keyup',k));
          if(e.form){ const b=e.form.querySelector('[type=submit],button:not([type=button])'); if(b) b.click(); else if(e.form.requestSubmit) e.form.requestSubmit(); } }
        return 'typed into '+e.tagName.toLowerCase();
    })()""").also { if (submit) kotlinx.coroutines.delay(2500) }

    suspend fun pressKey(key: String): String = js("""(()=>{const e=document.activeElement||document.body;const k={key:${q(key)},code:${q(key)},bubbles:true,keyCode:${q(key)}==='Enter'?13:${q(key)}==='Tab'?9:${q(key)}==='Escape'?27:0};
        e.dispatchEvent(new KeyboardEvent('keydown',k));e.dispatchEvent(new KeyboardEvent('keyup',k));
        if(${q(key)}==='Enter'&&e.form){const b=e.form.querySelector('[type=submit],button:not([type=button])'); if(b) b.click();}
        return 'pressed '+${q(key)}})()""").also { kotlinx.coroutines.delay(1200) }

    /** Wait (up to [seconds]) until the page shows some text, e.g. after a slow sign-in step. */
    suspend fun waitForText(text: String, seconds: Int): Boolean {
        repeat(seconds * 2) {
            if (js("(document.body?document.body.innerText:'').toLowerCase().includes(${q(text.lowercase())})") == "true") return true
            kotlinx.coroutines.delay(500)
        }
        return false
    }

    suspend fun scroll(down: Boolean): String = js("(()=>{window.scrollBy(0, ${if (down) 1 else -1}*window.innerHeight*0.85);return window.scrollY})()")

    /** Render the page into a PNG under the app's cache dir. */
    suspend fun screenshot(): File? = onMain<File?> { w, cb ->
        val bmp = Bitmap.createBitmap(w.width.coerceAtLeast(1), w.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        w.draw(Canvas(bmp))
        val f = File(context.cacheDir, "browser/shot-${System.currentTimeMillis()}.png").apply { parentFile?.mkdirs() }
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        cb(f)
    }

    private fun q(s: String?) = if (s.isNullOrEmpty()) "null" else JSONObject.quote(s)

    companion object {
        val SNAPSHOT_JS = """(()=>{
          const vis=e=>{const r=e.getBoundingClientRect();const s=getComputedStyle(e);return r.width>1&&r.height>1&&s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0'};
          const sel='a[href],button,input:not([type=hidden]),textarea,select,[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[role=checkbox],[role=radio],[role=switch],[role=combobox],[contenteditable=true],[tabindex]:not([tabindex="-1"]),summary,label[for],[onclick]';
          let n=0; const out=[];
          // Walk the page, open shadow roots and same-origin iframes: modern sign-in pages hide buttons in all three.
          const roots=[document]; const seen=new Set();
          const collect=(root)=>{
            root.querySelectorAll('*').forEach(e=>{ if(e.shadowRoot&&!seen.has(e.shadowRoot)){seen.add(e.shadowRoot);roots.push(e.shadowRoot)} if(e.tagName==='IFRAME'){try{const d=e.contentDocument;if(d&&!seen.has(d)){seen.add(d);roots.push(d)}}catch(_){}} });
          };
          for(let i=0;i<roots.length&&i<40;i++) collect(roots[i]);
          const cands=[];
          roots.forEach(r=>{ r.querySelectorAll(sel).forEach(e=>cands.push(e)); r.querySelectorAll('div,span,li').forEach(e=>{ if(getComputedStyle(e).cursor==='pointer'&&!e.closest(sel)&&e.innerText&&e.innerText.trim().length<60&&e.children.length<4) cands.push(e) }) });
          cands.forEach(e=>{ if(!vis(e)||n>=180) return; n++; e.setAttribute('data-agent-ref',String(n));
            const tag=e.tagName.toLowerCase(); const type=e.getAttribute('type');
            const label=(e.getAttribute('aria-label')||e.innerText||e.value||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('name')||'').trim().replace(/\s+/g,' ').slice(0,80);
            const href=tag==='a'?(' -> '+(e.getAttribute('href')||'').slice(0,80)):'';
            const off=(e.disabled||e.getAttribute('aria-disabled')==='true')?' (disabled)':'';
            out.push('['+n+'] '+tag+(type?'('+type+')':'')+' "'+label+'"'+href+off);
          });
          const text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').slice(0,9000);
          return {url:location.href,title:document.title,els:out.join('\n'),text};
        })()"""
    }
}

/**
 * Pages in the agent's browser can "send notifications". They land in the same store as phone
 * notifications (app "web:<site>"), so notification routines fire on them. Only while the page is
 * open in the agent's browser: WebView has no push service for closed sites.
 */
class WebNotifications(private val context: Context, private val profile: String) {
    @android.webkit.JavascriptInterface
    fun notify(title: String, body: String, host: String) {
        val g = com.past9.phoneaos.App.graph(context)
        g.scope.launch {
            val pkg = "web:$host"
            val dao = g.db.notifications()
            if (dao.dupes(pkg, title, body, System.currentTimeMillis() - 600_000) > 0) return@launch
            dao.insert(com.past9.phoneaos.data.NotificationRow(pkg = pkg, app = host, title = title, text = body, postedAt = System.currentTimeMillis()))
            g.db.triggers().list().filter { it.enabled && it.kind == "notification" }.forEach { t ->
                val (who, word) = t.spec.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val site = who.removePrefix("web:").lowercase()
                if ((who == pkg || (site.isNotBlank() && host.lowercase().contains(site))) && (word.isBlank() || title.contains(word, true) || body.contains(word, true)))
                    com.past9.phoneaos.triggers.Routines.runNow(context, t.id, "A web notification just arrived from $host: \"$title: ${body.take(400)}\". ")
            }
        }
    }

    companion object {
        val SHIM = """
            (function(){ if (window.__aosNotif || typeof __aosWeb === 'undefined') return; window.__aosNotif = 1;
              function send(t, o){ o = o || {}; try { __aosWeb.notify(String(t || ''), String(o.body || ''), location.host); } catch (e) {} }
              function N(t, o){ send(t, o); this.title = t; this.body = (o && o.body) || ''; this.close = function(){}; this.addEventListener = function(){}; this.removeEventListener = function(){}; }
              N.permission = 'granted';
              N.requestPermission = function(cb){ if (cb) cb('granted'); return Promise.resolve('granted'); };
              window.Notification = N;
              if (window.ServiceWorkerRegistration) ServiceWorkerRegistration.prototype.showNotification = function(t, o){ send(t, o); return Promise.resolve(); };
              if (navigator.permissions && navigator.permissions.query) { var q = navigator.permissions.query.bind(navigator.permissions);
                navigator.permissions.query = function(d){ return d && d.name === 'notifications' ? Promise.resolve({ state: 'granted', onchange: null }) : q(d); }; }
            })();
        """.trimIndent()
    }
}
