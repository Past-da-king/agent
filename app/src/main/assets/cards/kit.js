// kit.js: the bridge and helpers every card gets. Loaded before the card's own HTML.
//   card.data()          the card's latest data (JSON), or null
//   card.run(input)      runs the card's script with input (e.g. {query}), resolves with its new data
//   card.open(url)       opens a link in the app's browser
//   card.ask(text)       sends a question about this card to the agent
//   define render(data)  and it is called on load and whenever new data arrives
//   Kit.money / Kit.num / Kit.pct / Kit.when / Kit.ago   formatting
//   Kit.line(el, points, {unit}) / Kit.bars(el, items, {unit})   charts in the app's colours
//   <i data-ic="mail"></i>   icons (see Kit.icons for names)
(function () {
  const B = window.AppBridge;
  const pending = {};
  let seq = 0;
  window.card = {
    data() { try { return JSON.parse(B ? B.data() : "null"); } catch (e) { return null; } },
    run(input) {
      return new Promise((resolve, reject) => {
        const id = "r" + (++seq);
        pending[id] = { resolve, reject };
        if (!B) return reject(new Error("no bridge"));
        B.run(id, JSON.stringify(input || {}));
      });
    },
    open(url) { if (B) B.open(String(url)); },
    ask(text) { if (B) B.ask(String(text)); },
  };
  // Called by the app.
  window.__cardResolve = (id, ok, json) => {
    const p = pending[id]; if (!p) return; delete pending[id];
    let v = null; try { v = JSON.parse(json); } catch (e) {}
    if (ok) { p.resolve(v); if (typeof window.render === "function" && v) safeRender(v); } else p.reject(new Error(v && v.error || "failed"));
  };
  window.__cardUpdate = (json) => { let v = null; try { v = JSON.parse(json); } catch (e) {} if (typeof window.render === "function") safeRender(v); };

  function safeRender(d) {
    try { window.render(d); } catch (e) {
      document.body.insertAdjacentHTML("beforeend", '<div class="card flat small">This card hit a snag: ' + String(e.message).replace(/</g, "&lt;") + "</div>");
      if (B && B.error) B.error(String(e.stack || e.message));
    }
    paintIcons();
  }

  // Links never navigate the card away: they open in the app.
  document.addEventListener("click", (e) => {
    const a = e.target.closest && e.target.closest("a[href]");
    if (a) { e.preventDefault(); window.card.open(a.href); }
  });

  // Simple line icons (24 grid, stroke = currentColor).
  const P = {
    mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>',
    send: '<path d="M22 2 11 13"/><path d="M22 2 15 22l-4-9-9-4z"/>',
    reply: '<path d="M9 17 4 12l5-5"/><path d="M20 18v-2a4 4 0 0 0-4-4H4"/>',
    check: '<path d="M20 6 9 17l-5-5"/>',
    x: '<path d="M18 6 6 18M6 6l12 12"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/>',
    up: '<path d="m6 15 6-6 6 6"/>', down: '<path d="m6 9 6 6 6-6"/>',
    trend_up: '<path d="m3 17 6-6 4 4 8-8"/><path d="M14 7h7v7"/>',
    trend_down: '<path d="m3 7 6 6 4-4 8 8"/><path d="M14 17h7v-7"/>',
    tag: '<path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8z"/><circle cx="7.5" cy="7.5" r="1.5"/>',
    cart: '<circle cx="9" cy="20" r="1.5"/><circle cx="18" cy="20" r="1.5"/><path d="M2 3h3l2.7 12.4a2 2 0 0 0 2 1.6h7.7a2 2 0 0 0 2-1.6L21 7H6"/>',
    link: '<path d="M10 14a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1"/><path d="M14 10a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1"/>',
    star: '<path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3 6.4 20.2l1.1-6.2L3 9.6l6.2-.9z"/>',
    alert: '<path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 16v-4M12 8h.01"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
    users: '<circle cx="9" cy="8" r="3.5"/><path d="M2 20a7 7 0 0 1 14 0"/><path d="M16 4.5a3.5 3.5 0 0 1 0 7M22 20a7 7 0 0 0-4-6.3"/>',
    building: '<rect x="4" y="3" width="16" height="18" rx="1"/><path d="M9 7h1M14 7h1M9 11h1M14 11h1M9 15h1M14 15h1M10 21v-3h4v3"/>',
    briefcase: '<rect x="3" y="7" width="18" height="13" rx="2"/><path d="M9 7V5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2M3 13h18"/>',
    doc: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M9 13h6M9 17h6"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
    pin: '<path d="M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z"/><circle cx="12" cy="9.5" r="2.5"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    cloud: '<path d="M7 18a5 5 0 1 1 1.6-9.7A6 6 0 0 1 20 11a4 4 0 0 1-1 7z"/>',
    money: '<rect x="2" y="6" width="20" height="12" rx="2"/><circle cx="12" cy="12" r="2.5"/><path d="M6 12h.01M18 12h.01"/>',
    bolt: '<path d="M13 2 4 14h7l-1 8 9-12h-7z"/>',
    target: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1"/>',
    list: '<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>',
    chart: '<path d="M3 3v18h18"/><path d="M7 15v2M11 11v6M15 7v10M19 12v5"/>',
    phone: '<path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z"/>',
    refresh: '<path d="M21 12a9 9 0 1 1-2.6-6.4L21 8"/><path d="M21 3v5h-5"/>',
    arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    dot: '<circle cx="12" cy="12" r="4" fill="currentColor" stroke="none"/>',
  };
  function svg(name) {
    const d = P[name] || P.dot;
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + d + "</svg>";
  }
  function paintIcons(root) { (root || document).querySelectorAll("i[data-ic]:not([data-done])").forEach((el) => { el.innerHTML = svg(el.dataset.ic); el.dataset.done = "1"; }); }

  const css = (v) => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  window.Kit = {
    icons: Object.keys(P), icon: svg, esc,
    $: (sel) => document.querySelector(sel), $$: (sel) => Array.from(document.querySelectorAll(sel)),
    /** R 4,999 or R 4,999.50; other currencies by symbol. */
    money(v, sym = "R") {
      if (v == null || isNaN(v)) return "–";
      const n = Number(v), cents = Math.round(n * 100) % 100 !== 0;
      return sym + " " + n.toLocaleString("en-ZA", { minimumFractionDigits: cents ? 2 : 0, maximumFractionDigits: 2 }).replace(/ /g, ",");
    },
    num(v) { return v == null || isNaN(v) ? "–" : Number(v).toLocaleString("en-ZA").replace(/ /g, ","); },
    pct(v, digits = 0) { return v == null || isNaN(v) ? "–" : (Number(v) * 100).toFixed(digits) + "%"; },
    /** "Tue 7 Oct" or "Tue 7 Oct, 09:30". */
    when(ts, withTime = false) {
      const d = new Date(ts); const days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"], mon = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
      return days[d.getDay()] + " " + d.getDate() + " " + mon[d.getMonth()] + (withTime ? ", " + String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0") : "");
    },
    ago(ts) {
      const s = (Date.now() - ts) / 1000;
      return s < 60 ? "just now" : s < 3600 ? Math.round(s / 60) + " min ago" : s < 86400 ? Math.round(s / 3600) + " h ago" : Math.round(s / 86400) + " d ago";
    },
    /** A line chart of [[time, value], …] or [value, …], in the accent colour, with the latest value marked. */
    line(el, points, opts = {}) {
      if (typeof el === "string") el = document.querySelector(el);
      const pts = (points || []).map((p, i) => Array.isArray(p) ? [p[0], Number(p[1])] : [i, Number(p)]).filter((p) => !isNaN(p[1]));
      if (pts.length < 2) { el.innerHTML = '<div class="small">Not enough points yet.</div>'; return; }
      const W = 340, H = opts.height || 150, pad = 8, top = 16, bottom = 22;
      const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
      const x0 = Math.min(...xs), x1 = Math.max(...xs), lo = Math.min(...ys), hi = Math.max(...ys), span = hi - lo || 1;
      const X = (x) => pad + (x - x0) / ((x1 - x0) || 1) * (W - pad * 2), Y = (y) => top + (1 - (y - lo) / span) * (H - top - bottom);
      const d = pts.map((p, i) => (i ? "L" : "M") + X(p[0]).toFixed(1) + " " + Y(p[1]).toFixed(1)).join(" ");
      const area = d + " L" + X(x1) + " " + (H - bottom) + " L" + X(x0) + " " + (H - bottom) + " Z";
      const last = pts[pts.length - 1], fmt = opts.format || ((v) => (opts.unit === "R" ? Kit.money(v) : Kit.num(v)));
      el.innerHTML = '<svg class="chart" viewBox="0 0 ' + W + " " + H + '" role="img">' +
        '<path d="' + area + '" fill="' + css("--primary") + '" opacity=".12"/>' +
        '<path d="' + d + '" fill="none" stroke="' + css("--primary") + '" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round"/>' +
        '<circle cx="' + X(last[0]) + '" cy="' + Y(last[1]) + '" r="5" fill="' + css("--primary") + '" stroke="' + css("--surface-container-low") + '" stroke-width="2"/>' +
        '<text x="' + pad + '" y="' + (H - 4) + '" font-size="12" fill="' + css("--on-surface-variant") + '">low ' + esc(fmt(lo)) + "</text>" +
        '<text x="' + (W - pad) + '" y="' + (H - 4) + '" font-size="12" text-anchor="end" fill="' + css("--on-surface-variant") + '">high ' + esc(fmt(hi)) + "</text></svg>";
    },
    /** Horizontal bars: [{label, value}], biggest first unless opts.keepOrder. */
    bars(el, items, opts = {}) {
      if (typeof el === "string") el = document.querySelector(el);
      const list = (items || []).slice(); if (!opts.keepOrder) list.sort((a, b) => b.value - a.value);
      const max = Math.max(1, ...list.map((i) => Number(i.value) || 0)), fmt = opts.format || ((v) => (opts.unit === "R" ? Kit.money(v) : Kit.num(v)));
      el.innerHTML = '<div class="bars">' + list.map((i) => '<div class="bar-row"><span>' + esc(i.label) + '</span><div class="track"><span style="width:' +
        Math.max(2, (Number(i.value) || 0) / max * 100) + '%"></span></div><span class="v">' + esc(fmt(i.value)) + "</span></div>").join("") + "</div>";
    },
  };

  function start() { paintIcons(); if (typeof window.render === "function") safeRender(window.card.data()); }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start); else setTimeout(start, 0);
})();
