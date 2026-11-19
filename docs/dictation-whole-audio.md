# Dictation: one pass over the whole recording (phone 0.15.75)

The text that is sent is ONE Gemini pass over the WHOLE audio of the take. The Android SpeechRecognizer caption is a
preview only.

## What was sent before (0.15.74)
- `DictationEngine.polishFromTape()` cut the tape at quiet moments into pieces of at most 120 s and sent each piece as
  its own request (a 188 s take = 2 requests), then joined the texts. Missed caption stretches were also written down
  piece by piece beside it.
- Raw PCM16 16 kHz per piece; the phone wrapped it in a 44 byte WAV header (own key) or sent it as `$b64` PCM over the
  link (`voice.transcribe`, one argument), where the computer wrapped it and split anything over 3 minutes again.
- Model: `gemini-3.5-transcribe` (Interactions API, `custom_vocabulary`, `language_codes`), fallbacks
  `gemini-3.6-flash`, `gemini-3.5-flash`, `gemini-2.5-flash` with a one-line prompt.
- Names: linked = the computer's memory names; unlinked = only the user's and the agent's name.

## Now
1. `polishFromTape()` sends the whole tape in ONE request, two tries; only if both fail does it log
   `the whole-take pass failed twice, falling back to pieces` and write pieces (the old path). Missed caption
   stretches are no longer written down separately at stop: the whole pass settles them.
2. `SpeechModel.transcribe`: whole WAV inline up to 12 MB (about 6 min); larger takes are uploaded once to the Gemini
   Files API (resumable) and referenced by `uri`: still one request.
3. Names (`DictationNames`): the user's own always-spell list (Settings extra `dictation_always_spell`), stems several
   project names share (from data: "Strat Edge" from "Strat Edge ID" and "Strat Edge Platform"), the computer's project
   names and other names (kept for @-mentions, works offline), then the user's and the agent's name. Max 100.
   `custom_vocabulary` on the transcribe model, in the prompt for a general model.
4. Languages: unchanged (device locales + country neighbours, max 6).
5. Live caption: biased to the first 30 names (`EXTRA_BIASING_STRINGS`, Android 13+); `NameFix` puts a sound-alike of
   a multi-word name back in the PREVIEW only (never inside talk about the mistake itself, never in a sentence that
   already has the name, never in the whole-take text).
6. Model is a setting: Settings extra `dictation_model`, default `gemini-3.5-transcribe`.

## RPC contract with the computer (`voice.transcribe`), additive
    voice.transcribe([ {"$b64": <whole take PCM16 16 kHz mono, base64>},
                       {"whole": true, "names": ["Strat Edge", ...], "languages": ["en-ZA", "zu-ZA", ...]} ])
- `whole: true`: the audio is the entire take: ONE pass, no 2 minute splitting; merge `names` in front of the computer's
  own memory names; use `languages` if it has no better list.
- Reply unchanged: `{ok: true, text}` or `{ok: false, error}`. Without the second argument the call is a piece, as before.

## Instant send (phone 0.15.76; the design change of 19 Nov 21:30)
Send never waits. The 0.15.74 blocking wait (`settleDictation`, the Send spinner, "nothing was sent") is removed.

Phone, when Send is pressed while a dictation is still being written down (or KEPT, or DONE without a whole-take text):
1. The mic stops, the engine hands off (`DictationEngine.handOff()`: no pass on the phone, tape sealed) and the whole take
   is written to disk as a take (`filesDir/takes/<recording id>.json` + `.pcm`, `voice/Takes.kt`) BEFORE anything is sent.
2. The message goes at once with the live caption. Wire (additive, old computers ignore the extra field):
   `chat.sendOnce({sendId, sentAt, fresh, text: <caption>, attachments, chat?, replyIn?, dictation: {id: <recording id>}})`.
3. In parallel the take is handed over: `dictation.take({id, chat?, live: <caption>, audio: {"$b64": <whole take PCM16 16 kHz mono>}, names: [...], languages: [...]})`
   answered `{ok: true, accepted: true, duplicate?: bool}` (`PendingTakes.accept`). Idempotent by id; the phone retries
   2 s, 5 s, 10 s, then shows Retry; if the link is down the take stays queued and carries on when it is back and on every
   app start (`Takes.start`).
4. The computer runs ONE pass over the whole audio, replaces the message in place (`replace`) and releases the agent's turn
   (`settled(id)`, cap 30 s + 0.5 s per second said, max 100 s; then the caption plus the note
   "dictated; clean transcript failed, this is the live caption"). It must hold the turn for a message that carries
   `dictation.id` even if the take has not arrived yet (message and upload run side by side, either may land first).
5. What the phone reads back, for the "Refining" marker and Retry: the message row's `meta.dictation = {id, state}` with
   state `refining` | `done` | `failed` (the phone sets `refining` in its own rows). Without it the phone falls back to its own
   take queue (marker while its words equal the caption, gone after the take's cap). Retry on a failed message calls
   `dictation.retry(id)` (`PendingTakes.retry`) and answers `{ok: true}`.
6. `dictation.model()` answers `{model: "<id>"}`, the computer's speech model; the phone stores it and uses it when it
   writes a take itself (own setting `dictation_model` wins, then the computer's, then the default).

No computer linked, own Gemini key on the phone: the same queue runs the ONE pass on the phone
(`SpeechModel.transcribe`, whole file), edits its own message in place (`meta.dictation.state = done`) and only then is
the agent's turn started; a pass that fails or runs past the cap gives the agent the caption plus the note.
Takes older than a day and their audio are tidied; the audio goes as soon as a take is handed over or done.
