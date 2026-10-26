"""Offline personal replay and controlled comparisons; no keys or services."""

import argparse
import json
import sys
from datetime import timedelta
from pathlib import Path

from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.personal_replay import PersonalReplayRequest, PersonalReplaySettings
from copenhagen_fds.personal_replay import comparison_cases, dataset_cases
from copenhagen_fds.personal_replay_export import check_output_location, export_personal_replay, read_json, verify_personal_replay
from copenhagen_fds.synthetic.learning import read_learning_observations
from copenhagen_fds.synthetic.replay import instant


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    replay = commands.add_parser("replay", help="Replay purchases selected only by arrival time and identity")
    replay.add_argument("--as-of", required=True)
    replay.add_argument("--start", required=True)
    replay.add_argument("--end", required=True)
    replay.add_argument("--limit", type=int, default=6)
    compare = commands.add_parser("compare", help="Same amount/time and zero prior 24h counts across users")
    compare.add_argument("--cutoffs", nargs="+", required=True)
    compare.add_argument("--expired-at", required=True)
    compare.add_argument("--amount-minor", type=int, default=100000)
    compare.add_argument("--user-limit", type=int, default=2)
    for command in (replay, compare):
        command.add_argument("dataset", type=Path)
        command.add_argument("--settings", type=Path, required=True)
        command.add_argument("--users", nargs="*", default=())
        command.add_argument("--output", type=Path, required=True)
    verify = commands.add_parser("verify", help="Check artifact hashes and reproduce scores with recorded code")
    verify.add_argument("output", type=Path)
    show = commands.add_parser("show", help="Verify and print a compact feature comparison")
    show.add_argument("output", type=Path)
    show.add_argument("--feature", choices=tuple(FEATURE_SPECS), default="amount_minor")
    args = parser.parse_args(argv)
    try:
        if args.command in {"verify", "show"}:
            report = verify_personal_replay(args.output)
            if args.command == "show":
                summary = read_json(args.output / "comparisons.json")
                print("case_id | stage | Cold | personal | weight | combined")
                for row in summary["rows"]:
                    feature = next(f for f in row["features"] if f["feature_id"] == args.feature)
                    values = [row["case_id"], feature["stage"], feature["cold"], feature["personal"],
                              feature["personal_weight"], feature["combined"]]
                    print(" | ".join("null" if v is None else f"{v:.4f}" if isinstance(v, float) else str(v) for v in values))
                return 0
        else:
            check_output_location(args.dataset, args.output)
            settings = PersonalReplaySettings.model_validate(read_json(args.settings))
            observations = read_learning_observations(args.dataset)
            data = observations[0]
            if args.command == "replay":
                cases = dataset_cases(data, profile_as_of=args.as_of, start=args.start, end=args.end,
                                      limit=args.limit, user_ids=args.users)
            else:
                if instant(args.expired_at) < instant(args.cutoffs[-1]) + timedelta(seconds=settings.profile_valid_for_seconds):
                    raise ValueError("expiry point must reach the last profile's configured expiration")
                cases = comparison_cases(data, cutoffs=args.cutoffs, expired_at=args.expired_at,
                    amount_minor=args.amount_minor, user_ids=args.users, user_limit=args.user_limit)
            request = PersonalReplayRequest(settings=settings, cases=cases)
            report = export_personal_replay(args.dataset, args.output, request, observations=observations)
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, RecursionError):
        print("Personal replay failed: check settings, observable data, explicit times, identities and a new isolated output directory.", file=sys.stderr)
        return 2
    display = dict(report)
    if "code" in display:
        display["code"] = {k: v for k, v in display["code"].items() if k != "files"}
    print(json.dumps(display, ensure_ascii=False, sort_keys=True, indent=2))
    return 3 if report["status"] == "FAILED" else 0


if __name__ == "__main__":
    raise SystemExit(main())
