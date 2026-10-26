"""Synthetic semantic lineage and behavioral invariants for S15P21A207-356."""

from copy import deepcopy
from datetime import timedelta
import hashlib
import json

import pytest

from copenhagen_fds.contracts.features import FeatureTransformInput
from copenhagen_fds.features import transform_features
from copenhagen_fds.synthetic.replay import ReplayIndex
from copenhagen_fds.temporal_benchmark.semantic_cases import (
    INJECTION, SEMANTIC_RECIPES, SemanticExpected, SemanticObservation,
    bind_semantic_observation, build_semantic_case,
)


@pytest.fixture
def source_data():
    return dict(
        users=[dict(user_id="u", currency="KRW", registered_at="2026-01-01T00:00:00+09:00",
                    collection_started_at="2026-01-01T00:00:00+09:00")],
        purchases=[dict(purchase_request_id="p", user_id="u", currency="KRW", amount_minor=10001,
            occurred_at="2026-01-03T12:00:00+09:00", available_at="2026-01-03T12:00:00+09:00",
            order_snapshot_id="s", order_version="v1", snapshot_hash="a" * 64,
            merchant_orders=[dict(order_id="o1", merchant_id="m1", amount_minor=5000),
                             dict(order_id="o2", merchant_id="m2", amount_minor=5001)])],
        events=[dict(event_id="prior", kind="PURCHASE_REQUESTED", user_id="u", purchase_request_id="prior",
            order_id="o0", attempt_id=None, currency="KRW", occurred_at="2026-01-03T11:30:00+09:00",
            available_at="2026-01-03T11:30:00+09:00")], coverage_gaps=[],
    )


@pytest.fixture
def feature(source_data):
    return ReplayIndex(source_data).feature_input("p")


@pytest.mark.parametrize("recipe", SEMANTIC_RECIPES)
def test_semantic_recipes_preserve_all_behavioral_values(feature, recipe):
    original = feature.model_dump_json()
    observation, expected = build_semantic_case(feature, recipe)
    bound, analysis = bind_semantic_observation(feature, observation)
    assert feature.model_dump_json() == original
    before, after = feature.model_dump(), bound.model_dump()
    before["evaluation"]["request"].pop("order")
    before["evaluation"]["request"].pop("intent")
    after["evaluation"]["request"].pop("order")
    after["evaluation"]["request"].pop("intent")
    assert before == after
    assert analysis.context == feature.evaluation.request.context
    assert analysis.order == bound.evaluation.request.order
    assert (analysis.user_request.source, analysis.user_request.version) == (
        bound.evaluation.request.intent.source, bound.evaluation.request.intent.version,
    )
    assert observation.original_order_reference == feature.evaluation.request.order.reference
    old_values = [(f.feature_id, f.value, f.status) for f in transform_features(feature).features]
    new_values = [(f.feature_id, f.value, f.status) for f in transform_features(bound).features]
    assert old_values == new_values
    assert expected.origin == "AUTHORED_SYNTHETIC_NOT_HUMAN_REVIEWED"


@pytest.mark.parametrize("recipe,label", [
    ("MATCH", "MATCH"), ("PRODUCT_MISMATCH", "MISMATCH"),
    ("MISSING_ATTRIBUTE", "UNKNOWN"), ("INJECTION_MATCH", "MATCH"),
    ("INJECTION_MISMATCH", "MISMATCH"), ("MISSING_TEXT", None),
])
def test_authored_labels_are_independent_and_missing_text_has_no_gold_response(feature, recipe, label):
    observation, expected = build_semantic_case(feature, recipe)
    assert expected.comparison == label
    if label is None:
        assert observation.analysis.user_request.text is None
        assert expected.expected_extraction is expected.expected_comparison is None
        assert expected.extraction_status == expected.comparison_status == "NOT_RUN"
    else:
        assert expected.expected_extraction.budget is None
        expected.expected_comparison.validate_for_request(
            observation.analysis.with_extracted_intent(expected.expected_extraction),
        )
    serialized = json.loads(observation.model_dump_json())
    assert not {"recipe", "expected_comparison", "expected_extraction", "basis", "comparison"} & serialized.keys()
    assert "is_fraud" not in observation.model_dump_json()


def test_unknown_is_missing_evidence_and_injection_stays_product_data(feature):
    unknown, expected = build_semantic_case(feature, "MISSING_ATTRIBUTE")
    assert "방수" in unknown.analysis.user_request.text
    assert "방수" not in unknown.analysis.order.orders[-1].items[0].description
    assert expected.expected_comparison.findings[0].code == "INSUFFICIENT_DATA"
    injected, _ = build_semantic_case(feature, "INJECTION_MISMATCH")
    assert INJECTION not in injected.analysis.user_request.text
    assert INJECTION in injected.analysis.order.orders[-1].items[0].description


def test_snapshot_hash_covers_exact_content_and_is_deterministic(feature):
    observation, expected = build_semantic_case(feature, "PRODUCT_MISMATCH")
    again, repeated_expected = build_semantic_case(feature, "PRODUCT_MISMATCH")
    assert observation == again and expected == repeated_expected
    order = observation.analysis.order
    encoded = json.dumps(order.model_dump(mode="json", exclude={"reference"}),
                         ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    assert order.reference.snapshot_hash == hashlib.sha256(encoded.encode()).hexdigest()
    assert order.reference != feature.evaluation.request.order.reference
    assert order.orders[0].total.amount_minor == 5000
    assert order.orders[1].total.amount_minor == 5001
    assert order.total.amount_minor == 10001
    restored = SemanticObservation.model_validate_json(observation.model_dump_json())
    assert restored == observation


@pytest.mark.parametrize("change", ["text", "order", "context", "original_reference", "time"])
def test_observation_tampering_is_rejected(feature, change):
    observation, _ = build_semantic_case(feature, "MATCH")
    data = observation.model_dump(mode="json")
    if change == "text":
        data["analysis"]["user_request"]["text"] = "다른 요청"
    elif change == "order":
        data["analysis"]["order"]["orders"][0]["items"][0]["name"] = "다른 상품"
    elif change == "context":
        data["analysis"]["context"]["user_id"] = "other"
    elif change == "original_reference":
        data["original_order_reference"]["snapshot_id"] = "other"
    else:
        data["available_at"] = "2026-01-03T12:00:01+09:00"
    with pytest.raises(ValueError):
        SemanticObservation.model_validate(data)


@pytest.mark.parametrize("change", ["user", "history", "amount", "requested_features", "time"])
def test_cross_input_binding_rejects_even_contract_valid_changes(feature, change):
    observation, _ = build_semantic_case(feature, "MATCH")
    data = feature.model_dump()
    if change == "user":
        data["evaluation"]["request"]["context"]["user_id"] = "other"
        for event in data["history"]["events"]:
            event["user_id"] = "other"
    elif change == "history":
        data["history"]["events"] = []
    elif change == "amount":
        data["evaluation"]["request"]["order"]["total"]["amount_minor"] += 1
    elif change == "requested_features":
        data["evaluation"]["requested_features"] = ["amount_minor"]
    else:
        data["current_occurred_at"] -= timedelta(seconds=1)
    different = FeatureTransformInput.model_validate(data)
    with pytest.raises(ValueError, match="different behavioral input"):
        bind_semantic_observation(different, observation)


def test_retry_preserves_semantic_snapshot_text_and_source_availability(source_data):
    data = deepcopy(source_data)
    for number in (1, 2):
        data["events"].append(dict(event_id=f"a{number}", kind="PAYMENT_ATTEMPTED", user_id="u",
            purchase_request_id="p", order_id=None, attempt_id=f"a{number}", currency="KRW",
            occurred_at=f"2026-01-03T12:0{number}:00+09:00", available_at=f"2026-01-03T12:0{number}:00+09:00"))
    index = ReplayIndex(data)
    first, _ = build_semantic_case(index.feature_input("p"), "PRODUCT_MISMATCH")
    retry_feature = index.feature_input("p", attempt_id="a2")
    retry, _ = build_semantic_case(retry_feature, "PRODUCT_MISMATCH")
    assert first.analysis.order == retry.analysis.order
    assert first.analysis.user_request == retry.analysis.user_request
    assert first.available_at == retry.available_at
    assert first.source_feature_sha256 != retry.source_feature_sha256
    assert first.analysis.context.evaluation_id != retry.analysis.context.evaluation_id
    bound, _ = bind_semantic_observation(retry_feature, retry)
    assert bound.current_attempt_id == "a2"


def test_utc_timestamps_round_trip(feature):
    data = feature.model_dump(mode="json")
    from datetime import datetime, timezone
    for field in ("current_occurred_at", "current_available_at"):
        data[field] = datetime.fromisoformat(data[field]).astimezone(timezone.utc).isoformat()
    utc_feature = FeatureTransformInput.model_validate(data)
    observation, _ = build_semantic_case(utc_feature, "MATCH")
    assert SemanticObservation.model_validate_json(observation.model_dump_json()) == observation


def test_unobserved_times_and_approved_snapshots_cannot_be_fabricated(feature):
    data = feature.model_dump()
    data["current_occurred_at"] = data["current_available_at"] = None
    with pytest.raises(ValueError, match="observed current purchase times"):
        build_semantic_case(FeatureTransformInput.model_validate(data), "MATCH")
    data = feature.model_dump()
    data["evaluation"]["request"]["context"]["approval"] = dict(
        approval_id="approved", snapshot=data["evaluation"]["request"]["order"]["reference"],
    )
    with pytest.raises(ValueError, match="approved snapshot"):
        build_semantic_case(FeatureTransformInput.model_validate(data), "MATCH")


def test_model_copy_bypass_and_reference_relabel_are_rejected(feature):
    observation, expected = build_semantic_case(feature, "MATCH")
    changed = observation.model_copy(update={"observation_sha256": "0" * 64})
    with pytest.raises(ValueError):
        bind_semantic_observation(feature, changed)
    reference = expected.model_dump()
    reference["recipe"] = "MISSING_TEXT"
    with pytest.raises(ValueError):
        SemanticExpected.model_validate(reference)


def test_distinct_original_snapshots_and_intent_content_cannot_share_target_versions(feature):
    observation, _ = build_semantic_case(feature, "MATCH")
    data = feature.model_dump()
    data["evaluation"]["request"]["order"]["reference"]["version"] = "v2"
    revised, _ = build_semantic_case(FeatureTransformInput.model_validate(data), "MATCH")
    assert observation.analysis.order.reference.snapshot_hash == revised.analysis.order.reference.snapshot_hash
    assert observation.analysis.order.reference.snapshot_id != revised.analysis.order.reference.snapshot_id
    missing, _ = build_semantic_case(feature, "MISSING_TEXT")
    assert observation.analysis.order == missing.analysis.order
    assert observation.analysis.user_request.version != missing.analysis.user_request.version


def test_rehashed_monetary_rewrite_still_fails_binding(feature):
    """Checksums detect corruption; financial invariants also check valid hashes."""
    observation, _ = build_semantic_case(feature, "MATCH")
    data = observation.model_dump(mode="json")
    order = data["analysis"]["order"]
    order["total"]["amount_minor"] += 1
    merchant = order["orders"][0]
    merchant["total"]["amount_minor"] += 1
    merchant["items"][0]["unit_price"]["amount_minor"] += 1
    merchant["items"][0]["line_total"]["amount_minor"] += 1

    def digest(value):
        return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                         separators=(",", ":")).encode()).hexdigest()

    body = {k: v for k, v in order.items() if k != "reference"}
    content_hash = digest(body)
    lineage = dict(original=data["original_order_reference"], content_sha256=content_hash)
    order["reference"].update(snapshot_hash=content_hash, snapshot_id="semantic-" + digest(lineage))
    data["observation_sha256"] = digest({k: v for k, v in data.items() if k != "observation_sha256"})
    changed = SemanticObservation.model_validate(data)
    with pytest.raises(ValueError, match="monetary facts"):
        bind_semantic_observation(feature, changed)
