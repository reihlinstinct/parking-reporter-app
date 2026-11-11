# Immutable private photo staging for live app tests

The existing protected `m0-trial-submit` environment, main-only dispatch,
explicit `FILE-LIVE-REPORT` confirmation, approved text/photo digests and
cross-run duplicate guard remain required. This change does not dispatch a
report or authorize a filing.

To use a new report without committing its encrypted photo to the public app
repository, stage these environment secrets alongside the approved TRIAL data:

- `TRIAL_PRIVATE_PHOTO_REF`: the full lowercase 40-character commit ID containing
  the original in the fixed private `reihlinstinct/parking-reports` repository.
- `TRIAL_PRIVATE_PHOTO_PATH`: `reports/<report-id>/original.jpg` at that commit.
- `PRIVATE_LOOKUP_READ_TOKEN`: the existing short-lived private Contents-read
  token, scoped to that repository only. It also fetches the pinned directory.

Use a verified original, not a preview. Confirm the commit/path through the
private ledger before dispatch. Preserve the usual sole-owner reservation and
municipal duplicate checks. Verify exact approved text and original photo hash.
Never dispatch a report that was released to another filing lane.

If either private-photo field is supplied, both must pass strict validation.
A branch name, URL, traversal, nested path, unavailable input, redirect, empty
file, oversized file or hash mismatch stops staging. There is no fallback to a
legacy fixture on private-input failure. The destination is replaced atomically
only after its approved hash matches. Private paths and response errors are not
printed. The token is sent only to GitHub's fixed API host, without redirects.

If both new fields are empty, the old encrypted committed fixture remains
supported. The existing workflow photo-binding step checks its hash as before.
Clear both private-photo fields when intentionally selecting that legacy mode;
never clear them just to get past a staging failure.

After the run, follow existing evidence handling, revoke temporary tokens,
prove 401, delete the temporary read-token environment secret and remove local
credential copies. Accepted means never resend. An unknown outcome stays
reserved and must be reconciled, not retried automatically.

The workflow never edits `m0-engine-trial.yml`. The app still produces and sends
its reviewed EXIF-free derivative from the digest-bound original. City acceptance
of that new derivative path remains a separate live-test result.
