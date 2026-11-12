# Live voice in the assistant

The assistant sheet (side key, corner swipe, wake word) can open a Gemini Live session instead of the one-request
input. Settings > Default assistant > "Live voice in the assistant" (off by default).

## What it does
- Talks naturally, answers aloud at once, can be talked over, minimises to a floating bubble (the agent's own mascot).
- **Sees the screen itself.** While a session is open it sends frames of the screen into the Live session as
  `realtimeInput.video` JPEGs (about 768 px on the long side, about one a second while the screen changes, much slower
  when it holds still, and a fresh one when the user starts speaking). Live answers "what is on my screen" and
  "look at this" from those frames; only real work goes to the agent.
  - Source: the phone's own assistant path (`VoiceInteractionSession` screenshot, see `AssistScreenNow.captureFrame`), no
    screen recording and no capture indicator.
  - Needs "Let the assistant read the screen" and "Live sees your screen" (Default assistant settings). Off means no frames, ever.
  - Never banking or money apps (the assistant's `ScreenGuard` and the background screen's blocklist), never a protected
    (FLAG_SECURE) window: nothing is sent and Live is told the screen is private. When it cannot tell which app is on
    screen, nothing is sent. The sheet and the bubble show an eye while Live can see, a crossed eye while it is private.
  - Pictures are deleted as soon as they are shrunk.
- One tool, `hand_to_agent {request, context, include_screen?}`. The words on screen go with a hand-off; the picture only
  when `include_screen` is true.
- Long sessions: `sessionResumption` and `contextWindowCompression` (slidingWindow) are on in the setup. The latest
  resumption handle is kept; on `goAway` or a dropped socket the session reconnects with it, speech said in the gap is
  kept and sent in order, and the bubble stays LIVE.

## A live session is a call (the contract with the computer)
A live session is recorded exactly like a voice call, so the chat shows ONE collapsed call block with the hand-offs
and the transcript folded inside. It is never a user bubble.

### Hand-off (phone to computer): `voice.callHandoff`
The same RPC a voice call already uses. Existing arguments: `target` ("main"), `message`, `newChat`. A live hand-off adds:

| field | type | meaning |
|---|---|---|
| `origin` | string | always `"live_voice"` for a live session |
| `request` | string | Live's own wording of the job (a paraphrase) |
| `spoken` | string | what the user actually said just before it ("" when nothing was said yet) |
| `include_screen` | boolean | whether a picture of the screen is attached |
| `images` | `[{mime:"image/jpeg", data:<base64>}]` | only when `include_screen` is true |

`message` is the text for the agent: the request, a `[Live voice hand-off: ...]` note saying it is Live's wording plus the
user's own words in quotes, and the usual `[Assistant sheet ...]` note with the screen's words. A computer that ignores the
new fields still gets everything the agent needs in `message`. The computer should record the message with
`via: {from: "call"}` as it does for calls, so it is drawn as part of the call, not as something the user typed. A
computer without `voice.callHandoff` (old) falls back to `chat.send`.

On this phone's own agent (no computer) a hand-off is `runtime.send(text, images, fromCall = request)`: an "On it: ..."
activity row, no user bubble.

### Call record (phone to computer): `voice.callSave`
The existing record `{startedAt, durationMs, lines:[{who:"you"|"agent", text, at}]}` with two additions:

| field | value |
|---|---|
| `kind` | `"live_assistant"` (an ordinary call has none) |
| `source` | `"assistant_sheet"` |
| `handoffs` | `[{at, request, spoken, include_screen}]`, in order |

The computer stores it as its usual "call" row (keeping `kind` and `handoffs` in the row's meta) and draws the same
collapsed call block as for any call: title "Live voice · 3 min · 12 messages · 2 hand-offs", one-line summary "Handed to
your agent: <last request>", and inside, the transcript with each hand-off as "Sent to your agent" with the user's words
as a quote.

### On the phone
- A call row from the computer (`kind` "call") is drawn by `CallLogRow`; with `meta.kind == "live_assistant"` it reads
  `meta.handoffs`.
- This phone's own chat writes the session as call rows (`meta.call = true`, `meta.live = true`; a hand-off row also has
  `meta.handoff = true`, `meta.spoken`, `meta.include_screen`) which `CallRow` folds into one block.
- Both use `ui/screens/LiveCallParts.kt`.

## Code
`assist/`: `LiveHost` (owns the session), `LiveAssistant` (session, hand-offs, call record), `LiveAssistCore` (state, prompt,
tool, record), `LiveFrames` + `FrameLogic` (frames, gate, throttle, diff), `AssistScreenNow` (looks), `LiveBubble*`,
`LiveService` (microphone and media foreground service), `LiveToken`. `voice/`: `LiveSession` (the socket, resumption),
`SendGate`, `Earcon`, `LiveTiming`.


## Patient turn-taking, wait-for-me and answers read as written (0.15.64)

Sources: Google Live API capabilities, session management, tools and API reference
(https://aistudio.google.com/docs/live-api/capabilities, https://aistudio.google.com/docs/live-api/session-management,
https://aistudio.google.com/docs/live-api/tools, https://aistudio.google.com/api-reference/live).

**Setup values (every Live session except dictation)**

| Field | Default | Why |
|---|---|---|
| `realtimeInputConfig.automaticActivityDetection.endOfSpeechSensitivity` | `END_SENSITIVITY_LOW` | the server waits longer before it decides the turn is over |
| `...startOfSpeechSensitivity` | `START_SENSITIVITY_LOW` | a cough or a noise does not start a turn |
| `...silenceDurationMs` | 1400 (Quick 800, Very patient 2400) | Google's server default is about 800, so a 1.1 s pause mid-sentence ended his turn |
| `...prefixPaddingMs` | 200 (Quick 100, Very 250) | |
| Wait for me | off | `automaticActivityDetection.disabled: true`; the phone sends `activityStart` on Tap to talk and `activityEnd` on I'm done, and no sound in between |
| `contextWindowCompression` | `slidingWindow` and `triggerTokens` 100000 | a long call is trimmed instead of ending at about 15 minutes |
| `sessionResumption` | handle kept, used on goAway and drops | unchanged |
| `inputAudioTranscription.customVocabulary` | the agent's name, the user's name, the names of agents at work (up to 60) | names are transcribed right |
| `speechConfig.languageCode` | `en-US`; "Follow my language" leaves it out | |

Settings: Settings > Default assistant > How Live takes turns (Quick, Patient, Very patient; Wait for me; Follow my language). They apply to the next Live session.

If the server refuses a tuned field (close code 1007 before setup completes) the session retries once with the server's defaults, then without resumption fields.

**Nothing is sent to the model while he talks or while it is mid-answer.** `TurnHold` keeps announcements and [Context update] notes until he has been quiet for 1.8 s and the model has finished (Google: a clientContent message interrupts current generation; on gemini-3.8-live a note sent mid-speech derailed the answer).

**Answers are read as written.** A finished answer reaches Live as `[Agent result, speak as written] ... [End of agent result]` (markdown removed, up to 4000 characters, then "(The rest is in the chat.)"). Live's instruction says to read it as written and never summarise a summary. The hand-off to the agent says its reply is read aloud, so it writes the spoken version.
