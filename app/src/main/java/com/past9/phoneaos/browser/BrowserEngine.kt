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
class BrowserEngine(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var pageDone: CompletableDeferred<Unit>? = null
    private val _page = MutableStateFlow(PageState())
    val page: StateFlow<PageState> = _page

    private val width = 1080
    private val height = 2340

    @SuppressLint("SetJavaScriptEnabled")
    fun create() = main.post {
        if (web != null) return@post
        web = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { _page.value = PageState(url, view.title ?: "", true) }
                override fun onPageFinished(view: WebView, url: String) { _page.value = PageState(url, view.title ?: "", false); pageDone?.complete(Unit) }
            }
            layoutOffscreen(this)
            loadUrl("about:blank")
        }
    }

    private fun layoutOffscreen(v: WebView) {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, width, height)
    }

    fun destroy() = main.post { web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }; web = null }

    /** For the Browser screen: hand the live WebView to a container, and take it back after. */
    fun attachTo(parent: ViewGroup) {
        val v = web ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        parent.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    fun detachFrom(parent: ViewGroup) { web?.let { if (it.parent === parent) { parent.removeView(it); layoutOffscreen(it) } } }

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
        return runCatching {
            val o = JSONObject(raw)
            buildString {
                appendLine("URL: ${o.optString("url")}"); appendLine("Title: ${o.optString("title")}")
                appendLine("\nInteractive (use the number as ref):"); appendLine(o.optString("els"))
                appendLine("\nText:"); append(o.optString("text"))
            }.take(maxChars)
        }.getOrDefault(raw.take(maxChars))
    }

    suspend fun click(ref: String?, selector: String?, text: String?): String = js("""(()=>{
        let e=null; const ref=${q(ref)}, sel=${q(selector)}, txt=${q(text)};
        if(ref) e=document.querySelector('[data-agent-ref="'+ref+'"]');
        if(!e && sel) e=document.querySelector(sel);
        if(!e && txt){ const all=[...document.querySelectorAll('a,button,[role=button],input[type=submit],input[type=button],label,summary,[onclick]')];
          e=all.find(x=>(x.innerText||x.value||'').trim().toLowerCase()===txt.toLowerCase()) || all.find(x=>(x.innerText||x.value||'').toLowerCase().includes(txt.toLowerCase())); }
        if(!e) return 'no match';
        e.scrollIntoView({block:'center'}); e.focus&&e.focus(); e.click(); return 'clicked '+e.tagName.toLowerCase()+' '+((e.innerText||e.value||'').trim().slice(0,60));
    })()""").also { kotlinx.coroutines.delay(1500) }

    suspend fun type(ref: String?, selector: String?, value: String, submit: Boolean): String = js("""(()=>{
        let e=null; const ref=${q(ref)}, sel=${q(selector)};
        if(ref) e=document.querySelector('[data-agent-ref="'+ref+'"]');
        if(!e && sel) e=document.querySelector(sel);
        if(!e) e=document.activeElement&&/INPUT|TEXTAREA/.test(document.activeElement.tagName)?document.activeElement:null;
        if(!e) return 'no match';
        e.scrollIntoView({block:'center'}); e.focus();
        const proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
        const setter=Object.getOwnPropertyDescriptor(proto,'value'); if(setter&&setter.set&&e.tagName!=='DIV') setter.set.call(e,${q(value)}); else e.innerText=${q(value)};
        e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
        if(${submit}){ e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,bubbles:true})); if(e.form){ e.form.requestSubmit?e.form.requestSubmit():e.form.submit(); } }
        return 'typed into '+e.tagName.toLowerCase();
    })()""").also { if (submit) kotlinx.coroutines.delay(2500) }

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
          const vis=e=>{const r=e.getBoundingClientRect();const s=getComputedStyle(e);return r.width>1&&r.height>1&&s.visibility!=='hidden'&&s.display!=='none'};
          const sel='a[href],button,input:not([type=hidden]),textarea,select,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[contenteditable=true],summary';
          let n=0; const out=[];
          document.querySelectorAll(sel).forEach(e=>{ if(!vis(e)||n>=150) return; n++; e.setAttribute('data-agent-ref',String(n));
            const tag=e.tagName.toLowerCase(); const type=e.getAttribute('type'); 
            const label=(e.getAttribute('aria-label')||e.innerText||e.value||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('name')||'').trim().replace(/\s+/g,' ').slice(0,80);
            const href=tag==='a'?(' -> '+(e.getAttribute('href')||'').slice(0,80)):'';
            out.push('['+n+'] '+tag+(type?'('+type+')':'')+' "'+label+'"'+href);
          });
          const text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').slice(0,9000);
          return {url:location.href,title:document.title,els:out.join('\n'),text};
        })()"""
    }
}
