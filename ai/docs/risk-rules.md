# FDS 규칙 우선순위와 보완 근거

`evaluate_risk_rules(RiskPolicyInput)`는 [규칙 조건 평가](risk-rule-checks.md)를 실행하고
충족된 규칙의 위험 하한을 조립한다. 원래 [그룹 가중합](risk-aggregation.md)과 기여도,
충분도·단계·결측·실패 상태를 `aggregation`에 그대로 보존한다.

```python
from copenhagen_fds.risk_rules import evaluate_risk_rules
from copenhagen_fds.contracts.risk_rules import RiskRuleResult

result = evaluate_risk_rules(supplied)
restored = RiskRuleResult.model_validate_json(result.model_dump_json())
assert restored.validate_for_input(supplied) == result
```

## 반환값과 우선순위

| 필드 | 의미 |
| --- | --- |
| aggregation | 원래 점수·기여도·계산 해시·평가/실행 상태·정확한 원본 연결 |
| checks | 설정된 모든 규칙의 ID·조건·관측 점수와 MATCHED/NOT_MET/UNAVAILABLE |
| rule_evidence | MATCHED인 규칙의 ID·관측 점수·하한만 기존 최종 결과 계약 형태로 반환 |
| minimum_risk | 일치 규칙 중 가장 높은 MEDIUM/HIGH 하한, 일치 규칙이 없으면 null |
| decisive_rule_ids | 가장 높은 하한을 제시한 모든 규칙 ID |
| reasons | 일치 규칙마다 하나의 RULE_TRIGGERED 사유 |

평가 버전은 `risk-floor-observed-v1`이다. HIGH가 MEDIUM보다 우선하며 같은 하한이면
임의로 하나를 지우지 않고 모든 규칙 ID를 남긴다. `checks`·근거·ID·사유는 규칙 ID의
문자열 오름차순으로 반환한다. 입력 규칙 배열 순서로 우선순위가 바뀌지 않는다.

같은 규칙 ID의 중복은 기존 정책 계약에서 거절한다. 다른 ID로 같은 조건을 등록하거나
단일·동시 이상 규칙이 겹치면 모든 근거를 추적하되 가장 높은 하한 하나만 반환한다.
위험 점수나 기여점수를 반복 가산하지 않는다. 예를 들어 금액 HIGH와 금액+빈도 MEDIUM이
동시에 충족되면 결과 하한은 HIGH이고 두 규칙 근거와 HIGH의 결정 규칙 ID가 남는다.

규칙이 없거나 모두 미충족/관측 불가이면 하한은 null이다. 이는 LOW 판정이 아니라
규칙이 추가하는 하한이 없다는 뜻이다. 기존 가중합이 높은 경우를 낮추지 않는다.

## 결측·실패와 적용 단계

규칙에 필요한 그룹의 모든 최종 점수를 최소 관측 조건으로 사용한다. COLD·TRANSITION·
PERSONALIZED는 기존 점수와 실제 혼합 비중을 그대로 사용하며 단계별 가중치를 새로 적용하지 않는다.

- 필요한 그룹이 결측·미실행·계산 실패로 점수를 제공하지 못하면 해당 규칙은 UNAVAILABLE다.
  점수 없는 항목을 100이나 강한 이상으로 대체하지 않는다.
- 규칙과 관계없는 그룹이 결측이면 관측된 다른 그룹의 규칙은 충족될 수 있다. 종합 점수 null과
  INCOMPLETE 상태는 유지한다. 이때도 규칙의 하한 근거를 최종 위험 등급으로 확정하지 않는다.
- 개인 계산 실패 후 유효한 Cold 고점수로 복귀했다면 그 **관측 점수**로 규칙을 충족할 수 있다.
  FAILED·Cold 복귀·실패 사유는 그대로 남는다. 실패가 규칙을 발동시키는 조건은 아니다.
- GPT MATCH/MISMATCH/UNKNOWN/실패는 이 행동 규칙에 숫자로 반영하지 않는다. GPT 정책과
  최종 상태·위험 분류·확인 권고의 조립은 후속 단계에서 담당한다.

`minimum_risk`는 후속 분류에 사용할 정책 하한이다. 이 결과 자체에는 최종 `risk_level`이나
결제 허용/차단 권한이 없다. 실제 사기 여부·탐지 정확도 또는 학습된 최적 임계값을 뜻하지 않는다.

## 재현 검증

JSON 복원 시 규칙 누락·중복, 관측 점수·충족 상태·하한·우선순위·근거/사유 조작을 거절한다.
`validate_for_input()`으로 원본 요청·프로필·정책과 연결한다. 실패를 복원할 때 Feature 계산기를
다시 호출하지 않으며, 당시 기록된 실패를 현재 실행 성공으로 바꾸지 않는다.
정책 배열 순서만 달라도 원본 정책/입력 해시는 달라질 수 있지만 조건 판단과 하한은 같다.

## 완료 기준별 직접 검증 — S15P21A207-274

가상환경 활성화 후 `ai/`에서 실행한다. 선택된 사례가 모두 PASSED이면 해당 규칙 검증이 통과한 것이다.

| 완료 기준 | 명령 | 기대 결과 |
| --- | --- | --- |
| 1. 강한 신호 희석·그룹 동시 이상 | `python -m pytest tests/learning/personalization/evaluation/risk/test_rules.py -k 'dilution or concurrent' -v --tb=short` | 원래 낮은 가중합 보존·지정한 규칙 하한 반환 |
| 2. 임계값 직전/동일/직후·단계 | `python -m pytest tests/learning/personalization/evaluation/risk/test_rule_checks.py tests/learning/personalization/evaluation/risk/test_rules.py -k 'boundary or stages' -v --tb=short` | 경계의 포괄 비교·Cold/전환/개인화 동일 정책 |
| 3. 중복·충돌·규칙 ID/근거 | `python -m pytest tests/learning/personalization/evaluation/risk/test_rule_checks.py tests/learning/personalization/evaluation/risk/test_rules.py -k 'duplicate or overlap or priority or order or tamper' -v --tb=short` | 중복 거절·HIGH 우선·동률 ID 보존·조작 거절 |
| 4. 결측·실패·원본 보존 | `python -m pytest tests/learning/personalization/evaluation/risk/test_rules.py -k 'missing or failure or preserved or roundtrip' -v --tb=short` | 관측 불가 구분·원본 점수/기여도/상태 보존 |

Task 전체 검증:

```bash
python -m pytest tests/learning/personalization/evaluation/risk/test_rule_checks.py tests/learning/personalization/evaluation/risk/test_rules.py -v --tb=short
```

관련 회귀: `python -m pytest tests/learning/personalization -q --tb=short`.
외부 API/DB 호출 없이 합성 사례로 규칙 구현을 검증한다.
