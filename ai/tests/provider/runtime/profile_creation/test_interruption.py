"""S15P21A207-302: safe failures and cooperative interruption between CPU phases."""

import asyncio
from datetime import timedelta

import pytest

from copenhagen_fds.contracts.provider_errors import ProviderError


@pytest.mark.parametrize("phase", ["build_profile_candidate", "initialize_profile_policy"])
@pytest.mark.parametrize("interruption", ["deadline", "cancel", "failure"])
def test_interrupted_creation_never_returns_partial_state(creation_case, run_async, monkeypatch, phase, interruption):
    import copenhagen_fds.provider_runtime.profile_creation as module

    case = creation_case()
    original, seen = getattr(module, phase), []
    before = case.request.model_dump_json()

    def interrupted(*args):
        result = original(*args)
        seen.append(phase)
        if interruption == "deadline":
            case.monotonic[0] = 11
        elif interruption == "cancel":
            asyncio.current_task().cancel()
        else:
            raise ArithmeticError("private-profile-evidence-must-not-escape")
        return result

    monkeypatch.setattr(module, phase, interrupted)
    next_phase = "initialize_profile_policy" if phase == "build_profile_candidate" else "project_profile_create_response"
    monkeypatch.setattr(module, next_phase, lambda *a, **kw: pytest.fail("interruption must stop the next phase"))
    if interruption == "cancel":
        with pytest.raises(asyncio.CancelledError):
            run_async(case.execute())
    else:
        encoded = run_async(case.execute())
        error = ProviderError.model_validate_json(encoded)
        assert error.code == ("DEADLINE_EXCEEDED" if interruption == "deadline" else "INTERNAL_ERROR")
        assert b"private-profile" not in encoded and b'"state"' not in encoded
    assert seen == [phase] and case.request.model_dump_json() == before


@pytest.mark.parametrize("when", ["future_submission", "expired_before", "expired_during"])
def test_profile_must_be_valid_at_execution_and_completion(creation_case, run_async, monkeypatch, when):
    import copenhagen_fds.provider_runtime.profile_creation as module

    case = creation_case()
    if when == "expired_during":
        original = module.initialize_profile_policy

        def expire(*args):
            state = original(*args)
            case.wall[0] = case.request.profile.valid_until
            return state

        monkeypatch.setattr(module, "initialize_profile_policy", expire)
        monkeypatch.setattr(module, "project_profile_create_response", lambda *a, **kw: pytest.fail("expired result cannot be returned"))
    else:
        case.wall[0] = (case.request.call.sent_at - timedelta(seconds=1) if when == "future_submission"
                        else case.request.profile.valid_until)
        monkeypatch.setattr(module, "build_profile_candidate", lambda *_: pytest.fail("invalid interval cannot be computed"))
    assert ProviderError.model_validate_json(run_async(case.execute())).code == "INVALID_CONTRACT"
