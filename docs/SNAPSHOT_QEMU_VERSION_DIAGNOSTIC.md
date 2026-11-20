# Clean-producer QEMU metadata diagnostic

The clean producer reached snapshot creation on 2026-11-20, then the unchanged
manifest validator rejected `qemu-version.txt` as a forbidden text file. Nothing
was published. This was not a dependency-resolution or app-test failure.

The meaning and necessity of this file have not been established. It is neither
allowed nor deleted. A retry of the same code is not expected to fix it.

The opt-in clean-producer diagnostic now classifies only that exact basename.
It uses a no-follow, nonblocking open, requires a regular file, caps the read at
17 bytes, and prints only a fixed label: exact ASCII 2, ASCII 2 plus LF, empty,
other bounded value, over 16 bytes, not regular, or unreadable. It never prints
contents, hashes or full paths. Ordinary restore validation never reads it.

The file remains rejected after classification. No cache permission, cleanup or
snapshot-consumer behavior changes. This is evidence gathering, not recovery.
Any subsequent exact-file cleanup or allowance needs its own reviewed change
and evidence that snapshot compatibility is preserved. Do not broaden the text
suffix rule or run repeated unchanged producer retries.
