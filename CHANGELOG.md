# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog and the project uses Semantic Versioning: bug fixes bump PATCH, backward-compatible features bump MINOR, and breaking changes bump MAJOR.

Every release gets a `v<version>` git tag, a matching entry here, and release notes in Hebrew and English. Every bug fix ships with a regression test.

## [Unreleased]

## [0.53.1] - 2026-11-19

### Fixes

- fix: clean disposable emulator read marker before cache validation (6b4f004)
- fix: honor ELF declared load alignment (refs #84) (2560702)
- fix(intake): refuse normalized malformed EXIF offsets (#556) (0e1e0c9)
- fix(hybrid): reject malformed identities before preflight (#558) (69ed01b)
- fix(cache): reject invalid and repeated benchmark evidence (#548) (92eb5b6)
- fix: keep regular plate band padding and theme-visible border (refs #391) (16f6138)
- fix: stream pinned address assets within the API29 heap (6c553be)
- fix: require shipping address asset and correct data licence notice (6570b05)
- fix: bundle owner-confirmed OSM address default and licence entry (ab205e6)
- fix: gate unavailable address search and support verified private import (98ac736)
- fix(review): preserve diagnostic status and run complete snapshot test inventory (f4a2fe9)
- fix: preserve Hebrew reporter names across private Java bridge environment (52778ca)
- fix(drafts): inset save feedback above system navigation (b7aeb85)
- fix(drafts): make persistent save feedback visibly contrasted (68fbb72)
- fix(vpn): delete only unchanged run-generated host config (#623) (0db2813)
- fix(secrets): shorten parent identity environment lifetime (#648) (8d6ab27)
- fix(intake): validate derivation dependencies at entry (#610) (9e734dc)
- fix(diagnostics): avoid global replacement of short numeric flags (#233) (c82d9d5)
- fix(drafts): retain unfinished offline address entry (#660) (d7aa5e5)
- fix(proof): reject success footers before final test status (#338) (28ee9f0)
- fix(cache): classify rejected directories without leaking names (#635) (c6f8b31)
- fix(input): refuse symlinked staging ancestors (#625) (00bdf99)

### Other changes

- Merge pull request #573 from reihlinstinct/dependabot/github_actions/reihlinstinct/repo-checks/actions/privacy-1.1.1 (9b6c819)
- Merge pull request #685 from reihlinstinct/diag/api29-retained-heap-684 (b3eb973)
- Merge pull request #691 from reihlinstinct/test/api29-recovery-proof (52c14e2)
- Merge main and make unmitigated heap experiments manual while keeping normal CI gates (a2f4299)
- Merge remote-tracking branch 'origin/main' into test/api29-recovery-proof (715d76e)
- Merge pull request #693 from reihlinstinct/fix/snapshot-producer-read-marker (8188518)
- Merge pull request #687 from reihlinstinct/ci/bounded-ksp-resolution (3877f54)
- Merge pull request #676 from reihlinstinct/feat/overnight-third-batch (df83dc8)
- Merge pull request #670 from reihlinstinct/feat/next-twenty-offline (dec5e1b)
- Merge main into next-twenty work; retain both hybrid boundary regressions (c5fdf90)
- Merge pull request #689 from reihlinstinct/ci/pally-reviewer (e822961)
- ci: run API29 diagnostic regressions on every code PR (refs #684) (339e0aa)
- ci: run pinned KSP retry regressions in repository checks (refs #686) (f3d622b)
- test: bound natural API29 recovery proof to eight boots (refs #684) (2a19034)
- Merge remote-tracking branch 'origin/main' into ci/pally-reviewer (364c884)
- Merge pull request #690 from reihlinstinct/ci/api29-heap-preflight (d5b73cb)
- ci: validate actual API29 heap before the full suite (refs #684) (2303afe)
- test: align synthetic UI coordinates with municipal envelope (a218673)
- ci: recognize Pally independent exact-head reviews (503cc08)
- Merge pull request #575 from reihlinstinct/dependabot/github_actions/actions/setup-java-6.3.0 (487baad)
- Keep emulator action dispatch on one standalone shell line (f0615f1)
- Make optional init evidence collection permission-safe (37dc1db)
- Probe API29 zygote startup budget with bounded synthetic controls (fe0c6cc)
- Refresh negative resolution metadata on bounded KSP retries (27eaec0)
- Make heap diagnostics reject invalid dumps and compare one device (422025e)
- ci: bound retries to pinned KSP configuration failures (6ad7fff)
- test: control reset effects and preserve exact diagnostic argument guard (31c2bff)
- test: measure API29 retained heap and isolate suite history (2f16912)
- Merge pull request #677 from reihlinstinct/feat/overnight-fourth-batch (92a7259)
- test(fetch): reach symlink guard with encrypted fixture after main integration (1c06936)
- Merge main after #669, preserving redirect and symlink regressions (74258aa)
- Merge current main with reviewed loader fix into PR 670 (46c41fe)
- Merge pull request #669 from reihlinstinct/feat/twenty-issue-safety-batch (e9fbcf1)
- test(prpkg2): reject nonfinite numeric schema inputs (#10) (a4231eb)
- docs: define preview signing and non-destructive update acceptance (refs #673) (fe2a215)
- test: derive tag side contour probes from rendered alpha (refs #355) (23f95f2)
- test: add bounded ELF 16KiB alignment precheck (refs #84) (e3b1a4d)
- docs: define private held-intent reconciliation decision record (refs #615) (e81c5e5)
- docs: assess input-read and claim-write credential separation (refs #607) (faca736)
- test: refuse every redirect class for encrypted batch input (refs #612) (222de32)
- docs: assess cache filename privacy and bounded purge design (refs #375) (d63478a)
- docs: specify fail-closed VPN persistence recovery design (refs #609) (608665a)
- docs: assess separate claim and evidence key domains (refs #608) (7417591)
- test: pin one selected picker identity across duplicate paths (refs #362) (37499ba)
- test: cover vehicle-first pending tag accessibility state (refs #397) (672b01f)
- Merge branch 'main' into feat/twenty-issue-safety-batch (166160f)
- Merge pull request #672 from reihlinstinct/fix/private-address-install (1c11a29)
- chore(deps): bump actions/setup-java from 6.1.0 to 6.3.0 (8b8843f)
- Merge pull request #671 from reihlinstinct/fix/bridge-unicode-environment (9b6ca2f)
- test: clear copied location and provide licence catalog in fixtures (62eb04a)
- test: enter address through UI on main-based fixture (01f7d54)
- test: retain disabled lookup screenshot with emulator artifacts (2a61aab)
- test: prove default asset resolves without import on device (e0953a4)
- test: pin bundled data notices and capture unavailable search state (6050ddb)
- test: run new HTTP and coverage-scope regressions in offline CI (c3bfb2c)
- #649: pin single-attempt initial-claim refusal; live create-only proof remains separate (7931bcd)
- #241: tidy time presentation regression annotations without changing DST refusal (a4cc48d)
- #44: measure only report-level JaCoCo counters, never nested class totals (b818496)
- #630: reject ambiguous duplicate keys and nonfinite private bridge responses (08a8afe)
- #626: prove absent UI adapter cannot reserve or fall back to direct writes (442ad7a)
- #632: reject malformed and reused port identities before API preflight (c7e7563)
- #376: retain native crash and memory diagnostics without weakening gates (ce9e077)
- #503: pin exact returned identifier and municipality-local detail date (fec4c2e)
- #498: regress history-only filtering with ten thousand synthetic rows (7b66292)
- #234: pin invalid legacy identity and blank-means-keep behavior (7554c43)
- #522: cover no retry for create and attach under HTTP 429 and 500 (57092a2)
- #650: document compiled versus mocked proof and complete staging test contract (c22330c)
- #243: raise upload throughput floor and identify refusal host (8bb7e1c)
- #381: include root build and debug source in display gate (6564999)
- #359: save and restore real keyboard setting around inset tests (c1e13f5)
- #409: retain full-device IME evidence on settings viewport failure (d3fa11e)
- #400: wait for visible settings reason after IME viewport settles (846a58a)
- #444: wait for refresh result UI rather than callback start (92add37)
- #452: restore lock timeout after deterministic device dry check (a438539)
- #638: default shared staging to encrypted input; explicit offline exception (101e60e)
- test(evidence): preserve ciphertext through upload and reconciliation (#621) (6d9dcb4)
- test(vpn): pin kill-switch enforcement distinct from DNS check (#622) (f3d189b)
- test(vpn): cover interrupted teardown and idempotent absence (#624) (fcd7867)
- test(schema): isolate legacy mandatory plate validation (#627) (7a7af89)
- test(dependencies): guard strict standalone verification metadata (#662) (7f78512)
- test(activation): reject each workflow/config pin drift (#652) (3ac94d9)
- test(expiry): pin microsecond cutoff at each effect boundary (#647) (7feb67a)
- test(bridge): pin integer ITM payload contract (#637) (bd8028f)
- test(trial): reject approved-hash PNG bytes at JPEG boundary (#617) (71ee4e0)
- test(trial): prove inclusive coordinate envelope parity (#611) (723969b)
- test(ocr): cover alternate roots references and spaced logging (#240) (742d5c3)
- test(input): pin encrypted ZIP and malformed directory rejection (#339) (33ef857)
- Merge pull request #668 from reihlinstinct/feat/per-submission-branch-lane (82af435)
- Add one-case ephemeral exact-main submission branch lane (5f00ba5)
- Merge pull request #666 from reihlinstinct/fix/663-photo-attachment-only (39bc2b5)
- Fix zero CRM receipt photo attachment using exact returned web reference (#663) (6bebca9)
- Two fixed CI car cases, once each with durable accepted web receipts (#664) (#665) (708f836)
- Isolate supervised API bridge from Android app compilation (#658) (#659) (c986dfb)
- chore(deps): bump reihlinstinct/repo-checks/actions/privacy (888b9fb)

## [0.53.0] - 2026-11-18

### Features

- feat: bounded compressed journal capacity (inactive) (#580) (0527818)
- feat: bounded API outgoing-photo preparation (inactive) (#601) (c88037d)
- feat: API-only sequential batch controller (inactive) (#599) (56fc559)
- feat: immutable read-only history and ledger audit (#582) (e511671)
- feat: hold legacy identity aliases with provenance (offline only) (#572) (3912b08)
- feat(batch): inactive UI boundary adapter and gated municipal client (#558) (#564) (9a8e3da)
- feat: inactive hybrid CAS journal, controller and production API bridge (#559) (6fdf360)
- feat: minimal optional batch JSON and safe runtime derivation (inactive) (#557) (4b260af)

### Fixes

- fix: prove pre-case failure before permitting a same-photo retry (#595) (4db7182)

### Other changes

- chore(deps): bump filelock from 4.2.9 to 4.2.10 (#576) (7913e03)
- Activate one pinned expiring supervised API trial, no replay (#645) (28c44bc)
- chore(deps): bump com.google.ai.edge.litert:litert from 1.4.2 to 2.3.2 (#574) (992f157)
- chore(deps): bump com.google.devtools.ksp from 2.3.13 to 2.3.14 (#578) (97ec1e0)
- chore(deps): bump gradle-wrapper from 9.9.0 to 9.9.1 (#577) (2e49861)
- Use in-envelope synthetic success fixture and record activation prerequisites (#640) (42f7406)
- Recognize Tab in trusted exact-head independent review allowlist (#614) (3cde8e5)
- Add fixed-vocabulary snapshot rejection diagnostics without relaxing validation (#606) (e8c1dce)
- Add inactive one-incident GPS-only API trial with durable private claim (#604) (9c22e44)
- data: stage encrypted single-request input without dispatch (#594) (bb08c2c)
- data: stage encrypted single-case input without dispatch (#593) (bc1aba6)
- data: stage encrypted single-case input without dispatch (#592) (d7ee3b8)
- data: stage encrypted single-case input without dispatch (#591) (08f16f2)
- data: stage owner-approved encrypted single-case input (#590) (78ba853)
- data: stage owner-approved encrypted single-case input (#589) (6675c2b)
- data: encrypted single-case input 18-2 (no dispatch) (#588) (e9dbec2)
- data: encrypted single-case input 18-1 (no dispatch) (#587) (0afc93c)
- test: exercise generated category AppFunction through real system binder (#570) (b7907bd)

## [0.52.0] - 2026-11-17

### Features

- feat(municipal): multi-photo submission path, one CreateCase plus N AddFileToCase (#548) (#552) (bc6db9a)
- feat: inactive serialized recovery writer fence (#358) (#542) (aaa22d6)
- feat: inactive one-use recovery auth request identity (#358) (#541) (9ffe741)
- feat: inactive strict recovery snapshot codec (#358) (#540) (2186ada)
- feat: inactive recovery envelope and retention policy (#358) (#539) (c59f3ac)
- feat(reports): details screen for a sent report (#503) (#504) (a651b66)

### Fixes

- fix: preserve fail-closed pre-start proof before diagnostics shred (#555) (038351e)
- fix(review): retain plate-edit provenance after rotation (#358) (#537) (c91fa30)
- fix(mock): reject non-object JSON under optimized Python (#532) (#533) (2c00cc3)
- fix(ci): validate commit status by returned URL context and state (#534) (#535) (c912e5a)
- fix(intake): preserve typed rejection for malformed UTF-8 (#527) (#528) (fa0d1cb)
- fix(intake): OfflineReceiver.open rejects JSON arrays with the typed IllegalArgumentException (#514) (#515) (4ee2d3b)

### Other changes

- docs: trial a read-only repo-specific Copilot reviewer (#568) (940ec82)
- Convert resolved trial topics to canonical draft identity before readiness gate (#566) (030eb3b)
- Stage one owner-approved encrypted report photo for CI direct route (#561) (79ff148)
- Allow captured numeric catalog topics in direct CI engine boundary (#563) (fcc11e9)
- Inactive batch intake contract, ZIP validator and clean cache design (#549) (e0e253a)
- Stage final encrypted approved live input (#554) (57cad07)
- Clean emulator snapshot cache producer, consumer and mock benchmark (inactive) (#550) (64d09ff)
- Stage next encrypted approved live input (#551) (dad08ce)
- Stage next encrypted approved live input (#547) (b630cba)
- Keep encrypted live diagnostics when instrumentation output is empty (#544) (9bb0416)
- Stage next encrypted approved live input (#546) (229300a)
- Stage next encrypted approved live input (#545) (e9dedc0)
- Stage encrypted one-report live input bundle (#543) (f55fc0e)
- docs: encrypted expiring process-death review recovery design (#358) (#538) (657e9ab)
- test: exercise review recreation through real activity and ledger (#419) (#536) (18684d5)
- ci: Independent review gate check for auto-merge (#518) (#519) (9b40a9a)
- ci: ClusterFuzzLite JVM receiver fuzzing with valid synthetic corpus (#513) (#526) (1f1b2b5)
- test: mock municipal 106 API server with failure scenarios and request counters (#523) (#524) (32ce753)
- test(settings): bounded visibility wait and failure screenshot (#409) (#529) (03fbaab)
- test: migrate to mockwebserver3 with matching socket effects (#83) (#531) (61ce0ff)
- test(device): wait for visible SystemUI PIN entry before one unlock attempt (#452) (#530) (767975b)
- docs(wiki): Hebrew user guide with synthetic screenshots; PR template Wiki checkbox (#520) (#521) (a641d0f)
- ci: add five PR-only concurrency cancellation groups (#525) (523c376)
- ci(coverage): production-only JaCoCo denominator and a 50% line-coverage ratchet floor (#44) (#512) (3131294)
- docs(security): 14-day acknowledgement commitment and explicit report channels (#516) (#517) (c5fae2a)
- test(live): seed synthetic earlier submissions in the history tab (#501) (#502) (a90105c)
- docs(prpkg2): JSON Schema, golden values, spec test and version policy (#10) (#507) (c99bc44)
- docs: runtime architecture, design and registry docs aligned with the code (#508) (#509) (bdccc46)
- ci(security): scope contents: write in manual-gpg-signing to its single job (#73) (#505) (6f801b7)
- ci(coverage): fix the empty JaCoCo report and fail the unit summary when no percentage can be measured (#44) (#506) (62922a1)
- test(registry): UI test for the inactive-record line in he and en (#403) (#500) (687412d)

## [0.51.0] - 2026-11-16

### Features

- feat(ui): crossing street entry, nearest-address result and description line (#492) (21d63b6)
- feat(municipal): nearest numbered address for a street crossing (#487) (#488) (56a49a2)
- feat(ui): server-returned case number on the success screen in every build (#490) (3b52286)
- feat(ui): report history lists sent reports by case number, else WebCaseIdInCRM (#493) (#494) (1991229)
- feat(ui): full-screen report-submitted confirmation; debug-only server receipt line (#478) (#480) (3a9b033)
- feat(review): show the category suggestion hint with confidence and a change-category button (#109, #105) (#466) (c291e2d)
- feat(core): CategorySuggester interface, low-confidence stub and visible-hint rules (#109, #105) (#463) (99192e5)
- feat(assistant): OPEN_APP_FEATURE voice shortcut to the new-report deep link (#149 step 1) (#458) (4b8380b)
- feat(ocr): benchmark ML Kit window variants for plate localisation (#442) (#443) (485d590)

### Fixes

- fix(reports): a sent report appears only in the history (#498) (#499) (976b9e3)
- fix(review): category hint follow-ups from #466 (#486) (9fe4b19)
- fix(municipal): address lookup matches points named by the tail of the street name (#484) (ee64b8a)
- fix(ui): adjustResize for API 29 keyboard insets; run API 29 in PR checks (#472) (2014ec8)
- fix(review): hide the stub's constant confidence percent in the category hint (#109, #105) (#468) (164ef70)
- fix(review): restore edit panel, plate suggestions, manual address and discard dialog after rotation (#358, #419) (#461) (35ea1b6)
- fix(security): recover SecureSettings from a permanently invalidated Keystore key (#132) (#459) (dbd1fe7)
- fix(assistant): roll back a partial enable, test categoryEntryAction, document deep link trust (#390) (#460) (f62d4d8)
- fix(plate): include minus sign, maqaf and narrow spaces in the CD/CC dash run (#451) (#453) (5df4cdf)
- fix(plate): tighten the CD/CC and police boundaries in mentions (#445) (#450) (c99c6ec)

### Other changes

- test(live): show the report-history tab after the submission in the live recording (#496) (1452117)
- ci(live): stage the encrypted input bundle for the owner-approved pavement-island request of 16 Nov (#467 pattern) (#481) (#482) (547bbe7)
- test(live): confirm the location by street and house when the photo has no GPS (#477) (#479) (e4944fa)
- ci: rotating emulator slices over a month (#475) (a8ac524)
- ci(live): stage the encrypted input bundle for the owner-approved bollard request of 16 Nov (#467 pattern) (#476) (de39eea)
- test: wait for window focus before pressBack in the design back test (#444) (#473) (55471c3)
- ci(live): key the duplicate guard on text and photo; skip the burn only with device-side proof (#470) (78f8759)
- ci(live): stage the encrypted input bundle for the owner-approved live app submission of 16 Nov (#467) (3db6d12)
- test(ci): exercise the real recovery failure path and make the preflight summary best-effort (#388) (#465) (08de4c4)
- test(ocr): count bounds and raw text sources per engine file (#448) (#464) (2d799f7)
- test(settings): pin phone display mapping and international fallback (#235) (#462) (48f8bcc)
- docs: voice entry phase 2 proposal (#149) (#457) (b56e426)
- Pin remaining AppShell state-writing launches to the main dispatcher (#386) (#456) (a5a48fc)
- Normalize OfflineReceiver malformed JSON to the typed rejection (#353) (#455) (68e1e05)
- test(ci): log the dry-check cleanup result and fix the doc order (#452) (#454) (88799de)
- test(ci): leave the emulator awake and unlocked after the device-ready dry check (#444) (#449) (3ce0e35)
- docs(ocr): record the window localisation benchmark result (#442) (#447) (1038dcd)

## [0.50.0] - 2026-11-15

### Features

- feat(ocr): optional Tesseract digit whitelist, off by default, plus benchmark variant (#435) (#440) (1b3ad72)
- feat(ocr): Google ML Kit is the default engine, Tesseract is a Settings-only choice (#210) (#437) (6b93177)
- feat(review): suggest the description from the approved templates in the review screen (#424, slice 2b) (#434) (aa9e677)
- feat(core): approved Hebrew templates, shipped mapping and template session (#424, slice 2a) (#430) (45a5db2)
- feat(review): experimental on-device sidewalk-parking suggestion, pri… (#421) (d7e15c5)
- feat(review): read the plate type from the typed text, remove the type picker (#428 rebased onto main after #427) (#428) (79a4ff4)
- feat(core): description template engine - parts, per-sentence untouch… (#425) (69dc486)
- feat(review): plate type picker hidden by default in edit mode, shown… (#427) (f23c61a)

### Fixes

- fix(plate): match the plate as a whole token in the report text (#417) (#438) (b329542)
- fix(review): the rotation holder and the shell each keep their own timestamp (#419) (#439) (8d44574)
- fix(ocr): accept the separators and flag letter ML Kit really returns in plate candidates (#436) (fac8a5d)
- fix(review): test the late-fix guard and document when freshCapture is evaluated (#423) (#429) (55a6ae2)
- fix(live-e2e): wake, unlock and clear system dialogs before the flow; dry check in normal emulator CI (#426) (93a353d)

### Other changes

- chore(ocr): remove the Tesseract digit whitelist option, it did not help (#435) (#441) (360c7a1)
- ci(live): stage the encrypted input bundle for the next owner-requested live app submission (#433) (20481de)
- docs(github): PR template asks for a docs check (README, design, accessibility, wiki source) or a linked docs issue (#431) (23766fc)

## [0.49.0] - 2026-11-13

### Features

- feat(review): optional use-my-current-location button with a runtime … (#418) (3349679)

### Fixes

- fix(review): keep the in-progress review across rotation only, in pro… (#411) (88be945)

### Other changes

- docs(release): say perf and revert commits also trigger the daily release (#416 review follow-up) (#420) (9f96b1d)
- ci(live): stage the encrypted input bundle for the next owner-chosen live app test (#422) (f8b0a36)
- ci(release): release on the release label, once a day when a feat/fix… (#416) (f8740c8)

## [0.48.7] - 2026-11-12

### Other changes

- docs(github): PR template gets an Acceptance criteria section next to the pre-merge checklist; bug template uses checkboxes (#415) (3ec78a1)

## [0.48.6] - 2026-11-12

### Other changes

- Feat/plate class picker (#414) (c6a9959)

## [0.48.5] - 2026-11-12

### Fixes

- fix(catalog): withCheckTime recomputes the date-known flag, copy keeps the old one (#357) (#413) (8a867c6)

## [0.48.4] - 2026-11-12

### Other changes

- test+docs: compare the plate-flag fingerprint against parsed rows, point the KDoc at the app/helper differences (#385) (#412) (de4fec9)

## [0.48.3] - 2026-11-12

### Fixes

- fix(plate): manual field rejects letters, OCR skips runs next to mem/… (#410) (fdf2aee)

## [0.48.2] - 2026-11-12

### Fixes

- fix(review): automatic OCR plate prefill is not an unsaved edit (refs #358, part 1) (#407) (b6825a1)

## [0.48.1] - 2026-11-12

### Other changes

- ci: CodeQL advanced setup with explicit Gradle compile, weekly and manual only (refs #395, #60) (#408) (62dc346)

## [0.48.0] - 2026-11-12

### Features

- feat(ui): distinct limiter wording instead of generic unavailable (#369) (#393) (53e9448)

## [0.47.3] - 2026-11-12

### Fixes

- fix(review): hide the soft keyboard on finish-editing, send and Done (closes #256) (#406) (e11b3d1)

## [0.47.2] - 2026-11-12

### Fixes

- fix(registry): persist the inactive flag in the fallback cache, fail closed on entries without it (#404) (#405) (4666596)

## [0.47.1] - 2026-11-12

### Other changes

- docs+test: name #399 in Known gap, move the unverified regular-plate source out of Verified, use synthetic plate strings (#354) (#402) (dcd6f7e)

## [0.47.0] - 2026-11-12

### Features

- feat(ui): say when the vehicle record comes from the inactive registry (#366) (#394) (bf60130)

## [0.46.2] - 2026-11-12

### Other changes

- docs+test: verified plate-class sources, special-looking plates stay unstyled and unqueried (#354, not closing) (#396) (fdf766d)

## [0.46.1] - 2026-11-12

### Other changes

- test: wait for the live relabel and the missing-fields reason in two Settings UI tests (#400) (#401) (1c84018)

## [0.46.0] - 2026-11-12

### Features

- feat(ui): neutral pending tag state while the vehicle half is resolved (#365) (#392) (aca1e23)

## [0.45.0] - 2026-11-12

### Features

- feat(ui): regular Israeli plate styling, first slice of #354 (#384) (2e118ed)

## [0.44.0] - 2026-11-12

### Features

- feat(assistant): typed startNewReportInCategory AppFunction, default off (#190 first slice) (#389) (bc3c71c)

## [0.43.6] - 2026-11-12

### Fixes

- fix(ui): reviewed light/dark near-expiry warning tokens with pinned contrast (#367) (#387) (8f0c5ac)

## [0.43.5] - 2026-11-12

### Fixes

- fix(ci): disclose bounded emulator recovery in job summary (#379) (5a241a0)

## [0.43.4] - 2026-11-12

### Other changes

- test: pin helper fingerprint parity and document plate-flag difference (#368) (#383) (70f845c)

## [0.43.3] - 2026-11-12

### Fixes

- fix(topics): confine catalog bootstrap state writes to the main dispatcher (#382) (2f5ea36)

## [0.43.2] - 2026-11-12

### Fixes

- fix(home): show capture actions before long explanatory copy (#377) (ebe8904)

## [0.43.1] - 2026-11-12

### Fixes

- fix(ci): run display gate for UI outside ui and app configuration (#378) (e1e0c73)

## [0.43.0] - 2026-11-12

### Features

- feat(assistant): opt-in AppFunctions entry into visible reporting flow (#320) (efe3d4a)

## [0.42.0] - 2026-11-12

### Features

- feat(registry): private expiry-bounded cache as always-live lookup fallback (#361) (4f31692)

## [0.41.2] - 2026-11-12

### Other changes

- docs: refresh current preview status and README claims (#374) (fcafe15)

## [0.41.1] - 2026-11-12

### Other changes

- ci(display): targeted compact200% coverage for risky UI changes (#324) (0ff3ed2)

## [0.41.0] - 2026-11-12

### Features

- feat(review): registry status below relevant vehicle plate (#333) (9b5dc39)

## [0.40.3] - 2026-11-12

### Fixes

- fix(picker): readable duplicate paths and unclipped Hebrew search (#326) (c059fc9)

## [0.40.2] - 2026-11-12

### Fixes

- fix(review): protect unsaved edits on explicit and system back (#323) (062bf3e)

## [0.40.1] - 2026-11-12

### Fixes

- fix(insets): keep scroll actions above the real keyboard (#325) (da05990)

## [0.40.0] - 2026-11-12

### Features

- feat(home): side-by-side camera and gallery photo actions (#321) (9fc35bf)

## [0.39.2] - 2026-11-12

### Fixes

- fix(catalog): unknown check date without an invented age (#322) (d3c8b7d)

## [0.39.1] - 2026-11-12

### Fixes

- fix(a11y): tag boundaries on dark and light surfaces (#350) (9cab9e0)

## [0.39.0] - 2026-11-12

### Features

- feat(registry): bounded public disability tag adapter (#351) (8c0af2c)

## [0.38.3] - 2026-11-12

### Fixes

- fix(intake): validate and bind one outgoing report time (#349) (c902dff)

## [0.38.2] - 2026-11-12

### Other changes

- ci(live): next approved filing uses API36 emulator (#346) (6c14c31)

## [0.38.1] - 2026-11-12

### Fixes

- fix(review): remove time controls and derive local report time (#343) (e70e595)

## [0.38.0] - 2026-11-12

### Features

- feat(assets): original accessible disability-tag vectors (#342) (13cc40c)

## [0.37.0] - 2026-11-12

### Features

- feat(ci): use digest-pinned encrypted live input bundle (#337) (b21fefb)

## [0.36.6] - 2026-11-12

### Fixes

- fix(ci): require successful final upgrade proof runner footer (#335) (bff7102)

## [0.36.5] - 2026-11-12

### Other changes

- ci(upgrade): keep real old-to-new namespace install proof (#328) (fb04c14)

## [0.36.4] - 2026-11-12

### Fixes

- fix(e2e): allow exact numeric municipal topic for live app trial (#330) (c289a08)

## [0.36.3] - 2026-11-12

### Other changes

- refactor(android): use project-owned source namespace, keep installed identity (#319) (58fee92)

## [0.36.2] - 2026-11-11

### Other changes

- ci: bounded emulator health gate and periodic 200% checks (#314) (#316) (f0cf7a8)

## [0.36.1] - 2026-11-11

### Fixes

- fix: show every municipal path for duplicate picker codes (#313) (f11c883)

## [0.36.0] - 2026-11-11

### Features

- feat: stable Android design updates and opt-in dynamic palette (#303) (#309) (81e68b7)

## [0.35.0] - 2026-11-11

### Features

- feat: searchable city-sourced topic catalog with last-cache offline mode (#301) (#307) (ac6a228)

## [0.34.9] - 2026-11-11

### Other changes

- ci: weekly compact 200% and foldable API36 screenshot checks (#304) (#305) (b3a1a94)

## [0.34.8] - 2026-11-11

### Other changes

- ci(emulator): per-PR API 26 + 37, weekly mid-version matrix (#297) (#302) (df0b489)

## [0.34.7] - 2026-11-11

### Fixes

- fix(settings): grey the save button with visible, announced reasons instead of silent no-ops (#295) (#296) (68bbfab)

## [0.34.6] - 2026-11-11

### Fixes

- fix(ui): reframe the app as reporting to Jerusalem 106, not "free sidewalks" (#299) (#300) (c4847e6)

## [0.34.5] - 2026-11-11

### Other changes

- docs(accessibility): add the maintainer email as a feedback contact (#298) (ca29db7)

## [0.34.4] - 2026-11-11

### Other changes

- build(deps): bump com.android.application from 9.5.0 to 9.5.1 (#269) (030105c)

## [0.34.3] - 2026-11-11

### Other changes

- build(deps): bump actions/setup-java from 6.1.0 to 6.2.0 (#268) (23b8e88)

## [0.34.2] - 2026-11-11

### Other changes

- build(deps): bump github/codeql-action/upload-sarif (#267) (5d39611)

## [0.34.1] - 2026-11-11

### Other changes

- build(deps): bump gradle-wrapper from 9.8.2 to 9.9.0 (#270) (4020310)

## [0.34.0] - 2026-11-11

### Features

- feat: #88 inactive journal commit protocol and fault tests (#289) (ebaeac2)

## [0.33.1] - 2026-11-11

### Fixes

- fix: selected Ai06 launcher proportions and equal strokes (#291) (6f3ec6c)

## [0.33.0] - 2026-11-11

### Features

- feat: #88 inactive authenticated migration-journal codec (#288) (2c6ab3e)

## [0.32.0] - 2026-11-11

### Features

- feat: #88 inactive encrypted DAO and session repository (#284) (1694b79)

## [0.31.2] - 2026-11-11

### Other changes

- test: pin private-photo staging redirect and size boundaries (#293) (8e45487)

## [0.31.1] - 2026-11-11

### Other changes

- test: await reloaded outgoing preview before disclosure send (#294) (af457e3)

## [0.31.0] - 2026-11-11

### Features

- feat: inactive default-off per-photo donation consent model (#287) (8f2cc11)

## [0.30.4] - 2026-11-11

### Other changes

- docs: built-in opt-in training donation, no collection activation (#286) (d942341)

## [0.30.3] - 2026-11-11

### Other changes

- docs: stage ledger encryption storage wiring behind activation gates (#282) (caa72bf)

## [0.30.2] - 2026-11-11

### Other changes

- test: first-send disclosure revision and reload before explicit resend (#244) (#279) (553166c)

## [0.30.1] - 2026-11-11

### Other changes

- ci: stage digest-bound live photos from immutable private inputs (#281) (5c8037a)

## [0.30.0] - 2026-11-11

### Features

- feat: #88 inactive typed encrypted storage codec (#283) (6833775)

## [0.29.1] - 2026-11-11

### Other changes

- test: pin release/wiki write grants and refresh evidence (#71) (#290) (0437bb0)

## [0.29.0] - 2026-11-11

### Features

- feat: #88 pure restart-state planner, no storage activation (#285) (6b782ef)

## [0.28.0] - 2026-11-11

### Features

- feat: add isolated authenticated ledger record envelope (#88) (#280) (b49d045)

## [0.27.6] - 2026-11-11

### Other changes

- test: verify outgoing pixels for all eight EXIF orientations (#278) (6693c20)

## [0.27.5] - 2026-11-10

### Fixes

- fix: exclude private diagnostic append from network duration (#238) (#277) (17eb271)

## [0.27.4] - 2026-11-10

### Other changes

- test: fail clearly on Gradle wrapper pin drift (#237) (#276) (ce585f5)

## [0.27.3] - 2026-11-10

### Fixes

- fix: submit the exact EXIF-free review image in app (#201) (#273) (890cc5c)

### Other changes

- ci: run offline OCR network-isolation suite (#245) (#275) (af878b9)
- test: execute actual private OCR input-integrity gate (#247) (#274) (a394fa6)

## [0.27.2] - 2026-11-10

### Fixes

- fix: install pinned directory before live emulator filing (#272) (56b6e09)

## [0.27.1] - 2026-11-10

### Fixes

- fix: surface fast local submit refusals in the E2E driver (#271) (4973431)

## [0.27.0] - 2026-11-10

### Features

- feat(review): send is the single confirmation; drawer icon proof in e2e (#266) (c817e34)

## [0.26.13] - 2026-11-10

### Other changes

- diagnostics: trace the send tap from semantics click to submit gate (#265) (1aa29fc)

## [0.26.12] - 2026-11-10

### Fixes

- fix(live-driver): re-tap a no-op send click only while pre-network (#264) (ea40127)

## [0.26.11] - 2026-11-10

### Other changes

- Add event-driven advisory Gemini review caller (#254) (6711d49)

## [0.26.10] - 2026-11-10

### Other changes

- docs: ledger encryption design (#88) (#255) (06f8350)

## [0.26.9] - 2026-11-10

### Fixes

- fix: block send with a note when the device has no lockscreen (#262) (44e4f0a)

## [0.26.8] - 2026-11-10

### Fixes

- fix(driver): set throwaway PIN and complete device credential confirmation (#261) (6e9c4cb)

## [0.26.7] - 2026-11-10

### Other changes

- test(driver): wait for OCR idle + enabled send, dismiss IME before send tap (#259) (3ee0625)

## [0.26.6] - 2026-11-10

### Fixes

- fix: remove redundant review time and address notes (#258) (da61cc8)

## [0.26.5] - 2026-11-10

### Other changes

- Capture encrypted live process and crash diagnostics (#252) (c1ed930)

## [0.26.4] - 2026-11-10

### Other changes

- Make the catalog the single language source (#221) (ea07e5f)

## [0.26.3] - 2026-11-10

### Other changes

- Wait for synthetic import before negative-send cleanup (#253) (463b4f3)

## [0.26.2] - 2026-11-10

### Other changes

- Use first-person ID-card mode label (#251) (2059198)

## [0.26.1] - 2026-11-10

### Fixes

- fix: prove network isolation before private OCR staging (#242) (ecd436f)

## [0.26.0] - 2026-11-10

### Features

- feat: reviewed passport and international phone compatibility (#225) (cc019ec)

## [0.25.1] - 2026-11-10

### Other changes

- chore: re-encrypt trial photo under rotated TRIAL_DATA_KEY (#246) (920f642)

## [0.25.0] - 2026-11-10

### Features

- feat: confirm exact addresses for photos without GPS (#236) (add8724)

## [0.24.2] - 2026-11-10

### Fixes

- fix: preserve offsetless EXIF seconds and fractions on unchanged confirmation (51781cb)
- fix: preserve human time catalog changes and all current contact translations (7b2215d)
- fix: retain main plate labels in all languages and remove retired review keys (c5f85f9)
- fix: keep the same plate input label in catalog, wording pins and UI flow tests (63a0a2c)
- fix: preserve human Jerusalem time, plate formatting and both integration tests (a787bce)
- fix: use Jerusalem display and edits consistently for UTC and foreign-offset evidence (46b587c)
- fix: remove raw metadata controls from normal review and use plain Jerusalem time confirmation (4e38417)
- fix: explicit advanced timezone correction for missing EXIF offset (5e09e8c)
- fix: readable local review time and remove approval note (0309563)

### Other changes

- Merge pull request #213 from reihlinstinct/fix/review-readable-local-time (30fbb2b)
- Merge main contact validation before human time UI verification (d4e5899)
- chore: stage current main catalog for signed integration (ec7cdd4)
- test: navigation does not assume shared instrumented ledger is empty (3eb8b8a)
- test: import text-click helpers used by synthetic GPS consent path (08c9f68)
- test: align plate field wording pins with the merged formatted plate catalog (4eb1410)
- test: use translated warning code in synthetic address review pixels (a58475b)
- test: acknowledge synthetic EXIF GPS prompt before unapproved-send guard check (dfe4bf2)
- test: reconcile plate label and replace retired raw-location correction tests (a5a14fa)
- test: mark removed approval note as retired catalog key (2264306)
- test: dismiss address consent once per synthetic review fixture (4f6f814)
- Merge main with plate formatting and basic setup before final time UI verification (94e0bdc)
- chore: stage merged plate/setup files for signed main integration (25bb10f)
- test: use platform-compatible synthetic EXIF tags and remove raw-field warning tripwires (8fdc4cd)
- test: scope synthetic EXIF to stub flow without changing shared-entry fixture (41a5d4b)
- test: retire removed approval-note catalog entry (cdeef0b)

## [0.24.1] - 2026-11-10

### Other changes

- test: capture bounded OCR text only in encrypted private benchmark (#228) (65819cf)

## [0.24.0] - 2026-11-10

### Features

- feat: encrypted private municipal response diagnostics (#220) (57d3fe1)

## [0.23.1] - 2026-11-10

### Other changes

- build: track verified Gradle wrapper for automatic discovery (#232) (720ac48)

## [0.23.0] - 2026-11-10

### Features

- feat: validate reporter contact fields and hide advanced municipal settings (#223) (f037c4d)

## [0.22.2] - 2026-11-10

### Other changes

- live e2e: recognize failure notes, classify by state, one-line collect shells (#226) (fdb5041)

## [0.22.1] - 2026-11-10

### Other changes

- test: verify private OCR network isolation (#217) (80fd25a)

## [0.22.0] - 2026-11-10

### Features

- feat: formatted plate entry and OCR prefill (#215) (a35317a)

## [0.21.0] - 2026-11-10

### Features

- feat: basic settings after first-run welcome (#209) (#211) (5215817)

## [0.20.1] - 2026-11-10

### Other changes

- test: isolated private real-photo OCR benchmark (#210) (#216) (f8bb8fd)

## [0.20.0] - 2026-11-10

### Features

- feat: owner-selected Ai06 adaptive launcher icon (#206) (7bc4e17)

## [0.19.2] - 2026-11-10

### Fixes

- fix: street and house number in report review (#204) (#207) (40e7d00)

## [0.19.1] - 2026-11-10

### Fixes

- fix: live E2E driver - semantics clicks immune to soft keyboard, SEND_NOT_FIRED outcome, wait screenshots (#208) (5c25681)

## [0.19.0] - 2026-11-10

### Features

- feat: one-time first-run welcome screen (#187) (#188) (f9245a4)

### Added

- One-time first-run welcome screen (#187). Upgrading installs have no flag yet, so they see the welcome once after the update.

## [0.18.5] - 2026-11-10

### Other changes

- settings: clear-and-save municipal credentials + greyed-out send naming missing values (#180) (7546fdd)

## [0.18.4] - 2026-11-10

### Fixes

- fix: use shipped theme in live emulator recording (#205) (76c2bd0)

## [0.18.3] - 2026-11-10

### Other changes

- live-e2e: fix outcome-unknown hang + keep heavy downloads off the owner tunnel (#203) (174074a)

## [0.18.2] - 2026-11-10

### Other changes

- live-e2e: fix device staging (mkdir files dir) + fail-fast preflight (#202) (da62573)

## [0.18.1] - 2026-11-10

### Other changes

- trial: restage photo for the next owner-approved live filing + photo-digest gate (#200) (ca41db3)

## [0.18.0] - 2026-11-09

### Features

- feat: municipal navy/gold Material 3 theme (#161 item 1) (#199) (77f86f9)

### Other changes

- test: opt-in live emulator submission E2E with encrypted recording (#194) (db5f2c2)

## [0.18.0] - 2026-11-09

### Features

- feat: municipal navy/gold Material 3 theme (#161 item 1) (#199) (77f86f9)

## [0.17.2] - 2026-11-09

### Fixes

- fix: establish isolated owner VPN before one approved submit (#198) (1fccf5e)

## [0.17.1] - 2026-11-09

### Fixes

- fix: fail closed on missing mandatory trial inputs before decrypt/network (#196) (babcb5e)

### Fixed
- Fail closed with constant input-stage markers when a mandatory live-trial secret is missing, before decryption or municipal calls; add test-only constant stage and error-kind markers without logging values.

## [0.17.0] - 2026-11-09

### Features

- feat: plate-free bollard category and explicit trial subject (#189) (f73f86d)

### Added

- Add a plate-free bollard request category using the established municipal subject 1280, with labels in all five languages.

## [0.16.6] - 2026-11-09

### Other changes

- test: full report-flow E2E (stub default, opt-in live mode) (#185) (e63a112)

## [0.16.5] - 2026-11-09

### Other changes

- security: pin sha256 of the on-device address lookup (closes #165) (#184) (210d01c)

## [0.16.4] - 2026-11-09

### Other changes

- ci: manual TRIAL_DATA_KEY rotation workflow (closes #181) (#182) (e3e8ce0)

## [0.16.3] - 2026-11-09

### Other changes

- ci: keep the release PAT out of the checkout credential store (#177) (7427da2)

## [0.16.2] - 2026-11-09

### Other changes

- test: split ShellSettingsExtractionTest key-set check into two readable assertions (#178) (224c83c)

## [0.16.1] - 2026-11-09

### Other changes

- trial: M0 engine-level live trial (login-only diagnostic + digest-gated single submit) (#168) (daaf9b3)

## [0.16.0] - 2026-11-09

### Features

- feat(i18n): category, evidence-time and main-activity extraction (PR-2c of #115) (#176) (3f0d067)

## [0.15.0] - 2026-11-09

### Features

- feat(i18n): review, capture and reports screen extraction (PR-2b of #115) (#172) (2e91581)

### Fixes

- fix(ci): push semantic releases with the owner PAT (#174) (bee5f69)

### Other changes

- i18n(licenses): state the boundary condition on the OSM reproducibility claim (#173) (963c0d2)
- docs/tools: address-lookup follow-ups from the #163/#167 reviews (#170) (9810b0c)
- test(municipal, import): pin no-retry, the 16 MB cap and the import size boundary (#171) (b7d3bf6)

## [0.14.5] - 2026-11-09

### Other changes

- M0 diagnostic screen: proper RTL, trimmed copy, bidi isolates (#162) (cacea76)

## [0.14.4] - 2026-11-09

### Other changes

- docs: name the manual city read-only check in CONTRIBUTING (#143) (c651383)

## [0.14.3] - 2026-11-09

### Other changes

- docs/builder: ODbL s4.6 = generator route only (owner 2026-11-09) (#167) (9008bb5)

## [0.14.2] - 2026-11-08

### Other changes

- docs/licenses: match the \u00a74.6 wording to reality, fix ML Kit terms link (#166) (6c41e7b)

## [0.14.1] - 2026-11-08

### Other changes

- ODbL address-lookup builder + Settings Licenses screen (#163) (60c7fa5)

## [0.14.0] - 2026-11-08

### Features

- feat(i18n): language picker and shell/settings string extraction (PR-2a of #115) (#154) (ca4e64a)

## [0.13.2] - 2026-11-08

### Other changes

- M-B: gated municipal submit on the review screen (#153) (#160) (4c1c18a)

## [0.13.1] - 2026-11-08

### Other changes

- M-A: municipal 106 submission client port + contract tests (#155) (a05c50f)

## [0.13.0] - 2026-11-08

### Features

- feat: deep link, share-sheet target and Quick Settings tile (#149 phase 1) (#151) (fb02b17)

## [0.12.2] - 2026-11-08

### Other changes

- data: refresh expected catalog fingerprint after live check 2026-11-08 (subject 1291 whitespace) (#156) (f876ef9)

## [0.12.1] - 2026-11-08

### Other changes

- docs: platform roadmap note (Android first, iOS port later) (#150) (8f6a98b)

## [0.12.0] - 2026-11-08

### Features

- feat: two-button capture, gallery EXIF import, my-reports screen (#138) (d329673)

## [0.11.0] - 2026-11-08

### Features

- feat(i18n): string catalog, plural rules and bidi isolates (infrastructure) (#148) (6af42b9)

## [0.10.0] - 2026-11-08

### Features

- feat: manually entered municipal API credentials in encrypted settings (#128) (54661e5)

## [0.9.7] - 2026-11-08

### Other changes

- ci: add bounded privacy guard (advisory mode) (#146) (c31da31)

## [0.9.6] - 2026-11-08

### Other changes

- test: stubbed offline test for scripts/city_readonly_check.py (#142) (4d691de)

## [0.9.5] - 2026-11-08

### Other changes

- ci: baseline issue/reason fields, wiki-sync persist-credentials false (#137) (#145) (8494d62)

## [0.9.4] - 2026-11-08

### Other changes

- chore: re-pin vulnerability scan to v1.0.9 (#139) (c186839)

## [0.9.3] - 2026-11-08

### Other changes

- build(deps): bump certifi from 2025.11.12 to 2026.10.18 (#123) (6d11b5f)

## [0.9.2] - 2026-11-08

### Other changes

- ci: pipefail + --check on the python smoke gate; checklist pipe/GITHUB_TOKEN/atomicity clarifications (Tab #131 finding) (#135) (fa25fd3)

## [0.9.1] - 2026-11-08

### Other changes

- build(deps): bump filelock from 3.20.0 to 4.2.1 (#122) (5df3bc4)

## [0.9.0] - 2026-11-08

### Features

- feat: municipal taxonomy parity - captured catalog asset, fingerprint loader, category mapping (#101) (d7ec942)

## [0.8.4] - 2026-11-08

### Other changes

- chore: add weekly OSV vulnerability scan via dependency-inventory (#120) (1402f44)

## [0.8.3] - 2026-11-08

### Other changes

- ci: test-evidence artifacts (JUnit+JaCoCo, emulator screen recording, per-test screenshots) (#118) (8d8a39b)

## [0.8.2] - 2026-11-08

### Other changes

- docs: review-lessons checklist in CONTRIBUTING (#131) (602096e)

## [0.8.1] - 2026-11-08

### Other changes

- build: hash-locked Python tooling requirements (#130) (9945c8d)

### Security

- Python tooling requirements are hash-locked: `requirements-lock.txt` pins every package, transitive ones included, with sha256 hashes, generated from the unchanged `requirements.txt` pins. The Scorecard guard now checks the lock (exact pins, hashes, generating command, input pins) and install commands in Markdown code and Python scripts; regeneration in docs/DEPENDENCY-LOCKS.md. The workflow install moves to the lock in a separate maintainer change. (#70)

## [0.8.0] - 2026-11-08

### Features

- feat(ui): bottom navigation with Settings, debug-only demo gate, consistent RTL alignment (#112) (ee8384a)

## [0.7.2] - 2026-11-08

### Other changes

- ci: maintainer-approved read-only city checks (#119) (0468368)

## [0.7.1] - 2026-11-08

### Other changes

- registry: require https baseUrl when an API key is set (#117) (692f433)

### Security

- `VehicleRegistryConfig` now requires an https `baseUrl` when an `apiKey` is set, so the key header is never sent in plaintext; the rejection message carries neither the key nor the URL. A config without a key is unchanged. (#114)

## [0.7.0] - 2026-11-08

### Features

- feat(registry): read-only data.gov.il vehicle lookup client (#113) (6ad6ad5)

### Added

- Read-only data.gov.il vehicle registry lookup client (`registry/VehicleRegistryClient.kt`): plate validation, typed found / not-found / error result, rate limit and in-memory TTL cache, optional API key header. Not wired into the UI yet. Dataset and fields in docs/VEHICLE_REGISTRY.md. (#102)

## [0.6.4] - 2026-11-07

### Other changes

- Sync layout kit: NUL-safe ls-files, tracked-set required checks, no persisted credentials (#95) (3709e2a)

## [0.6.3] - 2026-11-07

### Other changes

- Add repo layout contract and CI enforcement (#93) (9eff2f4)

## [0.6.2] - 2026-11-06

### Other changes

- build(deps): bump com.google.devtools.ksp from 2.3.12 to 2.3.13 (#85) (831afd5)

## [0.6.1] - 2026-11-04

### Other changes

- docs: refine ledger migration proof and crash recovery phases (#91) (c9cfb90)

## [0.6.0] - 2026-11-04

### Features

- feat: add ledger key envelope and Keystore custody (not wired yet) (#90) (9dc0563)

## [0.5.1] - 2026-11-04

### Other changes

- docs: design encrypted report ledger and safe migration (#89) (d5365e8)

## [0.5.0] - 2026-11-04

### Features

- feat: add approved public PayPal Sponsor button (#87) (849ef89)

## [0.4.27] - 2026-11-01

### Other changes

- Toolchain migration: AGP 9.5.0 + Gradle 9.8.2 + compileSdk 37 + okhttp/mockwebserver 5.5.1 (#81) (#82) (49d2e66)

## [0.4.26] - 2026-10-30

### Other changes

- ci: skip heavy jobs on docs-only PRs, add concurrency, caching and weekly full run (#80) (9f15747)

## [0.4.25] - 2026-10-30

### Other changes

- Add regression guard to existing OpenSSF Scorecard workflow (#74) (a6ecef0)

## [0.4.24] - 2026-10-29

### Other changes

- docs: add CI status badge(s) to README (#78) (b6833b7)

## [0.4.23] - 2026-10-29

### Other changes

- Add CODEOWNERS (#76) (d496db9)

## [0.4.22] - 2026-10-29

### Other changes

- Add code of conduct and accessibility statement (#69) (f1afdaf)

## [0.4.21] - 2026-10-29

### Other changes

- Merge pull request #67 from reihlinstinct/ci/run-summary-v12 (4191a32)
- ci: step summary v19 - coverage map checks filtered to nonempty strings (96d61c8)
- ci: step summary v18 - docstring states the self-test stop condition (8ea4bee)
- ci: step summary v17 - edge-case audit: deletion verdict needs after, empty log row, payload shape guards (6af8c6e)
- ci: step summary v16 - accurate no-range reason for deletion pushes (946b92c)
- ci: step summary v15 - push range uses payload after SHA, ignore deletions (6470ed7)
- ci: step summary v14 - recognize quoted job ids (08860cd)
- ci: step summary v13 - job headers with trailing comments, clear current on unknown job keys (d12d639)
- ci: step summary v12 - log-row helper, jobs-block parser fix (0a2dffa)

## [0.4.20] - 2026-10-29

### Other changes

- Merge pull request #65 from reihlinstinct/ci/64-run-summaries (99cb783)
- ci: parse git output as bytes and order name-scalar checks (#64) (c9f59ce)
- ci: keep the forged-row self-test free of literal hosts (#64) (bd9cecf)
- ci: parse plain-scalar YAML comments out of job display names (#64) (1ba47aa)
- ci: close U+2028/U+2029 log-split injection in summaries (#64) (e3c6c05)
- ci: escape HTML in summary text and diff PR base...head (#64) (412fb42)
- ci: harden summary rendering, pin job permissions, fix maps (#64) (f8c0d27)
- ci: align run_summary.py and coverage maps with review findings (#64) (d8212fc)
- chore: update run_summary.py to canonical v3 (60d62a6)
- ci: add a run-summary job to every workflow (#64) (9d0c036)

## [0.4.19] - 2026-10-29

### Other changes

- Pin actions/checkout to full SHA in wiki-sync, fix design doc reference (#62 follow-up) (#63) (25ce9ae)

## [0.4.18] - 2026-10-29

### Other changes

- Wiki pilot: Hebrew user guide in docs/wiki/ + sync workflow (#61) (#62) (95e2404)

## [0.4.17] - 2026-10-28

### Other changes

- ci: pin runs-on to ubuntu-24.04 (#59) (58401d2)

## [0.4.16] - 2026-10-28

### Other changes

- ci: rebase release commit onto latest main before tagging and pushing (#55) (9261068)

## [0.4.15] - 2026-10-28

### Other changes

- docs: distinguish Gradle runtime and wrapper bootstrap versions (#52) (#53) (2bc15e3)

## [0.4.14] - 2026-10-28

### Other changes

- build(deps): bump com.google.crypto.tink:tink-android (#48) (c79b131)

## [0.4.13] - 2026-10-28

### Other changes

- build(deps): bump org.jetbrains.kotlinx:kotlinx-coroutines-android (#47) (4041114)

## [0.4.12] - 2026-10-28

### Other changes

- build(deps): bump org.json:json from 20240303 to 20260814 (#50) (987f1ca)
- build(deps): bump github/codeql-action/upload-sarif (#49) (f47a3db)

## [0.4.11] - 2026-10-22

### Other changes

- docs: label, milestone and issue-order scheme (#43) (d0a67c0)

## [0.4.10] - 2026-10-22

### Other changes

- ci: fix unit-test coverage report (deviceless JacocoReport task) (#42) (6dd2f94)

## [0.4.9] - 2026-10-22

### Other changes

- ci: test-run summaries with per-test detail in every CI job (#41) (f07e5fc)

## [0.4.8] - 2026-10-22

### Other changes

- test: regression check - release.yml must push the tag explicitly (#39) (83b35ed)

## [0.4.7] - 2026-10-22

### Other changes

- build(deps): bump pillow from 12.3.0 to 13.0.0 (#30) (1564de0)

## [0.4.6] - 2026-10-22

### Other changes

- ci: add python tooling smoke test (venv + synthetic intake suite) (#37) (69faeff)

## [0.4.5] - 2026-10-22

### Other changes

- build(deps): bump ossf/scorecard-action from 2.4.4 to 2.5.0 (#31) (21d44c9)

## [0.4.4] - 2026-10-22

### Other changes

- build(deps): bump kotlin to 2.4.21, ksp to 2.3.12, room to 2.8.5 (curated compatible set) (#35) (772e831)

## [0.4.3] - 2026-10-22

### Other changes

- build(deps): bump androidx.fragment:fragment-ktx from 1.8.6 to 1.9.1 (#24) (837a875)

## [0.4.2] - 2026-10-22

### Other changes

- Update release.yml (#34) (9d60ffe)

## [0.4.1] - 2026-10-22

### Other changes

- build(deps): bump github/codeql-action/upload-sarif (#16) (cf45bae)

## [0.4.0] - 2026-10-22

### Added

- Offline OCR development preview: on-device plate recognition candidates with manual review; no cloud photo analysis.
- PRPKG2 encrypted intake prototype (Tink ECDSA/ECIES), synthetic transport demo and offline receiver reference.
- Keystore AES-GCM encrypted reporter settings (first name, last name, phone, ID, email), no plaintext fallback or backup.
- Room ledger with stable report IDs, original hashes and review binding.
- Documentation set: DESIGN, ENCRYPTED_INTAKE_PROTOCOL, OFFLINE_OCR, OFFLINE_RECEIVER, RELAY_OPTIONS, RELEASE_CHECKLIST, ARCHITECTURE with Mermaid diagrams.
- Repository security baseline: Dependabot (gradle, github-actions, pip), CodeQL default setup, OpenSSF Scorecard, secret scanning with push protection, private vulnerability reporting, SHA-pinned workflows.

### Notes

- Development preview only: no live transport, no municipal endpoint or credentials in the client, no production keys.
