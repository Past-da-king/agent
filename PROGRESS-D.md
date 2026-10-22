# PROGRESS D: the phone links to a computer (Remote mode)

Workstream D of "One agent across all devices". Contract: `docs/devices-protocol.md` on the desktop repo's `devices`
branch, sections 2, 3, 5, 5.2, 8, 9 (revised 01:45: native screens, no WebView) and 11 (shared device tools).
Dev home: this checkout, server `~/phone-aos/app-remote`, branch `feature/remote`, base 0.13.0 (3811400).

## Done
- Merge step. 0.13.0 (3811400) already holds 0.12.12 and the uncommitted 0.11.23 edits; the non-git app-thin copies
  (PC and server, identical) are older. Backups: `backup/app-0.11.23-dirty`, `backup/app-thin-snapshot`. The stray
  Slack fix is kept on `fix/slack-subtext-on-0130` and not merged (0.13.0 base only, owner's ruling 01:45).
  Full unit suite on the base: 516 of 518 pass; the 2 OpenUiButtonsAndPromptTest failures have failed in every
  full run since 17 Oct.
- Remote mode, 0.14.0 (code in `app/src/main/java/com/past9/phoneaos/remote/`):
  - Onboarding: third way "Use the agent on my computer". Settings: Linked devices.
  - Pairing: QR by camera (CameraX + zxing core), `agent://pair` links (the phone's own camera app, adb), or a
    typed address + short code. Pinned TLS (fingerprint from the QR); short code trusts on first use and shows the
    fingerprint words. Pass + brains in Keystore-encrypted prefs. No AI keys used while linked.
  - The phone's own screens show the brain's main chat, general memory, goals, tasks and routines, mirrored over
    `/v1/rpc` into `linked.db` (the phone's own `agent.db` is never touched). Sends, answers, approvals, stop,
    task/memory/routine edits and Run now go to the brain.
  - Brain choice + failover per s8, strip "Answered by <brain>" / "No agents available" + Retry.
  - Device commands open_url, send_file, show_image, notify, acked. s11: the phone lends browser, readable
    notifications, files, questions/approvals, notify_user; switches per group; shell never lent.
  - Socket only while on screen or while a reply is running (then the existing foreground service holds it).
  - Work handed to a project (a project chat on the computer) shows in the chat as a helper row with the project's
    icon (its picture, emoji or first letter on its colour) and the project chat's live step; Stop stops that chat.
  - Pictures in chat rows (screenshots, photos, image attachments) are fetched once through /v1/file with the pass
    and drawn by the existing chat. Lent ask_user / request_approval show as a notification with buttons AND as the
    app's own question card; either answer goes back as device.result. Lent screenshots return as base64 parts.
- Proof:
  - `RemoteLinkTest` (25): parsing, addresses, failover choice, real pinned TLS (right cert accepted, wrong refused,
    TOFU records it), the RPC wire format over a real WebSocket, row ids, QR decoding from camera frames.
    Mutation check: breaking the pin or the between-turns rule turns the right tests red.
  - On Android 13 (the server's redroid, installed as `com.past9.phoneaos.linktest`, the existing app untouched)
    against `tools/fake-node/fake-node.mjs` (two brains, loopback only, reached through `adb reverse`):
    short-code link, QR link, wrong-certificate QR refused, chat both ways, file/link/notify/tool commands,
    failover Desk to Server in ~2 s, No agents in ~2 s, Retry, automatic return between turns in 15 s, task tick
    written back, off switch shows the phone's own data, unlink, socket closed 30 s after leaving.
    Later pass (01:00-01:15): the project row live and done with the project icon, a picture from the computer in
    the chat, a lent question answered in the app (device.result "The user answered: Yes").
    Screenshots: `~/phone-aos/proof-0140/` on the server.
  - Fixed after the emulator caught it: opening the app cold from a link code froze the main thread (the QR camera
    was torn down while CameraX was still starting, and the teardown waited for it). Now it never waits.
  - Full suite: 543 tests, 541 pass; the 2 failures are OpenUiButtonsAndPromptTest (bookViewingAsksTheAgentAboutThatFlat,
    openListingOpensTheLinkAndSendsNothing), failing in every full run since 17 Oct. No new failures.
- APK size: 85.8 MB (0.13.0: 86.2 MB). Thin: no runtimes inside; the size is unshrunk dex (~70 MB, minify is off on
  this line) plus ML Kit OCR (11 MB). R8 shrinking would likely bring it to 30-60 MB but needs keep rules and a full
  re-test, so it is left for a separate change.

## Not proven / open
- Against the real desktop or headless node: its /v1/pair, /v1/devices and /v1/rpc were not built when this was
  tested. Node side must accept the short code with or without the dash (the phone sends `ABCDEFGH`).
- Camera scanning on a real phone (redroid has no camera); the decoder itself is tested with real QR images.
- Not installed on the owner's phone (not asked to).

## Next
- Run against the real node once A's pairing + RPC land; adjust field names if theirs differ.
- A delegated project agent shown like a helper with the project's icon (s9): the phone shows whatever helper rows
  the brain writes into the main chat; the icon needs the brain to put it in the row's meta.

## Last commit
See `git log -1` on `feature/remote`.
