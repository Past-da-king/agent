Here are three 1-bed flats under R8k in Northcliff.

```openui-lang
root = Stack([flat1, flat2, flat3], "row")

flat1 = Card([img1, head1, facts1, btns1], src1)
img1 = ImageBlock("https://flats.example/photos/1.jpg", "1-bed flat in Northcliff")
head1 = CardHeader("R7,200 a month", "Northcliff")
facts1 = EntityList([rent1, beds1, size1, avail1, dep1], "small")
rent1 = {left: "Rent", right: "R7,200", rightVariant: "number"}
beds1 = {left: "Bedrooms", right: "1"}
size1 = {left: "Size", right: "62 m2"}
avail1 = {left: "Available", right: "1 Nov 2026"}
dep1 = {left: "Deposit", right: "R7,200", rightVariant: "number"}
btns1 = Buttons([book1, open1])
book1 = Button("Book viewing", @ToAssistant("Book a viewing", {item: "R7,200 1-bed, 62 m2", listing: "RR1000001"}))
open1 = Button("Open listing", @OpenUrl("https://flats.example/listing/RR1000001"))
src1 = [{title: "R7,200 1-bed in Northcliff", sourceName: "flats.example", url: "https://flats.example/listing/RR1000001"}]

flat2 = Card([img2, head2, facts2, btns2], src2)
img2 = ImageBlock("https://flats.example/photos/2.jpg", "1-bed flat at Parkview")
head2 = CardHeader("R6,800 a month", "Parkview")
facts2 = EntityList([rent2, beds2, size2, avail2, dep2], "small")
rent2 = {left: "Rent", right: "R6,800", rightVariant: "number"}
beds2 = {left: "Bedrooms", right: "1"}
size2 = {left: "Size", right: "38 m2"}
avail2 = {left: "Available", right: "Now"}
dep2 = {left: "Deposit", right: "R6,800", rightVariant: "number"}
btns2 = Buttons([book2, open2])
book2 = Button("Book viewing", @ToAssistant("Book a viewing", {item: "R6,800 1-bed, 38 m2, Parkview", listing: "RR1000002"}))
open2 = Button("Open listing", @OpenUrl("https://flats.example/listing/RR1000002"))
src2 = [{title: "R6,800 1-bed at Parkview", sourceName: "flats.example", url: "https://flats.example/listing/RR1000002"}]

flat3 = Card([img3, head3, facts3, btns3], src3)
img3 = ImageBlock("https://flats.example/photos/3.jpg", "1-bed flat in Northcliff")
head3 = CardHeader("R6,950 a month", "Northcliff")
facts3 = EntityList([rent3, beds3, size3, avail3, dep3], "small")
rent3 = {left: "Rent", right: "R6,950", rightVariant: "number"}
beds3 = {left: "Bedrooms", right: "1"}
size3 = {left: "Size", right: "40 m2"}
avail3 = {left: "Available", right: "1 Nov 2026"}
dep3 = {left: "Deposit", right: "R6,950", rightVariant: "number"}
btns3 = Buttons([book3, open3])
book3 = Button("Book viewing", @ToAssistant("Book a viewing", {item: "R6,950 1-bed, 40 m2", listing: "RR1000003"}))
open3 = Button("Open listing", @OpenUrl("https://flats.example/listing/RR1000003"))
src3 = [{title: "R6,950 1-bed in Northcliff", sourceName: "flats.example", url: "https://flats.example/listing/RR1000003"}]
```