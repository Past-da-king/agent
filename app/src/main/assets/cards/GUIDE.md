# Building a card (OpenUI)

A card is a small screen you write in OpenUI Lang, the same language you use to answer in the chat. It opens in a slide-up sheet and as a tile on Home. The app draws it natively in the user's accent colour and light or dark theme, so it looks like the app made it. There is no HTML, no CSS and no colour to pick: never write a hex value or a colour name.

The sheet already shows the card's title, source and updated time in a native header. Do not repeat them at the top.

## What you pass to card_save
- `openui`: the body, an OpenUI Lang program that starts with `root = Stack([...])`. Use the components listed below.
- `data`: optional JSON. Put `{{key}}` in any string of the body and the app fills it from the data, so a script or card_update can refresh the numbers without you rewriting the layout. Dotted paths and array positions work: `{{best.price}}`, `{{rows.0.name}}`. A missing key shows nothing.
- `script`: optional Node script that refreshes `data` with no AI. Its last output line is `{"data": {...}}`. It may also return `"openui"` inside data to replace the body, and `"headline"` for the Home tile line.
- `headline`: one short line for the Home tile, like "R3,299 cheapest" or "12 sent, 3 replies".

## Rules
1. Phone first. One Card per thing (a price, a person, a day), several in a `Stack`. A single summary is `root = Stack([card])`.
2. The most important thing first: the cheapest price, the replies today, the next thing to do. Use `EntityList` for facts, `Table` for a comparison, `Callout` for a warning, `TagBlock` for status.
3. Short labels, plain words, money like R4,999. No paragraphs. No em dashes or en dashes anywhere: use a comma, a colon or the word to. No emoji.
4. Buttons use `@ToAssistant("short instruction", {context})` (the tap comes back to you as the user's message) or `@OpenUrl("https://...")`. A button never sends, pays or posts by itself: when a tap asks for that, still use the approval flow.
5. Only the components below. Charts are not drawn: show change over time as a Table.

## Example
```openui-lang
root = Stack([c1])
c1 = Card([head, facts, next])
head = CardHeader("{{store}}", "Cheapest today")
facts = EntityList([{left: "Price", right: "R{{price}}", rightVariant: "number"}, {left: "Was", right: "R{{was}}", rightVariant: "number"}])
next = Buttons([Button("Buy link", @OpenUrl("{{url}}")), Button("Watch this", @ToAssistant("Keep watching this price", {store: "{{store}}"}), "secondary")])
```
with `data` = `{"store": "Takealot", "price": "3299", "was": "3599", "url": "https://example.com/p/1"}`.
