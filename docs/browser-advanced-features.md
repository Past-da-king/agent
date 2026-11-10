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

`place_whatsapp_call {chat, goal, max_minutes?, contact?, wait?}`

- `chat`: the chat's exact name, the number with its country code, or the chat id. A part of a name works when only
  one chat matches. Groups are refused.
- `goal`: why the agent calls and what to say or find out.
- `max_minutes`: hang up after this many minutes of conversation. Default 5, at most 30.
- `contact`: what to call the person when the chat's name is not what they are called.
- `wait`: `false` returns at once; `browser_call_voice` with action `status` then shows the phase and the result.

What it does: opens WhatsApp Web when the browser is elsewhere, checks that it is signed in and that the call voice
is in the page (loading the page again once when it is not), opens the chat, checks that the chat has a voice call
button, arms the voice, presses Voice call from page script (a tap on the button does not start a call), waits for
an answer (60 seconds), holds the conversation, hangs up when the voice says the conversation is done, when
`max_minutes` is reached or when the voice fails, stops the voice and saves the words to the chat.

It returns JSON:

```json
{"outcome": "answered | no_answer | failed", "reason": "...", "contact": "...", "chat": "<chat id>",
 "rang_seconds": 8, "duration_seconds": 22, "ended_by": "agent | max_minutes | other side or dropped | page",
 "transcript": "Them: ...\nVoice: ...", "saved_to_chat": true, "calling_offered": true,
 "test_account": "...", "whatsapp": {"...": "the call as WhatsApp reported it"}}
```

When the chat has no voice call button it places no call and says why: WhatsApp does not offer calling in WhatsApp
Web to the account, and whether the test-account switch is off or the signed-in number is not a marked one.

The call runs on the app's own scope. If the one who asked stops waiting (a linked computer waits a few minutes at
most), the call still ends properly and `browser_call_voice status` has the result under `call`.

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
  turning it on asks you to confirm the number, in the sheet itself.
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

## Tests

- `IsolatedOriginsTest`, `BrowserSheetTest`, `PcmTest`, `CallBridgeTest`, `WhatsAppCallTest` run on any machine: `./gradlew :app:testDebugUnitTest`.
- The page scripts run in Node: `node tools/check-call-bridge-hooks.mjs` and `node tools/check-whatsapp-call-scripts.mjs`.
- `CrossOriginIsolationDeviceTest` needs a real WebView, so a phone or an emulator:
  `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.past9.phoneaos.CrossOriginIsolationDeviceTest`
