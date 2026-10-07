# Building a card (the app's design skill)

A card is a small screen you write in HTML. It opens in a slide-up sheet inside the app and must look like the app made it: same colours, type, shapes and feel. The user can't tell it apart from a native screen. **How it looks matters most.**

## What you write
Only the BODY: HTML, plus an optional `<style>` and `<script>`. The app wraps it with:
- the user's live colours as CSS variables (light or dark, their accent),
- `agent-ui.css`: the component classes below,
- `kit.js`: the bridge, icons, formatting and charts.

The sheet already shows the card's **title**, **source** and **updated time** in a native header. Don't repeat them at the top.

## Rules
1. **Phone first:** 390 px wide. Nothing scrolls sideways except a `.table-wrap`.
2. **Only theme colours:** `var(--primary)`, `--on-surface`, `--surface-container`, `--success`, `--error`, `--warn` and so on. Never a hex or rgb value. Dark mode then works for free.
3. **Show, don't tell:**
   - numbers in `.stat`s,
   - change over time in `Kit.line`,
   - comparisons in `Kit.bars`,
   - schedules in `.timeline`,
   - progress in `.progress`,
   - status in `.badge`.
   - Words only where a number or picture can't do it. One short line per thought. No paragraphs.
4. **One headline first:** the single most important thing (the cheapest price, replies today, the next thing to do), in a `.hero` or the first `.stat`.
5. **Format for people:** `Kit.money(4999)` gives "R 4,999", `Kit.num`, `Kit.pct`, `Kit.when(ts)` gives "Tue 7 Oct", `Kit.ago(ts)`. No raw ISO dates, no ids, no 12-decimal numbers.
6. **Icons from the kit only:** `<i data-ic="mail"></i>`. Names: mail send reply check x clock calendar up down trend_up trend_down tag cart link star alert info user users building briefcase doc search pin sun cloud money bolt target list chart phone refresh arrow dot. Never emoji.
7. **Links** (`<a href>`) open in the app's browser on their own; you don't need JS for them.
8. **No network from the card.** Data comes from `card.data()`. No `fetch`, no CDNs, no web fonts, no external images except product/photo URLs in `<img>`, which are allowed.
9. **Empty and loading states:** if the data is missing or a list is empty, show a calm `.empty` with one line of what will appear and when.
10. **Copy:** plain words, sentence case, no em dashes, no exclamation marks.
11. **Get elements with `Kit.$("#id")`** (or `document.getElementById`). Never use an id as a bare variable: ids like `focus`, `name`, `status`, `close`, `open` and `top` collide with built-ins and silently do nothing.

## Data: make it live
- Put data in `card_save(data=…)` and render it with a `render(data)` function. The kit calls `render` on open and whenever new data arrives. Don't bake numbers into the HTML.
- **If a script can fetch it** (a price, an API, counting emails in a connected app), give the card a `script`. It runs on a schedule with no AI, and its last output line must be `{"data": {...}}`.
  - In a card script, `await apps("GMAIL_FETCH_EMAILS", {query: "..."}, account?)` runs a connected-app action (read-only; anything that sends or changes something is refused). It needs the user's one-tap OK the first time.
  - Previous data: `JSON.parse(process.env.CARD_DATA || "null")`. Input from a search box: `JSON.parse(process.env.CARD_INPUT || "{}")`.
- **If it needs judgement** (a brief, a summary), don't add a script. A routine of yours calls `card_update` with fresh data.
- **A search box:** `<input class="input" id="q">` plus `card.run({query: q.value})`. That runs the script with `CARD_INPUT` and re-renders with what it returns.

## Patterns (copy and adapt)

Hero plus stats:
```html
<div class="hero stack tight"><span class="eyebrow">Cheapest today</span><span class="headline num" id="best"></span><span class="small" id="bestName"></span></div>
<div class="stats section"><div class="stat"><span class="label">Sent today</span><span class="value" id="sent"></span><span class="delta up"><i data-ic="trend_up" class="sm"></i> 4 vs yesterday</span></div>
<div class="stat"><span class="label">Replies</span><span class="value" id="rep"></span></div></div>
```

List with thumbnails and prices:
```html
<div class="section-head section"><span class="eyebrow">Tracked monitors</span></div>
<div class="list" id="items"></div>
<script>
function render(d){ if(!d){return}
  items.innerHTML = d.items.map(i => `<a class="item" href="${Kit.esc(i.url)}"><img class="thumb" src="${Kit.esc(i.image)}"><div class="main"><span class="name">${Kit.esc(i.name)}</span><span class="meta">${Kit.esc(i.store)}</span></div><div class="end"><span class="value">${Kit.money(i.price)}</span>${i.drop?`<span class="badge good">-${Kit.money(i.drop)}</span>`:""}</div></a>`).join("");
}
</script>
```

Tracker table (status as badges, numbers right-aligned):
```html
<div class="table-wrap"><table><thead><tr><th>Company</th><th class="n">Sent</th><th>Status</th></tr></thead><tbody id="rows"></tbody></table></div>
```

Timeline (daily brief):
```html
<div class="timeline"><div class="t-item done"><span class="t-dot"><i data-ic="check" class="sm"></i></span><div><div class="t-time">08:00</div><div class="t-title">Gym</div></div></div>
<div class="t-item now"><span class="t-dot"><i data-ic="clock" class="sm"></i></span><div><div class="t-time">10:30</div><div class="t-title">Call Lerato about the invoice</div><div class="small">She asked for the PDF yesterday</div></div></div></div>
```

Charts: `<div id="trend"></div>` then `Kit.line("#trend", d.history, {unit:"R"})`. And `Kit.bars("#by", [{label:"Acme", value:3}])`.

## Check your work
`card_save` returns a screenshot of the card at phone size. **Look at it.** If anything is cramped, overflowing, misaligned, unreadable, word-heavy or not obviously the most important thing first, fix it and save again. Do this before you tell the main agent it's done.
