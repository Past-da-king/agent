# Agent for Android

The phone half of Agent. Your agent runs on your own computer, and this app is the window you carry: the same chats,
cards, approvals, reports and mini-apps, plus dictation, the camera and the files on your phone.

You bring the model. Your agent on the computer holds the AI key, the accounts and the memory, and the phone talks to
it over your own network. Nothing of yours goes through a service of ours, because there is no service of ours.

## What it does

- **The same conversation, wherever you are.** Ask from the phone, watch the work happen, approve what needs approving.
- **Files, pictures and video the agent shows you play in the chat**: a picture opens full screen, a video has a real
  player, and the bytes come from your computer a range at a time, so scrubbing works and nothing is copied to a server.
- **Dictation that keeps its words.** Each chat's message box keeps what you said on disk, and a recording list lets you
  copy it, insert it or transcribe it again.
- **More than one computer.** A message goes to the brain that answers; if the computer that should answer says nothing
  within a few seconds, the app moves on to the next one with the same message id, so the work still runs exactly once.
- **Cards, approvals and mini-apps** work on the phone the way they do on the desktop.

## What you need

- The Agent desktop app on a computer (Windows, macOS or Linux) with **Settings, Devices** turned on, or a server
  running the same agent, reachable from the phone.
- Android 8 or newer.
- Your own AI key, or a plan account, held on the computer.

## Install

1. Download the APK from the [latest release](https://github.com/Past-da-king/agent/releases/latest).
2. Allow installing from this source when Android asks.
3. Open Agent, then on the computer choose **Settings, Devices, Add a device** and scan the code.

## Build it yourself

```
./gradlew :app:assembleDebug
```

Release builds are signed from a properties file kept outside this repository (`-PsigningProps=<file>`); no key is ever
committed here.

## History

This repository started as an early version of the app and carries the work of its contributors. The line that has been
shipping since was developed privately and was merged into `main` in November 2026, keeping the earlier history and the
licence intact.

## Licence

MIT. See [LICENSE](LICENSE).
