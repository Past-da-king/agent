# Sites that need advanced browser features

Some sites do heavy work inside the page and need shared memory for it (SharedArrayBuffer, threaded WebAssembly):
WhatsApp Web calls, in-browser editors, Ai models that run in the page. Android's WebView hides that from every site
unless the app names the site. The agent's browser names only the sites on your list.

## The list

Settings, Browser, "Sites that need advanced browser features".

- A fresh install has one site: `https://web.whatsapp.com`. You can remove it, and it does not come back.
- Add a site by its exact address. Secure sites only (https), the site itself with nothing after the name, no
  wildcards. `stackblitz.com` becomes `https://stackblitz.com`; `http://...`, `https://*.example.com` and
  `https://example.com/app` are refused, and the sheet says why.
- Changes apply to new tabs: reload the page, or let the browser close and open it again.
- Naming a site relaxes a browser guard (against Spectre-style reads) for that site only. Add only sites you trust.

It needs Android System WebView with the cross-origin isolation list (`WebViewFeature.CROSS_ORIGIN_ISOLATED_ALLOWLIST`,
androidx.webkit 1.18). On an older WebView nothing happens and nothing breaks: the site says itself that it is not
supported. The site must also send its own isolation headers (COOP and COEP, or Document-Isolation-Policy); WhatsApp
Web does.

### Check it on the phone

Open `https://web.whatsapp.com` in the agent's browser, then run this with `browser_eval`:

```js
return ({coi: self.crossOriginIsolated, sab: typeof SharedArrayBuffer, reason: require('WAWebVoipGatingUtils').getUnsupportedBrowserReason()})
```

Expected: `{coi: true, sab: "function", reason: null}`. Before 0.15.33: `{false, "undefined", "missing_shared_array_buffer"}`.

## The agent's voice in WhatsApp calls

Same sheet: "Let the agent speak in WhatsApp calls". Off by default.

When it is on, pages on `https://web.whatsapp.com` (and no other site) load with a small script that can stand in for
the microphone and listen to the speaker of a call. Sound passes straight through until the agent starts a call
voice; chats, voice notes and calls you place by hand behave as before. With the switch off the script is not put in
the page at all, so nothing is changed.

Since 0.15.34 the switch takes effect at once. An open browser gets the script the moment the switch is turned on
(an open WhatsApp Web page is loaded again), and a tool that finds the switch on in a browser that opened earlier
puts it in and reloads the page itself. Before, the switch was only read when a browser opened, so the browser had
to sit idle for minutes until its service closed it, and then be opened again.

### What the script answers for WhatsApp (0.15.34)

On a real call (Samsung A35, WebView 156, 10 Nov 2026) the call connected but the other person heard nothing.
WhatsApp never captured a microphone: in this browser the device list comes back without ids
(`enumerateDevices()` gives `deviceId: ''`) and `navigator.permissions.query({name: 'microphone'})` says `prompt`, so
WhatsApp found no device (`voip: [AV] No audio device selected.`). With the switch on, on web.whatsapp.com only:

1. `navigator.mediaDevices.enumerateDevices()` lists one microphone,
   `{kind: 'audioinput', deviceId: 'default', label: 'Agent mic', groupId: 'agent-audio'}`, with an id that stays the
   same. Speakers and cameras are handed back as the browser gives them, so WhatsApp keeps its default speaker and
   never asks for one by a made-up id.
2. `navigator.permissions.query` answers `granted` for `microphone` (a status object WhatsApp can listen to). Every
   other permission name goes to the browser as before.
3. The made-up microphone is mono at 48 kHz.

While no call voice runs, a request for the listed microphone by its id reaches the phone's real microphone.

### A whole call in one step: `place_whatsapp_call`

`place_whatsapp_call {chat, goal, max_minutes?, min_seconds?, contact?, wait?}`

- `chat`: the chat's exact name, the number with its country code, or the chat id. A part of a name works when only
  one chat matches. Groups are refused.
- `goal`: why the agent calls and what to say or find out.
- `max_minutes`: hang up after this many minutes of conversation. Default 5, at most 30.
- `min_seconds`: the conversation must last at least this long after the answer. Default 0. See "A minimum length"
  below.
- `contact`: what to call the person when the chat's name is not what they are called.
- `wait`: `false` returns at once; `browser_call_voice` with action `status` then shows the phase and the result
  (`state` and `call` in the summary, the words under part `transcript`).

What it does: opens WhatsApp Web when the browser is elsewhere, checks that it is signed in and that the call voice
is in the page (loading the page again once when it is not), opens the chat, checks that the chat has a voice call
button, arms the voice, presses Voice call from page script (a tap on the button does not start a call), waits for
an answer (60 seconds), holds the conversation, hangs up when the voice says the conversation is done, when
`max_minutes` is reached or when the voice fails, stops the voice and saves the words to the chat.

It returns JSON:

```json
{"outcome": "answered | no_answer | failed", "reason": "...", "contact": "...", "chat": "<chat id>",
 "rang_seconds": 8, "duration_seconds": 22, "ended_by": "agent | max_minutes | other side or dropped | page",
 "min_seconds": 0, "transcript": "Them: ...\nVoice: ...", "transcript_chars": 123, "saved_to_chat": true,
 "ended": {"...": "who or what ended it, see below"}, "calling_offered": true, "test_account": "...",
 "whatsapp": {"...": "the call as WhatsApp reported it"}, "trimmed": {"...": "only when something was cut"},
 "timeline": [], "counters": [], "page_errors": []}
```

The keys come in this order, and the whole answer is never longer than 10,000 characters (see "Answers that fit").

When the chat has no voice call button it places no call and says why: WhatsApp does not offer calling in WhatsApp
Web to the account, and whether the test-account switch is off or the signed-in number is not a marked one.

The call runs on the app's own scope. If the one who asked stops waiting (a linked computer waits a few minutes at
most), the call still ends properly and `browser_call_voice status` has the result under `call`.

### A minimum length: `min_seconds` (0.15.36)

In the first real call the goal asked for at least 65 seconds of conversation and the voice called `end_call` at
42.4 seconds. A goal is only words to the voice; `min_seconds` is a rule the app keeps. It is on
`place_whatsapp_call` and on `browser_call_voice` `start`, and it is 0 (no minimum) unless asked for.

- The voice is told the minimum before the call, in its instructions.
- If it calls `end_call` before `min_seconds` have passed since the answer, the call is not ended. The voice gets
  back `{ok: false, call_continues: true, seconds_left: N}` and is told to keep the conversation going naturally
  and not to say goodbye yet. The timeline keeps an `end_call_refused` event with the second it was asked and how
  long was left; `ended.voice` has `end_call_refused` (how often) and `min_seconds`, and `ended.notes` says it.
- Once the minimum has passed, a voice that was refused is told so once (`min_seconds_reached` in the timeline) and
  its next `end_call` is carried out as always.
- A hangup on the other side ends the call at once, whatever the minimum.
- `max_minutes` wins: a minimum longer than the limit is the limit.
- Before anyone has answered, the whole minimum is still to come.

### Answers that fit (0.15.36)

A phone tool's answer that travels to a linked computer is cut there at 12,000 characters (the computer's device
tools, `MAX_TEXT_TO_AGENT`). The whole status of the first real call was longer, and the transcript came last, so
it was lost. Now no answer of `browser_call_voice` or `place_whatsapp_call` is longer than 10,000 characters, by
construction (`browser/CallReport.kt`, `CallReportTest`):

- `browser_call_voice` `status` with nothing else is a summary of at most 5,500 characters: `state`, `call` (the
  phase while it runs, then outcome, reason, contact, durations, `ended_by`), `ended` (by, detail, seconds after
  the answer, signs, notes, the page and voice blocks), `duration_seconds`, `voice` (running, ready, the minimum
  and what is left of it, frames heard and sent), `transcript_chars`, `transcript_lines`, `transcript_last`,
  `timeline_events` with `timeline_last` (the last six), `counters_total`, `page_errors` (a count) and `more`.
- `status` with `part` gives one whole part: `transcript`, `timeline`, `counters`, `errors`, `ended` or `page`
  (the page's own state and its last log lines). A part that does not fit comes in pages: each says `from`, `to`,
  the total, `more` and `next`; ask again with `from` set to `next` until `more` is false. `more` and `next` come
  before the content.
- The result of `place_whatsapp_call` has the outcome, the transcript and `ended` first. A transcript over 5,000
  characters is given as its beginning and its end with a line saying how much is left out between them. The
  timeline is cut to the events that explain the call (the answer, the end, a refused `end_call`, failures) and
  the newest of the rest, the counters to the first and last lines, the page errors to the last few. `trimmed`
  says what was cut and how to fetch each whole.
- Anything else these tools say (the words `stop` hands back, for one) goes through the same cap.

Hanging up is made sure of: the page's own End call button, the same again, and as the last resort the page is left
(`about:blank`), which takes the call down with it.

### Who the voice thinks it is talking to

The voice is told who it is, for whom it calls (the name in the app's profile), who will answer (the `contact`, or
the chat's name), to greet that person by name first, why it calls, and to say a polite goodbye and call `end_call`
when it is done. When the person called is the user, it greets them directly and does not say it calls on anyone's
behalf. If the call connects and nobody speaks for four seconds, the voice is told once to greet first.

`browser_call_voice` (start, status, stop) still exists for doing the steps by hand. `start` now reads the name of
the open chat when no `peer` is passed.

### Calling for a test account

Same sheet: "Turn on calling for a test account". Off by default.

WhatsApp decides per account whether WhatsApp Web may place calls. For an account without it the chat has no call
buttons. The app can switch it on inside the open page, but:

> WhatsApp has not turned on calling in WhatsApp Web for this account. Forcing it on could get the number banned.
> Use a test number only, never your main number.

How it decides:

- You type the test account's number, with its country code. The switch cannot be turned on without one, and
  turning it on asks you to confirm the number, in the sheet itself. This sheet is the one supported way to set it;
  an agent cannot (the phone tools have no way to), and anything typed into the WhatsApp Web page by hand is not a
  setting.
- It is applied only by `place_whatsapp_call`, at the moment of the call, and only when the number signed in to
  WhatsApp Web is exactly a number you marked. The page script checks the signed-in number again itself.
- Any other account, a switch that is off, or a page that does not say who is signed in: nothing is changed, and
  the tool says calling is not offered.
- When WhatsApp already offers calling to the account, nothing is forced.
- It lives in the page's memory only. Nothing is sent to WhatsApp, and a reload undoes it.
- No number is written into the app. Removing the last number turns the switch off.

Proven so far: a real call both ways on a Samsung A35 (WebView 156) on 10 Nov 2026, with the three answers above
typed into the page by hand. In unit tests and against made-up pages in Node: the page script
(`node tools/check-call-bridge-hooks.mjs`), the scripts of `place_whatsapp_call`
(`node tools/check-whatsapp-call-scripts.mjs`), the call from start to end in every outcome, the test-account rule
and the sheet. Not yet proven on a phone: 0.15.34 itself, so the answers as shipped in the app,
`place_whatsapp_call` end to end, hanging up by the End call button, and the switch taking effect at once.

### These settings and an update (0.15.36)

After the update from 0.15.33 to 0.15.35 the test account switch was off and had no number, and it looked as if
the update had lost it. It had not: 0.15.33 has no such switch (it came with 0.15.34, which was never installed),
and the calls made on 0.15.33 had calling forced by hand inside the page, which is page memory and gone on a
reload. Nothing was ever stored, so there was nothing to carry over.

What is stored, and where: all four browser settings live in the app's `settings` preferences file, under keys
that have not changed since they were added: `x_isolated_origins` and `x_call_bridge_on` (0.15.33),
`x_call_test_on` and `x_call_test_numbers` (0.15.34). There is no migration, nothing is cleared when the version
changes, and opening the store writes nothing. `SettingsCarryOverTest` writes the file the way 0.15.33 and 0.15.35
did and reads it with today's code, and fails if a key is renamed.

One real gap was closed: these settings were written in the background, so a switch turned on a moment before the
app was killed (an update over adb kills it) could be lost. They are now on disk before the tap returns.

### Why a call ended: the call's own story (0.15.35)

The first real calls (10 Nov 2026) ended 10 and 25 seconds after the answer, and nothing could say why. WhatsApp
logged both as `Completed`, result 5, not ended here, no failure reason. Reading WhatsApp's source showed that
result 5 is `CallLogResult.Connected`: it only says the call was connected, never who ended it. Sound flowed both
ways until the last second, the two calls ended at different times, and nothing in the app runs on a clock near 20
seconds, so the likely cause is that the person hung up. It could not be proven from what was kept.

So every call now explains itself. `browser_call_voice status` and the result of `place_whatsapp_call` carry:

- `ended`: `by` and `detail`, then what each part said.
  - `agent_end_call`: the voice called `end_call` and the agent hung up. `max_minutes`: the limit. `voice_failed`:
    the voice broke, so the agent hung up. `agent`: any other hangup by the agent (nobody answered, the call
    stayed disconnected for 30 seconds).
  - `remote_hangup`: WhatsApp's call engine reported `CallTerminateReceived` (or `RTCPByeReceived`): the other
    side ended it.
  - `whatsapp_failure`: WhatsApp reported `RxTimeout`, `TxTimeout`, `CallFatal`, `RelayBindsFailed` and the like,
    a `callFailedReason`, or result `Failed`. The call broke; the names are in `detail`.
  - `ended_in_this_browser`: `userEndedCall` is true (End call was pressed here).
  - `page`: the page stopped answering the app (reloaded, frozen, closed).
  - `unknown`: WhatsApp logged a normal end and none of the above was seen. `whatsapp_events_read` says whether
    WhatsApp's own call events could be read in this WhatsApp build; when it is false, a hangup and a silent drop
    cannot be told apart.
  - `whatsapp` (the fields of `lastEndedCall`, with `result` as a name), `voice` (failed, closed with its close
    code, end_call asked), `microphone` (the state of its audio context, tracks handed out, ended, muted, and how
    often it had to be started again), `page` (answering, `hidden_now`, `was_hidden`, `hidden_seconds`, errors),
    `seconds_after_answer`.
  - `signs`: what looked wrong on our own side, in plain words, whatever the verdict: the voice failed or its
    connection closed (with the close code), a microphone track ended, the microphone's audio context was not
    running or had to be started again, the page loaded again or stopped answering, nothing arrived or nothing was
    sent in the last counter lines, sound stopped while the page was hidden. An empty list with `unknown` or
    `remote_hangup` points away from us.
  - `notes` (0.15.36): things worth knowing that are not faults. A page that was hidden while sound kept flowing
    is one. In the first real call the person answered on the same phone, so WhatsApp's own call screen covered
    the browser; 0.15.35 listed "The page was hidden during the call." under `signs` while `page.hidden` said
    false (it was visible again by the end). Now `page` says both things apart (`hidden_now`, and `was_hidden`
    with `hidden_seconds`), the note says for how long and whether it is hidden now, and it only becomes a sign
    when counter lines taken while it was hidden, after the answer, show no sound (no sound frames in the page, or
    nothing sent, or nothing received). The page script counts the hidden time itself (`page_hidden` and
    `page_visible` in the timeline) and does nothing else about it.
- `timeline`: the events that matter, each with `s`, the seconds since the answer (negative before it): voice
  armed, ready, reconnecting, closed; first words heard and spoken; `wa_connected`, `wa_not_connected`,
  `wa_ended`; each WhatsApp call event by name (`wa_event`); microphone and track changes; page loaded again.
- `counters`: one line every 5 seconds: voice chunks played into the microphone, frames heard, bytes sent and
  received on the busiest of the call's connections (`getStats`), how many connections are open, the ICE state,
  the microphone's state, whether the page is hidden and for how many seconds of the line (`hidden_seconds`).
  Lines are timed from the answer like the timeline. A long call keeps its first minute and its last four.
- `page_errors`: the page's last 20 errors, with their second.

The story stays readable after the call, until the next call voice starts.

How to read the next call. `remote_hangup` with steady counters to the end: the person hung up. Counters where
`bytes_received` goes to 0 for two or three lines and then `RxTimeout` or `RxTrafficStopped`: the network or
WhatsApp's relay dropped it. `voice_chunks` at 0 while the transcript shows the voice speaking, `mic_context` not
`running`, or a `mic_track ended`: our microphone died. `page_silent` or `page_loaded_again`: the page went away.

What else changed so that a call is not lost on our side:

- The made-up microphone is one unbroken stream for the whole call and is never perfectly silent: between the
  voice's sentences WhatsApp gets a hiss far too quiet to hear instead of exact zeros. If the browser stops its
  audio context mid-call it is started again, and the same for WhatsApp's own call sound.
- While a call voice runs the phone holds a wake lock and a Wi-Fi lock (let go when the voice stops), and the
  browser's service is kept from closing every 5 seconds, sound or no sound.
- A voice whose connection closes for good mid-call (after its own reconnects) now counts as failed, so
  `place_whatsapp_call` hangs up and says so instead of leaving the person on a silent line until `max_minutes`.
- The microphone's audio context is also checked once a second and started again if the browser stopped it
  without saying so.
- `place_whatsapp_call` no longer ends a call that WhatsApp is reconnecting after 5 seconds: while WhatsApp still
  shows the call it gets 30 seconds to come back. The only limits are 20 seconds for the call to start, 60
  seconds of ringing and `max_minutes`.
- Reading WhatsApp's call events wraps one function of WhatsApp Web (`handleWAWebVoipNativeCallEvent`) while a
  call voice runs. Each event is handed on untouched. Nothing is changed, forced or ended by it.

## Tests

- `IsolatedOriginsTest`, `BrowserSheetTest`, `PcmTest`, `CallBridgeTest`, `CallDiagTest`, `WhatsAppCallTest`, `CallReportTest`, `SettingsCarryOverTest` run on any machine: `./gradlew :app:testDebugUnitTest`.
- The page scripts run in Node: `node tools/check-call-bridge-hooks.mjs` and `node tools/check-whatsapp-call-scripts.mjs`.
- `CrossOriginIsolationDeviceTest` needs a real WebView, so a phone or an emulator:
  `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.past9.phoneaos.CrossOriginIsolationDeviceTest`
