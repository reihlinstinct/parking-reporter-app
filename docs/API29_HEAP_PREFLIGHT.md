# API29 actual-heap CI preflight

Issue #684. CI only. No production APK, manifest, app feature, municipal flow, heap property or independent-review policy is changed.

## Evidence and cause

Diagnostic PR #685 records Android 10 emulator zygote initialization at 16 MiB before its configured 512 MiB property is available. The same failing full-suite process measured `Runtime.maxMemory() == 16777216`, despite `ActivityManager.memoryClass == 512`. Separate healthy processes measured 512 MiB. This explains a reproduced environment failure, not every historical failure or the optimal memory use of the app.

See [same-run evidence](https://github.com/reihlinstinct/parking-reporter-app/pull/685#issuecomment-7001713489) and the [Android 10 race investigation](https://www.rainforestqa.com/blog/hunting-race-condition-in-android-10-emulator). The latter describes the initialization race; it does not validate this PR's restart implementation.

## Boundary and sequence

1. Build and install unchanged production code and the test APK.
2. Run the existing readiness check, including its existing bounded reboot if needed.
3. On API29 only, require the existing configured `dalvik.vm.heapsize` to be exactly `512m`.
4. Run one test-APK metadata probe without UI, addresses or network calls. Read its actual process PID and `Runtime.maxMemory()` through `run-as`.
5. If already 512 MiB, do not restart. If exactly 16 MiB, request one `ctl.restart zygote`, without changing any heap property. Wait for a different zygote PID, the package service and healthy window/process state. Do not reboot into the same race.
6. Require a fresh probe process with a different PID and exactly 512 MiB, while the configured property remains unchanged. Unknown budgets, missing metadata and unsuccessful recovery fail closed before the full suite. Never retry application assertions.
7. Run the complete existing suite once, with no filters or removed assertions. The new metadata test also runs, adding one test to the original suite. A failed preflight uploads its diagnostic artifacts through the workflow's existing always-upload step.

The probe is only in `androidTest`, not production source. Its JSON contains a PID and a heap limit, no reporter details or evidence. Other Android APIs are unchanged by this preflight.

Readiness precedes heap validation so its reboot cannot invalidate a successful heap check. After a runtime restart, readiness is checked without another reboot.

## Verification and limits

Local tests cover healthy/no-restart, 16 MiB recovery, persistent failure, stale PID, unexpected budgets/property changes, invalid probe data, other-API no-op, and workflow ordering. Existing readiness regressions remain required.

Live acceptance is separate: artifacts must show `before.javaMaxBytes = 16777216`, `after.javaMaxBytes = 536870912`, distinct PIDs and the full suite passing. A healthy first boot proves only the no-restart path. A green workflow alone is not evidence that recovery ran. Formal exact-head review and the normal existing gate are required before merge.

The restart is not a repair for app allocation leaks, an increased heap allowance, or a release-device workaround. Root access is confined to the disposable emulator. No paid services or private data are used.

## Bounded recovery-proof follow-up

The separate `API29 bounded recovery proof` workflow checks up to eight natural API29 boots in one emulator session. It never injects a heap property or alters the original app. Only an actually measured16MiB process triggers the merged preflight gate and its one runtime restart. A verified fresh512MiBprocess is followed by the full suite once. Eight healthy boots produce `not-reproduced`, not recovery evidence, even if CI is green. Unknown budgets and probe/recovery errors stop the run. The artifact records each cold probe and control metadata. The full suite is not retried and no merge gate is changed.
