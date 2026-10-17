Here are four wireless mice worth a look, best pick first.

```openui-lang
root = Stack([p1, p2, p3, p4], "row")

p1 = Product("https://shop.example/photos/1.jpg", "Orbit M3 wireless mouse, quiet clicks", "R899", 4.6, 1204, "Demo Store", "R1,099", "Best value", b1)
b1 = Buttons([add1, view1])
add1 = Button("Add to cart", @ToAssistant("Add to cart", {item: "Orbit M3 wireless mouse", price: "R899", sku: "DM-1001"}))
view1 = Button("View", @OpenUrl("https://shop.example/p/DM-1001"), "secondary")

p2 = Product("https://shop.example/photos/2.jpg", "Glide Pro ergonomic mouse", "R1,499", 4.8, 392, "Demo Store", null, "Most comfortable", b2)
b2 = Buttons([add2, view2])
add2 = Button("Add to cart", @ToAssistant("Add to cart", {item: "Glide Pro ergonomic mouse", price: "R1,499", sku: "DM-2002"}))
view2 = Button("View", @OpenUrl("https://shop.example/p/DM-2002"), "secondary")

p3 = Product("https://shop.example/photos/3.jpg", "Nano travel mouse", "R349", 3.9, 87, "Sample Shop", null, null, b3)
b3 = Buttons([add3, view3])
add3 = Button("Add to cart", @ToAssistant("Add to cart", {item: "Nano travel mouse", price: "R349", sku: "SS-3003"}))
view3 = Button("View", @OpenUrl("https://sample.example/p/SS-3003"), "secondary")

p4 = Product(null, "Pebble silent mouse, no reviews yet", "R279", null, null, "Sample Shop", null, "New", b4)
b4 = Buttons([add4, view4])
add4 = Button("Add to cart", @ToAssistant("Add to cart", {item: "Pebble silent mouse", price: "R279", sku: "SS-4004"}))
view4 = Button("View", @OpenUrl("https://sample.example/p/SS-4004"), "secondary")
```
