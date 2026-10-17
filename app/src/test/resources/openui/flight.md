Your flight leaves Johannesburg at 08:20 today and lands in George at 10:25.

```openui-lang
root = Stack([flight])
flight = Card([CardHeader("FN204", "Johannesburg to George, today"), EntityList(rows), Buttons([passBtn], "row")])
rows = [{left: "Departs", right: "08:20 JNB", rightVariant: "text"}, {left: "Arrives", right: "10:25 GRJ", rightVariant: "text"}, {left: "Boarding", right: "07:50, Group C", rightVariant: "text"}, {left: "Seat", right: "21C", rightVariant: "text"}, {left: "Booking ref", right: "K7Q2ZP", rightVariant: "text"}, {left: "Baggage", right: "1 checked bag", rightVariant: "text"}]
passBtn = Button("Show boarding pass", @ToAssistant("Show my boarding pass", {booking: "K7Q2ZP", flight: "FN204"}))
```