# Registry cache privacy assessment

Refs #375. Proposal only. The current live lookup and retention policy is not changed.

`RegistryFallbackCache.file` uses unsalted SHA-256 of public plate digits.
This avoids literal plate names in directory listings but does not anonymize them:
the finite plate space can be enumerated. Android private no-backup storage is
the access boundary. A reader who obtains the cache can also read its contents.
Records omit vehicle model, owner/reporter identity, images and report text.
The filename hash cannot make the remaining status/date information secret.

## Proposed versioned improvement

Use HMAC-SHA256 with a random per-install key in private no-backup storage for
filenames. Fix the input encoding to UTF-8 and label the domain, for example a
versioned registry-cache namespace. No key or filename salt is shared between
installs. This reduces offline filename enumeration when only a directory list
leaks. It does not protect against an attacker with both cache and key or a live
compromised app process. Do not describe it as encryption or anonymity.

Avoid silently migrating all old entries: clearing this optional fallback cache
is safer than interpreting an old filename under a new algorithm. An owner-
approved migration should delete legacy entries and start empty, leaving report
history and submission records untouched. Live requests still happen on every
review. If key storage fails, skip caching rather than skipping the live lookup.

A separate Settings action could clear this cache after explicit confirmation.
Its scope must exclude reporter identity, drafts, photo evidence and municipal
submission history. Preserve the current 128-entry cap and earlier-of-seven-days
or Jerusalem day-after-licence-expiry deadlines. A cache purge is not permission
to replay a submission or remove duplicate history.

## Tests required before implementation

Check key persistence across app restart, distinct keys across clean installs,
fixed UTF-8/domain vectors, corrupt/missing keys, legacy-directory cleanup,
atomic writes, failed deletion and cache-only purge isolation. Retain existing
expiry, insertion-deadline-not-sliding and always-live request tests. Check that
no plate, key or raw status is added to diagnostics. UI wording, actual storage
migration and key provisioning remain outside this assessment.
