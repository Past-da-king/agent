# Automation from other apps (MacroDroid, Tasker)

Your Agent app has a small web server inside it. A macro in MacroDroid, a task in Tasker, or any other app on the
phone can call it to fire one of your routines, or to send the agent an instruction and read the answer back.

## The one limit

It answers only on the phone itself, at 127.0.0.1, and only while the app is running.

Nothing outside the phone can reach it. Not your computer, not your Wi-Fi, not the internet.

If you force stop the app, or Android kills it, the address stops answering until you open the app again. A macro that
runs while the app is closed gets a connection error. That is the honest behaviour of this first version: there is no
background service holding the port open.

## Turning it on

1. Open the app, then Settings.
2. Find "Automation from other apps" and tap it.
3. The sheet shows the address and the token. Copy the token with the Copy token button.
4. Turn on "Allow outside runs". While that switch is off, every call is refused.

The address is normally http://127.0.0.1:47321. If another app already holds that port, the app moves to the next free
one and the sheet shows the address it is really using.

The token is made once per install. "New token" replaces it. Every macro that still holds the old token stops working
straight away, so rotate it if it has been seen by someone else, then paste the new one into your macros.

## MacroDroid

### Fire a routine

1. In your macro, add an action.
2. Choose "HTTP Request".
3. Method: POST
4. URL: http://127.0.0.1:47321/v1/routines/run
5. Content type: application/json
6. Add a header.
   - Name: Authorization
   - Value: Bearer YOURTOKEN
7. Body, as text:

   {"name":"Morning brief"}

   The name must match a routine in the app, exactly or close enough to be the only one. You can send {"id":12} instead
   if you know the routine's id. An optional "context" string is passed to the agent as a note about why it is running.

The reply comes back at once and looks like this:

   {"runId":"run-1","status":"running"}

The routine is now running in the background. The runId is how you collect the answer.

### Read the result

Add a later action to the same macro, after a short wait:

1. Action: HTTP Request
2. Method: GET
3. URL: http://127.0.0.1:47321/v1/runs/RUNID

Replace RUNID with the id from the first reply. To use it directly, MacroDroid lets you put the previous action's
response into the URL as a variable, so the address becomes .../v1/runs/{http_response} or a JSON path to runId.

The reply:

   {"runId":"run-1","status":"done","result":"Slept 6 hours 40. Three meetings today.","needsApproval":false,"durationMs":18420}

status is running, done or failed. result is the full text the agent produced, not a shortened preview. needsApproval is
true when the run stopped because it wanted your approval for something.

### Send an instruction

Same shape, different route:

1. Action: HTTP Request
2. Method: POST
3. URL: http://127.0.0.1:47321/v1/instruction
4. Header: Authorization, value Bearer YOURTOKEN
5. Body, as text:

   {"text":"What is on my plate today?"}

The reply is a run id again:

   {"runId":"instruction-2","status":"running"}

Then poll GET http://127.0.0.1:47321/v1/instructions/instruction-2 for the reply. Instructions take longer than
routines because a real conversation runs: expect several seconds, sometimes a minute.

## Tasker

### Fire a routine

1. Add an action, choose Net, then HTTP Request.
2. Method: POST
3. URL: http://127.0.0.1:47321/v1/routines/run
4. Headers: Authorization:Bearer YOURTOKEN
5. Body: {"name":"Morning brief"}
6. Content Type: application/json
7. Tick "Structure Output" if you want the JSON split into fields, so %http_data.runId is usable in the next action.

Read the result with a second HTTP Request action, GET http://127.0.0.1:47321/v1/runs/%http_data.runId, and read the
answer from %http_data or from %http_data.result with Structure Output on.

### Send an instruction

The same action, POST http://127.0.0.1:47321/v1/instruction, body {"text":"Summarise my unread email"}. Poll
GET http://127.0.0.1:47321/v1/instructions/<id> for the reply.

## Endpoints

| Method | Path | Body | Comes back |
| --- | --- | --- | --- |
| GET | /v1/health | none | {"ok":true,"app":"Agent","server":"automation","allowed":true} |
| GET | /v1/routines | none | every routine: id, name, enabled, schedule, lastResult |
| POST | /v1/routines/run | {"name":"..."} or {"id":12}, optional "context" | {"runId":"run-1","status":"running"} |
| GET | /v1/runs/{id} | none | runId, status, result, needsApproval, durationMs |
| POST | /v1/instruction | {"text":"..."} | {"runId":"instruction-1","status":"running"} |
| GET | /v1/instructions/{id} | none | runId, status, result, needsApproval, durationMs |

Every route needs the header Authorization: Bearer YOURTOKEN. Without it, or with a wrong token, the answer is 401 and
nothing runs. With the switch off, the answer is 403 and nothing runs. A body over 64 KB is refused with 413. An
unknown route answers 404 and lists the routes it does know.

## What is refused

Anything that would send, spend or delete needs your approval, and nobody is there to give it while a macro runs. So
the agent stops instead of guessing, and the run's result says so in a line you can match on:

   Needs the user's approval and nobody is around. Not done.

In the JSON that is the text of "result", and "needsApproval" is true. A macro can branch on either: if the reply
contains "nobody is around", the thing it wanted did not happen.

Reading is fine: checking mail, looking at calendar, searching memory, reading a page, all of that runs with nobody
watching. Firing an agent that sends an email or deletes a file does not, whatever a macro asks for.

## Security

- Loopback only. The server binds 127.0.0.1, so no other device can connect, even on the same Wi-Fi.
- One token per install. It lives in the app's encrypted settings and is only shown in Settings.
- Rotate it if it leaks or if you pasted it somewhere you no longer trust. Old macros stop working at once.
- The token is a password. Anyone who can run an app on this phone and read that token can fire your routines.
- Turn the switch off when you are not using it. Nothing answers then.

## By intent, instead of HTTP

Any app can also reach the agent with a broadcast. It uses the same token and the same "Allow outside runs" switch,
and it works when the Agent app is closed, because Android starts the app for it.

- Package: `com.past9.phoneaos`. Required. Android does not deliver a broadcast to a closed app without it.
- Fire a routine: action `com.past9.phoneaos.RUN_ROUTINE`, extras `token`, `routine` (its name or id) and an optional `note`.
- Send an instruction: action `com.past9.phoneaos.INSTRUCT`, extras `token` and `text`.

In MacroDroid use the action Send Intent with Target set to Broadcast. In Tasker use Send Intent with Target set to
Broadcast Receiver. The sheet in Settings has a recipe to copy with your token filled in.

A routine is found by its id, by its exact name, or by a part of the name that only one routine has. When two routines
match, nothing runs.

A sender that uses an ordered broadcast gets the same JSON the HTTP endpoint returns as the result data, with the
status as the result code: 200, 400, 401, 403 or 404. To read a run's full result, poll `/v1/runs/{id}` over HTTP.

If you force stop the Agent app, Android holds back broadcasts to it until you open it once.

## The other direction: the agent talks to your apps

The agent can send any app on the phone an Android intent with its `android_intent` tool, and see what is installed
with `android_apps`. The README section "Connect other apps" has the fields, the Google Maps example and the rule for
what asks first. MacroDroid and Tasker macros are a preset on top of that, described next.

## The agent runs your macros

The agent can run a MacroDroid or Tasker macro with its `macrodroid_run` tool. It sends an Android broadcast on this
phone, and nothing leaves the phone.

What the broadcast carries:

- Action: `com.past9.phoneaos.MACRO`
- `macro`: the macro's name, for example `Wifi off`
- `v1`, `v2`, `v3`: short values, or empty
- `params`: a JSON object as text, `{}` when unused
- `from`: `agent`

Every extra is always there and is always text. That matters because MacroDroid's Intent Received trigger fires only
when every extra it was set up with is present.

### Set up the macro

1. New macro. Add the trigger **Intent Received**.
2. Action: `com.past9.phoneaos.MACRO`
3. Add an extra. Name: `macro`, in small letters. Value: `Wifi off`, with nothing after it, not even a space.
   Optional: `v1` = `*` saved to a variable, and the same for `v2`, `v3` and `params`.
4. Add the actions you want.
5. In the Agent app, open Settings, Automation, *Agent runs your macros*, and add `Wifi off` under Allowed macros.

### If the macro does not run

MacroDroid gives no error when a trigger does not match. Measured against MacroDroid 6.1.5:

- The extra's name is case-sensitive. `Macro` does not match `macro`. The agent also sends the name as `Macro` and
  `MACRO`, because phone keyboards capitalise the first letter.
- The value must match in full. `demo ` with a space after it does not match `demo`. Capital letters in the value do
  not matter. `demo*` matches too.
- MacroDroid listens only while it is switched on and running. On Samsung phones, set its battery use to Unrestricted
  and keep it out of Sleeping apps.
- To find out which part is wrong, remove the extra and test with the action alone.

### What asks first

- A macro on the Allowed macros list runs straight away.
- Any other macro shows an approval card with the macro and its values. If nobody answers, as in a routine running at
  night, it is not sent and the agent is told why.
- The tool answers `sent` when the broadcast went out. MacroDroid cannot reply, so `sent` means it was delivered, not
  that the macro did what you wanted.

### From your computer

The agent on a linked computer can call the same tool on the phone through the device link. The same allowlist
applies, and the question shows on the phone. Switch it off in Settings, Linked devices: *MacroDroid macros*.

### The webhook, optional

Off by default. When it is on, the agent may send `via: webhook` to `https://trigger.macrodroid.com/<device id>/<macro>`,
where the macro part is the name in lower case with dashes (`wifi-off`), with v1, v2, v3, params and from as query
values. This goes through MacroDroid's server, not only your phone. Use it only when the agent is not running on the
phone that runs MacroDroid.
