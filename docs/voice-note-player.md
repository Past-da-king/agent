# Chat voice notes

The chat voice-note bubble reads the local audio file's duration with MediaMetadataRetriever on an IO dispatcher, then prepares MediaPlayer asynchronously. Until duration is available it says Loading audio rather than showing a false zero. Before first playback it shows total duration. After playback or a seek it shows elapsed / total in tabular figures.

The waveform is the seek control. Tapping seeks; dragging previews the destination and commits on release while playback continues. Cancelled gestures leave playback alone. Coordinates are clamped and mirrored for RTL. TalkBack has a progress range and setProgress action. Both the play button and waveform have a minimum 48 dp touch height.

Each bubble owns its player and releases it when disposed. A process-wide coordinator pauses the previous bubble before another starts. Pausing keeps position. Seeking before asynchronous preparation finishes is applied when prepared.

Playback requests transient-may-duck media focus with speech content. Other media apps can duck for the note. Permanent loss pauses without resuming; transient loss remembers whether playback was active and resumes on gain. A manual pause or another bubble starting cancels that resume intent. Duckable loss lowers this player's volume. Completion, manual pause, disposal and decoder errors abandon focus. Abandoned Android focus listeners are fenced against queued callbacks.

Tests are in NotePlaybackTest and NotePlayerShotsTest / NotePlayer360ShotsTest. Screenshot artifacts are written outside the repository under VOICE_PLAYER_PROOF_DIR, or proof-voice-note-player in the system temporary folder by default. Release proof and any limits are recorded in that proof folder's README.

## Shared playback and lifecycle

Autoplay and visible bubbles use the same Graph.voiceNotes entry for a chat item; they never create separate Tts and bubble players. Legacy Tts playback and notes stop one another. stopSpeaking also stops notes and pending preparation, so sending, dictation and the assist sheet cannot record a playing note. MainActivity stops notes on ON_STOP; background/screen-off playback is deliberately paused rather than offered without a media notification.

A detached bubble releases its native player and audio focus while its position stays in the shared holder. Rotation uses that holder too. At most64 inactive entries are cached; visible, playing and preparing entries are protected. File metadata and existence are remembered outside the100ms position read.

The noisy-route broadcast pauses playback immediately on earphone disconnection. Focus refusal is a retryable focus state, not a decoder failure, so retry keeps position. Seeks are serialized: one native seek runs while later gestures replace its target; an older completion cannot overwrite the latest target. Unknown player duration preserves positive file metadata and seeking is disabled while duration is unknown.

The explicit product requirement keeps our own volume at20% on LOSS_TRANSIENT_CAN_DUCK, restoring it on gain, even though pausing speech is another common media policy. Permanent and transient non-duckable losses still pause as described above.

Waveforms accept horizontal drags; vertical drags remain chat scrolling. A duration or layout-direction change cancels a preview. Actions reuse translated Play/Pause and voice-note names in every existing locale. Spoken durations use Android ICU CLDR plural rules and translated units; progress wording is a translatable resource. No English-only loading or unit text is built in Kotlin.
