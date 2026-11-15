# Assistant context: the assistant starts knowing what the agents are doing

Asked 12 Nov 2026. The assist sheet (the default-assistant sheet, `assist/AssistActivity.kt`) and Live voice (`assist/LiveAssistant.kt`) used to start blank: they did not know what the other chats and agents were doing, so they asked for things the user had already told another agent (an agent waiting for a pairing code, and the assistant did not know). Now both open with the current context and send what the user says to whoever is waiting for it.

Nothing here is tuned to one person: no names, models, tools or agents are named in code. Everything comes from what the user's own agent already holds.

## What goes in

| Part | What | Cap |
|---|---|---|
| Waiting on the user | every open question and approval from the main agent, helpers and project agents, each with a short ref (A1, A2), who asks, the text, the options | 5, 260 chars each |
| Agents | the main agent when it is working, project agents, helpers: working, waiting on the user, or finished in the last 20 minutes, with what each is doing and what it waits on | 8, 140 chars each |
| The last of the main chat | his own words and the agent's replies (not other chats, not call rows) | 6 turns, 180 chars each |
| Recent notifications | what the agent notified in the last 6 hours (not questions) | 4, 160 chars each |

The whole text (`AssistContext.prompt`) never passes 3,200 characters. Over the cap it drops, in order: old notifications, old chat turns, agents beyond the first three, then the oldest asks. The asks go last.

## Where it comes from, in each mode (`assist/AssistContextSource.kt`)

- **Phone's own agent (local mode):** `g.db.chat()` rows (the main chat, helper rows, question and approval rows), `g.runtime.status` (working and its label). Helpers count only when `HelperLive.isLive` says they are at work.
- **Remote mode (a PC or a server node is the brain):** everything is already on the phone over the existing link, so no new RPC and **no change to the desktop app**:
  - `RemoteSession.db` chat rows: the mirror holds the main chat's tail and every open question or approval, from helpers and project chats too (`RemoteSession` keeps open cards of other chats).
  - `RemoteSession.cards` (the computer's `helpers.cards`): helpers and project agents, with state, line, `waiting`.
  - `RemoteSession.status`: the main agent working and its label.
- **Notifications:** `NotifyLog` (`SharedPreferences notify_log`), written by `AndroidPhone.notify` for every note the agent sends that is not a question. 12 entries, 6 hours.

Privacy: only what the user's own agent already holds. Nothing is fetched from elsewhere and nothing leaves the phone except in the instructions of the live session the user started.

## When it is built and refreshed

- **Live voice:** read when the session starts (`LiveAssistant.start`, at most 700 ms, never holding the microphone: the microphone and speaker open after the read, which is one local database read) and put into the system instruction (`LiveAssistCore.systemPrompt(..., context)`). While the session is open `LiveHost` follows `AssistContextSource.updates`; a change goes to `LiveAssistCore.contextChanged`, which tells the voice, once, about: a new question or approval, one answered somewhere else ("do not ask again"), an agent that finished or now waits. A question the voice answered itself is not announced as "answered elsewhere". What the instruction already said is never told again.
- **Typed or listening sheet:** `AssistActivity` collects the same updates and draws the strip.

## Routing

Two paths, one decision (`assist/AssistRouter.kt`, free of Android):

1. **Live voice** has a tool, `answer_ask(ref, answer, user_said)`. The voice names the question by its ref when sure. `AssistRouter.forTool` checks it:
   - a ref that matches a question: the answer goes to it (a question with options takes the matching option);
   - a code or number must be in what the user actually said (`user_said`): the voice cannot make one up, it is refused if not;
   - no ref and one question: that one; no ref and several: the router reads the words, else the choice sheet;
   - an approval is never answered this way (see below).
2. **The typed sheet** has no model, so `AssistRouter.fromWords` reads the final words:
   - one of a question's own options said in a few words ("blue please", "yes" for Yes/No);
   - a code (digits, spoken digits, a letters-and-digits token) goes to the one question that asks for a code (code, pin, otp, pairing, token, verification);
   - "the answer is ..." or "reply: ..." goes to the free-text question, narrowed by a name he used ("tell the report helper ...");
   - anything else: `None`, the words go to the agent as before.

Where it goes: `g.runtime.answer(ask.id, answer)`, the same path the question card uses (this phone's agent, or in remote mode `chat.answer` on the computer), so the agent that is waiting gets it, helper or project chat included.

**Approvals are never answered by words.** They stay hold-to-approve on the card (rule of the app). The context tells the voice an approval waits and that the approve button is on the screen.

**Aiming a job at a helper or a project chat (linked only):** `hand_to_agent` has an optional `target` (a project, chat or helper by the name he said). It goes through `voice.callHandoff` with that target, the same model as a voice call's `start_work`. If two names fit (`ambiguous`), the voice asks out loud which one and sends again. A targeted job is not tracked through the main chat: its outcome reaches the voice as a context update.

## UI states

- **Strip** (`ContextStrip`, under the agent's name in the typed sheet while listening, typing or showing an answer, and in the Live sheet): one quiet line. One waiting question: "Watch helper: What is the pairing code...". Several: "3 things are waiting on you". Nothing waiting: "2 agents are working". Tap opens up to four lines. Draws nothing when nothing is going on.
- **Routed confirmation:** one line with a check, "Sent to Watch helper: 482917". In the typed sheet it is the answer text (`AssistCore.routed`, not read out); in Live it is `LiveView.routed` for 5 seconds, and the voice confirms in one short line.
- **Choice sheet** (`AskChoiceSheet`, the app's `ChoiceSheet`): when two or more waiting questions could take the words. A slide-up sheet, his words in its line, one big row per question (who and what). Picking sends it. Closing without picking: the typed sheet sends his words to the agent as before; Live tells the voice to ask out loud.
- No pending asks: the sheets look and behave exactly as before.

## Tests

`AssistContextTest` (context in both modes with fakes, size cap, routing a pairing code to the waiting agent, ambiguous, none pending, approvals refused, made-up codes refused, mid-session changes), `AssistContextShotsTest` (strip, routed line and choice sheet at 390 and 360 dp, light and dark).

## Live voice always knows (0.15.63, 13 Nov 2026)

0.15.61 and 0.15.62 shipped the context but Live voice still answered "nothing is running" and "I cannot see previous conversations". Causes and fixes:

- **The start read was dropped after 700 ms.** `LiveAssistant.start` waited 700 ms for the snapshot and opened the session with NO context when it was slower (a cold link, a large chat table). Now the snapshot is read in parallel with the token and the setup waits for it up to 3.5 s; the microphone never waits.
- **A cold link read the phone's own empty database.** In remote mode `RemoteHub.session` can still be null at the first moment, so the snapshot read the phone's local chat (empty). `AssistContextSource.snapshot` now waits for the link (3 s), then for the computer's cards (1.2 s), then falls back to `voice.callTargets` (who is at work on the computer) and otherwise marks the agents unknown.
- **Never silent.** An empty but known list says "Agents: none are working right now." Unknown says the voice could not read them and must not say none are running. A snapshot that failed entirely gives a block that says so. Agents now carry what they were started for and their two latest steps (this is how "waiting for the watch to come back on adb" reaches the voice).
- **No baseline, no delta.** When the session opened without context, later changes only told new questions and never listed the agents. Now every change to the context sends the whole current text as a quiet client turn (`LiveSession.note`: `clientContent` with `turnComplete` false) headed "[Context update]", debounced 1.2 s, at most every 4 s, retried until the line is ready. Spoken deltas (a new question, an agent finished) stay as before.
- **A fresh reconnect used a stale prompt.** `LiveSession.systemFrom` makes every setup (first and reconnect) build its instruction from the current context.
- **Calls.** `CallController` appends the same `AssistContextSource` block to its prompt.
- **Logged.** Tag `LiveAssist`: `context primed: agents=N known=B asks=N turns=N chars=N`, `context: ...` (trimmed to 700 chars) and `context update sent: ...`.
- The desktop token (`fieldMask: 'model'`) keeps the phone's instruction: proven by `voice.test.ts` and by the 12 Nov sessions, where the tools worked.

Tests: `LiveContextAlwaysTest` (fake socket: the setup carries agents, steps and asks; a reconnect's setup is built from now; a note is a no-answer client turn; known, unknown and failed context; the callTargets fallback; the cap keeps asks and agents first).
