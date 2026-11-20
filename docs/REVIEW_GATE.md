# Review gate source maintenance (#641)

`scripts/ci/review_gate.py` is the canonical source for the trusted reviewer list and gate algorithm. Run `python scripts/ci/render_review_gate.py` after editing it, then commit both the source and generated workflow. `--check` detects drift. The existing byte-for-byte parity regression and negative gate tests remain mandatory.

Generation happens only during development. The privileged workflow still uses checked-in embedded code, never checks out a PR and never imports or fetches a PR script or reviewer list. Do not replace it with a runtime import. A proposed reviewer addition is still a trust-policy change requiring the existing owner/review process; this renderer grants no permission to add one.

The regression mutates the canonical allowlist and checks the generated workflow differs, while preserving author/Bot rejection, exact-head and blocking semantics. No workflow permissions, reviewer identities or protection settings change in this patch.
