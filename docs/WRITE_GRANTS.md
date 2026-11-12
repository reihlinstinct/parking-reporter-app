# Release and wiki write grants (#71)

Reviewed against main `a13089d0c394a0943260e0b00c765a501bf9f921`.
This document covers the two publishing grants in issue #71, not a complete
inventory of every workflow. It changes no permissions, tokens or settings.

## Release

`.github/workflows/release.yml` defaults to `contents: read`.
Only the `release` job retains `contents: write`. This job-token grant is
unused while RELEASE_PAT is set: git pushes and GitHub release publication
use the PAT, not the job token. Narrowing the retained grant is a separate
owner-approved permission change, not part of this PR. The trigger is a push
to main; the job skips release commits and runs are serialized.

Checkout sets `persist-credentials: false`. Release preparation runs the
repository's script from main, before the publishing step receives credentials.
The publishing step receives the existing `RELEASE_PAT` and a `GH_TOKEN`
which uses that PAT when present, otherwise the job token. The explicit git
fetch/push URLs use `RELEASE_PAT`; the fallback for `gh` does not make the
whole workflow operable without that secret. No broader credential is added.

The workflow pushes the release commit directly to main with the PAT. Its
comments describe a protected-main owner bypass, but current branch-protection
and bypass settings were not independently verified in this review. That commit is validated by follow-on push CI, not by a PR before
landing. This is a limit of the current release process, not a permission this
document grants. The job token grant alone does not constrain the PAT's scopes.

Version and release-note paths enter the shell through environment variables;
notes are passed as a file. The destination is this repository. The workflow
writes CHANGELOG.md, app/build.gradle.kts, the version tag and release metadata.
No PR trigger or untrusted pull-request checkout is used. The separate summary
job explicitly has `contents: read` and does not persist checkout credentials.

## Wiki

`.github/workflows/wiki-sync.yml` defaults to `contents: read`.
Only `sync` grants `contents: write`. It runs on main pushes affecting the wiki
source or workflow, or on manual dispatch. It has no pull-request trigger.

Checkout sets `persist-credentials: false`. The publishing step receives only
the existing `GITHUB_TOKEN`, clones this repository's own `.wiki.git` into a
temporary directory and pushes there. No PAT is used. A fallback PAT remains
outside this scope and requires separate approval.

Only top-level Markdown files from docs/wiki are copied. `rsync --delete`
removes matching wiki pages absent from the source; excluded files remain.
The wiki URL is constructed from GitHub's repository identifier, not a PR field.
The summary job explicitly grants `contents: read` and does not persist checkout
credentials. The wiki sync job still uses ubuntu-latest and has no explicit
job timeout; this document does not change those settings.

## Baseline and regression tests

`.github/scorecard-baseline.json` records these exact findings:

| Path | Rule | Job | Evidence | Tracking |
|---|---|---|---|---|
| release.yml | job-write | release | contents: write | #71, retained; unused while RELEASE_PAT is set |
| wiki-sync.yml | job-write | sync | contents: write | #71, same repository wiki publication |
| codeql.yml | job-write | analyze | security-events: write | #395, CodeQL result upload; schedule and manual dispatch only |

Each reason names this document. The existing guard compares exact path, rule,
job and evidence: a new write, a top-level write, or a summary write is not
covered by either exception. Tests exercise these changes against the actual
repository baseline, check the #71 reasons, and require summary read-only
permissions and non-persisting checkouts. They do not print secret values.

The other four accepted write findings are enumerated in the same baseline:
manual signing contents, Scorecard security-events and id-token, and Gemini
pull-requests. This document does not justify or change those exceptions.

The tests do not enforce absence of secret echo, shell tracing or unexpected
secret env exposure. This remains a security-review limit, not a clean bill.

Scorecard may still report Token-Permissions findings #9 and #14 because it
penalizes job-level writes too. Documenting these retained grants does not
remove those findings, dismiss alerts or prove the repository has no other
security gaps. No live release or wiki dispatch is part of this verification.
