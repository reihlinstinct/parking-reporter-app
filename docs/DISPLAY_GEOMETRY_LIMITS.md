# Picker geometry verification limits

The picker tests verify title/label ordering, field width, label text ink and
result-path glyph bounds. The populated5164 fixture captures compact200-percent
and foldable screenshots for visual inspection of the full hint and editor gap.
The field-container bound includes its Above label, so it is not an independent
inner-editor boundary and the test does not claim that it is.

Removed the native accessibility character comparison introduced after357a303.
At28d0b99 all five device jobs failed it. On the inspected screenshots the label
and5164 are separate, but the reported native character rectangles start at the
Above-label origin and cover the label rather than the visible input glyphs.
Comparing those rectangles to the label therefore cannot prove an overlap.
No constant pixel offset, tolerance expansion or skipped assertion replaces it.

The inner-editor separation remains a screenshot/manual verification limitation
until a direct inner-text layout coordinate or independently verified pixel check
is available. Require fresh CI and inspect actual compact/foldable output after
this test-only correction. This does not establish TalkBack spoken order.
