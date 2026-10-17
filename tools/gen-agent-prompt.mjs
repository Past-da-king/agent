// The Agent app's OpenUI prompt section: OpenUI's own generator (inline mode), the chat library trimmed to what the
// app renders, plus Stack (from OpenUI's playground library) so several items get one Card each. No charts.
import { readFileSync, writeFileSync } from "node:fs";
import { generateSystemPrompt } from "@openuidev/lang-core";
const chat = JSON.parse(readFileSync(process.argv[2], "utf8"));
const KEEP = ["Card","CardHeader","InlineHeader","TextContent","MarkDownRenderer","Callout","TextCallout","Separator","ImageBlock","Image",
  "Table","Col","EntityList","TagBlock","Tag","Buttons","Button","ListBlock","ListItem","Steps","StepsItem","FollowUpBlock","FollowUpItem"];
const components = {
  Stack: { signature: 'Stack(children: (Card | Product)[], direction?: "row" | "column")',
    description: 'Several Cards or Products, one per item. direction "row" shows them side by side to swipe through (listings, products, options); "column" (default) stacks them.' },
  Product: { signature: 'Product(image?: string, name: string, price?: string, rating?: number, reviews?: number, seller?: string, was?: string, tag?: string, actions?: Buttons)',
    description: 'One thing for sale or compare, drawn as a tall card: photo, seller, name, price (with the old price struck through in was), star rating out of 5 with the review count, an optional tag such as "Best value" or "In stock", then its Buttons (for example Add to cart, View). Put several in a Stack with direction "row" so the user swipes sideways through them.' },
};
for (const k of KEEP) {
  const c = structuredClone(chat.components[k]); if (!c) throw new Error("missing " + k);
  c.signature = c.signature.replace(/\(([A-Z][A-Za-z]*(?: \| [A-Z][A-Za-z]*)+)\)/g, (_, u) => "(" + u.split(" | ").filter(n => KEEP.includes(n)).join(" | ") + ")");
  delete c.schema;
  components[k] = c;
}
const DROPPED = Object.keys(chat.components).filter(n => !KEEP.includes(n));
const mentionsDropped = (s) => DROPPED.some(n => new RegExp("\\b" + n + "\\b").test(s));
const componentGroups = [{ name: "Layout", components: ["Stack", "Product"] }, ...chat.componentGroups.map(g => ({ ...g, components: g.components.filter(n => KEEP.includes(n)),
  notes: (g.notes ?? []).filter(s => !mentionsDropped(s)) })).filter(g => g.components.length)];
const library = { ...chat, root: "Stack", components, componentGroups };
delete library.promptOptions; delete library.schema;
const rules = [
  "This is a chat on a phone, not a dashboard editor. Use UI BY DEFAULT: almost every answer is ONE short line of text and then one openui-lang block with Buttons for the obvious next steps. That covers updates, results, options, status, plans, lists, bookings, approvals, money, search results and anything with two or more facts or a next action. Plain text with no block ONLY for a tiny conversational reply: a greeting, a one-line yes or no, or a clarifying question.",
  "One Card per item: several listings, products, flights or options go in root = Stack([card1, card2, card3], \"row\"), each its own Card with its own photo, facts and buttons. A single thing or a summary is root = Stack([card]).",
  "Products and things to compare or buy (research, shopping, deals): root = Stack([p1, p2, p3], \"row\") with one Product per item, never a long list. Each has its photo URL, name, price as the user reads it (R1,299), rating and reviews only when you really found them (never invent numbers), the seller, a short tag if one fits, and Buttons with a context object naming that product, e.g. @ToAssistant(\"Add to cart\", {item: \"Logitech MX Master 3S\", price: \"R1,899\"}) and @OpenUrl(its link). Five to eight is plenty; put the best pick first and tag it. Before the block, one line saying what you compared.",
  "Buttons: every Button and ListItem action is @ToAssistant (the user's tap comes back to you quietly, marked as a button tap: it is not shown in the chat as something they typed, so answer it without quoting it back to them) or @OpenUrl (opens a link in the browser). Give each @ToAssistant a short instruction and a context object naming exactly which item it is about, e.g. @ToAssistant(\"Book a viewing\", {item: \"R7,200 1-bed, 62 m2\", listing: \"RR1000001\"}).",
  "A button never sends, pays or posts by itself. When a tap asks you to send, pay or post, still use the approval flow before doing it.",
  "Write like the user: plain words, short labels, money as R7,200. No em dashes or en dashes anywhere: use a comma, a colon or the word to. No filler, no jokes, no cheeky or leading lines, no follow-ups unless they are useful next steps. NEVER use emoji.",
  "Only use the components listed above.",
];
const prompt = generateSystemPrompt({ library, promptOptions: { inlineMode: true, toolCalls: false, bindings: false, additionalRules: rules } });
writeFileSync(process.argv[3], prompt); console.log(prompt.length, "chars");
