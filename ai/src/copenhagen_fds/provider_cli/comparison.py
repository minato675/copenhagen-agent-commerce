"""Post-execution contract assertions, never inputs to handlers or prompts."""

import json

from copenhagen_fds.contracts.provider_errors import ProviderError
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationResponse
from copenhagen_fds.contracts.provider_profiles import ProviderProfileCreateResponse


def observe(response, execution):
    value = dict(status=None, risk=None, behavior_score=None, amount_stage=None, reasons=[],
        issue=None, actions=[], transition_reasons=[], published_status=None, stages=[],
        calls=len(execution["completion_invocations"]), handlers=len(execution["handler_invocations"]))
    if isinstance(response, ProviderError):
        value["status"] = response.code
    elif isinstance(response, ProviderEvaluationResponse):
        value.update(status=response.outcome.status, risk=response.outcome.risk_level,
            behavior_score=None if response.behavior_score is None else response.behavior_score.value,
            amount_stage=next((f.stage for f in response.features if f.feature_id == "amount_minor"), None),
            reasons=sorted({r.code for r in response.reasons}),
            stages=[dict(stage=s.stage, status=s.status) for s in response.stages])
    else:
        initial = isinstance(response, ProviderProfileCreateResponse)
        state = response.state if initial else response.result.state
        value.update(status=response.status if initial else response.result.status,
            published_status=None if state is None else state.published.long_term.status)
        if not initial:
            value.update(issue=response.result.issue, actions=sorted({t.action for t in response.result.transitions}),
                         transition_reasons=sorted({t.reason for t in response.result.transitions}))
    return json.loads(json.dumps(value))


def compare(observations, expected):
    if not isinstance(expected, dict) or set(observations) != set(expected) or not observations:
        raise ValueError("expectations must cover exactly the completed cases")
    rows = []
    for case_id, observed in observations.items():
        criteria = expected[case_id]
        if not isinstance(criteria, dict) or not criteria:
            raise ValueError("nonempty criteria required")
        checks = {}
        for key, value in criteria.items():
            if key in {"reasons_contain", "transition_reasons_contain"}:
                if not isinstance(value, list) or not value or any(not isinstance(v, str) for v in value):
                    raise ValueError("nonempty reason list required")
                checks[key] = set(value) <= set(observed[key.removesuffix("_contain")])
            elif key in observed:
                checks[key] = type(value) is type(observed[key]) and value == observed[key]
            else:
                raise ValueError("unknown expectation field")
        rows.append(dict(case_id=case_id, observed=observed, expected=criteria,
                         checks=checks, passed=all(checks.values())))
    return dict(status="PASSED" if all(r["passed"] for r in rows) else "FAILED", rows=rows,
                note="Synthetic contract regression, not measured fraud detection quality or live GMS output.")
