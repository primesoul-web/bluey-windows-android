#!/usr/bin/env python3
"""Validates the shared wire-protocol fixtures against packet.schema.json.

Every line of every *.ndjson fixture in shared-protocol/fixtures must:
  * valid files  — parse as JSON and validate against the schema;
  * 07-invalid-* — fail to parse or fail schema validation.

Exits non-zero with a message on the first violation.
"""
import json
import pathlib
import sys

try:
    import jsonschema
except ImportError:
    print("error: jsonschema is not installed (pip install jsonschema)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCHEMA_PATH = ROOT / "shared-protocol" / "packet.schema.json"
FIXTURES_DIR = ROOT / "shared-protocol" / "fixtures"


def main() -> int:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    fixtures = sorted(FIXTURES_DIR.glob("*.ndjson"))
    if not fixtures:
        print(f"error: no fixtures found in {FIXTURES_DIR}", file=sys.stderr)
        return 2

    checked = 0
    for path in fixtures:
        expect_invalid = path.name.startswith("07-invalid")
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if not line.strip():
                continue  # blank lines are ignored by every decoder
            checked += 1
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                parses = False
                obj = None
            else:
                parses = True

            validates = False
            if parses:
                try:
                    jsonschema.validate(obj, schema)
                    validates = True
                except jsonschema.ValidationError:
                    validates = False

            if expect_invalid and (parses and validates):
                print(f"FAIL {path.name}:{number}: expected invalid, got valid: {line}", file=sys.stderr)
                return 1
            if not expect_invalid and not (parses and validates):
                print(f"FAIL {path.name}:{number}: expected valid: {line}", file=sys.stderr)
                return 1

    print(f"OK: {checked} fixture lines across {len(fixtures)} files validated against the schema")
    return 0


if __name__ == "__main__":
    sys.exit(main())
