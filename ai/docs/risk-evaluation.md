# FDS 최종 정책 평가

`evaluate_risk_policy(supplied)`는 이미 계산된 행동 평가와 GPT 비교 결과를 하나의 최종 FDS 결과로 조립한다. [등급 결정 우선순위](risk-classification.md)에 따라 실행 상태와 위험 수준을 정하고 사유·확인 권고를 붙인다. 입력의 대상·버전·시각·정책을 재검증하며 실제 GMS 호출·학습·프로필 갱신·인증·승인·결제 실행은 하지 않는다.

```python
from copenhagen_fds.risk_evaluation import evaluate_risk_policy
from copenhagen_fds.contracts.risk_evaluation import RiskEvaluationResult

# supplied: 기존 점수와 GPT 결과를 담은 RiskPolicyInput
result = evaluate_risk_policy(supplied)
decision = result.decision
print(decision.status, decision.risk_level, decision.recommendation)
print(decision.behavior_score, decision.review_actions)
restored = RiskEvaluationResult.model_validate_json(result.model_dump_json())
restored.validate_for_input(supplied)
assert evaluate_risk_policy(supplied) == restored
```

## 권고 결정표

실패·자료 부족이 우선한다. 이 경우 `risk_level=UNKNOWN`, `recommendation=None`이며 확인·재평가 작업은 `review_actions`에 남는다. 완성된 결과에서는 아래 우선순위로 대표 권고를 정한다.

| 순위 | 상태/조건 | 대표 recommendation | 추가로 보존되는 확인 작업 |
| --- | --- | --- | --- |
| 1 | FAILED 또는 INCOMPLETE | 없음 | 자료 확인·재평가 및 원래 GPT 확인 요청 |
| 2 | SUCCESS / HIGH | REQUIRE_AUTH | GPT의 주문·의도·입력 확인 요청 |
| 3 | SUCCESS / MEDIUM 또는 GPT MISMATCH | REVIEW_ORDER | MISMATCH이면 VERIFY_USER_INTENT도 유지 |
| 4 | SUCCESS / LOW, 선택 GPT UNKNOWN/NOT_RUN | VERIFY_USER_INTENT | 입력 확인 또는 재평가 |
| 5 | SUCCESS / LOW, GPT MATCH | NO_ADDITIONAL_REVIEW | 없음 |

확인 작업은 중복 없이 `CHECK_INPUT → RETRY_EVALUATION → REVIEW_ORDER → VERIFY_USER_INTENT → REQUIRE_AUTH` 순서로 기록한다. 필요한 항목만 포함한다. 인증/입력/응답 오류의 확인 후 재평가 순서는 기존 [GPT 권고 정책](risk-intent-policy.md)을 유지한다.

LOW는 현재 행동 분류이고 의미 불일치가 없다는 뜻이 아니다. LOW + MISMATCH에서도 주문·의도 확인을 권고한다. HIGH 역시 확정 사기 판정이 아니다. 모든 값은 FDS의 검토 권고이며 BE의 승인 검증을 대신하거나 해제하지 않는다. `NO_ADDITIONAL_REVIEW`도 결제 승인 증표가 아니다.

## 사유와 원본 추적

| 결과 경로 | 보존하는 내용 |
| --- | --- |
| decision | 기존 NativeFraudDecision 형식의 상태·점수·등급·사유·대표 권고 |
| decision.groups | Feature별 원래 점수·기준과 그룹 MAX·비중·기여도 |
| decision.reasons | 양수인 Feature의 BEHAVIOR_DEVIATION, 없는 점수의 INSUFFICIENT_DATA, 행동 실패/미실행, 규칙 및 GPT 사유 |
| decision.rule_evidence | 발동 규칙의 ID·관측 점수·하한 |
| classification.evidence.rules | 모든 규칙의 MATCHED/NOT_MET/UNAVAILABLE 및 최고 하한 |
| classification.evidence.rules.aggregation.assessment | Cold/개인화/결합 계산 실패, 충분도, 원래 상세 결과 |
| classification.evidence.intent.source | GPT 비교 차원·상품 참조·확인 요청·실패·모델/프롬프트 버전·측정값 |
| decision.input_reference, decision.policy, decision.policy_sha256 | 원래 입력·프로필/정책 버전·평가 시각·내용 해시 |

Feature의 양수 이탈 근거는 그룹 MAX에 선택되지 않아 기여점수가 0이어도 남는다. 종합 점수와 하한을 덮어쓰지 않는다. 예를 들어 75점 + HIGH 규칙은 GPT MATCH에서도 75점/HIGH이고, 개인 금액 실패 후 Cold 복귀한 사례는 유효한 42.5점을 보존하면서 FAILED/UNKNOWN이다.

사유가 전혀 없는 완전한 정상 사례에만 WITHIN_POLICY를 붙인다. 판단 불가·실패를 이 사유로 덮지 않는다. 간단한 소비자는 `decision`을 사용하고, 상세 설명·진단·재현에는 전체 `RiskEvaluationResult`와 원래 `RiskPolicyInput`을 보존한다. 해시는 내용 연결용이며 발급자 인증이나 저장소 기능이 아니다. 분류 및 조립 알고리즘 버전도 결과에 명시된다.

## 완료 기준별 직접 검증

저장소의 `ai` 디렉터리에서 실행한다. PowerShell/Git Bash 공통 명령이며 GMS 키 없이 Mock/Fixture로 검증한다. `-m pytest`로 실행해야 테스트 결과가 출력된다.

전체 Task 검증은 **60 passed**를 예상한다. 분류 33개, 결과 조립 27개다.

```sh
./.venv/Scripts/python.exe -m pytest tests/learning/personalization/evaluation/risk/test_classification.py tests/learning/personalization/evaluation/risk/test_evaluation.py -v --tb=short
```

위 명령 끝에 아래 `-k` 인자를 붙이면 완료 기준별로 선택하여 실행한다.

| 완료 기준 | 추가 인자 | 예상 통과 수 |
| --- | --- | --- |
| 1. 정상·강한/복합 이상·GPT/자료 부족 결정표 및 임계값 | `-k 'decision_table or threshold_boundaries'` | 35 |
| 2. GPT 상쇄 금지·실패/결측 보존 | `-k 'never or failure_and_missing or unresolved_result or both_failures or policy_execution'` | 21 |
| 3. 점수·기여도·규칙·GPT·버전 연결 및 JSON 재현 | `-k 'trace_to_original or replay or tamper or rebinding'` | 9 |
| 4. 순수 함수·실행 권한 없음·입력 재검증 | `-k 'pure or final_entry or payment_authority'` | 5 |

완료 기준 4의 기존 FDS/GMS Mock 회귀는 아래 두 명령으로 확인한다.

```sh
./.venv/Scripts/python.exe -m pytest tests/learning/personalization -q --tb=short
./.venv/Scripts/python.exe -m pytest tests/contract tests/analysis tests/test_input_contracts.py tests/test_result_contracts.py tests/test_decision_contracts.py tests/test_behavior_contracts.py tests/test_cold_scoring.py tests/test_cold_evaluation.py -q --tb=short
```

모든 테스트가 통과해야 한다. 잘못된 연결·만료·변조를 예상하는 테스트는 검증 오류가 발생할 때 PASSED다. 자동 검증과 사용자의 완료 기준 확인은 구분한다. 이 결과는 합성 사례의 정책 재현성을 검증하며 실거래 탐지 성능·가중치 최적화를 입증하지 않는다. 실제 모델 호출을 포함한 전체 요청 통합과 합성 시나리오 CLI는 각각 후속 구현 범위다.
