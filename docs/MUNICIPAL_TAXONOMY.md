# Municipal taxonomy parity

The app carries a captured snapshot of the Jerusalem 106 subject catalog
(`GetSubjectCategories`) so that local categories can be mapped to municipal
subjects offline, without any network call or municipal credential. The submission path sends the chosen code plus the approved Hebrew description.
The snapshot itself makes no network calls.

## Asset

`app/src/main/assets/taxonomy/municipal_subjects.json`:

- 86 subjects (code, Hebrew name, hierarchy path), ported from the public
  parking-report-helper `configs/subjects.json`, itself a captured
  `GetSubjectCategories` response.
- `captured_at: 2026-10-05`, `verified_live_at: 2026-11-08`: on 2026-11-08 the
  captured catalog was compared against the live municipal response and found
  identical (parking-report-helper issue #52 / PR #305 smoke check).
- `fingerprint_sha256` records the canonical fingerprint, and
  `fingerprint_rule` documents how it is computed.

The catalog is a snapshot, not a live feed. Refreshing it is a manual,
reviewed step: re-capture via the helper, compare fingerprints, review the
diff, then update the asset and the expected fingerprint together.

## Fingerprint parity

`MunicipalSubjects.canonicalFingerprint` replicates the helper's
`subjects.catalog_fingerprint` exactly:

1. Encode each row as `json.dumps([code, name, path], ensure_ascii=False)`
   (Python escaping rules; `MunicipalSubjects.pythonJsonString` reproduces
   them, including backslash and control-character handling).
2. Sort the encoded rows, join with `\n`, sha256 hex.

Unit tests compare the recomputed fingerprint against the embedded value with
cross-platform golden vectors, so an accidental asset edit fails the build on
any platform. Expected fingerprint:
`77613b820e657c43c7df6558a5f1d24c3a4bcd1ea7d691bb4bb55db2607c66ab`.

## Local category mapping

| Local category | Municipal subject | Name |
| --- | --- | --- |
| `sidewalk_parking` | 1145 | חנייה אסורה - חנייה על מדרכה |
| `broken_sign` | 17841 | תמרור תנועה נפל / דהוי / שבור / נוטה ליפול |
| `faded_crosswalk` | 1137 | חידוש סימון מעבר חצייה |
| `bollard_request` | 5164 | אחר, contextual path: גדרות, מעקות, עמודים / אחר |

### Bollard requests use generic Other, not a dedicated installation code

The captured catalog repeats `5164` under 12 paths. `MunicipalSubjectsTest` records
`גדרות, מעקות, עמודים / אחר` as the intended context, but `CreateCase` sends
only `CaseSubjectCode`. The path is not transmitted and does not guarantee a
receiving department. The approved Hebrew description must explicitly state
the installation request. `1280` is for a leaning or fallen iron barrier post,
so it is not a substitute for a new installation.

Offline inspection of the official 106+ v8.6.4 app found the same wire shape:
selection assigns `CaseSubjectCode` from the selected subject, while
`WebCaseSubjectCode` remains null. This establishes request-shape parity,
not the city's internal routing. A live generic-Other filing needs an accepted
text-based-triage route or separate routing confirmation; this mapping alone
is not that confirmation. Do not probe routing by creating a test case.

## Boundaries

- The catalog loader makes no live municipal calls and uses no credentials.
- No automatic classification: category choice remains manual.
- The catalog is municipal public data captured from the 106 app; no user
  data is involved.
