Here are 3 one-bed flats in Umhlanga Ridge under R10k:

```openui-lang
root = Card([header, intro, sep1, f1head, f1img, f1info, f1btns, sep2, f2head, f2img, f2info, f2btns, sep3, f3head, f3img, f3info, f3btns, followups], sources)

header = CardHeader("1-Bed Flats in Umhlanga Ridge", "3 available under R10,000 / month")
intro = TextContent("All prices exclude or include deposits as shown. Tap to book a viewing or open the listing.", "small")

f1head = InlineHeader("R9,500 / month — 98 m²", "Largest of the three")
f1img = ImageBlock("https://images.pp.co.za/listing/12122338/Hyyli5R3aokEClguZNSV32/1600/1066/contain/jpegorpng", "Umhlanga Ridge 1-bed flat")
f1info = EntityList([{left: "Rent", right: "R9,500 / month", rightVariant: "number"}, {left: "Size", right: "98 m²"}, {left: "Available", right: "1 Nov 2026"}, {left: "Deposit", right: "R9,500", rightVariant: "number"}])
f1btns = Buttons([book1, open1], "row")
book1 = Button("Book viewing", @ToAssistant("Book a viewing of the 1-bed flat in Umhlanga Ridge at R9,500/month (98 m², available 1 Nov 2026). Listing RR4794507."), "primary")
open1 = Button("Open listing", @OpenUrl("https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4794507"), "secondary")

sep2 = Separator()
f2head = InlineHeader("R8,500 / month — 43 m²", "The Gateway · available now")
f2img = ImageBlock("https://images.pp.co.za/listing/12107340/SYJ3Ur69TCw3DamxqVpZI4/1600/1066/contain/jpegorpng", "The Gateway 1-bed flat")
f2info = EntityList([{left: "Rent", right: "R8,500 / month", rightVariant: "number"}, {left: "Size", right: "43 m²"}, {left: "Available", right: "Now"}, {left: "Deposit", right: "R8,500", rightVariant: "number"}])
f2btns = Buttons([book2, open2], "row")
book2 = Button("Book viewing", @ToAssistant("Book a viewing of the 1-bed flat at The Gateway, Umhlanga Ridge, at R8,500/month (43 m², available now). Listing RR4787859."), "primary")
open2 = Button("Open listing", @OpenUrl("https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4787859"), "secondary")

sep3 = Separator()
f3head = InlineHeader("R8,850 / month — 45 m²", "Available 1 Nov 2026")
f3img = ImageBlock("https://images.pp.co.za/listing/12048389/uzoYTZR76U2IcL0q12eVU0/600/450/contain/jpegorpng", "Umhlanga Ridge 1-bed flat")
f3info = EntityList([{left: "Rent", right: "R8,850 / month", rightVariant: "number"}, {left: "Size", right: "45 m²"}, {left: "Available", right: "1 Nov 2026"}, {left: "Deposit", right: "R8,850", rightVariant: "number"}])
f3btns = Buttons([book3, open3], "row")
book3 = Button("Book viewing", @ToAssistant("Book a viewing of the 1-bed flat in Umhlanga Ridge at R8,850/month (45 m², available 1 Nov 2026). Listing RR4762518."), "primary")
open3 = Button("Open listing", @OpenUrl("https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4762518"), "secondary")

followups = FollowUpBlock([fu1, fu2])
fu1 = FollowUpItem("Which one has the lowest total move-in cost?")
fu2 = FollowUpItem("Show only flats available now")

sources = [{title: "R9,500 · 98 m² · Umhlanga Ridge", sourceName: "privateproperty.co.za", url: "https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4794507"}, {title: "R8,500 · 43 m² · The Gateway", sourceName: "privateproperty.co.za", url: "https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4787859"}, {title: "R8,850 · 45 m² · Umhlanga Ridge", sourceName: "privateproperty.co.za", url: "https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4762518"}]
```

Each card has a **Book viewing** (sends me a request) and **Open listing** button.