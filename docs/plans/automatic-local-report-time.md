# Automatic local report time

Owner instruction: remove capture-time input from review because it is not a city field. If photo EXIF has a capture time, keep it; otherwise derive local report time when Send is tapped. No time editor, format sample, original-time readout, or confirmation warning remains on review.

CreateCase's ReportFields/buildReport has no time field. The report payload keeps the same ten keys, exact reviewed description, address and coordinates. No date is appended to or removed from the approved report text. Original JPEG bytes and Evidence remain unchanged. There is no municipal payload loss.

`ReportTime` centralizes local metadata behavior. Save preserves EXIF capture time if present, without fabricating a report timestamp for a photo with none. Send derives Jerusalem-local time from the send intent only when EXIF is absent. Offset-free EXIF stays offset-free original metadata; no timezone is guessed for a field the city does not use. Old manually entered corrections are no longer used as capture time. The ledger codec retains both original metadata and derived report time fields, requiring no database migration. Existing warning codes/translations remain readable for older drafts, but the time-only warnings no longer gate readiness and are cleared on review/save.

The optional encrypted-intake report still has its own existing `time` schema member; report creation supplies EXIF or that export/send intent's time, so a missing-EXIF photo does not create an invalid package. This does not add a time field to CreateCase.

Regression coverage: fixed-clock EXIF/missing-EXIF derivation, no time controls/readouts, exact metadata preservation, no-EXIF send readiness, unchanged address/location requirements, city payload key set, and synthetic screenshot in ReviewAddressTest. Existing synthetic FlowDriver no longer types a retired field. No live run is dispatched or repeated.

## PRPKG2 timestamp and digest contract (#348)

`time` must be an ISO offset date-time or an ISO local date-time preserving offset-free EXIF. Invalid dates, date-only values and display-format dates are refused explicitly at sender and receiver, independently of municipal readiness. Local date-times are not converted to an invented instant. A missing-EXIF send captures the clock once; repeated packaging of that intent reuses confirmedTime. A later Save clears a derived time as before, so a new send intent can acquire its own time.

`review_digest` is the local outgoing draft digest after its time has been resolved. It is provenance, not a digest the receiver can reconstruct from its partial draft fields. The separate `approval_digest` hashes the complete canonical outgoing report, including time and review_digest; the signature authenticates that full header and JPEG. Editing time after approval changes approval_digest and is refused by seal. Existing wire schema is unchanged; no new city payload field or automatic outgoing text is introduced.
