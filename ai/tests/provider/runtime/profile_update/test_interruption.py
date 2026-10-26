"""S15P21A207-303: no partial publication after failure, cancellation or expiry."""

import asyncio
from datetime import timedelta

import pytest

from copenhagen_fds.contracts.provider_errors import ProviderError
from copenhagen_fds.contracts.provider_profiles import ProviderProfileUpdateResponse


@pytest.mark.parametrize("interruption", ["deadline", "cancel", "failure"])
def test_interrupted_update_preserves_previous_state_and_returns_no_partial_result(update_case, run_async, monkeypatch, interruption):
    import copenhagen_fds.provider_runtime.profile_update as module

    case = update_case()
    before, native = case.request.state.model_dump_json(), module.update_profile_policy

    def interrupt(*args):
        result = native(*args)
        if interruption == "deadline":
            case.monotonic[0] = 11
        elif interruption == "cancel":
            asyncio.current_task().cancel()
        else:
            raise ArithmeticError("private-update-evidence-must-not-escape")
        return result

    monkeypatch.setattr(module, "update_profile_policy", interrupt)
    monkeypatch.setattr(module, "project_profile_update_response", lambda *a, **kw: pytest.fail("interrupted result must not be projected"))
    if interruption == "cancel":
        with pytest.raises(asyncio.CancelledError):
            run_async(case.execute())
    else:
        encoded = run_async(case.execute())
        assert ProviderError.model_validate_json(encoded).code == ("DEADLINE_EXCEEDED" if interruption == "deadline" else "INTERNAL_ERROR")
        assert b"private-update" not in encoded and b'"state"' not in encoded
    assert case.request.state.model_dump_json() == before


@pytest.mark.parametrize("when", ["future_submission", "expired_before", "expired_during"])
def test_update_rejects_invalid_execution_window(update_case, run_async, monkeypatch, when):
    import copenhagen_fds.provider_runtime.profile_update as module

    case = update_case()
    if when == "expired_during":
        native = module.update_profile_policy

        def expire(*args):
            result = native(*args)
            case.wall[0] = case.request.update.update.valid_until
            return result

        monkeypatch.setattr(module, "update_profile_policy", expire)
    else:
        case.wall[0] = (case.request.call.sent_at - timedelta(seconds=1) if when == "future_submission"
                        else case.request.update.update.valid_until)
        monkeypatch.setattr(module, "update_profile_policy", lambda *_: pytest.fail("invalid request cannot be replayed"))
    assert ProviderError.model_validate_json(run_async(case.execute())).code == "INVALID_CONTRACT"


def test_expired_previous_publication_can_be_recomputed_with_new_validity(update_case, profile_case, run_async):
    state = profile_case.create().state
    case = update_case(state=state, cutoff=state.published.profile.valid_until + timedelta(days=1))
    response = ProviderProfileUpdateResponse.model_validate_json(run_async(case.execute())).validate_for_request(case.request)
    assert response.result.status == "UPDATED"
    assert state.published.profile.valid_until < response.completed_at < response.result.state.published.profile.valid_until
