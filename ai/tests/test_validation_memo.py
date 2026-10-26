"""S15P21A207-357: exact-input validation reuse without forged-instance bypass."""

import asyncio
from datetime import timedelta
from pathlib import Path

import pytest
from pydantic import Field

from copenhagen_fds import profile_update_records, validation_memo as memo_module
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference
from copenhagen_fds.contracts.engine_behavior import EngineBehaviorResult
from copenhagen_fds.contracts.engine_execution import EngineExecutionResult
from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.contracts.values import InputModel
from copenhagen_fds.native_cli import runtime
from copenhagen_fds.native_cli.scenarios import demo_request, demo_script, observable_demo
from copenhagen_fds.profile_update_records import sample_content_hash
from copenhagen_fds.validation_memo import memoized_validation, scoped_validation_memo, validation_memo


@pytest.fixture
def memo_request():
    settings = PersonalReplaySettings.model_validate_json(
        (Path(__file__).parents[1] / "examples/personal-replay-settings.json").read_text(encoding="utf-8"))
    return demo_request("validation-memo", observable_demo(), settings, profile="full")


def test_same_sample_is_validated_once_and_cutoff_change_is_a_cache_miss(memo_request, monkeypatch):
    sample = memo_request.personal.state.observations[0].sample
    original = profile_update_records.canonical_sample
    calls = []

    def counted(value):
        calls.append(value)
        return original(value)

    monkeypatch.setattr(profile_update_records, "canonical_sample", counted)
    with validation_memo() as cache:
        first = sample_content_hash(sample)
        assert sample_content_hash(sample.model_copy(deep=True)) == first
        carried = sample.model_copy(update={"eligibility": sample.eligibility.model_copy(
            update={"as_of": sample.eligibility.as_of + timedelta(seconds=1)})})
        assert sample_content_hash(carried) == first
        assert len(calls) == 2 and cache.hits == 1
    assert cache.closed and not cache.entries and cache.key_bytes == 0


def test_copied_invalid_learning_cutoff_cannot_reuse_a_valid_hash(memo_request):
    sample = memo_request.personal.state.observations[0].sample
    forged = sample.model_copy(update={"eligibility": sample.eligibility.model_copy(
        update={"as_of": sample.raw_features.evaluated_at})})
    with validation_memo():
        sample_content_hash(sample)
        for _ in range(2):
            with pytest.raises(ValueError, match="time order"):
                sample_content_hash(forged)


def test_reference_reuses_only_an_exact_complete_request(memo_request, monkeypatch):
    original = EngineEvaluationRequest.model_validate
    calls = []

    def counted(value, *args, **kwargs):
        calls.append(value)
        return original(value, *args, **kwargs)

    monkeypatch.setattr(EngineEvaluationRequest, "model_validate", counted)
    with validation_memo():
        first = EngineRequestReference.from_request(memo_request)
        assert EngineRequestReference.from_request(memo_request.model_copy(deep=True)) == first
        changed = memo_request.model_copy(update={"trace_id": "changed-trace"})
        assert EngineRequestReference.from_request(changed).request_sha256 != first.request_sha256
        assert len(calls) == 2


@pytest.mark.parametrize("change", ["publication", "thresholds", "analysis_target"])
def test_forged_nested_request_cannot_reuse_a_valid_reference(memo_request, change):
    if change == "publication":
        publication = memo_request.personal.model_copy(update={"expected_policy_state_version": "forged"})
        forged = memo_request.model_copy(update={"personal": publication})
    elif change == "thresholds":
        policy = memo_request.risk_policy.model_copy(update={"thresholds":
            memo_request.risk_policy.thresholds.model_copy(update={"high_from": 1.0})})
        forged = memo_request.model_copy(update={"risk_policy": policy})
    else:
        analysis = memo_request.analysis.model_copy(update={"context":
            memo_request.analysis.context.model_copy(update={"user_id": "other-user"})})
        forged = memo_request.model_copy(update={"analysis": analysis})
    with validation_memo():
        EngineRequestReference.from_request(memo_request)
        with pytest.raises(ValueError):
            EngineRequestReference.from_request(forged)


class OptionalNumber(InputModel):
    value: float | None = Field(default=None, allow_inf_nan=False)


@pytest.mark.parametrize("number", [float("nan"), float("inf"), -float("inf")])
def test_nonfinite_values_cannot_collide_with_a_serialized_null(number):
    valid = OptionalNumber()
    invalid = valid.model_copy(update={"value": number})

    def calculate(value):
        return memoized_validation("optional-number", value,
            lambda: OptionalNumber.model_validate(value.model_dump()).value)

    with validation_memo():
        assert calculate(valid) is None
        with pytest.raises(ValueError):
            calculate(invalid)


def test_lru_entry_and_byte_limits_never_bypass_original_work():
    calls = []

    def calculate(number):
        value = OptionalNumber(value=float(number))
        return memoized_validation("bounded", value, lambda: calls.append(number) or number)

    with validation_memo(max_entries=1, max_key_bytes=1024) as cache:
        assert calculate(1) == calculate(1) == 1
        calculate(2)
        calculate(1)
        assert len(cache.entries) == 1 and calls == [1, 2, 1]
    with validation_memo(max_entries=1, max_key_bytes=1) as cache:
        calculate(1)
        calculate(1)
        assert not cache.entries and calls[-2:] == [1, 1]


def test_failure_is_not_cached_and_nested_scope_restores_parent():
    calls = []
    value = OptionalNumber(value=1.0)

    def failure():
        calls.append("failed")
        raise ValueError("failed validation")

    with validation_memo() as parent:
        memoized_validation("successful", value, lambda: "parent")
        with pytest.raises(ValueError):
            with validation_memo() as child:
                memoized_validation("successful", value, lambda: "child")
                memoized_validation("failure", value, failure)
        assert child.closed and not child.entries
        assert memoized_validation("successful", value, lambda: "wrong") == "parent"
        for _ in range(2):
            with pytest.raises(ValueError):
                memoized_validation("failure", value, failure)
        assert calls == ["failed"] * 3 and parent.hits == 1


@pytest.mark.parametrize("failure", [ValueError, asyncio.CancelledError])
def test_native_scope_is_cleared_on_error_or_cancellation(memo_request, monkeypatch, run_async, failure):
    captured = []

    async def fail(*args, **kwargs):
        captured.append(memo_module._active.get())
        raise failure()

    monkeypatch.setattr(runtime, "evaluate_fraud", fail)
    with pytest.raises(failure):
        run_async(runtime.run_request(memo_request, script=demo_script(memo_request)))
    assert captured and captured[0].closed and not captured[0].entries
    assert memo_module._active.get() is None


def test_concurrent_invocations_have_independent_scopes(run_async):
    caches = []
    value = OptionalNumber(value=1.0)

    @scoped_validation_memo
    async def invocation(label):
        cache = memo_module._active.get()
        caches.append(cache)
        memoized_validation("same-input", value, lambda: label)
        await asyncio.sleep(0)
        return memoized_validation("same-input", value, lambda: "wrong")

    async def run():
        return await asyncio.gather(invocation("first"), invocation("second"))

    assert run_async(run()) == ["first", "second"]
    assert caches[0] is not caches[1] and all(cache.closed for cache in caches)


def test_native_result_matches_uncached_execution(memo_request, run_async):
    script = demo_script(memo_request)
    expected = run_async(runtime.run_request.__wrapped__(memo_request, script=script))
    actual = run_async(runtime.run_request(memo_request, script=script))
    assert actual == expected
    assert memo_module._active.get() is None


@pytest.fixture
def memo_execution(memo_request, run_async):
    result, _ = run_async(runtime.run_request(memo_request, script=demo_script(memo_request)))
    return result.execution


@pytest.mark.parametrize("name,model,method", [
    ("behavior", EngineBehaviorResult, "_validate_binding"),
    ("execution", EngineExecutionResult, "_validate_run"),
])
def test_complete_result_validation_reuse_preserves_new_self(memo_execution, monkeypatch, name, model, method):
    original = getattr(model, method)
    calls = []

    def counted(self):
        calls.append(self)
        return original(self)

    monkeypatch.setattr(model, method, counted)
    value = memo_execution.behavior if name == "behavior" else memo_execution
    with validation_memo():
        first = model.model_validate(value.model_dump())
        second = model.model_validate(value.model_dump())
    assert first == second and first is not second
    assert len(calls) == 1


@pytest.mark.parametrize("change", ["behavior_reference", "execution_status", "execution_time"])
def test_copied_result_tampering_misses_prior_success(memo_execution, change):
    if change == "behavior_reference":
        value, model = memo_execution.behavior, EngineBehaviorResult
        forged = value.model_copy(update={"reference": value.reference.model_copy(update={"request_sha256": "0" * 64})})
    else:
        value, model = memo_execution, EngineExecutionResult
        update = ({"status": "FAILED"} if change == "execution_status" else
                  {"completed_at": value.started_at - timedelta(seconds=1)})
        forged = value.model_copy(update=update)
    with validation_memo():
        model.model_validate(value.model_dump())
        with pytest.raises(ValueError):
            model.model_validate(forged.model_dump())


def test_result_validation_is_not_memoized_outside_execution(memo_execution, monkeypatch):
    original = EngineBehaviorResult._validate_binding
    calls = []

    def counted(self):
        calls.append(self)
        return original(self)

    monkeypatch.setattr(EngineBehaviorResult, "_validate_binding", counted)
    value = memo_execution.behavior
    EngineBehaviorResult.model_validate(value.model_dump())
    EngineBehaviorResult.model_validate(value.model_dump())
    assert len(calls) == 2
