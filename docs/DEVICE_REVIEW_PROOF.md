# Review and accessibility device proof

These tests use synthetic fixtures only. They do not authorize live submissions.

## Keyboard route coverage (#359)

`KeyboardInsetTest` now exercises Settings inside the real AppShell Scaffold,
including an opt-in 200% font-scale composition. It also opens a real IME from both the
plate and description fields and checks the complete Save and Send bounds against
the positive IME inset. It never taps either review action. The previous device
IME setting is restored by the existing cleanup.

Run on API 30 and 36, and manually/weekly on a compact landscape device. Enable
the 200% case manually with instrumentation argument `extendedKeyboardMatrix=1` only on
manual runs, not every PR. No current workflow passes this argument, so no weekly
200% keyboard execution is claimed. The
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

## Picker projection (#362)

The existing duplicate-code selection test now also inspects Android's
AccessibilityNodeInfo projection before and after filtering, requiring one
visible checked/checkable enabled composite radio choice with the complete combined description.
AndroidX projects this composite row as android.view.View. The selected row is not
natively clickable; an unselected alternative must be actionable.
It does not substitute for TalkBack. Still verify Hebrew RTL focus order,
complete paths, one focus stop per code and selected-state speech at normal and
200% text. Preserve the existing glyph and geometry assertions.

## API 36 editor evidence (#363)

The existing tests already closed the IME normally, counted Settings callbacks
and captured the unsaved dialog before asserting visibility. They now also
assert the plate before Back and after IME closure, verify editing causes no save
or exit, and capture Settings before the edit assertion, before the second save
tap and before final result assertions. Phone state is asserted again after IME
closure. Do not weaken final value/callback assertions or retry a failing test.

Run both `ReviewUnsavedBackTest` and `SettingsScreenSecondSaveTest` on API 36.
Inspect the captures with the instrumentation failure and window/IME state.
No production cause has been established by these additional diagnostics.

## Recovery activation is blocked (#358)

Recovery remains hard-disabled and AppShell supplies no recovery ports. The current
15-minute rule is an absolute admission deadline, not a guaranteed deletion time.
Every start/restore/write admission checks elapsedRealtime, boot and version. The
Handler timer uses uptime, pauses in deep sleep and disappears on process death.
Ciphertext and the credential-bound alias may remain until a later startup sweep.
No claim of physical erasure or deletion exactly at15minutes is valid.

Before activation, implement and test elapsed-realtime cleanup through sleep and
process death, retain fail-closed admission checks, and show truthful deletion state
only after successful removal. Validate this with a real external kill/restart,
not Activity recreation or only JVM fakes. Do not enable recovery from this document.

Restore discovery must also be bound to the intended saved report before activation.
The current unbound beginRestore and Review-to-other retirement can discard another
report's checkpoint after process death. A future report picker must never decrypt
or retire a different report merely because it was opened. Required tests include
A checkpoint -> kill -> open B -> return A, with A preserved inside its original
absolute deadline, and rejection after expiry or submission journal creation.

The recovery status vocabulary and dialogs are staged only. Before activation,
remove the duplicated Button accessibility prefix and use restore-specific cancel
wording rather than claiming canceled restored edits remain in memory. These copy
changes need the same source-grounded review as the rest of the recovery UI.
