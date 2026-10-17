Here are three 1-bed flats under R10k in Umhlanga Ridge.

```openui-lang
root = Stack([flat1, flat2, flat3], "row")

flat1 = Card([img1, head1, facts1, btns1], src1)
img1 = ImageBlock("https://images.pp.co.za/listing/12122338/Hyyli5R3aokEClguZNSV32/1600/1066/contain/jpegorpng", "1-bed flat in Umhlanga Ridge")
head1 = CardHeader("R9,500 a month", "Umhlanga Ridge")
facts1 = EntityList([rent1, beds1, size1, avail1, dep1], "small")
rent1 = {left: "Rent", right: "R9,500", rightVariant: "number"}
beds1 = {left: "Bedrooms", right: "1"}
size1 = {left: "Size", right: "98 m2"}
avail1 = {left: "Available", right: "1 Nov 2026"}
dep1 = {left: "Deposit", right: "R9,500", rightVariant: "number"}
btns1 = Buttons([book1, open1])
book1 = Button("Book viewing", @ToAssistant("Book a viewing", {item: "R9,500 1-bed, 98 m2", listing: "RR4794507"}))
open1 = Button("Open listing", @OpenUrl("https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4794507"))
src1 = [{title: "R9,500 1-bed in Umhlanga Ridge", sourceName: "privateproperty.co.za", url: "https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4794507"}]

flat2 = Card([img2, head2, facts2, btns2], src2)
img2 = ImageBlock("https://images.pp.co.za/listing/12107340/SYJ3Ur69TCw3DamxqVpZI4/1600/1066/contain/jpegorpng", "1-bed flat at The Gateway")
head2 = CardHeader("R8,500 a month", "The Gateway")
facts2 = EntityList([rent2, beds2, size2, avail2, dep2], "small")
rent2 = {left: "Rent", right: "R8,500", rightVariant: "number"}
beds2 = {left: "Bedrooms", right: "1"}
size2 = {left: "Size", right: "43 m2"}
avail2 = {left: "Available", right: "Now"}
dep2 = {left: "Deposit", right: "R8,500", rightVariant: "number"}
btns2 = Buttons([book2, open2])
book2 = Button("Book viewing", @ToAssistant("Book a viewing", {item: "R8,500 1-bed, 43 m2, The Gateway", listing: "RR4787859"}))
open2 = Button("Open listing", @OpenUrl("https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4787859"))
src2 = [{title: "R8,500 1-bed at The Gateway", sourceName: "privateproperty.co.za", url: "https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4787859"}]

flat3 = Card([img3, head3, facts3, btns3], src3)
img3 = ImageBlock("https://images.pp.co.za/listing/12048389/uzoYTZR76U2IcL0q12eVU0/600/450/contain/jpegorpng", "1-bed flat in Umhlanga Ridge")
head3 = CardHeader("R8,850 a month", "Umhlanga Ridge")
facts3 = EntityList([rent3, beds3, size3, avail3, dep3], "small")
rent3 = {left: "Rent", right: "R8,850", rightVariant: "number"}
beds3 = {left: "Bedrooms", right: "1"}
size3 = {left: "Size", right: "45 m2"}
avail3 = {left: "Available", right: "1 Nov 2026"}
dep3 = {left: "Deposit", right: "R8,850", rightVariant: "number"}
btns3 = Buttons([book3, open3])
book3 = Button("Book viewing", @ToAssistant("Book a viewing", {item: "R8,850 1-bed, 45 m2", listing: "RR4762518"}))
open3 = Button("Open listing", @OpenUrl("https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4762518"))
src3 = [{title: "R8,850 1-bed in Umhlanga Ridge", sourceName: "privateproperty.co.za", url: "https://www.privateproperty.co.za/to-rent/kwazulu-natal/durban/umhlanga/umhlanga-ridge/RR4762518"}]
```