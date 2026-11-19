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

## Not in this PR (design change of 19 Nov 21:30, instant send)
Send waits for the whole-take text in 0.15.74 (spinner, Retry). The new design (send at once with the live caption marked
Refining, upload the audio in the background, the computer runs the one pass, replaces the message in place and holds the
agent's turn, durable queue keyed by recording id) needs a computer side that does not exist yet. Proposed contract:
- phone: `chat.send` with `meta.dictation = {id: <recording id>, pending: true, caption: <live text>}` at once;
  then `voice.takeAudio({id, $b64, whole: true, names, languages})` in the background (retried from an on-disk queue
  until acknowledged, idempotent on `id`).
- computer: holds the agent's turn for that message until the pass is done or `30 s + 0.5 s per second of audio` (max 100 s)
  has passed, runs the one pass, edits the row in place (`meta.dictation.pending=false, final=true`) or on failure
  appends the note "dictated; clean transcript failed, this is the live caption" and sets `meta.dictation.failed=true`
  so the phone shows Retry; `voice.takeAudio` again with the same `id` retries.
