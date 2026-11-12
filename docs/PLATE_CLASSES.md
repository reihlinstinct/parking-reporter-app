# Plate classes (issue #354)

Status: only REGULAR is implemented. A raw string that is not 7 or 8 ASCII digits is drawn as plain text and rejected by `PlateNumber.parse`. Nothing here infers legal status, licence validity or parking permission from a plate.

## Known gap (not fixed here)

The app does not pass raw strings. The manual plate field keeps decimal digits only (`PlateEntry.digits`), so typing or pasting a special-looking plate such as "CD 11 222 33" stores `1122233`. That is 7 digits, so it is drawn as a regular plate and both registries are queried. Special plates whose digits happen to be 6 after stripping (police and military style examples) are not affected. On the OCR path, `PlateCandidates.fromText` rejects Latin letters next to a digit run but not Hebrew letters, so a 7 or 8 digit run followed by "מ" or "צ" could become a candidate. This breaks the #354 rule "never strip letters then reinterpret a special plate as a regular numeric one". It needs its own change and the owner's decision, tracked in #399.

## Verified from official text (checked 2026-11-12)

- Police: red plate (Police order 15.07.02, 02/09/2021, https://www.police.gov.il/menifa/02.15.07.02_3.pdf). Police vehicles may instead carry civilian yellow plates, some switchable red/yellow, so a police vehicle does not mean a red plate.
- Police red plate spec 119/2016 (https://mr.gov.il/ilgstorefront/en/p/attachment/005056BF4DAB1EDA95C1078E4D2C811B/%D7%9E%D7%A1%D7%9E%D7%9B%D7%99%20%D7%94%D7%9C%D7%99%D7%9A): white digits, hyphen and the letter "מ". It says there is no Israeli standard for non-yellow plates and does not give the number of digits.

## Not verified (do not code)

- Regular civilian plate look (yellow reflective, black digits, blue left side with flag and "IL"; item 1 of the Third Addendum of the Traffic Regulations 1961). Only a secondary copy was read (https://otodealer.co.il/legislation/traffic-regulations/takanot-hateavura-1961/tosefet-3), no official text (Nevo or Reshumot) yet. This is what the first slice draws, for 7 or 8 ASCII digits.
- Digit count and grouping of police plates.
- Diplomatic (CD) and consular (CC) plate grammar. The MFA "Being a Diplomat" guide text has no plate section.
- Military (black, letter "צ") plate format. No official IDF or transport source found.
- Colors for any of these. Secondary sites and forum posts are not accepted as sources.

Synthetic examples (red "11-22-33 מ", white "CD 11 222 33", black "123456 צ") are invented for tests and are not claimed to be real plates. IsraeliPlateStyleTest pins the raw strings only (not styled as regular, rejected by PlateNumber.parse). It does not cover the manual field or OCR, which strip to digits first (see Known gap).

## Rule

A new class needs an official source for its exact format first. Ambiguous or unknown input stays unclassified, with no registry query and no invented color.
