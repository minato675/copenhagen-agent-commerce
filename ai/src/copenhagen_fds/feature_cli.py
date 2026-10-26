"""Inspect raw feature values from an explicitly supplied JSON snapshot."""

import argparse
from pathlib import Path

from pydantic import ValidationError

from copenhagen_fds.contracts.features import FeatureTransformInput
from copenhagen_fds.features import transform_features


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="supplied feature input JSON")
    args = parser.parse_args(argv)
    try:
        data = FeatureTransformInput.model_validate_json(args.input.read_text(encoding="utf-8-sig"))
        result = transform_features(data)
    except (OSError, UnicodeError, ValidationError, ValueError):
        # Do not echo arbitrary input identifiers, values or local paths.
        parser.exit(2, "Feature input could not be read or failed contract validation.\n")
    print(result.model_dump_json(indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
