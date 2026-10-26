import pytest

from copenhagen_fds.contracts.personal_components import PersonalComponentsResult, PersonalScoringPolicies
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.personal_amount import score_personal_amount
from copenhagen_fds.personal_components import evaluate_personal_components
from copenhagen_fds.personal_frequency import score_personal_frequencies
from copenhagen_fds.personal_time import score_personal_times


def test_selected_calculations_equal_existing_scorers_and_preserve_evidence(evaluation_cases):
    supplied = evaluation_cases.request(values={"purchase_count_10m": 2, "kst_hour": 12})
    result = evaluate_personal_components(supplied)
    existing = {"amount_minor": score_personal_amount(supplied).windows}
    for group in (score_personal_frequencies(supplied), score_personal_times(supplied)):
        existing.update({f.raw.feature_id: f.windows for f in group.features})
    assert len(result.features) == 9
    for component in result.features:
        assert component.candidate == existing[component.result.feature_id][0]
        assert component.candidate.calculation is not None
        assert component.result.bases[0].profile == supplied.personal.expected_reference


@pytest.mark.parametrize("days,window", [((1, 2, 3, 4), "RECENT"), ((1, 8, 9, 10), "LONG_TERM")])
def test_only_selected_window_is_calculated(evaluation_cases, monkeypatch, days, window):
    import copenhagen_fds.personal_components as module

    original = module.amount_candidate
    calls = []

    def counted(*args):
        calls.append(args[0])
        return original(*args)

    monkeypatch.setattr(module, "amount_candidate", counted)
    profile = evaluation_cases.publication([{"days": d} for d in days])
    result = evaluate_personal_components(evaluation_cases.request(profile=profile, requested=("amount_minor",)))
    assert calls == [window] and result.features[0].candidate.window == window


@pytest.mark.parametrize("kind", ["absent", "expired", "support_zero", "input_missing"])
def test_unusable_personal_data_never_executes_candidate(evaluation_cases, monkeypatch, kind):
    import copenhagen_fds.personal_components as module

    def unexpected(*args):
        raise AssertionError("unusable personal data must not execute scoring")

    monkeypatch.setattr(module, "amount_candidate", unexpected)
    kwargs = {"requested": ("amount_minor",)}
    expected = "INSUFFICIENT_DATA"
    if kind == "absent":
        kwargs["profile"] = False
    elif kind == "expired":
        profile = evaluation_cases.publication()
        kwargs.update(profile=profile, at=profile.state.published.profile.valid_until)
    elif kind == "support_zero":
        kwargs["profile"] = evaluation_cases.publication([{"days": 1}, {"days": 2}])
        expected = "NOT_RUN"
    else:
        kwargs["states"] = {"amount_minor": "NOT_COLLECTED"}
        expected = "NOT_COLLECTED"
    row = evaluate_personal_components(evaluation_cases.request(**kwargs)).features[0]
    assert row.candidate is None and row.result.score is None and row.result.status == expected


@pytest.mark.parametrize("change", ["scale", "transition", "amount", "frequency", "time", "nested_policy"])
def test_unsupported_configuration_rejected_before_calculation(evaluation_cases, monkeypatch, change):
    import copenhagen_fds.personal_components as module

    def unexpected(*args):
        raise AssertionError("invalid settings must fail before scoring")

    monkeypatch.setattr(module, "amount_candidate", unexpected)
    supplied = evaluation_cases.request()
    data = supplied.model_dump()
    policies = PersonalScoringPolicies()
    if change == "nested_policy":
        policies = policies.model_copy(update={"amount": policies.amount.model_copy(update={"spread_multiplier": 0.0})})
    elif change in {"scale", "transition"}:
        data["config"]["score_scale_version" if change == "scale" else "transition_policy_version"] = "unknown"
    else:
        index = {"amount": 0, "frequency": 1, "time": 7}[change]
        data["config"]["features"][index]["score_version"] = "unknown"
    with pytest.raises(ValueError):
        evaluate_personal_components(PersonalizedScoringInput.model_validate(data), policies)


def test_configured_parameters_are_applied_and_hashed(evaluation_cases):
    supplied = evaluation_cases.request(values={"purchase_count_10m": 2, "kst_hour": 12})
    policy = PersonalScoringPolicies(frequency={"spread_multiplier": 6.0}, time={"pseudocount": 2.0})
    default = evaluate_personal_components(supplied)
    changed = evaluate_personal_components(supplied, policy)
    assert changed.policies_sha256 != default.policies_sha256
    assert changed.features[1].result.score.value == pytest.approx(100 / 3)
    assert changed.features[7].result.score.value == pytest.approx(100 * (1 - 2 / 6))
    assert changed.policies == policy


def test_components_roundtrip_and_readonly_evaluation(evaluation_cases):
    supplied = evaluation_cases.request()
    before = supplied.model_dump_json()
    result = evaluate_personal_components(supplied)
    assert PersonalComponentsResult.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result
    assert supplied.model_dump_json() == before


@pytest.mark.parametrize("field", ["input_sha256", "policies_sha256", "diagnostic", "window", "calculation"])
def test_detached_component_evidence_rejected(evaluation_cases, field):
    supplied = evaluation_cases.request()
    data = evaluate_personal_components(supplied).model_dump()
    if field.endswith("sha256"):
        data[field] = "b" * 64
    elif field == "diagnostic":
        data["features"][0][field] = "OTHER"
    elif field == "window":
        data["features"][0]["candidate"]["window"] = "LONG_TERM"
    else:
        data["features"][0]["candidate"]["calculation"]["median_minor"]["numerator"] += 1
    with pytest.raises(ValueError):
        PersonalComponentsResult.model_validate(data).validate_for_input(supplied)
