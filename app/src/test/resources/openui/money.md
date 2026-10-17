You have **R105.30** available, with an Uber pre-auth of R259.94 still pending.

```openui-lang
root = Card([header, avail, hold, txnHead, txns, follow])
header = CardHeader("Money available", "Capitec Main · Sat 17 Oct 06:30")
avail = TextContent("**R105.30** available to spend", "large-heavy")
hold = TextCallout("warning", "Uber pre-auth holding funds", "R259.94 pending since 06:26 today — not yet settled.")
txnHead = InlineHeader("Recent transactions", "Last 3")
txns = EntityList([r1, r2, r3], "default", th, null)
th = {left: "Transaction", right: "Amount"}
r1 = {left: "Uber pre-auth · 17 Oct 06:26 (pending)", right: "-R259.94", rightVariant: "number"}
r2 = {left: "Checkers Umhlanga · 16 Oct 19:12", right: "-R87.45", rightVariant: "number"}
r3 = {left: "Transfer from Nkululeko · 16 Oct 08:03", right: "+R300.00", rightVariant: "number"}
follow = FollowUpBlock([f1, f2])
f1 = FollowUpItem("Why is my balance so low?")
f2 = FollowUpItem("Show all pending transactions")
```