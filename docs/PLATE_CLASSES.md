# Plate classes (issue #354)

Status: only REGULAR is implemented. A raw string that is not 7 or 8 ASCII digits is drawn as plain text and rejected by `PlateNumber.parse`. Nothing here infers legal status, licence validity or parking permission from a plate.

## Plate entry and OCR (#399)

The app does not pass raw strings. Two rules keep a special-looking plate from becoming a regular one:

- Manual field (`PlateEntry.accept`): digits (any Unicode decimal digit, stored as ASCII), hyphens, spaces and bidi marks are accepted. Any letter or other symbol rejects the whole edit and keeps the previous value. Typing or pasting "CD 11 222 33" no longer stores `1122233`.
- OCR (`PlateCandidates.fromText`): a 7 or 8 digit run next to a standalone Hebrew mem or tsadi (final forms included) is not offered. The letter may touch the run or be separated by one space or hyphen, but not a line break, and it must not be part of a longer Hebrew word ("נכים 1234567" and "1234567 מסוג" still offer the number). Latin letters or digits next to a run were already rejected.

Still true: only REGULAR is implemented. A raw string that is not 7 or 8 ASCII digits is drawn as plain text and rejected by `PlateNumber.parse`. Nothing here infers legal status, licence validity or parking permission from a plate.

## Plate type is read from the typed text (owner decision 2026-11-13)

The review screen has one plate field and no type picker. `PlateDetect` reads the type from what is typed, using only the markers the app already uses:

- "CD" or "CC" (any case, optional space or hyphen) before the digits: diplomatic or consular. Digits only after it (up to 12). The report text carries "CD 123" or "CC 123".
- A hyphen between the digits, or a trailing "מ": police (spec 119/2016: digits, hyphen and the letter mem). Both ways are accepted (owner answer). The mem is added to the report text by the app when typed or not.
- A 7 or 8 digit number grouped exactly like the app's own regular display (12-345-67, 123-45-678) stays regular, so a pasted regular plate is not read as police.
- Plain digits: regular (registry lookup as before). A police number typed as 7 or 8 plain digits with no hyphen and no mem cannot be told from a regular plate and is regular.
- Any other letter is not accepted in the field. A mix (CD with mem) or a lone "C" or "D" gives an empty plate: never ready.
- Ready means what `PlateClasses.ready` says: 7 or 8 digits for regular, at least one digit for the others. Digit counts and grammar for police, CD and CC are still not officially verified, and no colour claim is made beyond the police red.
- Changing the detected type removes the old plate line from the report text, as the picker did. Nothing structured is sent for a non-regular plate, as before.
- `PlateDetectTest` pins the rules. The picker strings stay in the catalog, unused.
- The ML Kit note and choice are hidden by default behind a button with the existing ML Kit label.

## Verified from official text (checked 2026-11-12)

- Police: red plate (Police order 15.07.02, 02/09/2021, https://www.police.gov.il/menifa/02.15.07.02_3.pdf). Police vehicles may instead carry civilian yellow plates, some switchable red/yellow, so a police vehicle does not mean a red plate.
- Police red plate spec 119/2016 (https://mr.gov.il/ilgstorefront/en/p/attachment/005056BF4DAB1EDA95C1078E4D2C811B/%D7%9E%D7%A1%D7%9E%D7%9B%D7%99%20%D7%94%D7%9C%D7%99%D7%9A): white digits, hyphen and the letter "מ". It says there is no Israeli standard for non-yellow plates and does not give the number of digits.

## Not verified (do not code)

- Regular civilian plate look (yellow reflective, black digits, blue left side with flag and "IL"; item 1 of the Third Addendum of the Traffic Regulations 1961). Only a secondary copy was read (https://otodealer.co.il/legislation/traffic-regulations/takanot-hateavura-1961/tosefet-3), no official text (Nevo or Reshumot) yet. This is what the first slice draws, for 7 or 8 ASCII digits.
- Digit count and grouping of police plates.
- Diplomatic (CD) and consular (CC) plate grammar. The MFA "Being a Diplomat" guide text has no plate section.
- Military (black, letter "צ") plate format. No official IDF or transport source found.
- Colors for any of these. Secondary sites and forum posts are not accepted as sources.

Synthetic examples (red "11-22-33 מ", white "CD 11 222 33", black "123456 צ") are invented for tests and are not claimed to be real plates. IsraeliPlateStyleTest pins the raw strings only (not styled as regular, rejected by PlateNumber.parse). PlateEntryTest and PlateCandidatesTest cover the manual field and OCR rules above.

## Rule

A new class needs an official source for its exact format first. Ambiguous or unknown input stays unclassified, with no registry query and no invented color.
