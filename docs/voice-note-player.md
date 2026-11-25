# Chat voice notes

The chat voice-note bubble reads the local audio file's duration with MediaMetadataRetriever on an IO dispatcher, then prepares MediaPlayer asynchronously. Until duration is available it says Loading audio rather than showing a false zero. Before first playback it shows total duration. After playback or a seek it shows elapsed / total in tabular figures.

The waveform is the seek control. Tapping seeks; dragging previews the destination and commits on release while playback continues. Cancelled gestures leave playback alone. Coordinates are clamped and mirrored for RTL. TalkBack has a progress range and setProgress action. Both the play button and waveform have a minimum 48 dp touch height.

Each bubble owns its player and releases it when disposed. A process-wide coordinator pauses the previous bubble before another starts. Pausing keeps position. Seeking before asynchronous preparation finishes is applied when prepared.

Playback requests transient-may-duck media focus with speech content. Other media apps can duck for the note. Permanent loss pauses without resuming; transient loss remembers whether playback was active and resumes on gain. A manual pause or another bubble starting cancels that resume intent. Duckable loss lowers this player's volume. Completion, manual pause, disposal and decoder errors abandon focus. Abandoned Android focus listeners are fenced against queued callbacks.

Tests are in NotePlaybackTest and NotePlayerShotsTest / NotePlayer360ShotsTest. Screenshot artifacts are written outside the repository under VOICE_PLAYER_PROOF_DIR, or proof-voice-note-player in the system temporary folder by default. Release proof and any limits are recorded in that proof folder's README.
