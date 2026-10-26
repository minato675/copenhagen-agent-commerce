"""Raw features preserve purchase identity, knowledge time and missingness."""

from copy import deepcopy
from datetime import datetime, timedelta
from itertools import permutations
import json

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.behavior import BehaviorEvaluationRequest
from copenhagen_fds.contracts.features import (
    FEATURE_SCHEMA_VERSION, FEATURE_SPECS, FeatureTransformInput, HistoryEvent,
    HistorySnapshot, RawFeatureValue, RawFeatureVector, StreamCoverage,
)
from copenhagen_fds.features import transform_features


NOW = datetime.fromisoformat("2026-09-09T10:00:00+09:00")
COUNT_FEATURES = tuple(name for name in FEATURE_SPECS if "count" in name)


@pytest.fixture
def feature_input():
    money = {"amount_minor": 10000, "currency": "KRW"}
    return {
        "evaluation": {
            "request": {
                "intent": {"purchase_request_id": "purchase-1", "source": "user_direct", "version": "intent-v1"},
                "context": {"evaluation_id": "evaluation-1", "user_id": "user-1", "purchase_request_id": "purchase-1"},
                "order": {
                    "purchase_request_id": "purchase-1",
                    "reference": {"snapshot_id": "checkout-1", "version": "2", "snapshot_hash": "a" * 64},
                    "orders": [{
                        "order_id": "order-1", "merchant_id": "merchant-1",
                        "items": [{"item_id": "item-1", "name": "합성 상품", "quantity": 1,
                                   "unit_price": deepcopy(money), "line_total": deepcopy(money)}],
                        "total": deepcopy(money),
                    }],
                    "total": deepcopy(money),
                },
            },
            "evaluated_at": NOW.isoformat(),
            "feature_schema_version": FEATURE_SCHEMA_VERSION,
            "requested_features": list(FEATURE_SPECS),
        },
        "current_occurred_at": (NOW - timedelta(minutes=1)).isoformat(),
        "current_available_at": NOW.isoformat(),
        "current_attempt_id": "attempt-current",
        "history": {
            "as_of": NOW.isoformat(),
            "purchases": collected(),
            "attempts": collected(),
            "events": [],
        },
    }


def collected(*, start=None, end=None):
    return {
        "status": "COLLECTED",
        "complete_from": (start or NOW - timedelta(days=1)).isoformat(),
        "complete_until": (end or NOW).isoformat(),
    }


def event(identity="event-1", *, purchase="prior-purchase", attempt=None,
          occurred=None, available=None, currency="KRW", order=None):
    occurred = occurred or NOW - timedelta(minutes=5)
    return {
        "event_id": identity,
        "kind": "PAYMENT_ATTEMPTED" if attempt is not None else "PURCHASE_REQUESTED",
        "user_id": "user-1", "purchase_request_id": purchase, "attempt_id": attempt,
        "order_id": order, "occurred_at": occurred.isoformat(),
        "available_at": (available or occurred).isoformat(), "currency": currency,
    }


def run(payload):
    return transform_features(FeatureTransformInput.model_validate(payload))


def by_name(result):
    return {feature.feature_id: feature for feature in result.features}


def set_money(payload, amount, currency):
    order = payload["evaluation"]["request"]["order"]
    for value in (order["total"], order["orders"][0]["total"],
                  order["orders"][0]["items"][0]["unit_price"],
                  order["orders"][0]["items"][0]["line_total"]):
        value.update(amount_minor=amount, currency=currency)


def test_full_manifest_has_exact_requested_features_and_no_anomaly_score(feature_input):
    data = FeatureTransformInput.model_validate(feature_input)
    result = transform_features(data)
    values = by_name(result)
    assert tuple(values) == tuple(FEATURE_SPECS)
    assert result.target == data.evaluation.target
    assert result.evaluated_at == NOW
    assert result.feature_schema_version == FEATURE_SCHEMA_VERSION
    assert result.validate_for_request(data.evaluation) is result
    assert values["amount_minor"].value == 10000
    assert values["kst_hour"].value == 9
    assert values["kst_day_of_week"].value == 2
    for name in COUNT_FEATURES:
        assert values[name].value == 0
        assert values[name].status == "AVAILABLE"
    assert all("score" not in item.model_dump() for item in result.features)


@pytest.mark.parametrize("amount,currency", [(0, "KRW"), (1, "JPY"), (101, "USD"), (99999999, "KRW")])
def test_amount_minor_unit_preserves_zero_and_currency_without_conversion(feature_input, amount, currency):
    set_money(feature_input, amount, currency)
    value = by_name(run(feature_input))["amount_minor"]
    assert (value.value, value.unit, value.currency, value.status) == (amount, "MINOR_UNIT", currency, "AVAILABLE")


@pytest.mark.parametrize("amount", [-1, 1.5, "100", True])
def test_amount_negative_or_coerced_input_is_rejected(feature_input, amount):
    set_money(feature_input, amount, "KRW")
    with pytest.raises(ValidationError):
        run(feature_input)


def test_amount_is_purchase_total_across_merchants(feature_input):
    order = feature_input["evaluation"]["request"]["order"]
    second = deepcopy(order["orders"][0])
    second.update(order_id="order-2", merchant_id="merchant-2")
    second["items"][0]["item_id"] = "item-2"
    order["orders"].append(second)
    order["total"]["amount_minor"] = 20000
    assert by_name(run(feature_input))["amount_minor"].value == 20000


@pytest.mark.parametrize("change", [
    {"unit": "PURCHASE_COUNT"}, {"currency": None}, {"window_seconds": 600},
    {"value": -1}, {"value": True}, {"value": "0"}, {"value": None},
])
def test_amount_unit_contract_rejects_incompatible_values(change):
    value = {"feature_id": "amount_minor", "status": "AVAILABLE", "value": 0,
             "unit": "MINOR_UNIT", "currency": "KRW"}
    with pytest.raises(ValidationError):
        RawFeatureValue.model_validate({**value, **change})


@pytest.mark.parametrize("feature,unit,value", [("kst_hour", "KST_HOUR", 24), ("kst_day_of_week", "KST_WEEKDAY", 7)])
def test_time_unit_contract_rejects_out_of_range_values(feature, unit, value):
    with pytest.raises(ValidationError):
        RawFeatureValue.model_validate({"feature_id": feature, "status": "AVAILABLE", "unit": unit, "value": value})


@pytest.mark.parametrize("state", ["NOT_COLLECTED", "INSUFFICIENT_HISTORY", "NOT_APPLICABLE"])
def test_missing_states_cannot_carry_zero(state):
    data = {"feature_id": "purchase_count_10m", "status": state, "unit": "PURCHASE_COUNT",
            "currency": "KRW", "window_seconds": 600}
    assert RawFeatureValue.model_validate(data).value is None
    with pytest.raises(ValidationError):
        RawFeatureValue.model_validate({**data, "value": 0})


def test_missing_history_and_current_time_do_not_fabricate_zero(feature_input):
    feature_input.update(history=None, current_occurred_at=None, current_available_at=None)
    values = by_name(run(feature_input))
    for name in (*COUNT_FEATURES, "kst_hour", "kst_day_of_week"):
        assert (values[name].status, values[name].value) == ("NOT_COLLECTED", None)
    assert values["amount_minor"].value == 10000


def test_missing_partial_coverage_only_exposes_windows_fully_observed(feature_input):
    feature_input["history"]["purchases"] = collected(start=NOW - timedelta(minutes=10))
    feature_input["history"]["attempts"] = {"status": "NOT_COLLECTED"}
    values = by_name(run(feature_input))
    assert (values["purchase_count_10m"].status, values["purchase_count_10m"].value) == ("AVAILABLE", 0)
    for name in ("purchase_count_1h", "purchase_count_24h"):
        assert (values[name].status, values[name].value) == ("INSUFFICIENT_HISTORY", None)
    assert values["attempt_count_10m"].status == "NOT_COLLECTED"


def test_missing_coverage_end_before_evaluation_is_insufficient(feature_input):
    feature_input["history"]["purchases"] = collected(end=NOW - timedelta(microseconds=1))
    assert by_name(run(feature_input))["purchase_count_10m"].status == "INSUFFICIENT_HISTORY"


def test_missing_not_applicable_stream_remains_distinct_from_observed_empty(feature_input):
    feature_input["history"]["purchases"] = {"status": "NOT_APPLICABLE"}
    values = by_name(run(feature_input))
    assert (values["purchase_count_10m"].status, values["purchase_count_10m"].value) == ("NOT_APPLICABLE", None)
    assert (values["attempt_count_10m"].status, values["attempt_count_10m"].value) == ("AVAILABLE", 0)


@pytest.mark.parametrize("feature", COUNT_FEATURES)
@pytest.mark.parametrize("position,expected", [("before_start", 0), ("at_start", 1), ("before_end", 1), ("at_end", 0), ("after_end", 0)])
def test_time_count_windows_include_start_and_exclude_evaluation(feature_input, feature, position, expected):
    seconds = FEATURE_SPECS[feature][1]
    moments = {
        "before_start": NOW - timedelta(seconds=seconds, microseconds=1),
        "at_start": NOW - timedelta(seconds=seconds),
        "before_end": NOW - timedelta(microseconds=1),
        "at_end": NOW,
        "after_end": NOW + timedelta(microseconds=1),
    }
    attempt = "prior-attempt" if feature.startswith("attempt") else None
    feature_input["history"]["events"] = [event(attempt=attempt, occurred=moments[position])]
    value = by_name(run(feature_input))[feature]
    assert (value.status, value.value) == ("AVAILABLE", expected)


@pytest.mark.parametrize("available,expected", [(NOW, 1), (NOW + timedelta(microseconds=1), 0)])
def test_leakage_availability_is_bounded_by_snapshot_and_evaluation(feature_input, available, expected):
    feature_input["history"]["events"] = [event(available=available)]
    assert by_name(run(feature_input))["purchase_count_10m"].value == expected


def test_leakage_future_conflicting_event_is_not_used_for_deduplication(feature_input):
    feature_input["history"]["events"] = [
        event(), event(currency="USD", available=NOW + timedelta(seconds=1)),
    ]
    assert by_name(run(feature_input))["purchase_count_10m"].value == 1


@pytest.mark.parametrize("conflict", ["time", "currency"])
def test_count_current_purchase_history_rejects_inconsistent_time_or_currency(feature_input, conflict):
    occurred = datetime.fromisoformat(feature_input["current_occurred_at"])
    current = event("current-purchase", purchase="purchase-1", occurred=occurred)
    if conflict == "time":
        current["occurred_at"] = (occurred - timedelta(minutes=1)).isoformat()
    else:
        current["currency"] = "USD"
    feature_input["history"]["events"] = [current]
    with pytest.raises(ValueError, match="current purchase history"):
        run(feature_input)


def test_leakage_current_purchase_conflicts_available_after_evaluation_are_ignored(feature_input):
    occurred = datetime.fromisoformat(feature_input["current_occurred_at"])
    current = event("current-purchase", purchase="purchase-1", occurred=occurred)
    unavailable_conflict = event(
        "current-purchase", purchase="purchase-1", currency="USD",
        occurred=occurred - timedelta(minutes=1), available=NOW + timedelta(microseconds=1),
    )
    feature_input["history"]["events"] = [current, unavailable_conflict, event("prior-purchase")]
    values = by_name(run(feature_input))
    assert values["purchase_count_10m"].value == 1
    assert values["kst_hour"].value == occurred.hour


def test_leakage_future_snapshot_is_rejected_even_without_events(feature_input):
    feature_input["history"]["as_of"] = (NOW + timedelta(microseconds=1)).isoformat()
    with pytest.raises(ValidationError, match="future history"):
        run(feature_input)


def test_leakage_old_snapshot_cannot_claim_full_current_window(feature_input):
    old = NOW - timedelta(minutes=1)
    feature_input["history"].update(as_of=old.isoformat(), purchases=collected(end=old), attempts=collected(end=old))
    feature_input["history"]["events"] = [event(available=NOW)]
    for name in COUNT_FEATURES:
        assert by_name(run(feature_input))[name].status == "INSUFFICIENT_HISTORY"


def test_count_multimerchant_purchase_once_and_payment_retries_separately(feature_input):
    feature_input["current_occurred_at"] = (NOW - timedelta(minutes=6)).isoformat()
    feature_input["history"]["events"] = [
        event("merchant-copy-1", order="merchant-order-1"),
        event("merchant-copy-2", order="merchant-order-2"),
        event("current-purchase", purchase="purchase-1",
              occurred=datetime.fromisoformat(feature_input["current_occurred_at"])),
        event("retry-1", purchase="purchase-1", attempt="attempt-prior-1", order="order-1"),
        event("retry-2", purchase="purchase-1", attempt="attempt-prior-2", order="order-1"),
        event("current-attempt", purchase="purchase-1", attempt="attempt-current", order="order-1"),
        event("other-attempt", purchase="prior-purchase", attempt="other-attempt", order="other-order"),
    ]
    values = by_name(run(feature_input))
    for suffix in ("10m", "1h", "24h"):
        assert values[f"purchase_count_{suffix}"].value == 1
        assert values[f"attempt_count_{suffix}"].value == 3


def test_count_currency_is_partitioned_without_exchange_rate_guessing(feature_input):
    feature_input["history"]["events"] = [
        event("krw"), event("usd", purchase="usd-purchase", currency="USD"),
        event("jpy-attempt", purchase="jpy-purchase", attempt="jpy-attempt", currency="JPY"),
    ]
    values = by_name(run(feature_input))
    assert values["purchase_count_10m"].value == 1
    assert values["attempt_count_10m"].value == 0
    assert all(values[name].currency == "KRW" for name in COUNT_FEATURES)


def test_count_duplicate_event_and_alias_ids_do_not_inflate_counts(feature_input):
    first = event()
    retry = event("attempt-event", attempt="prior-attempt", order="order-old")
    feature_input["history"]["events"] = [
        first, deepcopy(first), {**first, "event_id": "alias", "order_id": "merchant-copy"},
        retry, {**retry, "event_id": "attempt-alias", "available_at": NOW.isoformat()},
    ]
    values = by_name(run(feature_input))
    assert values["purchase_count_10m"].value == 1
    assert values["attempt_count_10m"].value == 1


@pytest.mark.parametrize("change", [
    {"currency": "USD"}, {"purchase_request_id": "other-purchase"},
    {"available_at": NOW.isoformat()}, {"order_id": "different-order"},
])
def test_duplicate_event_id_conflicting_contents_are_rejected(feature_input, change):
    original = event()
    feature_input["history"]["events"] = [original, {**original, **change}]
    with pytest.raises(ValueError, match="duplicate event ID"):
        run(feature_input)


@pytest.mark.parametrize("change", [
    {"currency": "USD"},
    {"occurred_at": (NOW - timedelta(minutes=6)).isoformat()},
])
def test_duplicate_purchase_alias_with_conflicting_fact_is_rejected(feature_input, change):
    original = event()
    feature_input["history"]["events"] = [original, {**original, "event_id": "alias", **change}]
    with pytest.raises(ValueError, match="logical initiation identity"):
        run(feature_input)


def test_duplicate_attempt_alias_cannot_change_order_identity(feature_input):
    original = event(attempt="retry-1", order="order-a")
    feature_input["history"]["events"] = [original, {**original, "event_id": "alias", "order_id": "order-b"}]
    with pytest.raises(ValueError, match="logical initiation identity"):
        run(feature_input)


@pytest.mark.parametrize("time,hour,weekday", [
    ("2026-09-08T14:59:59+00:00", 23, 1),
    ("2026-09-08T15:00:00+00:00", 0, 2),
    ("2026-09-08T23:59:59+09:00", 23, 1),
    ("2026-09-09T00:00:00+09:00", 0, 2),
])
def test_time_kst_day_rollover_uses_purchase_time_not_evaluation(feature_input, time, hour, weekday):
    feature_input["current_occurred_at"] = time
    values = by_name(run(feature_input))
    assert (values["kst_hour"].value, values["kst_day_of_week"].value) == (hour, weekday)
    assert values["kst_hour"].currency is None


@pytest.mark.parametrize("change", [
    {"current_available_at": None},
    {"current_occurred_at": None},
    {"current_available_at": (NOW + timedelta(microseconds=1)).isoformat()},
    {"current_occurred_at": (NOW + timedelta(microseconds=1)).isoformat()},
    {"current_available_at": (NOW - timedelta(minutes=2)).isoformat()},
])
def test_time_current_event_requires_consistent_known_timestamps(feature_input, change):
    with pytest.raises(ValidationError):
        run({**feature_input, **change})


@pytest.mark.parametrize("change", [
    {"available_at": (NOW - timedelta(hours=1)).isoformat()},
    {"occurred_at": "2026-09-09T09:55:00"},
    {"attempt_id": "unexpected"},
    {"kind": "PAYMENT_ATTEMPTED"},
])
def test_leakage_event_contract_rejects_ambiguous_time_or_attempt_identity(change):
    with pytest.raises(ValidationError):
        HistoryEvent.model_validate({**event(), **change})


@pytest.mark.parametrize("coverage", [
    {"status": "COLLECTED"},
    collected(start=NOW + timedelta(seconds=1)),
    {"status": "NOT_COLLECTED", "complete_from": NOW.isoformat()},
    {"status": "NOT_APPLICABLE", "complete_until": NOW.isoformat()},
])
def test_missing_coverage_contract_cannot_claim_unobserved_interval(coverage):
    with pytest.raises(ValidationError):
        StreamCoverage.model_validate(coverage)


def test_missing_stream_cannot_include_events_or_future_completeness(feature_input):
    history = feature_input["history"]
    with pytest.raises(ValidationError):
        HistorySnapshot.model_validate({**history, "purchases": {"status": "NOT_COLLECTED"}, "events": [event()]})
    with pytest.raises(ValidationError):
        HistorySnapshot.model_validate({**history, "purchases": collected(end=NOW + timedelta(seconds=1))})


def test_binding_history_rejects_different_user(feature_input):
    feature_input["history"]["events"] = [{**event(), "user_id": "another-user"}]
    with pytest.raises(ValidationError, match="evaluated user"):
        run(feature_input)


@pytest.mark.parametrize("change", [
    {"feature_schema_version": "unsupported-v1"},
    {"requested_features": ["unimplemented_feature"]},
    {"requested_features": ["amount_minor", "amount_minor"]},
])
def test_binding_input_rejects_wrong_schema_or_requested_features(feature_input, change):
    feature_input["evaluation"].update(change)
    with pytest.raises(ValidationError):
        run(feature_input)


@pytest.mark.parametrize("mutation", ["user", "order_version", "time", "schema", "feature_set", "currency"])
def test_binding_result_rejects_different_request(feature_input, mutation):
    result = run(feature_input)
    request = deepcopy(feature_input["evaluation"])
    if mutation == "user":
        request["request"]["context"]["user_id"] = "another-user"
    elif mutation == "order_version":
        request["request"]["order"]["reference"]["version"] = "another-version"
    elif mutation == "time":
        request["evaluated_at"] = (NOW + timedelta(seconds=1)).isoformat()
    elif mutation == "schema":
        request["feature_schema_version"] = "another-schema"
    elif mutation == "feature_set":
        request["requested_features"] = ["amount_minor"]
    else:
        altered = deepcopy(feature_input)
        set_money(altered, 10000, "USD")
        request = altered["evaluation"]
    with pytest.raises(ValueError):
        result.validate_for_request(BehaviorEvaluationRequest.model_validate(request))


def test_binding_missing_or_duplicate_feature_cannot_look_complete(feature_input):
    result = run(feature_input)
    data = result.model_dump(mode="json")
    data["features"].pop()
    incomplete = RawFeatureVector.model_validate(data)
    with pytest.raises(ValueError, match="every requested feature"):
        incomplete.validate_for_request(FeatureTransformInput.model_validate(feature_input).evaluation)
    data["features"].append(deepcopy(data["features"][0]))
    with pytest.raises(ValidationError, match="unique"):
        RawFeatureVector.model_validate(data)


def test_reproducible_event_permutations_and_requested_order_return_identical_json(feature_input):
    events = [event("one", purchase="one"), event("two", purchase="two", attempt="two"), event("alias", purchase="one")]
    outputs = set()
    for ordering in permutations(events):
        feature_input["history"]["events"] = list(ordering)
        for features in (list(FEATURE_SPECS), list(reversed(FEATURE_SPECS))):
            feature_input["evaluation"]["requested_features"] = features
            outputs.add(run(feature_input).model_dump_json())
    assert len(outputs) == 1


def test_reproducible_strict_json_round_trip_and_input_immutability(feature_input):
    feature_input["history"]["events"] = [event(), event("attempt", attempt="retry")]
    original = deepcopy(feature_input)
    data = FeatureTransformInput.model_validate(feature_input)
    decoded = FeatureTransformInput.model_validate_json(data.model_dump_json())
    result = transform_features(data)
    assert transform_features(decoded).model_dump_json() == result.model_dump_json()
    assert RawFeatureVector.model_validate_json(result.model_dump_json()) == result
    assert RawFeatureVector.model_validate(result.model_dump(mode="json")) == result
    assert feature_input == original


def test_reproducible_requested_subset_has_no_unrequested_features(feature_input):
    feature_input["evaluation"]["requested_features"] = ["kst_hour", "amount_minor"]
    result = run(feature_input)
    assert tuple(by_name(result)) == ("amount_minor", "kst_hour")
    assert result.validate_for_request(FeatureTransformInput.model_validate(feature_input).evaluation) is result


def test_count_current_attempt_identity_must_be_explicit_even_when_none(feature_input):
    missing = deepcopy(feature_input)
    missing.pop("current_attempt_id")
    with pytest.raises(ValidationError, match="current_attempt_id"):
        run(missing)
    feature_input["current_attempt_id"] = None
    feature_input["history"]["events"] = [event(attempt="prior-attempt", purchase="purchase-1")]
    assert by_name(run(feature_input))["attempt_count_10m"].value == 1


def test_time_current_purchase_can_occur_exactly_at_evaluation(feature_input):
    feature_input["current_occurred_at"] = NOW.isoformat()
    assert by_name(run(feature_input))["kst_hour"].value == 10


@pytest.mark.parametrize("change", [
    {"unit": "ATTEMPT_COUNT"}, {"currency": None}, {"window_seconds": 3600},
])
def test_count_unit_and_window_cannot_be_relabelled(change):
    value = {"feature_id": "purchase_count_10m", "status": "AVAILABLE", "value": 0,
             "unit": "PURCHASE_COUNT", "currency": "KRW", "window_seconds": 600}
    with pytest.raises(ValidationError):
        RawFeatureValue.model_validate({**value, **change})


def test_time_unit_cannot_claim_currency():
    with pytest.raises(ValidationError):
        RawFeatureValue.model_validate({"feature_id": "kst_hour", "status": "AVAILABLE", "value": 0,
                                       "unit": "KST_HOUR", "currency": "KRW"})


def test_cli_reproducible_json_output_matches_direct_transform(feature_input, tmp_path, capsys):
    from copenhagen_fds.feature_cli import main

    input_file = tmp_path / "features.json"
    input_file.write_text(json.dumps(feature_input), encoding="utf-8-sig")
    assert main([str(input_file)]) == 0
    output = capsys.readouterr()
    assert not output.err
    assert RawFeatureVector.model_validate_json(output.out) == run(feature_input)


@pytest.mark.parametrize("failure", ["missing_file", "invalid_unicode", "malformed_json", "invalid_contract", "conflicting_event"])
def test_cli_invalid_input_is_rejected_without_raw_data_or_path(feature_input, tmp_path, capsys, failure):
    from copenhagen_fds.feature_cli import main

    marker = "SYNTHETIC-PRIVATE-INPUT-SHOULD-NOT-LEAK"
    input_file = tmp_path / f"{marker}.json"
    if failure == "invalid_unicode":
        input_file.write_bytes(b"\xff\xfe\xff")
    elif failure == "malformed_json":
        input_file.write_text("{" + marker, encoding="utf-8")
    elif failure == "invalid_contract":
        feature_input["unexpected_secret"] = marker
        input_file.write_text(json.dumps(feature_input), encoding="utf-8")
    elif failure == "conflicting_event":
        original = event(marker)
        feature_input["history"]["events"] = [original, {**original, "currency": "USD"}]
        input_file.write_text(json.dumps(feature_input), encoding="utf-8")
    with pytest.raises(SystemExit) as error:
        main([str(input_file)])
    assert error.value.code == 2
    output = capsys.readouterr()
    assert output.out == ""
    assert output.err == "Feature input could not be read or failed contract validation.\n"
    assert marker not in output.err


def test_public_feature_contract_exports():
    from copenhagen_fds import contracts

    for model in (FeatureTransformInput, HistoryEvent, HistorySnapshot, RawFeatureValue, RawFeatureVector, StreamCoverage):
        assert getattr(contracts, model.__name__) is model
        assert model.__name__ in contracts.__all__
