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
the microphone and listen to the speaker of a call. It does nothing until the agent starts a call voice with
`browser_call_voice`; until then chats, voice notes and calls you place by hand behave as before.

How a call goes:

1. The agent opens the chat and calls `browser_call_voice` with action `start`, who it is calling and why.
2. It presses the voice call button in the page.
3. WhatsApp is handed a made-up microphone. The agent's live voice (Gemini Live, the same one phone calls use, with
   the computer's one-use token when linked or this phone's Google key) is played into it. What the other person says
   is taken from the call's speaker output, brought to 16 kHz and sent to the voice. The phone's own microphone and
   speaker are not used.
4. After hanging up, action `stop` ends the voice and saves the words: as a call in the linked computer's chat, or in
   the agent's memory of the conversation when the phone runs alone.

What it does not do: it changes no WhatsApp setting and switches on no WhatsApp feature. A call only works if
WhatsApp itself offers calling in WhatsApp Web to the account.

Proven so far: the list on a real phone (Samsung A35, WebView 156); the message protocol, the audio sums and the
wiring in unit tests; the page script against a made-up page (`node tools/check-call-bridge-hooks.mjs`). Not yet
proven: a real call end to end on a phone.

## Tests

- `IsolatedOriginsTest`, `BrowserSheetTest`, `PcmTest`, `CallBridgeTest` run on any machine: `./gradlew :app:testDebugUnitTest`.
- `CrossOriginIsolationDeviceTest` needs a real WebView, so a phone or an emulator:
  `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.past9.phoneaos.CrossOriginIsolationDeviceTest`
