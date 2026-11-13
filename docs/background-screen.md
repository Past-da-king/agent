# The background screen

The agent uses real apps on a hidden display while the person keeps their own screen. There are no ready-made
actions for any app: the agent reads the screen and presses, like a person would, and what worked becomes a
learned app it can run again in one step.

Code: `app/src/main/java/com/past9/phoneaos/screen/`, tools in `tools/ScreenTools.kt` and `tools/ScreenSkillTools.kt`,
sheets in `ui/screens/Screen*.kt`, the blocked-app list in `app/src/main/assets/screen-blocklist.txt`.

## Why it needs a helper

Android only lets the phone's own shell (or the system) put other apps on a virtual display, touch them there and
set that display's keyboard policy. A normal app cannot (AOSP `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`,
`DisplayManagerService.createVirtualDisplayInternal`). So the app ships a small program, the **screen helper**
(`screen/helper/HelperMain.kt`), that runs as the shell user (uid 2000) and owns the hidden display.

- It is started from the app's own APK: `CLASSPATH=<apk> app_process /system/bin com.past9.phoneaos.screen.helper.HelperMain <package>`.
- Who can start it: **Shizuku**, when the person has it and allowed this app (the app does it by itself), or a
  **computer over adb**: `adb shell sh /sdcard/Android/data/<package>/files/screen-helper.sh` (Settings > Background
  screen shows and copies the exact line).
- It listens on a local socket (`agent-screen.<package>`, abstract namespace) and answers only this app's uid, the
  shell and root. The app in turn only trusts a peer whose uid is the shell or root. Nothing leaves the phone.
- It stops when the phone restarts and must be started again. With nobody asking for ten minutes it closes the
  hidden display by itself.
- It never touches display 0, the person's own screen.

## What the helper does

| Request | What happens |
|---|---|
| `create` | A virtual display the phone's shape, 720 wide. Flags are tried best first: trusted, own display group, own focus, never steals the top focus (Android 14+); then plainer sets. The keyboard policy is set to hide, so a field on the hidden display never raises the keyboard on the person's screen. |
| `start` | `am start --display <id>` for a named app (`-n package/class` or `-p package`). A bare link with no app named is refused. |
| `frame` | The latest picture as a JPEG. |
| `tree` | Everything readable or pressable on that display, through Android's UI automation link (what `uiautomator dump --windows` uses), with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` so the person's own accessibility services keep running. The app declares no accessibility service. |
| `tap`, `swipe`, `key`, `touch` | Input events carrying the hidden display's id. |
| `text`, `act` | Set a field's text, press its own Send/Search, click, scroll. |
| `tasks`, `move`, `remove`, `release` | Where apps are, moving one between the person's screen and the hidden one, closing. |

## Rules that are in code, not in the prompt

1. **Banking and money apps are never used.** `assets/screen-blocklist.txt` lists them by package, by package
   pattern, by words in the package, by words in the app's name, plus words that mark a money screen. The helper
   reads the list from the APK itself and enforces it below every tool: it will not start or move such an app, it
   gives no picture, no element list and no touch while one is on the hidden display, and it closes one within
   400 ms if it lands there another way (a link, a button in another app). A screen that hides itself from being
   seen (FLAG_SECURE) and talks about balances, cards or PINs is treated the same. The app checks the same list
   before it even asks the helper. People can add apps in Settings; nothing can remove a built-in rule, and no agent
   tool can change the list. If the list cannot be read, the helper opens nothing at all.
   Android exposes no finance category for apps, so there is no category check.
2. **A press that sends, pays, deletes or posts asks first**, through the app's normal approval card, with what was
   typed. So does submitting typed text, and a press on a bare point with nothing readable under it. Per app the
   person can choose Allow, Ask (default) or Never (`screen/ScreenModel.kt`, `ScreenRules`).
3. **Stop and Take over always win.** Stop halts every screen tool at once and only the person can lift it. While
   they have taken over, the agent waits.
4. **An app that is open on the person's own screen is only moved with their yes**, and goes back when the hidden
   display closes.
5. **Nothing is left running.** `screen_close` closes the apps on the hidden display; an idle one is closed after
   three minutes by the app and after ten by the helper.

## Tools

Only generic ones: `screen_open_app`, `screen_look`, `screen_tap`, `screen_type`, `screen_swipe`, `screen_key`,
`screen_wait`, `screen_close`, `screen_status`. They are lent to a linked computer in the device tool group
`screen`, with the same approvals.

## Learned apps for apps on the phone

The loop: try, record, save, replay.

- Every run is written down step by step (`ScreenRuns`): what was opened, pressed, typed, asked, with a picture
  after each step. Each step keeps how to find its element again: its id in the app, its words, its kind and its
  place among its like (`ScreenSkills.selector`).
- `screen_skill_save` turns the run into a tool of a learned app, with the changing parts (a query, a recipient)
  as named inputs. It is stored in the same learned-app store as learned web apps (`connections/Learned.kt`, kind
  `screen`), shows in Connections > Learned apps, and is callable as `app_<app>_<tool>` by the agent, its helpers
  and a linked computer. `screen_skill_list`, `screen_skill_run` and `screen_skill_undo` manage them.
- A replay starts the app afresh, finds every element on the live screen, and still asks before a send, a payment
  or a delete. When an element is not in the list it looks for its words in the picture (on-device text
  recognition) before giving up.
- A replay that works tightens the tool: steps that were not needed are dropped and each step's wait is fitted to
  how long it really took. A replay that stops says which step and why, leaves the app open there, and the agent
  carries on by hand; saving again under the same name repairs the tool. Up to five earlier versions are kept, so a
  bad repair can be undone.
- The person can teach one too: Settings > Background screen > Recordings and learned apps > Show it a job. They do
  it once by hand on the live picture, and it is saved in the same format.

## play_media: music and video play here, never on the user's screen (0.15.65)

`play_media` is the only way the agent plays something. It never opens an app on the user's own screen (display 0)
unless the app is already in front there. Order, in `media/MediaTools.kt`:

1. A saved play job for the app (learned app of the app, tool `play`, `app_<app>_play(query)`), when there is one:
   replayed on the background screen, then checked with the media session (notification listener). Seconds.
2. The app's own media session (`playFromSearch`), or its media browser service when the app is closed. Opens nothing.
3. No saved job, or the replay broke: `MediaScreenLearner` works it out on the background screen once. It opens the
   app there (a cold start, through the helper, so `ScreenLanding` moves anything that lands on display 0 and puts
   the user's front app back), finds the search box (or presses a search button / tab), types the words, presses
   the row that carries most of them (`MediaScreenPlan`, which reads words, hints and ids, never an app name), and
   a Play button when the row only opened a page. It then watches the media session until it plays what was asked.
   Audio from the virtual display plays. A broken replay is learned again once, never looped.
4. Video: the YouTube watch link is opened in the video app on the background screen (`screenLink`), never by an
   intent; when YouTube cannot be read the app's own search is used as in 3.
5. An app already in front on the user's own screen is asked in place (a play-from-search intent to the app that is
   already showing: it stays in front, so the user's screen is the same before and after). The background screen
   cannot take it from under them: `ScreenSession.open` refuses, and so does the helper.

If the background screen is off and the app has no session, `play_media` fails with that reason; it does not fall back
to opening the app.

**Saving the job.** Saving a learned tool normally shows an approval card. The play job is saved without one
(`ScreenLearned.save(autoApprove = true)`) only when it is read-only (playback: it never sends, pays or deletes; a
press on such a button still asks through the usual gate), the run just proved it by playing what was asked, and the
run took one clean path (no wrong rows pressed first). The job is visible and removable in Connections > Learned apps.
A result row is saved as "the row at this place" (id, kind, position) and not by the words it had that day
(`generic` taps in `ScreenSkills.fromRun`), so the same steps play any query.

**Nothing else plays.** `android_intent` refuses a play request (`MEDIA_PLAY_FROM_SEARCH`, a VIEW of a YouTube, Spotify
or other media link, audio or video type) and the phone's `open_url` refuses media links, both with the reason and
the way to `play_media` (`MediaIntentGuard`). The Live voice and call prompts say `play_media` is the only way and that a
failure is reported, not worked around.
## Recordings

Kept on the phone only, in `files/screen-runs/<id>/` (`run.json` and one JPEG per step). Password fields and
anything typed as private are painted over before a picture is kept, and their text is never stored. Capped at
150 MB or 40 runs, oldest cleared first; each can be deleted, or all at once. They can be switched off.

## Proving it

- Unit tests: `ScreenTest.kt` (blocked-app matcher, rules, the session against a made-up phone, learned apps,
  recordings) and `ScreenSheetsShotTest.kt` (every sheet at 390 and 360, light and dark).
- The helper can be driven from a computer without the app, which is how it is checked on an emulator or a phone:
  `adb shell CLASSPATH=<apk> app_process /system/bin com.past9.phoneaos.screen.helper.HelperMain <package> --call '{"op":"ping"}'`.

## Credits

The approach follows scrcpy (Apache-2.0) and Agent-VScreen (MIT). See `THIRD-PARTY-NOTICES.md`.

### An app that opens in a state that hides its search (0.15.66)

An app often comes back to where it was left: a full-screen or mini player, a dialog, a stale page. The learner never assumes the front door.
Before it looks for a search box it calls `MediaScreenLearner.reachSearch()`, generic steps only, all on the background screen:

1. If a search box or a search button shows, nothing is touched.
2. Back, up to 3 times, looking after each press (an empty screen means Back left the app, so it stops pressing).
3. One swipe down (folds an expanded player).
4. The app is started fresh with a clean task (`ScreenSession.open(fresh = true)`), which forgets where it was left.
5. Still no search: it fails and says what was tried.

The first open of a learning run is also a fresh start. Display 0 is never touched.

A media session that is stopped or paused ON THE RIGHT TRACK (title, artist or album carry the words asked for) is sent transport Play once before any screen route.
If that starts something that is not what was asked for, it is paused again. A saved play job that was replayed says so in the answer, with the time it took.
The length shown by `media_control now_playing` is read from the metadata and left out when it is shorter than the position (some apps publish a [vault:vercel-recovery-past952.token] length while the real track plays); the right track is judged by title, artist and album, never by length.

A session in error is never asked (0.15.68): an app asked to recover from an error can open its own window on the user's screen, so the background screen is used instead.

A session in error is never asked (0.15.68): an app asked to recover from an error can open its own window on the user's screen, so the background screen is used instead.
