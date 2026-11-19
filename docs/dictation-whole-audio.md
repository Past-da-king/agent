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
Send never waits. The 0.15.74 blocking wait (`settleDictation`, the Send spinner, "nothing was sent") is removed. The wire is
the computer's FINAL contract (agent-desktop-app docs/dictation-whole-audio.md, 19 Nov 2026, desktop 0.2.137):

1. Send while a dictation is still being written down (or KEPT, or DONE without a whole-take text): the mic stops,
   `DictationEngine.handOff()` runs no pass on the phone, and the whole take is written to disk as a take
   (`filesDir/takes/<recording id>.json` + `.pcm`, `voice/Takes.kt`) BEFORE anything is sent.
2. `chat.sendOnce({sendId, sentAt, fresh, text: <caption>, attachments, chat?, replyIn?, dictation: {id, pending: true, caption}})`.
   The row appears at once with `meta.dictation = {id, pending: true}`: "Refining". The agent's turn does not start.
3. In parallel, `voice.takeAudio({id, audio: {"$b64": <whole take PCM16 16 kHz>}, chat?, caption, names, languages})`, answered
   `{ok: true, state, duplicate?}`. Idempotent by id. The phone retries 2 s, 5 s, 10 s, then keeps the take; a link that is down
   keeps it queued, and every app start and every link-up resumes the queue.
4. The computer runs ONE pass, replaces the row in place (`meta.dictation = {id, pending: false, final: true}`) and then starts the
   agent's turn. On failure or timeout the agent gets the caption plus "dictated; clean transcript failed, this is the live
   caption" and the row becomes `{id, pending: false, failed: true}`: the phone draws "Sent as heard" and Retry.
5. Retry: `voice.takeAudio({id, retry: true})` (no audio).

No computer linked, own Gemini key on the phone: the same queue runs the ONE pass on the phone (`SpeechModel.transcribe`, whole file),
edits its own message in place and only then starts the agent's turn (cap 30 s + 0.5 s per second said, max 100 s, then the caption
plus the note). Takes older than a day are tidied; the audio goes as soon as a take is handed over or done.

Model: setting `dictation_model` (Settings extra), default `gemini-3.1-flash-lite` (the computer's default). A `*transcribe*` model
uses the Interactions API; any other reads the file with the prompt, and if it fails the speech model gets the same audio.
