# Private real-photo OCR comparison (#210)

The manual comparison workflow never files reports, reads municipal credentials or
changes live staged inputs. Ordinary CI stages no manifest and skips private work.

- Select a recent, bounded private photo sample with expected plates grounded in the
  private report record and visual check. Record selection and duplicates privately.
- Package originals and a manifest (cases: file `sample-N.jpg`, expected canonical
  digits) in a tar archive. No original paths, plates or photos in this public repo.
- Encrypt locally with a dedicated random OCR_BENCH_KEY. Host only the encrypted
  archive; set dedicated OCR_BENCH_INPUT_URL, OCR_BENCH_INPUT_SHA256 and
  OCR_BENCH_KEY secrets. Never reuse or modify TRIAL_* / J106_* inputs.
- After exact-head green/review, dispatch main with COMPARE-ONLY. Both engines use
  the same production OutgoingEvidence-prepared bitmap, alternating engine order.
  The emulator network is off; models are bundled. No screenshots or video.
- Upload only encrypted results/logs with three-day retention. Decrypt privately,
  verify every original hash and sample count, then compare exact top-1, expected
  anywhere in candidates, empty result, errors and median/mean time. Multiple wrong
  candidates do not count as a clean extraction. Include sample size and limitations.
- Remove temporary input hosting and dedicated secrets after the comparison. Private
  encrypted evidence is not a user-facing deliverable or permission to publish photos.

After measurement, pick the better default and expose the alternative only in
Settings, with the existing telemetry disclosure. No choice in first-run setup and
no OCR result automatically approves or sends a report.

## Encrypted raw-text diagnostics

The manual benchmark records bounded raw OCR text (64,000 characters per window; a window variant joins up to 10 windows, so up to about 640,000 characters per variant/sample) alongside candidates in its private JSON output. The existing compare-only script encrypts this file and all collected output before uploading; normal CI has no staged private input and exports no OCR output. Raw text is never printed by the test. Production recognition still calls the exact same PlateCandidates filter. This diagnostic does not join numeric fragments, loosen plate matching, approve a plate or submit a report. Compare empty results against raw text privately before choosing an engine.

## Result: Tesseract digit whitelist (15 Nov 2026)

One private run, 30 photos, same prepared image per variant. Exact top-1: Tesseract plain 0, Tesseract with digit whitelist 0, ML Kit 13. Expected digits anywhere in the raw text: 1, 1 and 17. Median time 0.66 s, 0.63 s and 3.7 s. The whitelist did not help, so the option was removed again. Aggregates only.

## Variants: plate localisation by windows (#442)

The manual benchmark compares three ML Kit variants on the same prepared photo: the whole frame (`mlkit`), the whole frame plus a 3 x 3 grid of half-size windows with 25% steps (`mlkit_tiles`), and the same windows enlarged 2x (`mlkit_tiles_x2`). Candidates from all windows are ranked by how many windows read the same number (`PlateCandidates.rank`); the usual strict filter still decides what counts as a candidate. These variants are measured only here and are not used by the app. Cost is reported as median time per photo. Aggregates only, no plates, photos or raw text in any public place. Tesseract is not run in this comparison (the earlier runs settled it). The job timeout is 90 minutes because the window variants make 10 ML Kit calls per photo each (about 25 to 30 minutes estimated for 30 photos, plus build and emulator boot).

## Result: plate localisation by windows (15 Nov 2026)

One private run (main at 485d590), 24 photos (only 24 distinct usable plates were left outside the two earlier samples), same prepared image per variant. Aggregates only.

| Variant | Exact top-1 | Expected digits in raw text | No candidate | Wrong top-1 | Median time per photo |
| --- | --- | --- | --- | --- | --- |
| `mlkit` (whole frame) | 11 / 24 | 16 / 24 | 13 | 0 | 3.5 s |
| `mlkit_tiles` (3 x 3 windows) | 17 / 24 | 20 / 24 | 6 | 1 | 25.8 s |
| `mlkit_tiles_x2` (windows enlarged 2x) | 18 / 24 | 20 / 24 | 3 | 3 | 31.5 s |

Windows raise exact reads from 46% to 71% (75% enlarged) and cut empty results, at 7 to 9 times the time per photo. The enlarged variant adds wrong answers. The sample is small, so the numbers are indicative. Whether to use windows in the app is a separate owner decision (cost, battery, telemetry disclosure); nothing in the app changed.
