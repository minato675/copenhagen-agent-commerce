"""Reviewed synthetic history for explicit creation and full-replay update calls."""

from copy import deepcopy
from datetime import datetime, timedelta
import json

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.provider_profiles import ProviderProfileCreateRequest, ProviderProfileUpdateRequest
from copenhagen_fds.native_cli.scenarios import demo_request, observable_demo
from copenhagen_fds.profile_policy_replay import policy_observations_from_replay
from copenhagen_fds.profile_policy_updates import initialize_profile_policy
from copenhagen_fds.provider_cli.scenarios import PreparedCase, prepare_case
from copenhagen_fds.provider_runtime.profile_candidate import build_profile_candidate
from copenhagen_fds.synthetic.learning import synthetic_learning_inputs


def initial_request(settings, *, population=False, empty=False):
    data = observable_demo()
    native = demo_request("previous", data, settings, profile="full")
    state = native.personal.state
    snapshot, profile = state.candidate.snapshot, state.candidate.snapshot.profile
    scope = "population" if population else "personal"
    context = dict(reference=profile.reference, state_version="demo-initial-v1", origin=profile.origin,
        source_reference=profile.source_reference, as_of=profile.as_of, generated_at=profile.generated_at,
        valid_until=profile.valid_until, config=snapshot.config)
    if population:
        peer = json.loads(json.dumps(data).replace("demo-user", "demo-peer").replace("demo-prior-", "peer-prior-"))
        data = {key: data[key] + peer[key] for key in data}
        context.update(reference=dict(scope="POPULATION", profile_id="demo-cohort-profile", version="demo-v1",
            cohort_id="demo-cohort"), cohort=dict(cohort_id="demo-cohort", membership_version="demo-members-v1",
                definition="Two declared synthetic users", source_reference="demo-members",
                available_at=profile.as_of, currency="KRW", member_user_ids=["demo-user", "demo-peer"]),
            config={**snapshot.config.model_dump(), "version": "demo-population-v1", "min_users": 2})
    else:
        context.update(user_id="demo-user", currency="KRW")
    observations = () if empty else policy_observations_from_replay(synthetic_learning_inputs(data, as_of=profile.as_of),
        origin=profile.origin, source_reference=profile.source_reference)
    # First construct the typed profile so default population policy fields are hashed too.
    from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest
    from copenhagen_fds.contracts.population_profiles import PopulationProfileRequest
    typed = (PopulationProfileRequest if population else PersonalProfileRequest).model_validate(context)
    return ProviderProfileCreateRequest(operation="CREATE_" + scope.upper() + "_PROFILE", profile=typed,
        call=dict(request_id="create-" + scope, trace_id="demo-create-" + scope, sent_at=typed.generated_at),
        policy=state.evaluation.config, configuration=dict(configuration_id="demo-create-" + scope, version="v1",
            content_sha256=personalization_hash((typed.config, state.evaluation.config))),
        replay=dict(mode="FULL_REPLAY", omitted_purchase="REMOVE", complete_from=typed.as_of-timedelta(days=typed.config.long_term_days),
            complete_until=typed.as_of, observations=observations))


def update_request(state, *, rows=None, cutoff=None, retention=None):
    snapshot = state.candidate.snapshot
    cutoff = cutoff or snapshot.profile.as_of + timedelta(hours=1)
    observations = []
    for row in state.observations if rows is None else rows:
        value = row.model_dump() if hasattr(row, "model_dump") else deepcopy(row)
        value["sample"]["eligibility"]["as_of"] = cutoff
        observations.append(value)
    config = snapshot.config
    update = dict(expected_state_version=state.candidate.state_version, expected_profile_reference=snapshot.profile.reference,
        as_of=cutoff, generated_at=cutoff, valid_until=cutoff+timedelta(days=7),
        changes_complete_from=snapshot.profile.as_of, changes_complete_until=cutoff)
    if retention is not None:
        config = config.model_copy(update=dict(long_term_days=retention))
        update["personal_config"] = config
    return ProviderProfileUpdateRequest(operation="UPDATE_PROFILE", state=state,
        update=dict(expected_policy_state_version=state.state_version, update=update),
        call=dict(request_id="demo-update", trace_id="demo-update", sent_at=cutoff),
        configuration=dict(configuration_id="demo-update", version="v1",
            content_sha256=personalization_hash((config, state.evaluation.config))),
        replay=dict(mode="FULL_REPLAY", omitted_purchase="REMOVE", complete_from=cutoff-timedelta(days=config.long_term_days),
            complete_until=cutoff, observations=observations))


def profile_cases(settings):
    cases, expected, states = {}, {}, {}
    for population, empty, name in ((False, False, "create-personal"), (True, False, "create-population"),
                                    (False, True, "create-empty")):
        request = initial_request(settings, population=population, empty=empty)
        cases[name] = prepare_case(request)
        expected[name] = dict(status="CREATED", calls=0, handlers=1, published_status="EMPTY" if empty else "READY")
        if not empty:
            # Prior published states are fixture inputs, independent of evaluated requests or labels.
            states[name] = initialize_profile_policy(build_profile_candidate(request), request.replay.observations, request.policy)
    for scope in ("personal", "population"):
        previous = states["create-" + scope]
        request = update_request(previous, cutoff=previous.candidate.snapshot.profile.as_of)
        name = "update-unchanged-" + scope
        cases[name] = prepare_case(request)
        expected[name] = dict(status="UNCHANGED", calls=0, handlers=1, actions=[])
    state = states["create-personal"]
    request = update_request(state, rows=[])
    cases["update-withdrawn"] = prepare_case(request)
    expected["update-withdrawn"] = dict(status="UPDATED", calls=0, handlers=1, actions=["WITHDRAWN"], published_status="EMPTY")
    for amount, name, action in ((10000, "update-admitted", "ADMITTED"), (1_000_000, "update-held", "HELD")):
        # Match an observed Thursday/noon, varying only amount. A new weekday is
        # itself a policy deviation and must not be labelled an admitted example.
        data = observable_demo()
        at = datetime.fromisoformat("2026-01-08T12:00:00+09:00")
        cutoff = at + timedelta(minutes=3)
        for table in ("purchases", "events", "feedback"):
            row = json.loads(json.dumps(data[table][0]).replace("demo-prior-0", name))
            known_at = at + timedelta(minutes=1) if table == "feedback" else at
            row.update(occurred_at=known_at.isoformat(), available_at=known_at.isoformat())
            if table == "purchases":
                row["amount_minor"] = row["merchant_orders"][0]["amount_minor"] = amount
            data[table].append(row)
        rows = policy_observations_from_replay(synthetic_learning_inputs(data, as_of=cutoff),
            origin=state.candidate.snapshot.profile.origin,
            source_reference=state.candidate.snapshot.profile.source_reference)
        request = update_request(state, rows=rows, cutoff=cutoff)
        cases[name] = prepare_case(request)
        expected[name] = dict(status="UPDATED", calls=0, handlers=1, actions=[action])
        if action == "HELD":
            expected[name]["transition_reasons_contain"] = ["ABRUPT_CHANGE"]
    cases["update-replay-required"] = prepare_case(update_request(state, retention=91))
    expected["update-replay-required"] = dict(status="REPLAY_REQUIRED", issue="RETENTION_GAP", calls=0, handlers=1)
    valid = prepare_case(update_request(state))
    changed = deepcopy(valid.request)
    changed["update"]["expected_policy_state_version"] = "stale-version"
    cases["update-stale-version"] = PreparedCase(changed, valid.call, valid.server)
    expected["update-stale-version"] = dict(status="INVALID_CONTRACT", calls=0, handlers=0)
    return cases, expected
