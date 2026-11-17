# No-code live batch intake and clean emulator cache

Issue: #548. Status: isolated, inactive foundation. No live workflow changes.

## Owner outcome

One private ZIP contains photos, the pinned address directory and one JSON manifest. A protected main-only job accepts a full private data commit SHA, constrained batch path and exact archive SHA256. It uses the existing fixed private repository and redirect-free read credential pattern in `fetch-live-photo.py`; no new credential is created by this foundation. Each report has its own municipal topic, exact text, location, capture time, plate and one or more photos. The job submits reports sequentially using the app UI. No source commit or per-report repository-secret edit is needed.

The manifest is data, not permission. Execution still requires source-grounded owner authority for each report, exact manifest approval binding and the existing protected environment. Unknown topics need separate authority. No arbitrary steps, code, URLs, credentials or shell fields are accepted. Reporter credentials are injected separately from existing approved secrets after all checks.

## Input contract (v1)

ZIP entries: `manifest.json`, `addresses.json`, and referenced `photos/*.jpg|jpeg|png`. Only stored/deflated regular files; no directories, links, duplicate names, traversal or extras. Caps: 50 reports, 10 photos per report, 512 entries, 100 MiB compressed, 200 MiB expanded and 1 MiB JSON. These are intake ceilings, not claims of municipal upload limits. The live adapter must enforce verified municipal limits before dispatch.

Manifest root keys: `schemaVersion: 1`, `batchId`, `reports`.

Report keys: `reportId`, `text`, `textSha256`, `plate`, `address`, `latitude`, `longitude`, `capturedAt` (offset required), `subjectCode`, `photos`.

Each photo: `path`, `sha256`. Digests bind exact bytes; no normalization. No repeated report ID, photo path or photo digest anywhere in a batch. The journal also conservatively reserves exact textSha256 globally across batches, even with a fresh photo. This can hold a legitimate repeat that reuses exact wording; there is no automatic override. Incident deduplication cannot infer whether different wording/photos depict the same real event, so owner-side reconciliation remains required. Strict unknown-key rejection. `subjectCode` must match an independently loaded current leaf-topic directory; the manifest cannot define its own category labels. Address bytes must match the production pin. File signatures are an initial filter, not full image decoding. Decode and verify every image's MIME/dimensions with bounded resources before staging.

A plaintext ZIP may be fetched only from verified private storage. For encrypted input, decrypt into a temporary private directory after checking the outer immutable digest; validate the decrypted ZIP against a separately approved inner digest. `BATCH_APPROVED_OUTER_SHA256` binds bytes downloaded by the fetch helper; `BATCH_APPROVED_INNER_SHA256` will bind the decrypted ZIP at workflow integration. Use the existing approved key where applicable. No new key creation/rotation/delivery in this work. Do not log manifests, hashes, raw artifact URLs, names, text, plates or municipal responses. Only fixed progress markers are allowed in public logs.

## Intake and execution stages

1. Fetch `batches/<id>/input.zip[.enc]` from the existing fixed private `parking-reports` repository at a full immutable commit SHA. No caller-supplied repository or URL, no redirects and no mutable ref. The private-read helper is a source pattern only: the PRIVATE_LOOKUP_READ_TOKEN secret was absent in the live repository check. A new narrowly scoped credential requires approval before activation; never copy a broad account token into Actions and never put it in app params. An encrypted same-public-repository asset is an alternative requiring no private-read token, but its fetch adapter has not been built. This is a private data commit, not a source-code change or a private Actions run. `fetch_live_batch.py` implements this inactive boundary with streamed size/digest checks and atomic mode-0600 staging.
2. Download once, verify digest, decrypt if required, validate the entire batch before any external write. Never execute archive files. The offline validator returns data only and does not extract files.
3. Join all live routes under the existing single-live concurrency group. Never change shared TRIAL fields. Reserve all report/photo identities in durable private state before submission. The old 14-day artifact guard alone is insufficient for long-term duplicate safety.
4. Restore a clean app-installed emulator, verify boot and APK signatures/version/hash, inject private data and reporter credentials only now. Recheck topic/address pins and owner binding.
5. Drive one report through the normal app UI, preserving production validation and device-unlock gates. Record and encrypt diagnostics. For multi-photo reports, create exactly one case, attach every photo to that case sequentially, and persist each attachment state.
6. Reconcile server response and durable app state even when a UI test times out. Acceptance is separate from test verdict. A numeric case number may be null: preserve WebCaseId and leave the numeric field unset.
7. On ambiguous CreateCase or attachment, stop the batch, encrypt evidence and hold that identity for reconciliation. Do not create another case or blindly retry an attachment. Accepted cases never get refiled. Known pre-create failures may release only with explicit, verified pre-create proof.
8. Encrypt all evidence before upload, verify decryptability and expected inventory, persist private journal/results, then remove private files/device state. Encryption or persistence failure is a stop, never a reason to replay a report. Resume only unfinished independently safe work after explicit reconciliation.

## Clean emulator snapshot cache

This is a snapshot copied into a fresh public Linux runner, not a VM kept running. Build without secrets, photos, reporter identity, municipal responses, auth sessions, network tunnels or private data. Install app and test APKs before saving, prepare the emulator, then shut it down cleanly. Never save a cache after private injection. Cache paths are an explicit AVD/APK allowlist, never broad home, SDK, Gradle credential, log or workspace directories.

Key inputs: Android system-image revision, emulator version, architecture/API/profile, host image/toolchain identity, APK and test-APK hashes, preparation-script hash and a three-day UTC epoch. No restore-prefix fallback across incompatible keys. An embedded provenance manifest includes exact identities, clean-build marker and creation timestamp. Refuse future timestamps, age over 72 hours, mismatches or missing fields. Only trusted-main producers may publish a reusable snapshot. PR CI can test key/provenance logic but cannot publish a live-trusted snapshot.

GitHub cache is best-effort and can evict unused entries. On miss or rejection, cold-build the same verified clean image. A scheduled refresh plus on-app/tool/image change keeps the three-day requirement; verify actual age on consumption regardless of scheduler delays. Cache presence never grants filing authority. Do not widen token permissions or repository protection as part of setup.

Benchmark cold path versus restore across image acquisition, APK build, boot, install, preflight and total preparation time; record cache size and restore/publish latency. Use at least three cold and three warm mock-only runs before claiming savings. Do not promise a fix for adb disconnects or the known UI false-negative timer.

## Delivery slices and proof

- This PR: offline strict ZIP validator, private SQLite reservation/result journal and mock-only sequential controller, adversarial tests and crash injection at adapter write boundaries. Inactive.
- Journal computes its stored manifest digest from canonical UTF-8 JSON (sorted keys, compact separators, no NaN); an optional supplied canonical digest must match. This canonical binding is distinct from exact inner ZIP bytes and outer ciphertext bytes. Journal requires one persistent private database shared across batches, backed up only in encrypted form. It commits creating/attaching intent before adapter calls. There is no automatic resume or release operation; unresolved identities remain held. The executor concurrency group is still needed across separate runners. No live adapter or artifact persistence integration exists yet.
- Next: integrate persistent private journal storage and explicit reconciliation, with process-kill and disk-failure tests beyond the mock exceptions.
- Next: app/test multi-photo adapter and per-photo state, with unit + emulator tests and server stubs proving one CreateCase plus N attachments.
- Next: separate protected batch workflow and private artifact intake, with no change to current single-report workflow; full dry-run evidence, no municipal writes.
- Next: clean snapshot producer/consumer, provenance tests and measured benchmark.
- Activation: exact-head independent review, green required CI, normal merge, current owner execution approval, and one clearly scoped supervised live proof. Preserve the old route until the new one is proven.

## Acceptance tests required before activation

Unknown fields/commands, duplicate JSON/ZIP names, archive traversal/symlinks/bombs, digest mismatch, stale directory/topic IDs, malformed images, NaN coordinates, ambiguous timestamp and unauthorized category all fail before writes. Two reports with different topics and multiple photos use distinct cases; partial attachment failure never creates a replacement case. Timeouts after server acceptance cannot trigger replay. Cache contains no private bytes, invalidates on every relevant input and over-72h age, and cold fallback works. Public artifacts/logs reveal no private content. Every accepted report has encrypted evidence and durable private state even after process interruption.

Sources for cache constraints and clean-runner behavior:
- https://docs.github.com/en/actions/concepts/runners/github-hosted-runners
- https://docs.github.com/en/actions/using-workflows/caching-dependencies-to-speed-up-workflows
- https://github.com/ReactiveCircus/android-emulator-runner
