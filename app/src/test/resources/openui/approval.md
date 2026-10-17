Here is the drafted invoice email, waiting on your go-ahead.

```openui-lang
root = Stack([card])
card = Card([header, details, actions])
header = CardHeader("Invoice to Shey", "Drafted, not sent")
details = EntityList([rowTo, rowSubject, rowBody, rowAttachment, rowAmount])
rowTo = {left: "To", right: "Shey (Bo-Smart)"}
rowSubject = {left: "Subject", right: "Invoice Submission"}
rowBody = {left: "Body", right: "Hi Shey, please find attached my invoice for this month. Thanks, Ayanda"}
rowAttachment = {left: "Attachment", right: "GF-13 invoice 07.pdf"}
rowAmount = {left: "Amount", right: "R20 000"}
actions = Buttons([sendBtn, editBtn, holdBtn], "column")
sendBtn = Button("Send", @ToAssistant("Send the invoice email to Shey", {action: "send", to: "Shey (Bo-Smart)", subject: "Invoice Submission", attachment: "GF-13 invoice 07.pdf", amount: "R20 000"}), "primary")
editBtn = Button("Edit", @ToAssistant("Change the invoice email before sending", {action: "edit", to: "Shey (Bo-Smart)", subject: "Invoice Submission"}), "secondary")
holdBtn = Button("Not now", @ToAssistant("Hold the invoice email, do not send", {action: "hold", to: "Shey (Bo-Smart)", subject: "Invoice Submission"}), "tertiary")
```