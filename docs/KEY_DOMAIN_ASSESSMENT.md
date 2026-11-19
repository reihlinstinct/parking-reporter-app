# Claim and evidence key separation assessment

Refs #608. Design only. No envelope, key, credential or workflow is changed.

## Current source boundary

`scripts/supervised_api_trial.py:encrypt_record` derives a 32-byte AES-GCM key
from `TRIAL_DATA_KEY`, a random 16-byte salt, SHA-256 PBKDF2 and 200000 iterations.
It authenticates the record with `api-trial-record-v1` AAD.
`.github/workflows/supervised-api-trial.yml` encrypts the evidence tar with OpenSSL
AES-256-CBC, PBKDF2/SHA-256, 200000 iterations and a separate random salt, using
the same password. Separate salts yield different derived keys; this is not the
same thing as independent compromise domains. A leaked password opens both.
CBC evidence also lacks an authenticated-envelope contract comparable to GCM.
The journal has its own versioned GCM AAD in `live_batch_persistence.py`; AAD
separates authentication contexts, not password compromise.

## Recommended next version

Use independently provisioned claim and evidence secrets for actual compromise
separation. If operational constraints require one root secret, use a reviewed
versioned HKDF-SHA256 schedule with distinct fixed info labels for claim,
evidence and journal. HKDF labels must be protocol constants, never user input.
Random per-envelope salt and nonce remain mandatory; labels do not replace them.
Prefer an authenticated evidence envelope with explicit magic/version/AAD.
Do not splice a new derivation into existing magic values.

## Migration and recovery

1. Inventory encrypted formats and readers using metadata only. Preserve all
   accepted and uncertain claim records; absence is not a fresh history.
2. Add new-version readers and synthetic cross-domain refusal tests first.
3. Obtain separate permission for secret provisioning and a private recovery
   route. A source change does not grant access to old evidence.
4. Keep old readers during a bounded migration. Never rewrite a held claim as a
   new claim or delete the original after a failed acknowledgement.
5. Test old/new round trips, wrong-domain keys, changed AAD, truncated envelopes,
   crash before/after remote CAS and backup restore without municipal requests.
6. Rotate only after an approved recovery rehearsal. No live transition or
   rotation is delivered by this assessment.

Independent random keys simplify compromise reasoning but increase recovery
operations. HKDF is easier to deploy but a root compromise still exposes every
domain. That tradeoff is an owner decision before activation, not a blocker for
this design-only assessment.
