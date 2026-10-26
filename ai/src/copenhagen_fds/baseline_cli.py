"""Inspect explicitly supplied Cold candidates; do not fit or call a provider."""

import argparse
import json
from pathlib import Path

from pydantic import ValidationError

from copenhagen_fds.baselines import _unique_json, select_cold_baselines
from copenhagen_fds.contracts.baselines import BaselineSelectionRequest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="JSON containing request, population and policy")
    args = parser.parse_args(argv)
    try:
        data = json.loads(args.input.read_text(encoding="utf-8-sig"), object_pairs_hook=_unique_json)
        if not isinstance(data, dict) or set(data) != {"request", "population", "policy"}:
            raise ValueError("unexpected input envelope")
        request = BaselineSelectionRequest.model_validate(data["request"])
    except (OSError, UnicodeError, ValueError, RecursionError, ValidationError):
        parser.exit(2, "Baseline input could not be read or failed request validation.\n")
    result = select_cold_baselines(request, population=data["population"], policy=data["policy"])
    print(result.model_dump_json(indent=2))
    return 0 if all(f.status == "SELECTED" for f in result.features) else 1


if __name__ == "__main__":
    raise SystemExit(main())
