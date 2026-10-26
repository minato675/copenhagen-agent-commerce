"""Authored suites are review candidates, never self-certified model accuracy."""

from collections import Counter
from copy import deepcopy
from datetime import datetime, timezone
import json

import pytest

from copenhagen_fds.evaluation.experiments import HumanReview, make_plan
from copenhagen_fds.evaluation.grading import grade_case
from copenhagen_fds.evaluation.offline import run_mock_dataset
from copenhagen_fds.evaluation.suite_cases import load_holdout_dataset, load_stability_dataset
from copenhagen_fds.evaluation.suite_export import audit_suite, comparison_content, export_suites, normalize, review_html


def test_holdout_balanced_fact_groups_with_consistent_numeric_envelopes():
    data = load_holdout_dataset()
    assert Counter(c.expected.comparison for c in data.cases) == {"MATCH": 100, "MISMATCH": 100, "UNKNOWN": 100}
    assert len({c.case_id.rsplit("-", 1)[0] for c in data.cases}) == 100
    assert Counter(c.expected.comparison for c in data.cases if "prompt-injection" in c.categories) == {
        "MATCH": 20, "MISMATCH": 20, "UNKNOWN": 20}
    for case in data.cases:
        case.mock_comparison.validate_for_request(case.request.with_extracted_intent(case.mock_extraction))
        order = case.request.order
        assert sum(o.total.amount_minor for o in order.orders) == order.total.amount_minor
        for merchant in order.orders:
            assert sum(i.line_total.amount_minor for i in merchant.items) == merchant.total.amount_minor
            for product in merchant.items:
                assert product.quantity * product.unit_price.amount_minor == product.line_total.amount_minor
                assert product.unit_price.currency == order.total.currency
        if case.mock_extraction.budget:
            assert case.mock_extraction.budget.amount.currency == order.total.currency
    # This pin changes only after reviewing authored facts/rubric changes.
    assert data.sha256 == "5fbefceccb55154c521809ec21fc8dbe8393d1ae6b8e41c31176b1dc0e5ebe89"


@pytest.mark.parametrize("factory", [load_holdout_dataset, load_stability_dataset])
def test_every_reference_roundtrips_real_contract_and_grader_offline(factory, run_async):
    dataset = factory()
    batch = run_async(run_mock_dataset(dataset))
    by_id = {o.case_id: o for o in batch.observations}
    failed = [case.case_id for case in dataset.cases if not grade_case(case, by_id[case.case_id])["passed"]]
    assert failed == []  # Scripted reference validation, not an LLM quality result.


def test_twenty_stability_inputs_are_distinct_and_only_comparison_is_planned():
    data = load_stability_dataset()
    plan = make_plan(data, experiment_id="stability-test", dataset_role="STABILITY", mode="COMPARISON_REPEAT",
                     repetitions=5, max_http_requests=100)
    assert len(plan.run_ids) == 100 and len(set(plan.comparison_input_hashes.values())) == 20
    assert plan.extraction_input_hashes == {}
    assert {v.source for v in plan.frozen_inputs.values()} == {"REFERENCE_INTENT"}
    assert Counter(c.expected.comparison for c in data.cases) == {"MATCH": 8, "MISMATCH": 6, "UNKNOWN": 6}


def test_audit_detects_identity_only_duplicates_and_prompt_overlap(dataset):
    data = load_holdout_dataset()
    audited = audit_suite(data, (dataset, load_stability_dataset()))
    assert not audited["exact_comparison_duplicates"] and not audited["development_or_prompt_exact_overlaps"]
    assert audited["human_review_status"] == "PENDING"
    assert audited["near_pairs_for_review"]  # Counterfactuals must not be called unrelated samples.
    case = data.cases[0]
    # Different case/context IDs cannot conceal an identical comparison.
    copied = case.model_copy(update={"case_id": "ho-duplicate-only-id"})
    duplicate_data = data.model_copy(update={"cases": (*data.cases, copied)})
    duplicates = audit_suite(duplicate_data, (), ())
    assert [case.case_id, copied.case_id] in duplicates["exact_comparison_duplicates"]
    example = deepcopy(comparison_content(case))
    example["orders"][0]["order_id"] = "different-example-order"
    example["orders"][0]["items"][0]["item_id"] = "different-example-item"
    overlap = audit_suite(data, (), ({"input": example},))
    assert {"case_id": case.case_id, "matches": ["prompt-example-1"]} in overlap["development_or_prompt_exact_overlaps"]
    assert normalize({"name": "  MODEL　A ", "order_id": "arbitrary"}) == {"name": "model a"}


def test_export_never_approves_or_overwrites_and_html_escapes_attacks(tmp_path):
    output = tmp_path / "suites"
    result = export_suites(output)
    assert result["holdout_review_status"] == "PENDING"
    draft = json.loads((output / "holdout-draft-plan.json").read_text(encoding="utf-8"))
    assert draft["human_review"] is None
    html = (output / "holdout-review.html").read_text(encoding="utf-8")
    assert html.count("<details ") == 300 and "&lt;system&gt;" in html and "<system>" not in html
    original = (output / "manifest.json").read_bytes()
    with pytest.raises(FileExistsError):
        export_suites(output)
    assert (output / "manifest.json").read_bytes() == original


def test_human_review_is_bound_to_every_case_and_dataset_hash():
    dataset = load_holdout_dataset()
    review = HumanReview(dataset_sha256=dataset.sha256, reviewed_case_ids=tuple(c.case_id for c in dataset.cases),
                         reviewer="synthetic-test-reviewer", reviewed_at=datetime.now(timezone.utc), decision="APPROVED")
    for mutation in ({"dataset_sha256": "0" * 64}, {"reviewed_case_ids": review.reviewed_case_ids[:-1]},
                     {"reviewed_case_ids": (*review.reviewed_case_ids, review.reviewed_case_ids[0])}):
        with pytest.raises(ValueError, match="review"):
            make_plan(dataset, experiment_id="review-test", dataset_role="HOLDOUT", max_http_requests=600,
                      human_review=review.model_copy(update=mutation))
    plan = make_plan(dataset, experiment_id="review-test", dataset_role="HOLDOUT", max_http_requests=600, human_review=review)
    assert plan.human_review == review


def test_unreviewed_holdout_is_blocked_before_loading_live_credentials(tmp_path, monkeypatch):
    from copenhagen_fds.evaluation import collect_gms
    plan = make_plan(load_holdout_dataset(), experiment_id="pending-review", dataset_role="HOLDOUT", max_http_requests=600)
    path = tmp_path / "plan.json"
    path.write_text(plan.model_dump_json(), encoding="utf-8")

    def forbidden(*args, **kwargs):
        pytest.fail("unreviewed labels must be rejected before credentials/network")

    monkeypatch.setattr(collect_gms, "load_openai_settings", forbidden)
    assert collect_gms.main(["--plan", str(path), "--approve-plan-sha256", plan.sha256,
                             "--output-dir", str(tmp_path / "output")]) == 2
    assert not (tmp_path / "output").exists()
