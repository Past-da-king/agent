You have R1,240.50 available in your MyBank main account, with a pending Bolt pre-auth of R184.20 holding funds.

```openui-lang
root = Stack([card])
card = Card([header, bal, sep, txHeader, tx, note])
header = CardHeader("MyBank main account", "Sat 17 Oct 2026, 07:20")
bal = EntityList([avail, hold, pending], "default", {left: "Item", right: "Amount"})
avail = {left: "Available balance", right: "R1,240.50", rightVariant: "number"}
hold = {left: "Bolt pre-auth hold", right: "-R184.20", rightVariant: "number"}
pending = {left: "Pending", right: "Yes", rightVariant: "text"}
sep = Separator("horizontal", true)
txHeader = InlineHeader("Last 3 transactions")
tx = EntityList([t1, t2, t3], "default", {left: "Date", right: "Amount"})
t1 = {left: "17 Oct 07:15 Bolt pre-auth", right: "-R184.20", rightVariant: "number"}
t2 = {left: "16 Oct 19:12 Spar Northcliff", right: "-R312.60", rightVariant: "number"}
t3 = {left: "16 Oct 08:03 Transfer Lerato", right: "+R500.00", rightVariant: "number"}
note = TextCallout("info", "Bolt hold", "The R184.20 pre-authorisation is pending and still holding money. It may drop off or settle once the ride finalises.")
```