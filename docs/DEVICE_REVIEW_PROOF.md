# Review and accessibility device proof

These tests use synthetic fixtures only. They do not authorize live submissions.

## Keyboard route coverage (#359)

`KeyboardInsetTest` now exercises Settings inside the real AppShell Scaffold,
including a 200% font-scale composition. It also opens a real IME from both the
plate and description fields and checks the complete Save and Send bounds against
the positive IME inset. It never taps either review action. The previous device
IME setting is restored by the existing cleanup.

Run on API 30 and 36, and manually/weekly on a compact landscape device. The
200% composition changes text scale, not device resolution or orientation, and
is not a replacement for compact/landscape proof. Inspect the generated
`shell-settings-real-ime-*` and `review-actions-real-ime-*` screenshots. A test
implementation or passing geometry assertion without inspected screenshots is
not visual proof. Do not adjust production padding until evidence shows the bug.

## Pending tag in the review route (#397)

`pendingTagIsExposedInsideReviewWithoutChangingTheReport` holds the tag lookup
while the vehicle completes, checks the pending text inside the actual Review
screen and its polite live-region ancestor, captures pending/completed states,
and verifies that saving does not inject registry text or trigger Send. All
registry answers are in-memory. Inspect both `registry-review-tag-*` screenshots.
Actual TalkBack must still demonstrate one pending announcement and no repeated
pending announcement when the result replaces it. Compose semantics alone cannot
certify that. No issue closure is implied by adding this test.
