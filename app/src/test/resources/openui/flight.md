Your flight GE372 leaves Cape Town at 07:10 today — boarding 06:40, group B.

```openui-lang
root = Card([header, flightInfo, sep, boarding, bagNote, buttons])

header = CardHeader("Flight today", "Friday, 16 Oct 2026")

flightInfo = EntityList([route, depart, arrive, status])
route = {left: "Flight", right: "GE372"}
depart = {left: "Cape Town (CPT)", right: "07:10"}
arrive = {left: "Durban (DUR)", right: "09:15"}
status = {left: "Status", right: "Checked in"}

sep = Separator("horizontal")

boarding = EntityList([boardingTime, group, seat, ref])
boardingTime = {left: "Boarding", right: "06:40"}
group = {left: "Group", right: "B"}
seat = {left: "Seat", right: "15E (middle)"}
ref = {left: "Booking ref", right: "385E655C"}

bagNote = TextContent("One checked bag · Boarding pass saved on phone")

buttons = Buttons([bp])
bp = Button("Show boarding pass", @ToAssistant("Show boarding pass"), "primary")
```