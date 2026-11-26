# Preferred brain recovery

Candidate reviewed on 25 November 2026. Companion desktop/node release 0.2.146.
The release owner sets the combined phone version separately.

Tied answer priorities use pairing time, then device id. Unknown pairing times sort after known times. Older links remain readable. A tied order previously sorted by name, so an upgrade can change the selected brain once, between turns.

Both main-chat entry points, project messages, device events and the between-turn loop can recheck a preferred brain before starting new work. A ready health check never clears the 30-second cooldown after a message was not acknowledged. Selection and send reservation share a short lock; delivery waits happen outside it, so one waiting chat cannot block another.

Turns, sends, lent commands through result delivery, and RPCs including pending device.ask approvals and voice.noteAudio keep their origin socket. Command events reserve their busy count before their coroutine is dispatched. A command or RPC beginning during a health probe is counted again at the final selection decision.

On mobile data with an open stand-in socket, recovery probes check only higher-ranked brains and run at most once per minute. They do not run the background all-brain status sweep.

Connections and Linked devices use translatable resource strings and the actual active-turn state. Optional node status is cached for a minute, including transient failures; unsupported methods are remembered per brain for the session. Current nodes expose drive warnings only in the answering text, so the compatibility matcher accepts drive/free-space wording without requiring the word disk.

Proof is recorded in the PR comment after the quiet window. PreferredBrainSessionTest drives the real send/sendAccepted/project/RPC/event paths over loopback HTTP and WebSockets; RemoteLinkTest covers cooldown and tie rules, and AnswerOrderStatusTest covers resources and status compatibility.

## Shared in-flight API for other PRs

File: app/src/main/java/com/past9/phoneaos/remote/InFlight.kt
Class: com.past9.phoneaos.remote.InFlight

RemoteSession exposes val inFlight: InFlight. All sends, RPC calls and dispatched commands use this same counter. DeviceCommands keeps its job lease until its result has been delivered.

- val count: Int and val isBusy: Boolean
- fun acquire(): InFlight.Lease
- suspend fun <T> during(block: suspend () -> T): T
- fun <T> guarded(block: () -> T): T
- Lease implements AutoCloseable: close() is idempotent; release(): Boolean returns true only on the first release.

For a suspend operation: session.inFlight.during { downloadOrAsk() }.
For a dispatched coroutine: acquire before dispatch, then close the lease in invokeOnCompletion, so cancellation before the coroutine starts also releases it.
Use guarded only for short non-suspending selection/reservation decisions. Its lock is shared with acquire/release, preventing new work from starting halfway through a socket replacement.
