You have R105.30 available in your Capitec main account, with a pending Uber pre-auth of R259.94 holding funds.

```openui-lang
root = Stack([card])
card = Card([header, bal, sep, txHeader, tx, note])
header = CardHeader("Capitec main account", "Sat 17 Oct 2026, 06:30")
bal = EntityList([avail, hold, pending], "default", {left: "Item", right: "Amount"})
avail = {left: "Available balance", right: "R105.30", rightVariant: "number"}
hold = {left: "Uber pre-auth hold", right: "-R259.94", rightVariant: "number"}
pending = {left: "Pending", right: "Yes", rightVariant: "text"}
sep = Separator("horizontal", true)
txHeader = InlineHeader("Last 3 transactions")
tx = EntityList([t1, t2, t3], "default", {left: "Date", right: "Amount"})
t1 = {left: "17 Oct 06:26 Uber pre-auth", right: "-R259.94", rightVariant: "number"}
t2 = {left: "16 Oct 19:12 Checkers Umhlanga", right: "-R87.45", rightVariant: "number"}
t3 = {left: "16 Oct 08:03 Transfer Nkululeko", right: "+R300.00", rightVariant: "number"}
note = TextCallout("info", "Uber hold", "The R259.94 pre-authorisation is pending and still holding money. It may drop off or settle once the ride finalises.")
```