package com.past9.phoneaos.connections

import org.json.JSONObject

/** The JavaScript the app puts into pages: running a learned tool, and watching what a site sends while the agent maps it. */
object PageJs {
    private fun q(s: String) = JSONObject.quote(s)

    /**
     * Runs `body` (the text of an async function that sees `args` and may `return` anything JSON-able) in the page and
     * leaves the outcome in window[slot] for the app to poll: {p:true} while it runs, then {ok:true,v:...} or {ok:false,e:"..."}.
     * Returns "started", "syntax" (the body really doesn't parse; the slot already says so) or "blocked": the page won't let
     * anything build a function from text (a security policy, or Trusted Types, as on GitHub, Reddit, Google and others),
     * whatever it throws, so [direct] must be used instead.
     */
    fun start(slot: String, body: String, args: JSONObject): String =
        "(function(){var k=${q(slot)};window[k]={p:true};" +
        "try{var f=new (Object.getPrototypeOf(async function(){}).constructor)('args',${q(body)});" +
        "f(${args}).then(function(v){window[k]={ok:true,v:v===undefined?null:v}},function(e){window[k]={ok:false,e:String(e&&e.message||e)}});return 'started'}" +
        "catch(e){if(e&&e.name==='SyntaxError'){window[k]={ok:false,e:'Syntax: '+String(e.message||e)};return 'syntax'}return 'blocked'}})()"

    /** Marks the slot as waiting, with "not started" (s:0), before [direct] runs. */
    fun pending(slot: String): String = "(function(){window[${q(slot)}]={p:true,s:0};return 1})()"

    /**
     * The same run, written straight into the page as script text. The app injects it itself, so a site's policy
     * doesn't apply. A body that doesn't parse stops the whole script, so s stays 0 and [startedState] says so.
     */
    fun direct(slot: String, body: String, args: JSONObject): String =
        "(function(){var k=${q(slot)};window[k].s=1;(async function(args){\n$body\n})(${args}).then(function(v){window[k]={ok:true,v:v===undefined?null:v}},function(e){window[k]={ok:false,e:String(e&&e.message||e)}})})()"

    /** 0: the script never started (it doesn't parse); 1: running; 2: finished; -1: slot missing. */
    fun startedState(slot: String): String = "(function(){var r=window[${q(slot)}];return r?(r.p?(r.s?1:0):2):-1})()"

    /** What to read to see how it went: null while still running. */
    fun poll(slot: String): String = "(function(){var r=window[${q(slot)}];return r&&!r.p?r:null})()"

    fun clear(slot: String): String = "(function(){delete window[${q(slot)}];return 1})()"

    /** What [js] hands back for a script that returns a plain string still wears its JSON quotes (an empty result arrives as two quote marks): take them off. */
    fun text(raw: String): String =
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') runCatching { org.json.JSONTokener(raw).nextValue().toString() }.getOrDefault(raw) else raw

    /** Only checks that the body parses as an async function, without running it. "" when it parses, or when the page won't allow the check (only a real SyntaxError is reported). */
    fun syntax(body: String): String =
        "(function(){try{new (Object.getPrototypeOf(async function(){}).constructor)('args',${q(body)});return ''}catch(e){return e&&e.name==='SyntaxError'?String(e.message||e):''}})()"

    /**
     * Put in every page at document start. It does nothing until sessionStorage.__aosNet is "1" (browser_network start),
     * then records the page's fetch / XMLHttpRequest calls (method, url, status, a slice of the request and the reply)
     * and WebSocket opens into window.__aosNet, newest last, at most 300.
     */
    val NET_SHIM = """
(function(){
  if (window.__aosNetHooked) return; window.__aosNetHooked = true;
  var log = window.__aosNet = [];
  function on(){ try { return sessionStorage.getItem('__aosNet') === '1'; } catch(e){ return false; } }
  function add(e){ e.t = Date.now(); log.push(e); if (log.length > 300) log.shift(); }
  function slice(x, n){ try { if (x == null) return ''; if (typeof x !== 'string') x = (x instanceof FormData ? '[form data]' : (x instanceof Blob || x instanceof ArrayBuffer ? '[binary]' : String(x))); return x.slice(0, n); } catch(e){ return ''; } }
  var of = window.fetch;
  if (of) window.fetch = function(input, init){
    var rec = null;
    if (on()) { try { rec = { k: 'fetch', m: ((init && init.method) || (input && input.method) || 'GET').toUpperCase(), u: String((input && input.url) || input).slice(0, 400), rq: slice(init && init.body, 500) }; } catch(e){} }
    var p = of.apply(this, arguments);
    if (rec) p.then(function(res){ rec.s = res.status; var ct = (res.headers.get('content-type') || '');
      if (/json|text|xml|javascript/.test(ct)) res.clone().text().then(function(t){ rec.rs = slice(t, 700); add(rec); }, function(){ add(rec); }); else { rec.rs = '[' + ct + ']'; add(rec); } }, function(err){ rec.s = 0; rec.rs = String(err); add(rec); });
    return p;
  };
  var X = window.XMLHttpRequest;
  if (X) { var oo = X.prototype.open, os = X.prototype.send;
    X.prototype.open = function(m, u){ this.__aos = { k: 'xhr', m: String(m).toUpperCase(), u: String(u).slice(0, 400) }; return oo.apply(this, arguments); };
    X.prototype.send = function(b){ var r = this.__aos; if (r && on()) { r.rq = slice(b, 500); var x = this;
      x.addEventListener('loadend', function(){ r.s = x.status; try { var ct = x.getResponseHeader('content-type') || ''; r.rs = /json|text|xml|javascript/.test(ct) && (x.responseType === '' || x.responseType === 'text') ? slice(x.responseText, 700) : '[' + ct + ']'; } catch(e){} add(r); }); }
      return os.apply(this, arguments); }; }
  var W = window.WebSocket;
  if (W) { window.WebSocket = function(u, p){ var ws = p === undefined ? new W(u) : new W(u, p); if (on()) add({ k: 'ws', m: 'OPEN', u: String(u).slice(0, 300) }); return ws; }; window.WebSocket.prototype = W.prototype; ['CONNECTING','OPEN','CLOSING','CLOSED'].forEach(function(c){ window.WebSocket[c] = W[c]; }); }
})();
""".trimIndent()

    fun netStart(): String = "(function(){try{sessionStorage.setItem('__aosNet','1')}catch(e){}return 1})()"
    fun netStop(): String = "(function(){try{sessionStorage.removeItem('__aosNet')}catch(e){}return 1})()"
    fun netClear(): String = "(function(){if(window.__aosNet)window.__aosNet.length=0;return 1})()"
    /** The recorded calls whose method+url contain `filter`, newest last, as a JSON array. */
    fun netLog(filter: String, limit: Int): String =
        "(function(){var f=${JSONObject.quote(filter.lowercase())};var l=(window.__aosNet||[]).filter(function(e){return !f||((e.m+' '+e.u).toLowerCase().indexOf(f)>=0)});return l.slice(-${limit.coerceIn(1, 100)})})()"
}
