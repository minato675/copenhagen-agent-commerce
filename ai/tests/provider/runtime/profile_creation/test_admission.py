"""S15P21A207-302: insufficient and held evidence never becomes learned normality."""

import pytest

from copenhagen_fds.contracts.profile_policy import PolicyObservation
from copenhagen_fds.contracts.provider_profiles import ProviderProfileCreateResponse


@pytest.mark.parametrize("population_scope", [False, True])
@pytest.mark.parametrize("count,status", [(0, "EMPTY"), (1, "INSUFFICIENT_SAMPLES")])
def test_empty_and_sparse_publications_preserve_insufficiency(creation_case, policy, run_async, population_scope, count, status):
    rows = tuple(policy.row(f"sample-{i}") for i in range(count))
    case = creation_case(population_scope=population_scope, rows=rows)
    response = ProviderProfileCreateResponse.model_validate_json(run_async(case.execute())).validate_for_request(case.request)
    assert response.status == "CREATED" and response.storage_status == "NOT_PERSISTED"
    for snapshot in (response.state.candidate.snapshot, response.state.published):
        assert snapshot.long_term.status == status
        assert snapshot.long_term.included_count == count
        assert all(feature.status == status for feature in snapshot.long_term.features)


@pytest.mark.parametrize("population_scope", [False, True])
def test_unconfirmed_suspected_excluded_and_current_purchases_are_not_admitted(creation_case, policy, run_async, population_scope):
    rows = [policy.row("unconfirmed", status="DEFER"), policy.row("fraud", status="EXCLUDE"),
            policy.row("suspected", status="SUSPECTED"), policy.row("unverified", status="UNVERIFIED"),
            policy.row("missing-review").model_copy(update={"feedback": None})]
    current = policy.row("current", status="DEFER").model_dump()
    current["sample"]["eligibility"].update(reason="CURRENT_PURCHASE", observed_purchase=False)
    rows.append(PolicyObservation.model_validate(current))
    case = creation_case(population_scope=population_scope, rows=rows)
    before = case.request.model_dump_json()
    response = ProviderProfileCreateResponse.model_validate_json(run_async(case.execute()))
    decisions = {d.target.purchase_request_id: (d.status, d.reason) for d in response.state.evaluation.decisions}
    assert decisions == {"unconfirmed": ("DEFER", "RAW_UNCONFIRMED"), "fraud": ("EXCLUDE", "RAW_FRAUD_CONFIRMED"),
        "suspected": ("DEFER", "RAW_SUSPECTED"), "unverified": ("DEFER", "RAW_UNVERIFIED_FEEDBACK"),
        "missing-review": ("DEFER", "MISSING_REVIEW_EVIDENCE"), "current": ("DEFER", "RAW_NOT_OBSERVED")}
    assert response.state.candidate.snapshot.long_term.included_count == 1
    assert response.state.published.long_term.included_count == 0
    assert response.state.published.long_term.status == "EMPTY"
    assert len(response.state.observations) == len(rows)
    assert case.request.model_dump_json() == before


@pytest.mark.parametrize("population_scope", [False, True])
def test_abrupt_confirmed_change_stays_candidate_with_its_policy_reason(creation_case, policy, run_async, population_scope):
    rows = (*policy.baseline(), policy.row("abrupt", amount=1_000_000))
    case = creation_case(population_scope=population_scope, rows=rows)
    response = ProviderProfileCreateResponse.model_validate_json(run_async(case.execute()))
    state = response.state
    held = next(d for d in state.evaluation.decisions if d.target.purchase_request_id == "abrupt")
    assert (held.raw_status, held.status, held.reason) == ("INCLUDE", "DEFER", "ABRUPT_CHANGE")
    assert state.candidate.snapshot.long_term.included_count == 4
    assert state.published.long_term.included_count == 3
    assert state.candidate.snapshot.profile.reference.profile_id + "-admitted" == state.published.profile.reference.profile_id
    assert state.candidate.snapshot.profile.reference.version != state.published.profile.reference.version
