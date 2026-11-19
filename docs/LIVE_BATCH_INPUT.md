# Private batch ZIP input: author format and offline preparation

Status: inactive input preparation only. There is no new batch workflow to dispatch
and no end-to-end hybrid submission command yet. A valid ZIP does not authorize or
submit a municipal case. Do not dispatch the old single-report workflow with ZIP
fields or change shared TRIAL secrets to imitate a batch.

## Smallest useful author input (version2)

`manifest.json` can omit `schemaVersion` (defaults to2):

```json
{
  "reports": [
    {
      "text": "Exact approved report description",
      "address": "synthetic street 10",
      "topic": "synthetic topic"
    }
  ]
}
```

The example is synthetic, not a real city topic/address. Use a real, independently
verified topic and exact street+house. Alternatively supply coordinates:

```json
{
  "reports": [
    {
      "text": "Exact approved report description",
      "subjectCode": "9000",
      "latitude": 31.7,
      "longitude": 35.2,
      "photos": ["photos/a.jpg", "photos/b.jpg"]
    }
  ]
}
```

The topic code and location here are synthetic too. No photo is required by the
input format. On Nov17 the owner stated that all topics accept a case without a
photo; this is recorded as the owner's experience, not a result of API tests.
Observed CreateCase success precedes AddFile, but that alone does not prove a
per-topic municipal evidence policy. No test case was sent to check it.

### Required and optional author fields

- Root `reports`:1–50 report objects. Root `schemaVersion` optional,2 when present.
- Each report needs `text` and enough location/topic information to resolve the
  intended case. Location is `address` OR the pair `latitude`,`longitude`. Supplying both
  is allowed; supplying neither is refused. A missing/ambiguous directory match blocks
  preparation rather than picking an arbitrary address. Coordinates must be
  numeric and finite. Address is exact street+house, not street-only.
- Topic: `topic` (exact independent directory name) OR `subjectCode` (verified
  leaf code). If both are supplied they must agree. No category extraction from
  prose. Topic is not silently chosen when absent.
- `plate`: optional globally; required only when the independently supplied topic
  policy explicitly requires it. Current plain-input support is5–8ASCII digits.
  A plate-required topic also requires that plate in the exact approved text.
  No-plate topics omit it or use an empty string. Never invent a plate. These are
  conservative local/app policy checks, not proof of vendor-required fields.
- `photos`: optional, defaults to empty; up to10per report. Each item is a ZIP
  path string, or `{ "path": "photos/a.jpg", "sha256": "..." }`. Hash optional;
  when supplied it must match. No repeated photo bytes/paths in a batch.
- `capturedAt`: optional ISO8601 with offset. When absent, derive only from JPEG
  EXIF DateTimeOriginal AND OffsetTimeOriginal when every selected photo agrees.
  Missing, corrupt, unsupported or conflicting metadata leaves it null. No system
  clock/upload-time substitution; PNG/HEIC time extraction is not implemented.
  This is not a CreateCase wire field. A future app lane may need extra data to
  satisfy app readiness; a valid input alone does not prove that lane executable.
- `textSha256`, `reportId`, root `batchId`: optional machine-generated values.
  Text hash is SHA256 of exact UTF8bytes. IDs are domain-separated fullSHA256 of
  canonical effective content, stable across identical retries. Supplied values
  must match the generated value, not replace it. Renaming a photo path does not
  change report identity. Existing global photo/exact-text dedup also remains.
- Unknown keys, partial coordinate pairs, bad supplied hashes and identity
  overrides are refused. Credentials, commands, external URLs and runtime
  instructions have no place in this JSON.

These are input/transport safety checks. The municipal payload builder is a port
of a helper, not a vendor-required-field specification. Its current CreateCase
payload includes description, topic, street code/name/house, address text,
CoordinateX/Y, Language and ApplicationCaseNumber. No separate plate field,
capture-time field or photo is in CreateCase. We have not established which fields
can be omitted at the vendor for every topic. Do not call local checks municipal
requirements or make live writes to probe them.

## How derivation works

`live_batch_derive.py` uses only the SHA-pinned `addresses.json` and an independent
verified current topic policy supplied outside the ZIP. Exact unique address
points can provide coordinates. Coordinate-only input resolves a numbered address
within150m only when the next distinct address is over20m farther away. Ties,
missing points and distant matches stop. This is deliberately more conservative
than full app alias/tail lookup; no network geocoder or prose inference runs.
Caller confirmation must cover the generated location/topic before live use.

The private effective manifest retains schemaVersion2, generated IDs/hashes,
resolved address/coordinates/code, optional empty plate, null time and zero or
more photos. A confirmed CreateCase with zero planned photos marks the mock
journal complete without AddFile. Creating/ambiguous/complete entries cannot
restart; no-photo reports still reserve their generated identity and exact text.

## ZIP contents

`manifest.json`, `addresses.json` in the exact approved directory version, and only
referenced `photos/*.jpg|jpeg|png`. No directory ZIP entries, links, traversal,
extras or executables. Size/path/image-signature caps remain as documented in
[the design](plans/LIVE_BATCH_AND_CACHE.md). JPEG/PNG signatures are not full image
decoding: bounded decode/dimensions checks remain required before live staging.
Legacy `schemaVersion:1` keeps its earlier strict contract unchanged.

## Offline operator steps (working today)

1. Ground and obtain approval for exact text, topic, location and selected photos.
   Resolve ambiguities first. Preserve original photo bytes for EXIF when relevant.
2. Create `manifest.json` in author format, include the approved `addresses.json`
   and only referenced photo files in a private ZIP. Do not include a topic-policy
   file in the ZIP or let the ZIP approve its own topics.
3. Generate the package SHA256 without hand typing it (`sha256sum input.zip`).
   Bind this immutable digest to approval outside the ZIP. Approving a filename,
   mutable branch or hash generated after substitution does not bind the text.
4. Obtain the independent verified topic policy outside the ZIP as a JSON object:
   each code maps to `names` (exact strings) and boolean `plateRequired`. It is
   caller-supplied trusted configuration, not proof that the city mandates plates.
   Its provenance/currentness must be checked before any future live use.
5. Make a mode0700private output directory and run from the repository root:

```bash
mkdir -m 700 private-prepared
python3 scripts/validate_live_batch.py \
  --zip input.zip \
  --approved-zip-sha256 "$APPROVED_ZIP_SHA256" \
  --approved-addresses-sha256 "$APPROVED_ADDRESSES_SHA256" \
  --topic-policy-file /private/verified-topic-policy.json \
  --output private-prepared/effective-manifest.json
```

The operator/environment supplies the generated approved digests, not the author
manually filling report JSON. The command refuses overwrite, writes mode0600,
prints only `BATCH_VALID_OFFLINE_ONLY` or a constant failure marker, and never
calls the municipality. The private output contains personal data. Do not put it
in public artifacts/logs or a public source PR.

6. Inspect and bind the effective prepared content before live execution. Preserve
   both original package digest and enriched canonical digest in durable private
   evidence. A computed digest alone is not approval. Stop here today.

## Not implemented yet

Protected `live-batch-hybrid.yml`, actual dispatch inputs, shared live journal
storage/one-writer persistence, encrypted private artifact intake credentials,
UI/API adapters, clean-cache consumption, real failure reconciliation and a
supervised approved proof. No submission instructions are claimed until these
exist and pass review/CI. Target is1–2app/emulator reports and the rest directAPI
per independently arriving package, sharing no-refile state and one concurrency
group. No automatic fallback after any write or uncertainty.


## Next-batch acceptance criterion: zero PRs in the filing loop

The next operational batch must need **no source PR and no data PR**. One-time
implementation changes still follow independent exact-head review and green CI.
Per batch: prepare approved private ZIP, commit it directly to the existing
private data repository, bind its full immutable commit/path/outerSHA256, then
protected workflow_dispatch. Explicit approval of exact text/location/topic/photo
bytes remains required and is bound by the generated outerdigest. Input data is
not source code. No private CI run or paid runner is needed. A preparation-only
workflow does not satisfy this criterion; no live route is claimed ready until
both lanes, durable oldidentityjournal and credential/approval boundaries work.

The offline CLI publishes its effective manifest atomically without replacing an
existing file or symlink. A failed serialization leaves no partial final file.
A hard process termination may leave a mode0600temporary file inside the private
output directory, which is not an accepted effective manifest. A later run can
still publish the requested final path. The derivation CI step uses
`scripts/run_unittest_file.py`, which fails on zero tests and import/test errors.

### Directory dependency validation (refs #610)

Schema-v2 enrichment checks the consumed directory structure before either exact
address lookup or coordinate lookup. Street rows need a name/code and typed
optional aliases; address rows need finite, globally bounded numeric coordinates,
a house value and a street name. Invalid directory dependencies produce a
constant `InvalidBatch` refusal, including direct in-process derivation callers.
A matching digest does not prove the directory shape is usable. Unknown metadata
is still ignored. This is offline validation, not a trial-envelope change or an
authorization to file. Synthetic tests cover both lookup paths and ZIP ingestion.
