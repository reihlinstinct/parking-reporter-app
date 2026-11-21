# Clean-producer QEMU metadata validation

The scheduled clean producer on2026-11-21 (run44884560876) built the APKs,
booted the emulator and saved its snapshot, then refused cache publication.
The diagnostic added inPR709 classified the rejected `qemu-version.txt` as
`exact_ascii_2`: exactly the single byte `2`, without a newline. This was not
a dependency-resolution or app-test failure. Retrying unchanged code wastes
a build and does not fix the refusal.

The scanner now permits only that exact, case-sensitive basename with exactly
that observed value. It retains the file unchanged, preserving its contents
for the emulator rather than guessing that removal is harmless. It does not
claim to establish the meaning of the version marker or prove snapshot restore
compatibility on its own. The existing cache identity also pins the emulator,
system image, runner image, app sources and preparation scripts.

Production and restoration apply the same check, with or without diagnostics:
no-follow/nonblocking open, regular-file check, bounded read of at most17bytes,
and exact byte equality. Other values, newlines, empty files, large files,
case variants, symlinks, directories and FIFOs remain refused. The general text
and log suffix rules are unchanged. Other forbidden files still block the whole
cache, even alongside a valid marker. Unknown metadata must be diagnosed and
reviewed separately, never deleted or broadly allowed to make a run green.

Opt-in clean-producer diagnostics remain fixed classifications only, never raw
contents, hashes or full paths. Rejected values remain unchanged. No workflow,
cache permissions, private-data access or live-consumer activation is added.
A successful producer/restore run is still needed before claiming recovery;
clearing this first refusal may expose another blocker.
