Your flight leaves Cape Town at 07:10 today and lands in Durban at 09:15.

```openui-lang
root = Stack([flight])
flight = Card([CardHeader("GE372", "Cape Town to Durban, today"), EntityList(rows), Buttons([passBtn], "row")])
rows = [{left: "Departs", right: "07:10 CPT", rightVariant: "text"}, {left: "Arrives", right: "09:15 DUR", rightVariant: "text"}, {left: "Boarding", right: "06:40, Group B", rightVariant: "text"}, {left: "Seat", right: "15E", rightVariant: "text"}, {left: "Booking ref", right: "385E655C", rightVariant: "text"}, {left: "Baggage", right: "1 checked bag", rightVariant: "text"}]
passBtn = Button("Show boarding pass", @ToAssistant("Show my boarding pass", {booking: "385E655C", flight: "GE372"}))
```