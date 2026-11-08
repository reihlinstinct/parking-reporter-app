# Address lookup builder (open data, ODbL)

`build_address_lookup.py` regenerates the app's address lookup
(`{"version": 1, "streets": [...], "addresses": [...]}`) from open,
attributed sources. It replaces the municipality-supplied
`addresses.json`, whose licence for publication was never established
and which is therefore kept out of this repository.

## Sources and licences

1. **Street codes and names** - Jerusalem municipality open-data portal,
   dataset "צירי דרכים בירושלים"
   (<https://jerusalem.datacity.org.il/dataset/8be07be2-c31c-4a53-bd0e-28cb1ff84333>),
   licensed **Open Data Commons Open Database License (ODbL)**.
2. **Address points (house numbers)** - **© OpenStreetMap contributors**,
   <https://www.openstreetmap.org/copyright>, licensed **ODbL**, fetched
   live from the Overpass API inside the Jerusalem municipality boundary
   (OSM relation 1381350).

Because the output is a produced work from ODbL databases, the app ships
an attribution notice ("© OpenStreetMap contributors / ODbL" plus the
municipal dataset) on its Settings -> Licenses screen, and this README
travels with the generator.

## Reproduce

```bash
pip install openpyxl
python3 tools/address-lookup/build_address_lookup.py --out addresses.json
```

The run prints row counts to stderr. Validate the result against the
pipeline resolver before shipping: resolve a golden set of known
Jerusalem addresses with both the old and the regenerated file through
`parking-report-helper`'s lookup path and compare street codes, house
numbers and ITM coordinates (`tools/address-lookup/validate_lookup.py`).

## Notes

- OSM has no Jerusalem municipal street codes, so the code list comes
  from the municipal ODbL layer; address points come from OSM. The two
  are joined inside the app by normalized street name, exactly like the
  pipeline's version-1 file behaves.
- Street names mapping to more than one municipal code are dropped from
  the streets section (the resolver refuses ambiguous names anyway).
