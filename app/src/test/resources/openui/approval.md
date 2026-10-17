Here is the drafted invoice email, waiting on your go-ahead.

```openui-lang
root = Stack([card])
card = Card([header, details, actions])
header = CardHeader("Invoice to Lerato", "Drafted, not sent")
details = EntityList([rowTo, rowSubject, rowBody, rowAttachment, rowAmount])
rowTo = {left: "To", right: "Lerato (Acme)"}
rowSubject = {left: "Subject", right: "March invoice"}
rowBody = {left: "Body", right: "Hi Lerato, please find attached my invoice for this month. Thanks, Sam"}
rowAttachment = {left: "Attachment", right: "INV-0042.pdf"}
rowAmount = {left: "Amount", right: "R4 500"}
actions = Buttons([sendBtn, editBtn, holdBtn], "column")
sendBtn = Button("Send", @ToAssistant("Send the invoice email to Lerato", {action: "send", to: "Lerato (Acme)", subject: "March invoice", attachment: "INV-0042.pdf", amount: "R4 500"}), "primary")
editBtn = Button("Edit", @ToAssistant("Change the invoice email before sending", {action: "edit", to: "Lerato (Acme)", subject: "March invoice"}), "secondary")
holdBtn = Button("Not now", @ToAssistant("Hold the invoice email, do not send", {action: "hold", to: "Lerato (Acme)", subject: "March invoice"}), "tertiary")
```