#!/usr/bin/env python3
"""Compare a regenerated address lookup against a reference file.

Resolves a golden set of Jerusalem "street house" addresses through the
parking-report-helper pipeline's own resolver with BOTH files and reports
every difference in street code, house, or ITM coordinate. Exit 1 on any
mismatch beyond a small tolerance.

Usage:
  python3 validate_lookup.py --reference OLD.json --candidate NEW.json \
      --helper /path/to/parking-report-helper
"""
import argparse
import importlib.util
import sys

GOLDEN = [
    ("אגריפס", "12"),
    ("הנביאים", "12"),
    ("יפו", "100"),
    ("הלל", "23"),
    ("שמאי", "10"),
    ("קינג ג'ורג'", "21"),
    ("עזה", "35"),
    ("בצלאל", "7"),
    ("הלני המלכה", "12"),
    ("דוד ילין", "4"),
]


def load_resolver(helper_root):
    spec = importlib.util.spec_from_file_location(
        "api106", f"{helper_root}/src/parking_report/api106.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", required=True)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--helper", required=True)
    args = parser.parse_args()
    module = load_resolver(args.helper)
    diffs = 0
    for street, house in GOLDEN:
        row = []
        for label, path in (("ref", args.reference), ("new", args.candidate)):
            try:
                resolved = module.Addresses(path).resolve(street, house, None)
                row.append((label, resolved))
            except Exception as exc:  # noqa: BLE001 - comparison harness
                row.append((label, f"ERR: {exc}"))
        same = row[0][1] == row[1][1]
        if not same:
            diffs += 1
        print(("OK   " if same else "DIFF ") + f"{street} {house}: {row[0][1]} vs {row[1][1]}")
    if diffs:
        print(f"{diffs} differences", file=sys.stderr)
        sys.exit(1)
    print("all golden addresses identical")


if __name__ == "__main__":
    main()
