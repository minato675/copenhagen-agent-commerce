"""Point-in-time synthetic replay and controlled same-input personal comparisons."""

from datetime import timedelta, timezone

from copenhagen_fds.baselines import select_cold_baselines
from copenhagen_fds.contracts.baselines import BaselineSelectionRequest
from copenhagen_fds.contracts.features import FEATURE_SPECS, FeatureTransformInput
from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest
from copenhagen_fds.contracts.personal_replay import PersonalReplayCase, PersonalReplayRequest, PersonalReplayResult, PersonalReplayRow
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, PublishedProfileInput, personalization_hash
from copenhagen_fds.contracts.scoring import ColdScoringInput
from copenhagen_fds.features import transform_features
from copenhagen_fds.personal_evaluation import evaluate_personalized_features
from copenhagen_fds.personal_profiles import build_personal_profile
from copenhagen_fds.profile_policy_replay import policy_observations_from_replay
from copenhagen_fds.profile_policy_updates import initialize_profile_policy
from copenhagen_fds.synthetic.learning import _visible_unique, synthetic_learning_inputs
from copenhagen_fds.synthetic.replay import ReplayIndex, instant


def _user_data(data, user_id):
    names = ("users", "purchases", "events", "outcomes", "feedback", "coverage_gaps")
    selected = {name: [r for r in data[name] if r["user_id"] == user_id] for name in names}
    if len(selected["users"]) != 1:
        raise ValueError("replay requires one observable user identity")
    return selected


def _publication(data, case, settings):
    if case.profile_as_of is None:
        return None
    at = case.profile_as_of.astimezone(timezone.utc)
    target = case.input.evaluation.target
    user_data = _user_data(data, target.user_id)
    inputs = synthetic_learning_inputs(user_data, as_of=at, current_purchase_request_id=target.purchase_request_id)
    # Provenance is the selected observable sample content, not full files that
    # include future reviews or evaluator-only labels.
    source = "personal-replay-observations-v1"
    rows = policy_observations_from_replay(inputs, origin="SYNTHETIC", source_reference=source)
    version = "personal-replay-v1-" + personalization_hash({
        "observations": [r.model_dump() for r in rows], "as_of": at,
        "profile": settings.profile.model_dump(), "ttl": settings.profile_valid_for_seconds,
    })
    request = PersonalProfileRequest(user_id=target.user_id, currency=case.input.evaluation.request.order.total.currency,
        reference=dict(scope="PERSONAL", user_id=target.user_id,
            profile_id="personal-replay-" + personalization_hash(target.user_id), version=version),
        state_version=version, origin="SYNTHETIC", source_reference=source,
        as_of=at, generated_at=at, valid_until=at + timedelta(seconds=settings.profile_valid_for_seconds), config=settings.profile)
    snapshot = build_personal_profile(request, tuple(r.sample for r in rows))
    state = initialize_profile_policy(snapshot, rows, settings.admission)
    return PublishedProfileInput(state=state, expected_reference=state.published.profile.reference,
        expected_policy_state_version=state.state_version, available_at=at)


def run_personal_replay(data: dict, supplied: PersonalReplayRequest) -> PersonalReplayResult:
    """Simulate publication at explicit cutoffs; never inspect truth/personas or mutate data."""
    supplied = PersonalReplayRequest.model_validate(supplied.model_dump())
    settings, rows = supplied.settings, []
    for case in supplied.cases:
        user = _user_data(data, case.input.evaluation.target.user_id)["users"][0]
        if user["currency"] != case.input.evaluation.request.order.total.currency:
            raise ValueError("replay currency must match the observable user")
        policy = settings.cold_policy
        baseline_request = BaselineSelectionRequest(evaluation=case.input.evaluation, population_reference=None,
            policy_id=policy.policy_id if policy else None, policy_version=policy.version if policy else None)
        cold = ColdScoringInput(request=baseline_request, raw_features=transform_features(case.input),
            selection=select_cold_baselines(baseline_request, policy=policy), trace_id="personal-replay-v1")
        request = PersonalizedScoringInput(cold=cold, config=settings.personalization,
            personal=_publication(data, case, settings))
        evaluation = evaluate_personalized_features(request, settings.scoring)
        rows.append(PersonalReplayRow(case=case, input=request, evaluation=evaluation))
    return PersonalReplayResult(request_sha256=personalization_hash(supplied), rows=rows).validate_for_request(supplied)


def dataset_cases(data, *, profile_as_of, start, end, limit=6, user_ids=()):
    """Choose purchase arrivals by time/identity only; retries are separate evaluation points."""
    cutoff, start, end = (instant(v) if isinstance(v, str) else v for v in (profile_as_of, start, end))
    if not cutoff <= start < end or type(limit) is not int or not 1 <= limit <= 50:
        raise ValueError("replay needs ordered cutoffs and a purchase limit from 1 to 50")
    selected = dict(data)
    selected["purchases"] = _visible_unique(data["purchases"], "purchase_request_id", end)
    selected["events"] = _visible_unique(data["events"], "event_id", end)
    index = ReplayIndex(selected)
    if not set(user_ids).issubset(index.users):
        raise ValueError("unknown requested replay user")
    purchases = sorted((p for p in selected["purchases"] if start <= instant(p["available_at"]) < end
        and (not user_ids or p["user_id"] in user_ids)), key=lambda p: (instant(p["available_at"]), p["purchase_request_id"]))[:limit]
    cases = []
    for purchase in purchases:
        for feature_input in index.evaluation_points(purchase["purchase_request_id"]):
            if start <= feature_input.evaluation.evaluated_at < end:
                cases.append(PersonalReplayCase(case_id="dataset-" + feature_input.evaluation.target.evaluation_id,
                    mode="DATASET", profile_as_of=cutoff, input=feature_input))
    if not cases:
        raise ValueError("no observable purchases within the requested interval")
    return tuple(cases)


def _probe_input(user_id, currency, amount, occurred_at, evaluated_at):
    """A declared counterfactual purchase with no prior 24h activity, shared across users."""
    purchase_id = "probe-" + personalization_hash([user_id, currency, amount, occurred_at])
    money = dict(amount_minor=amount, currency=currency)
    identity = personalization_hash([purchase_id, evaluated_at])
    return FeatureTransformInput.model_validate({
        "evaluation": {"request": {
            "context": {"evaluation_id": identity, "user_id": user_id, "purchase_request_id": purchase_id},
            "intent": {"purchase_request_id": purchase_id, "source": "unknown"},
            "order": {"purchase_request_id": purchase_id,
                "reference": {"snapshot_id": purchase_id, "version": "controlled-probe-v1", "snapshot_hash": personalization_hash(money)},
                "orders": [{"order_id": purchase_id, "merchant_id": "controlled-probe", "total": money,
                    "items": [{"item_id": purchase_id, "name": "Controlled comparison", "quantity": 1,
                               "unit_price": money, "line_total": money}]}], "total": money}},
            "evaluated_at": evaluated_at, "feature_schema_version": "cold-features-v1", "requested_features": list(FEATURE_SPECS)},
        "current_occurred_at": occurred_at, "current_available_at": occurred_at, "current_attempt_id": None,
        "history": {"as_of": evaluated_at, "events": [],
            "purchases": {"status": "COLLECTED", "complete_from": evaluated_at - timedelta(days=1), "complete_until": evaluated_at},
            "attempts": {"status": "COLLECTED", "complete_from": evaluated_at - timedelta(days=1), "complete_until": evaluated_at}},
    })


def comparison_cases(data, *, cutoffs, expired_at, amount_minor=100000, user_ids=(), user_limit=2):
    """Compare observed users at common probe points; no selection by outcomes or scores."""
    cutoffs = tuple(instant(v) if isinstance(v, str) else v for v in cutoffs)
    expired_at = instant(expired_at) if isinstance(expired_at, str) else expired_at
    if not cutoffs or tuple(sorted(set(cutoffs))) != cutoffs or expired_at <= cutoffs[-1]:
        raise ValueError("comparison cutoffs must be unique, increasing, and precede the expiry check")
    if type(user_limit) is not int or not 1 <= user_limit <= 10 or type(amount_minor) is not int or amount_minor < 0:
        raise ValueError("comparison requires a bounded user limit and nonnegative integer amount")
    by_id = {u["user_id"]: u for u in data["users"]}
    if len(by_id) != len(data["users"]) or len(set(user_ids)) != len(user_ids) or not set(user_ids).issubset(by_id):
        raise ValueError("comparison requires unique observable user identities")
    users = [by_id[name] for name in user_ids] if user_ids else sorted(
        (u for u in by_id.values() if instant(u["collection_started_at"]) <= cutoffs[0]), key=lambda u: u["user_id"])[:user_limit]
    if not users or len({u["currency"] for u in users}) != 1:
        raise ValueError("same-input comparisons require observed users sharing one currency")
    cases = []
    for user in users:
        name = user["user_id"]
        if instant(user["registered_at"]) > cutoffs[0]:
            raise ValueError("comparison user must be registered at the first point")
        points = [("cold", None, cutoffs[0], cutoffs[0] + timedelta(microseconds=1))]
        points += [(f"point-{i}", at, at, at + timedelta(microseconds=1)) for i, at in enumerate(cutoffs)]
        points.append(("expiry", cutoffs[-1], cutoffs[-1], expired_at))
        for label, as_of, occurred, evaluated in points:
            supplied = _probe_input(name, user["currency"], amount_minor, occurred, evaluated)
            if supplied.evaluation.target.purchase_request_id in {p["purchase_request_id"] for p in data["purchases"]}:
                raise ValueError("controlled probe must not replace a dataset purchase")
            cases.append(PersonalReplayCase(case_id=f"probe-{name}-{label}", mode="CONTROLLED_PROBE",
                profile_as_of=as_of, input=supplied))
    return tuple(cases)
