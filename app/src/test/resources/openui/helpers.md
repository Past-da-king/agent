Here is where your helpers are at.

```openui-lang
root = Stack([job1, job2, job3, job4], "column")
job1 = Card([h1, s1, b1])
h1 = CardHeader("Holiday photo slideshow", "Rendering")
s1 = TextContent("Rendering on the home PC, about 60% done.")
b1 = Button("Details", @ToAssistant("Show details for the Holiday photo slideshow", {job: "holiday-slideshow"}))
job2 = Card([h2, s2, b2])
h2 = CardHeader("Lindiwe's bursary form", "Waiting")
s2 = TextContent("Held until Lindiwe's proof of address comes in.")
b2 = Button("Details", @ToAssistant("Show details for Lindiwe's bursary form", {job: "lindiwe-bursary"}))
job3 = Card([h3, s3, b3])
h3 = CardHeader("Website update", "Done")
s3 = TextContent("Live on the site.")
b3 = Button("Details", @ToAssistant("Show details for the Website update", {job: "website-update"}))
job4 = Card([h4, s4, b4])
h4 = CardHeader("Car insurance quotes", "Done")
s4 = TextContent("Report ready.")
b4 = Button("Details", @ToAssistant("Show details for the Car insurance quotes", {job: "insurance-quotes"}))
```