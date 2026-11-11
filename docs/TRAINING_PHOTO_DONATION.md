# Built-in optional training-photo donation: design for review

Status: design only. No app UI, uploader, receiving service or data collection
is activated. No existing reporting permission is permission to train.

## UX and consent boundary

Donation is a separate optional action for each photo, never part of the city
Send button or a condition of filing. Default is OFF, including after updates,
re-import, restart, changed terms or changed processing. No prechecked checkbox,
all-photos switch, nudged acceptance or inference from tester enrollment.

After optional local review, show the exact prepared training image and the
proposed training labels. Before the explicit donation confirmation, explain:
controller/contact, purpose, permitted models/tasks, who can access it, whether
weights or data may be released, processing, retention, withdrawal limits and
hosting/subprocessors. Plain words and accessible localized presentation. A
version change requires new consent for future donations, never silent expansion
of past grants. City submit still works if donation is declined.

Consent receipt is created locally before any upload attempt, with random
contribution ID, terms version+hash, locale, shown image SHA256, processing
version, agreed purpose/release scope, grant time and current status. Original
report ID/hash linkage stays local. No city credentials, municipal description,
case number, reporter ID, exact location or original EXIF in the donation.
Receipt is encrypted under a reviewed independent storage/session boundary;
missing key, tamper, or failed durable write blocks upload. Copy/view/export of
plain-language receipt must not expose a deletion secret.

A receipt records consent, not proof that a user owns every depicted person's
rights. Before public launch, privacy/legal review must settle purpose/legal
basis, photographer rights/license, bystanders/minors, plates, retention,
controller notices and hosting. This design is not a legal determination.

## Image minimization

Never mutate the municipal original or substitute the training derivative into
the city send path. A separate local derivative fixes orientation and strips
EXIF/GPS/device/time metadata. Default proposed donation removes faces and
unneeded plates; manual review is required and detector success is not a privacy
guarantee. Street signs, reflections, homes, visible documents and other context
can still identify people or locations. Reject unsafe samples rather than
claiming stripping EXIF anonymizes a photo.

Open decision: scene/category training can use redacted plates; plate/OCR tasks
need a separately approved scope and treatment for visible plate crops. No raw
original training uploads by default. Do not silently pool scene classification,
plate OCR or vehicle identification into one permission.

## Withdrawal and lineage

Local state: DRAFT -> CONSENTED -> PREPARED -> UPLOAD_IN_FLIGHT -> RECEIVED ->
QUARANTINED -> ELIGIBLE -> INCLUDED. Terminal states: DECLINED, WITHDRAWN,
REJECTED, EXPIRED. An unknown upload outcome is UNCERTAIN, never success or a
blind retry. Durable contribution ID+digest gives deduplication on both sides.

Before upload, withdrawal clears the pending grant, retains only a minimal
local withdrawal receipt and never contacts the server. After upload, send a
signed authenticated withdrawal for the random contribution ID, retain durable
pending state until acknowledged. Retries of deletion requests may be idempotent
but must not imply deletion before confirmation. Offline or failed withdrawal
shows pending, not removed. Restore/reinstall behavior and credential loss must
have an accessible owner-assisted route that does not expose other donations.

Private receiver maintains source-to-derived-sample-to-dataset-version-to-model
lineage. Withdrawn samples leave raw/quarantine/current/future datasets, queues,
annotation copies and backups per an explicit deletion schedule. Keep minimal
non-content revocation audit only. Do not promise removal from already trained
or released weights: exclusion from future use, retraining and public-release
limits must be clearly agreed before the first training run. No distributed
weights by default pending that decision. Export policy must never bypass it.

## Proposed private receiving path without a credit card

First pilot candidate: owner-operated local receiver on an existing computer,
not a public GitHub issue/mailbox and not a Google Form. App encrypts to a pinned
receiver public key and signs with an enrolled device key. Receiver accepts only
enrolled pilot devices and bounded JPEG+receipt packages, never arbitrary URLs.
Private queue and storage outside git, scoped admin access, retention/withdrawal
journal, hard storage/quota limits. No municipal auth or network access in the
receiver. No publication, automatic training or data forwarding from receipt.

Connectivity choices to validate with synthetic packets: (a) local-network
transfer only for a small pilot; (b) an authenticated private device network if
a no-card, policy-compatible service is verified. Do not claim a free remote
service exists until its current costs, account requirements, limits and privacy
terms have been checked. An always-reachable receiver requires an owner machine,
maintenance and secure network configuration even if there is no new bill.
No cloud account, card, paid storage, port forwarding or deployment is approved.
A cloud alternative may be proposed only after independently verifying no-card
private access, quotas, upload/withdrawal authentication, region and deletion.

Requests have strict schema/size/count limits, random IDs, digest bindings,
device revocation, no redirects, no retries after uncertain writes and no raw
image/receipt/keys in logs. Synthetic interoperability/security tests precede
any real contributor. Receipt acknowledgment means received into quarantine,
not consent validated or included in training.

## Inactive implementation units and go-live gates

1. Reviewed design and owner decisions; privacy/legal scope review.
2. Versioned terms/consent state model and receipt codec, synthetic tests only.
3. Optional per-photo UI with preview/default-off/separate city flow, using a
   fake local sink only; tests prove decline never blocks filing and no network.
4. Independent encrypted local donation queue and withdrawal state machine;
   crash/tamper/key-loss/duplicate tests, no transport wired.
5. Private receiver prototype and signed encrypted protocol, synthetic data only;
   storage quota, enrollment/revocation, withdrawal and lineage tests.
6. Synthetic end-to-end pilot, device accessibility/security review, exact-head
   approval and green CI. No merges while independent review is unavailable.
7. Separate explicit owner go for real collection after legal/privacy review,
   with approved terms, receiver destination/operator and retention. Another go
   for training or release when required by those terms. Code approval is not
   consent to collect, and donation consent is not permission to file a city case.

Decisions needed before a real pilot: initial task scope (redacted scene-only
recommended), allowed model/data release and withdrawal promise, retention, and
local-only versus remote private receiving with its operator/maintenance owner.
