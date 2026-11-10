# Wake word

Say "Hey" and the name you gave your agent, and the assistant opens. It is **off by default** and marked **early** in
Settings > Wake word. Someone who never opens that sheet gets no service, no permission prompt and no listening.

The phrase is never built in. It is `"Hey " + the agent's name`, read from the app's settings every time it is needed
(rename the agent and the listener follows at once), or a phrase the person typed. The examples below use placeholder
names.

## The engine, and why

Hard requirements: fully on the phone, open source with a licence safe to ship (code and weights), no key, no account,
no third-party server, and **any phrase at run time** with no model trained per word.

| Engine | Code | Weights | Any phrase without training | Verdict |
|---|---|---|---|---|
| **sherpa-onnx keyword spotter** (k2-fsa) | Apache-2.0 | `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`, published as Apache-2.0 | Yes: keywords are text, split into word pieces | **Chosen** |
| openWakeWord | Apache-2.0 | Stock wake words are CC BY-NC-SA 4.0 (cannot ship). The shared feature models come from Google's Apache-2.0 speech embedding | No: one trained head per phrase | Out |
| microWakeWord | Apache-2.0 | Per-word models | No: trained per phrase with TensorFlow | Out |
| Vosk with a grammar | Apache-2.0 | Small English model about 40 MB, Apache-2.0 | Only words already in its dictionary; a new name is unknown | Out |
| Picovoice Porcupine | Proprietary | Needs an access key and an account | | Out |

sherpa-onnx 2.0.1 (26 Oct 2026, active, about 15,900 stars) ships an Android AAR. We use the build with onnxruntime
linked in (one `.so`, arm64 only), pulled from its GitHub release at build time (`settings.gradle.kts`).

**A note on the weights.** The model's publisher licenses the weights as Apache-2.0. It was trained on GigaSpeech,
whose audio is offered to researchers for non-commercial use. Whether training data terms reach a trained model is
not settled law. This app is free and open source; anyone selling a build should take their own advice.

**Size.** The engine library is 24.2 MB unpacked, 9.0 MB compressed; the model is 5.3 MB (int8). Native libraries are
now compressed inside the APK (`useLegacyPackaging`), which also shrinks the OCR library, so the measured growth of
the download should be under the 15 MB rule; the exact figure is on the release that carries this (see "Not proven").

## From a typed name to something the model can hear

The model is a very small streaming English speech recogniser whose search is held to the phrases it is given. It
knows 500 word pieces written in A to Z.

1. `WakePhrase.latin`: accents come off (José becomes JOSE), a few letters are spelled out, digits become words, and
   writing that is not Latin (Cyrillic, Greek, kana, Hangul, Arabic) is romanised with Android's own ICU
   transliterator (Android 10 and later).
2. `WordPieces`: the letters are split into the model's pieces on the phone, by our own implementation of the
   SentencePiece unigram split. It is checked against the real tokenizer on 956 phrases (`WakeCoreTest`).
3. **Say it three times** (`SpellingLearner`): the same model, with its search let loose, hears the person say the
   phrase and returns the pieces it heard ("hey tandy" for a name written Thandi). Those go beside the typed pieces.
   Nothing is trained and no audio is kept. This is what makes names from any language work.

### Which languages work

- **Typed only:** names that read naturally in English letters (Nova, Jarvis, Yuki). Names English would pronounce
  differently from how they are really said (many isiZulu, Arabic, Spanish names) are often missed when only typed.
- **After teaching by voice:** any name in any language, because the model stores how it sounds, not how it is spelled.
- **Not Latin script:** romanised on Android 10+, then as above. On Android 9 only teaching by voice works.
- The model has only heard English speech, so sounds English lacks (clicks, tones) are matched to the nearest English
  sound. That is usually enough to be consistent, but it is the weakest case.

## The listener

`WakeWordService` is a microphone foreground service with an ongoing notification (`Listening for "Hey <name>"`, a
Pause button). It does only what `ListenRules.decide` says:

- Starts from a user action (the switch) and whenever the app is opened; after a restart it tries, and if Android
  refuses, a notification says so and one tap starts it. Five seconds of pure zeros from the microphone (what Android
  gives a listener it has not let in) is reported as "the microphone is giving no sound", not hidden.
- Releases the microphone the moment the app's own voice features take it (`MicUse`: voice call, dictation, the
  phone's recogniser; a web page recording in the app's browser is seen through Android's recording callback), during
  phone calls, and while another app records. It listens again when they finish.
- Uses the phone's own microphone. It does not open a Bluetooth headset's call link, which would drop music quality.
- Holds a partial wake lock only while listening, so it works with the screen off.
- On a hit: a short tone, the microphone is freed, and `WakeTarget` opens the assistant (action
  `com.past9.phoneaos.action.ASSIST`, extra `source=wake_word`), or the app's voice screen if nothing answers that.
  Android only lets a background app open a screen when it may "Appear on top"; without that the person gets a
  heads-up notification to tap.

**Privacy.** Audio is held a tenth of a second at a time in memory, on the phone. Nothing is stored or sent.

## Battery

`SpeechGate` (loudness against the room's own floor, 20 ms frames) sits in front of the model, so the model only runs
while there is sound. On the dev set the gate was open 86 to 99% of the time in continuous speech, music and noise,
and 0% in silence (unit test). Settings: pause on battery saver (on by default), pause under 20%, only while charging.

**Not measured yet:** CPU percent and mAh per hour on a real phone.

## Accuracy so far (dev set, 10 Nov 2026)

Test audio is synthetic: Piper voices (LibriTTS-R 904 speakers, VCTK 109, L2-ARCTIC 24 non-native speakers and four
single voices), speeds 0.85 to 1.25, a third clean, the rest with real noise at 5 to 20 dB, some with room echo.
Negatives are LibriSpeech, People's Speech, GTZAN music and DEMAND noise. Scripts: `tools/wakeword-eval/`.
`WakeAccuracyRun` drives the app's own classes on the JVM.

| What | Result |
|---|---|
| "Hey Nova", typed, Balanced, app code with the gate, 80 clips | 47 of 80 heard (59%) |
| Same, model alone without the gate, thresholds 0.1 to 0.35 | 30% to 68% |
| False wake-ups, "Hey Nova", 65 minutes of speech, music and noise | 0 |
| Near misses ("Hey Noah", "Hey Nora", the name alone), 45 clips | 1 woke it |
| Clean speech, ten names typed only, 12 speakers each | English-spelled names 11 to 12 of 12; Thandi, Sipho, Khalid 0 of 12 |
| Same names after three taught examples, 20 new speakers each | 18 to 20 of 20 |

**This is below the 90% target in noise, which is why the sheet says "Early: may miss or mishear".** The full run
(nine names from five origins, three sensitivity steps, typed and taught, 7.7 hours of negatives) is built
(`eval/sets/test`) and has not been run. The three sensitivity steps are first guesses, not tuned.

## Not proven

- The full accuracy table, per name and per sensitivity.
- Anything on a real phone: real voices, the service with the screen off and in Doze, battery cost, opening over
  another app, a Bluetooth headset, Samsung's own battery rules.
- Emulator run with injected audio (`WakeProbe` in debug builds is written and unused).
- Teaching by voice with a real voice (only synthetic voices so far).
- The APK size change on a signed release.
