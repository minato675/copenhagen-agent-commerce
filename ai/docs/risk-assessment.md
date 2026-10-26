# FDS 행동 평가 상태와 실패 보존

`assess_risk_behavior(RiskPolicyInput)`은 [정책 전체 가용성](risk-coverage.md)에
원래 개인화/Cold 실행 결과와 계산 근거를 연결하여 `RiskBehaviorAssessment`를 반환한다.
평가 가능한 행동 점수가 준비되었는지 확인하는 단계이며 최종 위험 등급은 결정하지 않는다.

```python
from copenhagen_fds.risk_assessment import assess_risk_behavior
from copenhagen_fds.contracts.risk_assessment import RiskBehaviorAssessment

assessment = assess_risk_behavior(supplied)
restored = RiskBehaviorAssessment.model_validate_json(assessment.model_dump_json())
restored.validate_for_input(supplied)
assert assessment == restored
```

## 상태 결정표

| 행동 평가 원본 | 점수 범위 | execution_status | status |
| --- | --- | --- | --- |
| 미실행, behavior=null | UNAVAILABLE | NOT_RUN | NOT_RUN |
| 계산 실패 없음 | COMPLETE | SUCCESS | SUCCESS |
| 계산 실패 없음, 미수집/기준 부족/요청 누락 | PARTIAL 또는 UNAVAILABLE | SUCCESS | INCOMPLETE |
| Cold 또는 개인 계산 실패 | 어느 범위든 가능 | FAILED | FAILED |

`execution_status`는 원래 행동 계산의 실행 상태다. `status`는 정책 전체 점수 범위를 함께
고려한 **행동 평가 준비 상태**다. 여기서 SUCCESS여도 사기 위험이 낮다거나 최종 결제가
가능하다는 뜻은 아니다. GPT UNKNOWN/실패 등의 의미 정책은 후속 단계에서 결합한다.
GPT가 실패해도 이 함수는 행동 결과 자체의 상태를 별도로 보존한다.

`coverage.can_aggregate=true`와 `status=FAILED`는 동시에 가능하다. 개인 금액 계산이
실패해도 유효한 Cold 금액 0점으로 복귀했다면 점수는 존재하지만 계산 실패 사실은 남는다.
반대로 개인 충분도가 일부이고 Cold가 없으면 남은 개인 점수를 100%로 승격하지 않는다.
기존 최종 Feature의 null을 그대로 유지한다.

## 보존되는 근거

- `coverage.features`: 원시 값, 실제 최종 Feature 점수·상태·기준·사유, 필수 여부와 요청 여부.
- `evidence.detail`: 기존 Cold/개인/최종 점수, 실제 비중·단계, 개인 통계 창과 단계 사유.
- `evidence.readiness`: 기존 표본 수·관측 기간·최근성·가용 비율·수집 범위 진단과 충분도 축.
- `evidence.personal_execution`: 실제 선택 계산 후보와 진단 코드.
- `failures`: 실패한 Feature 결과를 COLD/PERSONAL/COMBINED 컴포넌트별로 기록.
- `cold_fallback_features`: 개인 계산 실패 후 유효한 Cold 점수를 적용한 Feature ID.
- `source_reasons`: 기존 행동 평가의 사유 원문. 오류 예외 문자열을 새로 출력하지 않는다.

값과 비중·충분도는 다시 계산하거나 곱하지 않는다. 실패를 주입한 뒤 복구된 환경에서
다시 역직렬화해도 기록된 실패는 사라지지 않는다. 평가 당시 결과에 근거하는 것이며 현재
계산기로 다시 실행했을 때 같은 장애가 발생한다고 주장하지 않는다.

가중합 전 단계이므로 `coverage.behavior_score`는 null이며, 부분 입력에서는
`coverage.can_aggregate=false`로 후속 종합 계산을 막는다. 실제 그룹/종합 점수 계산은
후속 가중합 Task에서 수행한다.

## 완료 기준별 직접 검증 — S15P21A207-272

가상환경을 활성화하고 `ai/`에서 실행한다. 다음 명령은 외부 API나 DB에 접근하지 않는다.

| 완료 기준 | 명령 | 기대 결과 |
| --- | --- | --- |
| 1. 필수/선택·0점/결측/해당 없음 결정표 | `python -m pytest tests/learning/personalization/evaluation/risk/test_coverage.py -k decision_table -v --tb=short` | 모든 결정표 사례 PASSED |
| 2. 요청/관측 범위 축소 시 정상 승격 금지 | `python -m pytest tests/learning/personalization/evaluation/risk/test_coverage.py -k 'omitted or missing or not_run or reduced' -v --tb=short` | 전체 9개 기준으로 누락 보존, 합산 불가 |
| 3. Cold 복귀 후 실패와 다른 근거 보존 | `python -m pytest tests/learning/personalization/evaluation/risk/test_assessment.py -k 'failure or fallback or readiness' -v --tb=short` | 실패와 원래 유효 점수·비중·충분도 보존 |
| 4. 동일 입력 재현·잘못된 연결/JSON 상태 거절 | `python -m pytest tests/learning/personalization/evaluation/risk/test_coverage.py tests/learning/personalization/evaluation/risk/test_assessment.py -k 'roundtrip or tamper or binding' -v --tb=short` | JSON 재현 및 조작 거절 |

Task 전체 검증:

```bash
python -m pytest tests/learning/personalization/evaluation/risk/test_coverage.py tests/learning/personalization/evaluation/risk/test_assessment.py -v --tb=short
```

기존 개인화·정책 계약 회귀는 `python -m pytest tests/learning/personalization -q --tb=short`로
실행한다. PASSED는 결정 규칙의 구현 검증이며 실제 사기 탐지 정확도를 의미하지 않는다.
