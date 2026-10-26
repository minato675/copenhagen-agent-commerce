"""Developer examples and semantic projection must obey the real contracts.

These scripted outcomes validate examples/mapping, not actual model accuracy.
"""

from copy import deepcopy
import json

import pytest

from copenhagen_fds.analysis import AnalysisBudget, IntentComparator
from copenhagen_fds.analysis.comparison_prompt import COMPARISON_EXAMPLES, build_comparison_prompt
from copenhagen_fds.contracts import FraudEvaluationRequest, IntentComparisonOutput
from copenhagen_fds.evaluation.cases import load_dataset
from .support import completion


@pytest.mark.parametrize("example", COMPARISON_EXAMPLES)
def test_complete_examples_are_valid_through_comparator(run_async, adapter, comparison_request, example):
    data = comparison_request.model_dump(mode="json")
    data["intent"].update(deepcopy(example["input"]["intent"]))
    for item in data["intent"]["items"]:
        item["quantity"] = 3
    order_template = deepcopy(data["order"]["orders"][0])
    item_template = deepcopy(order_template["items"][0])
    data["order"]["orders"] = [
        {**order_template, "order_id": order["order_id"], "merchant_id": f"merchant-{i}",
         "items": [{**item_template, **item} for item in order["items"]]}
        for i, order in enumerate(example["input"]["orders"])
    ]
    request = FraudEvaluationRequest.model_validate(data)
    clarifications = tuple(example["input"]["clarifications"])
    prompt = build_comparison_prompt(request, clarifications=clarifications)
    assert json.loads(prompt.user_message) == example["input"]
    IntentComparisonOutput.model_validate(example["output"]).validate_for_request(request)
    adapter.outcomes["comparison"] = completion("comparison", example["output"])
    result = run_async(IntentComparator(adapter).compare(
        request, trace_id="trace", budget=AnalysisBudget.start(10), clarifications=clarifications,
    ))
    assert result.status == "SUCCESS" and result.payload.model_dump(mode="json") == example["output"]


def test_budget_and_source_do_not_change_semantics_but_constraints_do():
    cases = {c.case_id: c for c in load_dataset().cases}
    def prompt(case_id):
        case = cases[case_id]
        return build_comparison_prompt(case.request.with_extracted_intent(case.mock_extraction))
    equivalent = [prompt(key).user_message for key in (
        "fds-01-clear", "fds-02-maximum", "fds-03-approximate", "fds-18-source-summary", "fds-20-user-injection",
    )]
    assert len(set(equivalent)) == 1
    for key in ("fds-07-required-condition", "fds-09-preference", "fds-10-brand", "fds-12-substitution"):
        assert prompt(key).user_message != equivalent[0]


def test_model_name_and_preference_survive_projection(comparison_request):
    data = comparison_request.model_dump(mode="json")
    data["intent"]["items"][0].update(name="모델 M 무선 마우스", brand=None)
    data["intent"].update(preferences=["CORAL 브랜드 선호"], explicit_conditions=None, ambiguities=None)
    body = json.loads(build_comparison_prompt(FraudEvaluationRequest.model_validate(data)).user_message)
    assert body["intent"]["items"][0]["name"] == "모델 M 무선 마우스"
    assert body["intent"]["items"][0]["brand"] is None
    assert body["intent"]["preferences"] == ["CORAL 브랜드 선호"]
    assert body["intent"]["explicit_conditions"] is None
