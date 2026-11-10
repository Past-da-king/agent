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
