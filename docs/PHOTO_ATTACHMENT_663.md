# Photo attachment with an asynchronously assigned CRM ID

The municipality can accept CreateCase with Success=true, a zero CRM GUID,
a nonzero WebCaseIdInCRM, and no human-readable case number yet. That is an
accepted case, not a reason to create another one.

The existing app transport and helper send the exact returned pair to
AddFileToCase: caseIdInCRM remains the zero GUID, webCaseIdInCRM remains the
nonzero web ID. Earlier successful app attachments use this shape. Do not
substitute one identifier for the other, guess a canonical ID, or add a lookup.

The supervised crosswalk and fixed-car bridges incorrectly rejected that
receipt or skipped its attachment. They now use a shared reference check and
send the same pair as the working app transport. Their controllers preserve
the accepted receipt before attachment intent, keep no-replay state, and make
one attachment attempt. Missing CRM or an all-zero/empty pair still refuses.
Attachment rejection or uncertainty stops without another create or retry.

This code change is for future explicitly approved submissions only. It does
not update previously accepted cases, reset claims, revive expired approvals,
or authorize a new filing. Existing accepted-photo-not-attached records stay
held. No recovery workflow or live dispatch is part of this fix.

Synthetic proof covers zero CRM plus web ID through both bridges and Python
controllers, unchanged HTTP field names/values and base64 bytes, invalid pairs,
mutated receipt refusal, burned create/attach operations, and durable accepted
state after attachment uncertainty. A passing test is not a new live submission.
