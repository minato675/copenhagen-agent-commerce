import json
from datetime import datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.personal_replay import PersonalReplayRequest, PersonalReplaySettings
from copenhagen_fds.personal_replay import comparison_cases
from copenhagen_fds.synthetic.learning import OBSERVABLE_TABLES
from copenhagen_fds.synthetic.storage import DATASET_VERSION, canonical, digest


@pytest.fixture
def replay_cases(tmp_path):
    start = datetime.fromisoformat("2026-01-01T12:00:00+09:00")
    data = {name: [] for name in OBSERVABLE_TABLES}
    for user, amount in (("a", 10000), ("b", 100000)):
        data["users"].append(dict(user_id=user, currency="KRW", registered_at="2025-01-01T00:00:00+09:00",
                                  collection_started_at="2025-12-01T00:00:00+09:00"))
        for day in range(5):
            at = start + timedelta(days=day)
            pid = f"{user}-{day}"
            merchants = [dict(order_id=pid, merchant_id="merchant", amount_minor=amount)]
            data["purchases"].append(dict(user_id=user, purchase_request_id=pid, currency="KRW", amount_minor=amount,
                occurred_at=at.isoformat(), available_at=at.isoformat(), order_snapshot_id=pid,
                order_version="v1", snapshot_hash="a" * 64, merchant_orders=merchants))
            data["events"].append(dict(event_id=pid, user_id=user, purchase_request_id=pid, currency="KRW",
                order_id=pid, kind="PURCHASE_REQUESTED", attempt_id=None, occurred_at=at.isoformat(), available_at=at.isoformat()))
            reviewed = at + timedelta(minutes=1)
            data["feedback"].append(dict(feedback_id=pid, user_id=user, purchase_request_id=pid,
                classification="NORMAL_CONFIRMED", source="SYNTHETIC_REVIEW", occurred_at=reviewed.isoformat(), available_at=reviewed.isoformat()))
    settings = json.loads((Path(__file__).resolve().parents[4] / "examples/personal-replay-settings.json").read_text(encoding="utf-8"))
    settings["profile"]["min_samples"] = 2
    settings["profile_valid_for_seconds"] = 3 * 86400
    for feature in settings["personalization"]["features"]:
        feature["readiness"].update(minimum_samples=2, full_samples=4, minimum_observed_seconds=0.0,
            full_observed_seconds=86400.0, fresh_for_seconds=604800.0, stale_after_seconds=864000.0,
            minimum_available_fraction=0.0, full_available_fraction=1.0)
    settings = PersonalReplaySettings.model_validate(settings)
    cutoffs = ["2026-01-02T13:00:00+09:00", "2026-01-03T13:00:00+09:00", "2026-01-04T13:00:00+09:00"]
    expired = "2026-01-08T13:00:00+09:00"

    def request(source=None, users=("a", "b")):
        return PersonalReplayRequest(settings=settings, cases=comparison_cases(data if source is None else source,
            cutoffs=cutoffs, expired_at=expired, amount_minor=30000, user_ids=users))

    def write(source=None, folder="dataset"):
        dataset = tmp_path / folder
        dataset.mkdir()
        files = {}
        for name in OBSERVABLE_TABLES:
            path = dataset / f"{name}.jsonl"
            records = (data if source is None else source)[name]
            path.write_text("".join(canonical(row) + "\n" for row in records), encoding="utf-8")
            files[path.name] = dict(sha256=digest(path), rows=len(records))
        (dataset / "manifest.json").write_text(canonical(dict(dataset_version=DATASET_VERSION, origin="SYNTHETIC", files=files)), encoding="utf-8")
        return dataset

    return SimpleNamespace(data=data, settings=settings, request=request, write=write, cutoffs=cutoffs, expired=expired, start=start)
