# Reviewed abandoned-bicycle route

This adds one explicit, isolated submission mode. It does not activate a batch,
change parking categories, or authorize any municipal report.

- Dispatch confirmation: `FILE_ONE_APPROVED_ABANDONED_BICYCLE`.
- Bridge mode: `ABANDONED_BICYCLE_ONCE`.
- Exact code: `1015`, event type `abandoned_bicycle`.
- Exact captured category path: `ניקיון` / `גזם / גרוטאות` /
  `אשפה - גרוטאות /מקרר/מכונת כביסה`.
- Login must return that exact code, leaf name and full path before the claim,
  contact registration or Create. A changed catalog stops without filing.
- `plate` must be JSON null. An empty string or vehicle identifier is refused.
- A separate `distinguishingDescription` of 30 to 1000 characters must appear
  verbatim in the approved text. Describe the particular bicycle and location.
- Known standing parking-enforcement paragraph markers are refused. The text
  still needs human approval; the validator is not a semantic review.

Use only the approved current photo, resized within existing JPEG limits. Prior
photos stay private unless separately authorized. A repeat photo is not proof
that the bicycle remained unmoved between observations. Do not invent missing
parts, a statutory abandonment duration, or continuous immobility.

The package digests, source checks, actor, expiry, single-writer fence, VPN route,
private claim, one Create, exact photo attachment and encrypted diagnostics stay
unchanged. Existing parking and cable modes reject a bicycle package. Synthetic
Python and JVM tests exercise the separate mode, null plate, description binding,
wrong category/path, changed text and duplicate Create boundaries.
