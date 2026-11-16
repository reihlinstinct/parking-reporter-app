# Registry fallback cache

Each review lookup always starts fresh read-only requests to BOTH public registries.
A valid private cache entry may display immediately while those requests run.
Successful live fields replace cached fields; failures retain a clearly labelled
cached fallback, with age in minutes. A mixed result is also labelled cached.
Nothing in this lookup gates Save/Send or changes approved report text.

Only plate-keyed tag type/presence, vehicle record presence/licence valid-through,
and insertion/check timestamps persist in the app's private no-backup directory.
Hashed plate filenames avoid raw plates in filenames. No owner/reporter details,
vehicle manufacturer/model/colour/year, photos, location or report text persist.
The cache is capped at128 entries. Corrupt or future-dated entries fail closed.
No background polling or automatic report submission is introduced.

Entries expire at the earlier of7days from insertion (hits AND successful live
refreshes do not slide this deadline), or the start of the Jerusalem day AFTER
licence valid-through. Expired licences are shown live, but not retained as cache.
UI fallback also disappears at its deadline if the screen remains open.
Expired entries are removed on read; a fresh live response can insert a new entry.
Errors/incomplete responses are never stored as negative findings. Disk failures
cannot suppress successful live data. Cache never skips the network request.

Public tag schema has no expiry date, only issue date. No expiry is inferred:
https://data.gov.il/api/3/action/datastore_search?resource_id=c8b9f9c8-4612-4068-934f-d4acd2e3c06e&limit=0
Tag cache therefore uses the same maximum7day record limit; every visit still
rechecks live, independently of that limit. Dates and statuses are informational,
not permission to park. Synthetic UI fixtures are not live expired examples.
