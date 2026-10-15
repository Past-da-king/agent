# Agent: PayPal for your phone agent

Entry for the PayPal AI Hackathon on Devpost (submissions close Thu 12 Nov 2026, 12:00 PT). Targets: Best Use of Agentic Commerce, Best Use of PayPal + AI.

The pitch: you run a small business from your phone. You say "invoice Thabo $25 for the logo work, due Friday", "did Sipho pay?", "refund the last order". The agent does the PayPal work and stops at one tap for anything that moves money.

## Architecture

PayPal is a built-in tool group of the agent, the same way Machines and the browser are. No extra server.

```
voice / chat / routine
        |
   agent loop (API-key mode)  or  Claude Code / Codex on the phone (subscription mode, via the local MCP server)
        |
   tools()  ->  paypal_* tools  (app/src/main/java/com/past9/phoneaos/paypal/PayPalTools.kt)
        |            |
        |      money tool?  -> GET the real object from PayPal -> APPROVAL card (Approve / Decline) -> POST
        |
   PayPalApi (PayPalApi.kt): OAuth client-credentials token (cached, 401 retry), PayPal-Request-Id on every write,
                             errors turned into one readable line
        |
   api-m.sandbox.paypal.com
```

- Keys: the user's own PayPal REST app (client ID + secret), pasted under Connections, PayPal. Checked with a token call before saving. Stored in the app's EncryptedSharedPreferences (Android Keystore). Sandbox only in this build: live mode is not offered.
- The tools only exist once PayPal is connected, and the system prompt gets one line about them.
- Both power modes get the same tools: API-key mode calls them directly; subscription mode (Claude Code or Codex on the phone) reaches them through the app's local MCP server, so the approval rule holds there too.
- A small on-phone ledger (`files/paypal/ledger.json`) records what this phone made (invoices, pay links, captures, refunds), so "the last order" resolves without PayPal's transaction search, which lags by hours.

## Tools

Names follow PayPal's official Agent Toolkit (`@paypal/agent-toolkit` 1.11: create_invoice, send_invoice, get_invoice, list_invoices, cancel_sent_invoice, create_order, get_order, pay_order, create_refund, list_transactions), with a `paypal_` prefix.

| Tool | Toolkit equivalent | Money action (approval card) |
|---|---|---|
| paypal_create_invoice | create_invoice | No: draft only |
| paypal_send_invoice | send_invoice | Yes |
| paypal_get_invoice | get_invoice | No |
| paypal_list_invoices | list_invoices | No |
| paypal_cancel_invoice | cancel_sent_invoice | Yes |
| paypal_create_order | create_order | No: makes a pay link, nothing moves until the buyer pays |
| paypal_get_order | get_order | No |
| paypal_capture_order | pay_order | Yes |
| paypal_refund | create_refund | Yes |
| paypal_list_transactions | list_transactions | No |
| paypal_recent | (app's own ledger) | No |

Why native tools and not the toolkit itself: the app's own agent loop is Kotlin, and the toolkit's tools run a payment the moment the model calls them. Here the approval is enforced in the tool, in code, so no model, prompt or helper can skip it. Keeping the toolkit's names and shapes means the model meets what PayPal documents.

## The approval rule

1. A money tool first GETs the invoice, order or capture from PayPal.
2. It builds the card from PayPal's data: who, what, amount, due date, original payment for refunds, and the line "PayPal sandbox: test money".
3. `ctx.ask("APPROVAL|...")` shows the existing approval card in chat, on the home screen and in a live call.
4. Approve runs the POST with a `PayPal-Request-Id`, so a retry never pays twice. Decline returns "The user declined. Not done."
5. Nobody around (a routine at 03:00) returns "Not done": background runs can never move money.
6. Helpers can't approve for the user: APPROVAL is a locked kind in EscalationGate, so the main agent can only forward it to the user.
7. In a live voice call the agent reads the card out, but only holding Approve on screen approves it. Voice can decline, never approve (CallController).

Enforced by `PayPalToolsTest.everyMoneyToolStopsAtACardBeforeAnyWrite`, which runs every tool in `PAYPAL_MONEY_TOOLS` against a fake PayPal, declines, and fails if any write went out.

## Proof

- `app/src/test/java/com/past9/phoneaos/PayPalToolsTest.kt`: offline, PayPal faked at the HTTP edge. Request shapes, approval cards, decline and no-answer paths, 401 retry, rand refusal, "the agent only has PayPal once the user added it".
- `app/src/test/java/com/past9/phoneaos/PayPalSandboxLiveTest.kt`: the real sandbox through the same tools. Runs only with `PAYPAL_CLIENT_ID` and `PAYPAL_SECRET` set. Writes the pay link to `PAYPAL_LINK_FILE` and waits `PAYPAL_WAIT_SEC` for the sandbox buyer to pay, then collects, part-refunds, refunds the rest, and lists. Log in `app/build/live/paypal-sandbox.txt`.

```
PAYPAL_CLIENT_ID=... PAYPAL_SECRET=... PAYPAL_BUYER=sb-...@personal.example.com PAYPAL_LINK_FILE=/tmp/pp-link PAYPAL_WAIT_SEC=600 \
  ./gradlew :app:testDebugUnitTest --tests 'com.past9.phoneaos.PayPalSandboxLiveTest'
```

## Secrets

- No key, secret or password is ever in the repo. The app keeps the PayPal client ID and secret in EncryptedSharedPreferences; the live test reads `PAYPAL_CLIENT_ID` and `PAYPAL_SECRET` from the environment.
- The live test log masks pay-link tokens (`checkoutnow?token=<order id>`) before it's written, because secret scanners match them.
- Guard: `git config core.hooksPath tools/hooks` turns on a gitleaks pre-commit scan (config `.gitleaks.toml`), and the Secret scan workflow runs gitleaks on every pull request.

## Sandbox notes

- PayPal has no rand. The tools refuse ZAR with a way forward (use USD and say the conversion).
- The sandbox app needs Invoicing and Transaction search switched on under its Features in the developer dashboard, or those calls return 403 NOT_AUTHORIZED (the tools say exactly that). Both were switched on for "Agent Hackathon" on 15 Oct 2026 and the live test is green end to end.
- A feature switched on later only reaches a NEW access token, and PayPal keeps returning the cached one (about 9 hours). PayPalApi handles it: on 403 NOT_AUTHORIZED it ends the token at /v1/oauth2/token/terminate, gets a fresh one and retries once.
- PayPal's transaction search runs hours behind, so a fresh capture or refund won't show in paypal_list_transactions straight away. paypal_recent and the order or invoice itself are instant.
- A ZA sandbox business can't take card payments directly (PAYEE_NOT_ENABLED_FOR_CARD_PROCESSING), so the buyer pays through the PayPal pay link. That is also the honest demo.

## Demo script (under 3 minutes, real phone, screen recording)

1. 0:00 Hook, one line over the home screen: "My phone runs my side business. Watch."
2. 0:10 Voice: "Invoice Thabo 25 dollars for the logo work, due Friday." Agent drafts it, then the approval card slides up: Thabo, Logo work, USD 25.00, due date, "sandbox: test money". Tap Approve. Pay link comes back.
3. 0:45 "Make a 30 dollar pay link for Sipho's braai pack." Link appears. Cut to a second phone or browser: Sipho pays with the sandbox buyer.
4. 1:15 "Did Sipho pay?" Agent checks the order: APPROVED. "Collect it." Card: Collect USD 30.00 from Sipho. Approve. Captured.
5. 1:45 "Refund him 10, the meat was late." Card shows original USD 30.00 and refund USD 10.00. Decline once on camera (nothing happens), then ask again and approve.
6. 2:15 "Who still owes me?" Agent lists unpaid invoices.
7. 2:35 Close: the code that enforces the card (PAYPAL_MONEY_TOOLS and the test), the repo link, MIT.

## Plan to 12 Nov

| By | What |
|---|---|
| Thu 15 Oct | DONE: Invoicing and Transaction search on; live test green for invoices, orders, capture, refunds and transactions. |
| Sun 18 Oct | Device build (assembleRelease with the runtime pack on My PC), install on the phone, connect PayPal sandbox in Connections, run the demo script once unrecorded. |
| Wed 21 Oct | Fix whatever the dry run shows (card wording, how the agent phrases amounts and conversions). |
| Sun 25 Oct | Record the demo on the real phone, two takes. Edit to under 3 minutes. Upload to YouTube unlisted. |
| Wed 28 Oct | Devpost write-up: what it does, how it's built, the approval rule, AI tools used, what was built in the window. Screenshots. |
| Sun 1 Nov | Merge to main, public release with PayPal, repo README section. |
| Thu 5 Nov | Submit on Devpost (a week of slack before the deadline). |

## Open items

- Live mode: deliberately off. If it ever ships, live needs its own switch, a spending cap and the card in red.
- Webhooks (invoice paid, payment captured) would let a routine tell the user "Thabo just paid" without polling. Needs a public endpoint, so it's out of scope for the phone-only build; a routine that checks paypal_list_invoices every 30 minutes does the same job.
