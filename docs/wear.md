# The agent on the wrist: the Wear OS app (v1)

The agent lives on any Wear OS watch as an assistant: open it from the app list, a tile, a complication or a key; talk or tap; read the answer; approve what needs you; get its notifications. It is a product feature for every user. Nothing here is tuned to one watch or one person: the agent's name comes from the phone's settings.

- Module `wear` (the watch app) and `wearproto` (the protocol both apps share) sit next to `app` (the phone).
- The watch holds **no account, key, model or memory**. The phone relays to the agent, so it works whichever brain the phone uses (its own agent, or the computer in remote mode).
- Same `applicationId` (`com.past9.phoneaos`) and the same signing key on both, so the Wearable Data Layer pairs them.

## 1. Transport (watch to phone, over the Wearable Data Layer)

The watch only ever talks to the phone it is paired with, through Google Play services' Data Layer (Bluetooth, or Wi-Fi/LTE via the cloud relay Google runs for paired devices). No server of ours, no third party. See `wearproto/.../Wire.kt`.

| Direction | API | Path | Payload (JSON) | Reply |
|---|---|---|---|---|
| watch to phone | MessageClient | `/agent/ask` | `{id, text, via}` via = keyboard, quick, speech, phone | `/agent/ack {id, ok, reason}` |
| watch to phone | MessageClient | `/agent/answer` | `{card, option}` | `/agent/ack {id:"card:<n>", ok, reason}` |
| watch to phone | MessageClient | `/agent/stop`, `/agent/speak-on-phone`, `/agent/hello` | empty | ack / none (hello is answered by a fresh state) |
| watch to phone | ChannelClient | `/agent/audio/<id>` | 16 kHz mono PCM16, up to 30 s | `/agent/heard {id, ok, text, reason}` |
| phone to watch | DataClient | `/agent/state` | the whole picture, one item, replaced each time | none |

`/agent/state` is `{v, agent, link, working, turn, pending[], recent[]}`:
- `agent`: the agent's name from the phone's settings ("" until named; the watch then writes "Agent" in its own language).
- `link`: `local` (the phone runs the agent), `pc` (the computer does), `offline` (the computer is not answering).
- `turn`: `{phase, heard, label, answer, answerId, problem}`; phase is sending, thinking, acting, answering, approval, asking or error, read from the phone's own chat rows by the same state machine the phone's assist sheet uses (`AssistCore`), so the watch can never say something the phone does not.
- `pending[]`: every approval or question that waits, `{id, kind, title, details, options[], by}`. An approval always carries exactly `Approve` and `Decline`.
- `recent[]`: the last 8 exchanges (what was said, the answer), newest first.

Why these APIs: a DataItem is kept by the system until the watch reads it, so a watch that was off or out of range wakes to the newest state; `setUrgent()` is used when something waits for him so a card reaches the wrist at once. Messages are for commands, which are small and need an answer. A Channel carries the voice take, which is too big for a message.

**The phone side** (`app/.../wear/`): `WatchRelayService` (a `WearableListenerService`, started by the system when a message arrives, so the watch works with the phone app closed) hands every message to `WatchRouter`, which calls the phone's own paths:
- ask: `runtime.sendAccepted(...)` (the call the assist sheet makes), with the same note the chat already hides.
- answer: `runtime.answer(itemId, option)`, the call the phone's buttons and its notification buttons make. **No new approval path.** The relay first checks that the card still waits and that the option is one the card itself offers (`WatchStateBuilder.checkAnswer`); anything else is refused. An approval can only be approved or declined, never anything else.
- stop: `runtime.stop()`. speak-on-phone: `AssistEntry.open(...)`, the phone's own listening sheet.
- A request that arrives twice (the Data Layer retries) runs once (ask ids are remembered).
- `WatchRelay` publishes the state (debounced 300 ms, identical states are not re-sent) and **starts only after a watch has said hello**, so a phone with no watch pays nothing. The phone declares the capability `agent_phone_relay` (`res/values/wear.xml`); the watch finds it with `CapabilityClient`.

**Watches away from the phone (LTE or Bluetooth-only).** The relay is the only path in v1. A watch that cannot reach the phone shows a clear **"Phone not reachable"** line on the talk screen and a failed send says so with a Try again button; nothing is lost silently. An LTE watch away from its phone still has the Data Layer over the cloud relay when the phone has internet, so it keeps working in most cases; with the phone off or offline it cannot, by design (no keys on the wrist). A direct watch-to-agent link (the watch calling the computer on its own LTE) is a later option and needs a watch-held credential, which this version deliberately avoids.

## 2. Screens

Wear Compose Material 3 (`androidx.wear.compose:compose-material3`), dark only (Wear is dark-first; the background is pure black so an OLED switches it off). Colours, the amber that means "this needs you", and the corner steps are the phone app's own tokens (`ui/theme/Theme.kt`) adapted in `wear/.../ui/Theme.kt`. Layouts are built for round and for square or rectangular glass: nothing assumes a circle (`ScreenScaffold`, `TransformingLazyColumn`, `EdgeButton`). Mocks are real Compose renders, in `docs/wear/<shape>-<size>/` at round 192 and 227 dp, square 192 dp and rectangular 180 x 220 dp.

1. **Talk** (home): the agent's name, what needs him (an amber button when something waits), then the ways to ask. **The watch mic leads**: a big edge button, Talk. Below it Speak on phone, Type (keyboard), quick replies (Yes, No, Not now, Remind me later, What is next, Tell me more) and Recent. If the mic is missing or fails, the screen **picks up the fallbacks by itself**: the edge button becomes Speak on phone, a line says the watch mic is not available, and a Try the watch mic button resets it.
2. **Listening**: a ring that grows with the voice, Stop at the bottom. It stops by itself when speech ends. Then **Sending** while the phone writes the words down.
3. **Answer**: what he said, a status line (Thinking, Reading your calendar, Needs you), the answer in a scrolling list (the crown and the bezel scroll it), and an edge button that is Reply, Stop (while working), Review (when a card waits) or Try again (on an error). Swipe right to go back.
4. **Approval / question**: an amber "Needs you 1/2", the summary, who asked, the details. **Approve is hold-to-approve**: press and hold 0.9 s while a bar fills (a tap cannot approve; the haptic fires when it counts; an accessibility long-click action exists for TalkBack). Decline is a normal button. A question shows its own answers; one with none says "Answer on your phone". After an answer a **full-screen confirmation** shows (Approved, Declined, Sent) for 1.4 s, then the next card or the conversation. **No centred pop-up dialogs anywhere**; everything is a full screen with swipe-to-dismiss.
5. **Recent** and **Exchange**: the last 8 exchanges; a tap opens one with the full answer and a Reply button.

## 3. Tile and complication

- **Tile** (`AgentTile`, ProtoLayout, tiles 1.6): the agent's name, one line (All clear, or "2 need you" in amber) and a big Ask pill. Ask opens the talk screen already listening (or on the phone's mic when this watch's is gone); the amber line opens the first card. It reads the last state from disk, so it draws instantly, and the app asks the system to redraw it when the state changes. No tile layout can run code, so it never does anything the app does not.
- **Complication** (`AgentComplication`, SHORT_TEXT and MONOCHROMATIC_IMAGE): the number of things that need him, or "Ask" with a mic when nothing does. A tap opens the card or the talk screen. Updated by push when the state changes (no polling).

## 4. Assistant entry: what Wear OS and One UI Watch really allow

**Read from a real Galaxy Watch4 Classic (SM-R880) over adb, read only, 12 Nov 2026:** Wear OS 6 / Android 16 (API 36), One UI Watch 8 (`ro.build.version.oneui=80000`), Wear SDK level 7, build `R880XXS2JZG3`, security patch 2026-08-05.
- The digital-assistant **role** (`android.app.role.ASSISTANT`) is held by `com.google.android.wearable.assistant` (Gemini, which replaced Google Assistant on Wear OS 4 and later; Google's own announcement: [Yahoo Tech](https://tech.yahoo.com/wearables/articles/wearos-starting-transition-gemini-first-223738249.html)). `Settings.Secure assistant` = `com.google.android.wearable.assistant/.MainActivity`; `voice_interaction_service` is empty.
- `ACTION_ASSIST` and `ACTION_VOICE_ASSIST` each resolve to exactly one activity, Google's. `ACTION_VOICE_COMMAND` resolves to Google's settings stub and to Bixby's `HeadSetReceiver`. Bixby (`com.samsung.android.bixby.agent` 1.5.29.4, `bixby.wakeup`) is installed. Samsung's voice input is `com.samsung.android.wear.samsungvoiceinput`; the keyboard is Samsung's and Gboard.
- The system settings that drive the keys (`Settings.Global`): `setting_home_key_long_press = googlevoiceassistant/googlevoiceassistant` (press and hold Home is the assistant), `setting_home_key_double_press = lastapp`, `setting_raise_to_talk_assistant = gemini` (raise to talk, off here), `setting_recent_key_short_press = 1`, `setting_action_button_press = None/None`. Wear's own `power_button_*` and `stem_primary_button_*` are null.
- A **Choose default apps** screen exists (`com.google.android.permissioncontroller/...role.ui.DefaultAppListActivity`, reached from Settings > Apps > Choose default apps).
- The microphone: the watch reports `android.hardware.microphone` even though its mic is dead, which is why the app judges the mic from what it records (section 5), not from the feature flag.

**What is therefore possible, and what v1 does:**
1. **Digital assistant app** (Settings > Apps > Choose default apps > Digital assistant app). Home Assistant's watch app is selectable there with an `ACTION_ASSIST` plus `ACTION_VOICE_ASSIST` activity alias ([its manifest](https://github.com/home-assistant/android/blob/master/wear/src/main/AndroidManifest.xml), [its docs](https://www.home-assistant.io/docs/assist/android)); on Android 15 and later the role needs a `VoiceInteractionService` too, so the watch app declares all of: the alias, a `VoiceInteractionService` + session service + recognition service (the same trio the phone app declares). When it holds the role, the Samsung "press and hold Home = Assistant" key opens it, and so does the system assistant gesture. **Not yet proven on a watch** (nothing may be installed yet): after install, check `adb shell cmd role get-role-holders android.app.role.ASSISTANT` and the list on the watch.
2. **Customize keys** (Settings > Advanced features > Customize keys, Home key > double press): Samsung lets a double press open any app ([Samsung](https://www.samsung.com/ae/support/mobile-devices/customize-your-galaxy-watch-double-press-key-functions/), Galaxy Watch4 to Watch7 and later; Home Assistant uses exactly this for its assist key). The app is a normal launcher app, so it appears in the list. Press and hold only offers the assistant choices (Samsung's page does not list Bixby or Gemini as selectable apps). The exact value Samsung stores for a chosen app is read back from `settings get global setting_home_key_double_press` after the first choice.
3. **Tile and complication** work on every Wear OS watch and need no role: they are the guaranteed way in. The app list and the launcher icon are the third.
4. Bixby cannot be replaced by an app (it is Samsung's own agent behind `setting_bixby_launching_disallowed`); the way is to turn Bixby's key off in the same Customize keys screen and give the key to the app.

Fallback order if the role is refused: double press Home, then tile, then complication, then the app list.

## 5. Voice: the watch mic leads, the fallbacks are automatic

1. **Normal case, a working mic**: Talk opens `AudioRecord` (VOICE_RECOGNITION, 16 kHz mono PCM16), a ring shows the level, it stops when speech ends, and the take goes to the phone over a Channel. The phone writes it down with its existing dictation pass (`PieceTranscriber`: the linked computer's `voice.transcribe`, which knows his names, or the phone's own Gemini speech model when not linked) and **sends the words to the agent itself**, then answers `/agent/heard` so the watch can show what was heard. One hop, no second send.
2. **Detecting a dead or missing mic** (`MicPolicy`, pure and tested): before recording, the hardware feature, a built-in input and the RECORD_AUDIO permission; during recording, a take of at least 1.5 s whose every 100 ms window peaks at 3 or less (digital silence, what a broken mic records) marks the mic dead; so does a recorder that fails to start. A dead note is kept for a day, or until he taps "Try the watch mic".
3. **Automatic fallbacks**, in the Talk screen: **Speak on phone** (the phone opens its own listening sheet; the words go to the agent and the answer comes back to the watch, nothing to retype), **Type** (the system keyboard through Wear's `RemoteInput`, so the watch's own keyboard, handwriting and dictation are all available), and the **quick replies**.
4. Nothing is installed or changed on a watch to do this, and no audio leaves the watch except to the paired phone.

## 6. Notifications

The phone app's notifications (a finished reply, a question or approval with its Approve and Decline buttons) are **bridged to the watch by Wear OS itself**, and their buttons run on the phone through the same receivers (`AnswerReceiver`), so approving from a watch notification is the phone's own path. The watch app does not set `NO_BRIDGING` and posts no notifications of its own in v1, so nothing is doubled. A watch-local notification with a bridge tag (to survive a phone that is away) and an Ongoing Activity chip while the agent works are follow-ups.

## 7. Future hooks

- **Double-tap gesture** (Wear OS 5+ `GestureAction` / One UI "quick actions") to start the mic: the entry is already one intent (`MainActivity` with extra `go=talk`).
- **Recording**: a long take saved to the phone's Recordings (the channel and the phone's `Dictations` store already exist).
- A watch-local notification with the bridge tag; an Ongoing Activity chip; project chats in Recent (v1 shows the main thread only); watch-held direct link for LTE (needs a design for a revocable watch credential); a Wear OS emulator pairing run in CI.

## 8. What was proved, and what was not

See the pull request description and the final report: unit tests for the protocol, the router, the card rules and the mic policy; screenshot tests for each screen at the four glass shapes; a debug build of the watch and the phone. **Not proven**: a real Data Layer round trip on devices (the server has no KVM, so no Wear emulator could run), the assistant role on a real watch, and voice (the test watch's mic is dead).
