Invoice email is drafted and waiting on your OK.

```openui-lang
root = Card([header, summary, note, actions], src)
header = CardHeader("Invoice for Shey", "Draft — not sent yet")
summary = EntityList([rowTo, rowSubject, rowAttach, rowAmount], "default", hdr)
hdr = { left: "Field", right: "Value" }
rowTo = { left: "To", right: "Shey (Bo-Smart)" }
rowSubject = { left: "Subject", right: "Invoice Submission" }
rowAttach = { left: "Attachment", right: "GF-13 invoice 07.pdf" }
rowAmount = { left: "Amount", right: "R20 000", rightVariant: "number" }
note = TextContent("Hi Shey, please find attached my invoice for this month. Thanks, Ayanda", "small")
actions = Buttons([sendBtn, editBtn, notNowBtn], "row")
sendBtn = Button("Send", @ToAssistant("Send the invoice email to Shey"), "primary")
editBtn = Button("Edit", @ToAssistant("Let me change the invoice email before sending"), "secondary")
notNowBtn = Button("Not now", @ToAssistant("Hold the invoice email for now"), "tertiary")
src = []
```

Tap **Send** and I'll fire it off to Shey.