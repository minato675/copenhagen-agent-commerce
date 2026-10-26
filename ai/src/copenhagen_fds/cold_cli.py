"""Evaluate a supplied Cold JSON bundle without connection settings or services."""

import argparse
import json
from pathlib import Path

from copenhagen_fds.baselines import _unique_json
from copenhagen_fds.cold import evaluate_cold
from copenhagen_fds.contracts.cold import ColdEvaluationInput


def _invalid_constant(value):
    raise ValueError("nonstandard JSON numeric constant")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="JSON with input, population and policy (explicit nulls allowed)")
    args = parser.parse_args(argv)
    try:
        bundle = json.loads(args.input.read_text(encoding="utf-8-sig"),
                            object_pairs_hook=_unique_json, parse_constant=_invalid_constant)
        if not isinstance(bundle, dict) or set(bundle) != {"input", "population", "policy"}:
            raise ValueError("unexpected Cold input envelope")
        supplied = ColdEvaluationInput.model_validate(bundle["input"])
        result = evaluate_cold(supplied, population=bundle["population"], policy=bundle["policy"])
    except (OSError, UnicodeError, ValueError, RecursionError):
        # Do not print validation details, arbitrary identifiers, file paths or inputs.
        parser.exit(2, "Cold input could not be read or failed contract validation.\n")
    except ArithmeticError:
        parser.exit(3, "Cold evaluation failed before a result could be produced.\n")
    print(result.model_dump_json(indent=2))
    if result.behavior.status == "FAILED":
        return 3
    return 0 if result.behavior.coverage == "COMPLETE" else 1


if __name__ == "__main__":
    raise SystemExit(main())
