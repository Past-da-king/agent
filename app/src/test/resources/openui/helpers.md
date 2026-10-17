Here's where your four jobs stand:

```openui-lang
root = Card([header, list])
header = CardHeader("Your helpers", "4 jobs · updated 13:50")
list = ListBlock([m1, s1, a1, r1])
m1 = ListItem("Mark Manson collage video", "Rendering · ~60% done", null, "Details", @ToAssistant("Show me the details of the Mark Manson collage video job"))
s1 = ListItem("Sne's college applications", "Waiting on Sne's proof of address", null, "Details", @ToAssistant("Show me the details of Sne's college applications job"))
a1 = ListItem("Agent app 0.11.13 release", "Done · published on GitHub", null, "Details", @ToAssistant("Show me the details of the Agent app 0.11.13 release job"))
r1 = ListItem("RMBLR audit", "Done · report ready", null, "Details", @ToAssistant("Show me the details of the RMBLR audit job"))
```

Two done, one rendering, one blocked on Sne.