Here is where your helpers are at.

```openui-lang
root = Stack([job1, job2, job3, job4], "column")
job1 = Card([h1, s1, b1])
h1 = CardHeader("Mark Manson collage video", "Rendering")
s1 = TextContent("Rendering on the Impetix server, about 60% done.")
b1 = Button("Details", @ToAssistant("Show details for the Mark Manson collage video", {job: "mark-manson-collage"}))
job2 = Card([h2, s2, b2])
h2 = CardHeader("Sne's college applications", "Waiting")
s2 = TextContent("Held until Sne's proof of address comes in.")
b2 = Button("Details", @ToAssistant("Show details for Sne's college applications", {job: "sne-college-apps"}))
job3 = Card([h3, s3, b3])
h3 = CardHeader("Agent app 0.11.13 release", "Done")
s3 = TextContent("Published on GitHub.")
b3 = Button("Details", @ToAssistant("Show details for the agent app 0.11.13 release", {job: "agent-app-0-11-13"}))
job4 = Card([h4, s4, b4])
h4 = CardHeader("RMBLR audit", "Done")
s4 = TextContent("Report ready.")
b4 = Button("Details", @ToAssistant("Show details for the RMBLR audit", {job: "rmblr-audit"}))
```