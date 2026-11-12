# Plate classes (issue #354)

Status: only REGULAR is implemented. Everything else is unclassified: drawn as plain text, and never sent to the vehicle or disability registry. Nothing here infers legal status, licence validity or parking permission from a plate.

## Verified from official text (checked 2026-11-12)

- Regular civilian plate: yellow reflective, black digits, blue left side with the flag and "IL" (Traffic Regulations 1961, Third Addendum, item 1, via the Nevo/OTOdealer text of the regulations). This is what the first slice draws, for 7 or 8 ASCII digits.
- Police: red plate (Police order 15.07.02, 02/09/2021, https://www.police.gov.il/menifa/02.15.07.02_3.pdf). Police vehicles may instead carry civilian yellow plates, some switchable red/yellow, so a police vehicle does not mean a red plate.
- Police red plate spec 119/2016 (https://mr.gov.il/ilgstorefront/en/p/attachment/005056BF4DAB1EDA95C1078E4D2C811B/%D7%9E%D7%A1%D7%9E%D7%9B%D7%99%20%D7%94%D7%9C%D7%99%D7%9A): white digits, hyphen and the letter "מ". It says there is no Israeli standard for non-yellow plates and does not give the number of digits.

## Not verified (do not code)

- Digit count and grouping of police plates.
- Diplomatic (CD) and consular (CC) plate grammar. The MFA "Being a Diplomat" guide text has no plate section.
- Military (black, letter "צ") plate format. No official IDF or transport source found.
- Colors for any of these. Secondary sites and forum posts are not accepted as sources.

Owner-supplied examples (red "21-12-20 מ", white "CD 23 044 22", black "174142 צ") are examples only and are pinned in IsraeliPlateStyleTest as never styled and never queried.

## Rule

A new class needs an official source for its exact format first. Ambiguous or unknown input stays unclassified, with no registry query and no invented color.
