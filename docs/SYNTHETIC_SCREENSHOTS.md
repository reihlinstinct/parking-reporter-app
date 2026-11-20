# Synthetic review screenshots

Refs #679. These nine instrumentation images contain synthetic test data only:

- address-unavailable-disabled.png
- bundled-address-licences.png
- no-gps-address-confirm-synthetic.png
- review-local-time-synthetic.png
- review-no-stray-time-example.png
- review-formatted-plate-synthetic.png
- review-resolved-address-synthetic.png
- review-address-summary-synthetic.png
- review-recreation-synthetic.png

## Retention boundary

Each test writes a fixed filename under the app's internal files directory. Repeated executions replace that file rather than creating timestamped copies. At most one copy of each named image remains on an instrumented device. This is intentional retention until the workflow collects screenshots, not a leak-cleanup omission.

The Android emulator job copies all app files-directory PNGs into its screenshot report after instrumentation, including on test failure, and uploads that report with 14-day retention. Deleting these nine files in a test's finally block would destroy pixel evidence before collection, so tests must not do that. The disposable hosted emulator is destroyed when its job ends. For a developer's persistent device, uninstalling the test app-under-test removes its app-private screenshots; clear app data only on a dedicated synthetic test installation, never an installation containing real reports.

Screenshot retention does not extend the lifetime of a recreated draft, database row, source image or recovery state. ReviewRecreationTest keeps its existing finally cleanup for those objects. No real photo, identity, plate, municipal response or credential belongs in these images. A new screenshot must have a bounded fixed name, a synthetic-only source, and an explicit collection path.

The inventory regression checks the complete filenames against both source files and preserves the workflow's collect-before-upload sequence and retention setting. File existence and test success do not prove visual quality. The collected PNGs still need actual pixel inspection before they serve as visual evidence.
