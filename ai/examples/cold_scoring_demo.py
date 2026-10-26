"""Fixed synthetic raw-vector demo, not a raw-event pipeline or fraud benchmark."""

import argparse
import json
from pathlib import Path

from copenhagen_fds.baselines import select_cold_baselines
from copenhagen_fds.cold_scoring import score_cold_features
from copenhagen_fds.contracts.baselines import BaselineSelectionRequest
from copenhagen_fds.contracts.features import FEATURE_SPECS, RawFeatureValue, RawFeatureVector
from copenhagen_fds.contracts.scoring import ColdScoringInput


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=["representative", "empty_history", "missing_history",
                                               "missing_input", "no_reference", "not_applicable"],
                        default="representative")
    args = parser.parse_args()
    fixture = json.loads(Path(__file__).with_name("cold_baseline_input.json").read_text(encoding="utf-8"))
    request = BaselineSelectionRequest.model_validate(fixture["request"])
    no_reference = args.scenario == "no_reference"
    selection = select_cold_baselines(request,
                                      population=None if no_reference else fixture["population"],
                                      policy=None if no_reference else fixture["policy"])
    values = {"amount_minor": 10000, "purchase_count_10m": 6, "kst_hour": 0}
    states = {name: "AVAILABLE" for name in values}
    if args.scenario == "empty_history":
        values["purchase_count_10m"] = 0  # explicitly supplied, complete empty window
    elif args.scenario in {"missing_history", "not_applicable"}:
        states["purchase_count_10m"] = "INSUFFICIENT_HISTORY" if args.scenario == "missing_history" else "NOT_APPLICABLE"
        values["purchase_count_10m"] = None
    elif args.scenario == "missing_input":
        states["kst_hour"], values["kst_hour"] = "NOT_COLLECTED", None
    features = []
    for name, value in values.items():
        unit, window = FEATURE_SPECS[name]
        features.append(RawFeatureValue(feature_id=name, status=states[name], value=value,
                                        unit=unit, window_seconds=window,
                                        currency=None if name == "kst_hour" else "KRW"))
    supplied = ColdScoringInput(
        request=request, trace_id="synthetic-scoring-demo",
        raw_features=RawFeatureVector(target=request.evaluation.target,
                                     evaluated_at=request.evaluation.evaluated_at, features=features),
        selection=selection,
    )
    print(score_cold_features(supplied).model_dump_json(indent=2))


if __name__ == "__main__":
    main()
