# Agent on the wrist, v2 (mocks, 12 Nov 2026)

Design only. Nothing installed on any watch or phone. Every image is rendered from real Compose code on branch `feat/wear-v2-design` (`wear/src/main/java/com/past9/phoneaos/wear/v2/`), so the mock is the build.

## The idea in five lines
1. The agent is its own mascot (the one picked in settings, with the two pill eyes) drawn as a glowing glass jewel: depth, rim light, a breath of colour behind it. Black stays black, so OLED switches off around it.
2. One object, five moods: breathing idle, listening (rings move with your voice), thinking (a comet of dots orbits it), speaking (a ring of bars follows its voice), needs-you (it turns amber).
3. Iris is action, amber means only "this needs you", same as the phone. Same corner steps, same eyebrow labels, curved to the bezel on a round watch.
4. Edge-aware: labels bend with the bezel, rows narrow with the glass, the crown shows as a slim rail. Round, square and tall rectangular all use one layout.
5. Motion is the product: the agent glides between home, listening, thinking and answering. Nothing is a stock list, nothing is a centred pop-up (choices rise as sheets).

## What is in the folder
Per size folder (`round-227`, `round-192`, `square-192`, `rect-180x220`), same 33 names in each:

| No | Screen |
|---|---|
| 01, 02 | Home idle, home with an approval waiting (agent turns amber) |
| 03, 04 | Listening (live words), thinking (status shimmer) |
| 05, 06 | Answer while it speaks, answer scrolled with the crown |
| 07, 08, 09 | Approval: ready, holding (ring fills), approved. Hold to approve, X to deny, side button for details |
| 10 | A question with choices |
| 11, 12, 13 | Call: live (orb follows voice), muted on earbuds, audio route slide-up sheet |
| 14, 15, 16 | Incoming ring: wake-up, the agent calling, urgent approval |
| 17, 18, 19 | Gestures list, action picker (slide-up), "Not on this watch" state |
| 20, 21 | Tile: tap to talk, today at a glance |
| 22 | Complications: tap to talk, approvals, tasks range, next task |
| 23 to 27 | Launcher: agent as home, with approvals, app grid, grid scrolled by crown, recent apps |
| 28 to 32 | Choose and switch back: Watch home setting, choose sheet, switch-back entry, confirm sheet, done |
| 33 | The five mascots (the watch follows the user's choice) |

`faces/` (450 x 450 px): three watch faces, each active and ambient. `clips/` (mp4 and gif, 15 fps): `talk-flow` (home, listening, thinking, answer), `approve-hold`, `ring`, `call`, `gesture-picker-rise`, `launcher-to-grid`, `face-orb-to-ambient`, `face-still-to-ambient`. `00-overview-*.png` are contact sheets.

## Watch face options
- A, Still: a huge thin clock, the small agent underneath asleep-ready, one line for the next task. Calmest, lowest battery.
- B, Orb: the agent is the dial. Clock on top, a light running the bezel for seconds, an amber badge on its shoulder when something needs you. Tap it to talk.
- C, Data: clock, small agent in the middle, tasks ring and approvals ring either side, next task along the bottom.
- Ambient (all): outline only, grey, no fills, agent as a line drawing with small eyes, so burn-in is safe and few pixels light.

## Notes
- Names and the mascot come from settings; "Nova" and the demo texts are placeholders. App icons in the grid are stand-ins: the real grid shows each app's own icon.
- Launcher mode is optional and never forced. Whether a watch lets the user pick another home app is still being checked by the engine helper; if a watch refuses, the Agent stays a normal app and the choose sheet explains it in plain words.
- Watch face build: Watch Face Format is XML, so the agent is baked as images per mood with Transform animation for the breath and blink, and shapes and gradients for the rest. The ambient look is a separate variant. The faces here are drawn in Compose to show the final picture, not as WFF yet.
- Not shown as motion: the crown scroll feel, haptics, and the real mic and speaker.
