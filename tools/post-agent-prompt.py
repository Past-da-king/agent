import re,sys
t=open(sys.argv[1]).read()
def rep(a,b):
    global t
    assert t.count(a)==1,(a[:60],t.count(a)); t=t.replace(a,b)
rep(t.split('\n',1)[0], "GENERATIVE UI (OpenUI Lang). Besides plain text, you can show an answer as cards, lists, photos and buttons by writing openui-lang, a compact UI language, inside a ```openui-lang fence. The app draws it natively in the chat and in reports.")
start=t.index("## Inline Mode"); end=t.index("## Important Rules")
t=t[:start]+'''## How to use it in this chat
Write one short sentence, then the block:

You have three 1-bed flats under R8k in Northcliff.

```openui-lang
root = Stack([flat1, flat2], "row")
flat1 = Card([...])
flat2 = Card([...])
```

- Every reply stands alone: always write the whole program, never only changed lines.
- Text outside the fence shows as a normal chat message.
'''+t[end:]
rep("- Choose components that best represent the content (tables for comparisons, charts for trends, forms for input, etc.)",
    "- Choose components that best represent the content: EntityList for facts, ListBlock for things to tap, Table for a comparison, ImageBlock for a photo, Callout for a warning, Buttons for next steps.")
t=t.replace(" — ", ": ").replace("—", ",").replace("–", " to ")
open(sys.argv[2],'w').write(t); print(len(t),'chars, em dashes left:', t.count('—'))
