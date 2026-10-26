# GPT 확인·재평가 권고 정책

`evaluate_risk_intent_policy(supplied)`는 [의미·실행 상태 신호](risk-intent.md)를 확인 권고로 해석하고, [행동 가중합과 보완 규칙](risk-rules.md)을 그대로 함께 반환한다. 제공된 결과를 사용하는 내부 정책 함수다. 실제 API 호출, 재시도 스케줄링, 사용자 인증 및 결제 실행은 수행하지 않는다.

```python
from copenhagen_fds.risk_intent_policy import evaluate_risk_intent_policy
from copenhagen_fds.contracts.risk_intent_policy import RiskIntentPolicyResult

# supplied: 이미 계산된 행동 평가와 GPT 비교 결과를 가진 RiskPolicyInput
result = evaluate_risk_intent_policy(supplied)
print(result.intent.signal, result.status, result.review_actions)
print(result.rules.aggregation.behavior_score, result.rules.minimum_risk)
restored = RiskIntentPolicyResult.model_validate_json(result.model_dump_json())
restored.validate_for_input(supplied)
```

## 결정표

| GPT 신호 | 필수일 때 status | 선택일 때 status | review_actions 순서 |
| --- | --- | --- | --- |
| MATCH | SUCCESS | SUCCESS | 없음 |
| MISMATCH | SUCCESS | SUCCESS | REVIEW_ORDER → VERIFY_USER_INTENT |
| UNKNOWN | INCOMPLETE | SUCCESS | CHECK_INPUT → VERIFY_USER_INTENT |
| FAILED | FAILED | FAILED | 아래 실패 정책 참고 |
| NOT_RUN | INCOMPLETE | SUCCESS | RETRY_EVALUATION |

여기서 `status`는 **GPT 신호가 정책에 필요한 정보를 제공했는지**만 나타낸다. MISMATCH의 SUCCESS는 불일치 분석이 완료되었다는 뜻이다. 선택 신호의 UNKNOWN/NOT_RUN도 원래 신호와 확인 권고가 남는다. 이 상태만으로 거래의 안전 여부를 판단해서는 안 된다. 최종 분류는 행동 상태·규칙·GPT 권고를 함께 처리하는 후속 정책에서 담당한다.

타임아웃·일시적 API 장애는 `RETRY_EVALUATION`을 권고한다. `retryable=False`, 입력 부족/오류, 응답 거절·스키마 오류·미완성 응답, 인증·권한·요청 오류는 `CHECK_INPUT → RETRY_EVALUATION` 순서다. 먼저 입력·응답 또는 호출 설정을 확인한 뒤 새 결과로 재평가하라는 뜻이며, 재시도 가능 여부를 덮어쓰거나 즉시 같은 호출을 반복하라는 지시가 아니다. 원본 실패의 세부 종류와 진단 정보는 `intent.source.failure`에 남는다.

MISMATCH는 원본 `findings`와 `confirmation_requests`를 `intent.source.payload`에 그대로 보존하면서 주문·구매 의도 확인을 권고한다. UNKNOWN은 확정 불일치가 아니며 FAILED는 의미 판단 결과 자체가 없다.

## 행동 근거 보존

`rules`는 기존 규칙 평가 결과 전체다. 그룹 점수·가중 기여도·위험 하한·실패·결측을 수정하지 않는다. 예를 들어 행동 점수 75점과 HIGH 보완 근거는 GPT가 MATCH여도 그대로다. GPT가 MISMATCH여도 임의의 점수를 가산하지 않는다. 기존 개인화 계산이 실패한 후 Cold 점수가 제공된 경우에도 행동 FAILED 기록이 유지된다.

GPT와 행동의 입력 참조가 정확히 같아야 결합할 수 있다. 결과 JSON 복원 후 `validate_for_input`으로 원래 입력에 다시 연결해 검증한다. 최종 위험 등급·대표 권고·실제 실행은 이 결과에 포함되지 않는다.

## 완료 기준별 직접 검증

실행 위치는 저장소의 `ai` 디렉터리다. PowerShell/Git Bash 공통으로 아래 명령을 사용한다. 실제 GMS 호출 없이 Mock/Fixture로 실행하며 네트워크 연결을 시도하면 테스트가 실패한다.

전체 Task 검증은 다음 **64 passed**를 예상한다. 권고 정책 파일만 실행하면 **34 passed**다.

```sh
./.venv/Scripts/python.exe -m pytest tests/learning/personalization/evaluation/risk/test_intent.py tests/learning/personalization/evaluation/risk/test_intent_policy.py -v --tb=short
```

완료 기준 1: 의미·실행 상태/필수·선택 결정표, 실패 권고 및 만료 경계를 재현한다. **34 passed** 예상.

```sh
./.venv/Scripts/python.exe -m pytest tests/learning/personalization/evaluation/risk/test_intent.py tests/learning/personalization/evaluation/risk/test_intent_policy.py -v --tb=short -k 'decision_table or freshness or expiry or failures_require'
```

완료 기준 2: 판단 불가·실패가 MATCH로 바뀌지 않고 불일치 상세 근거·상품 참조·확인 요청이 보존된다. **19 passed** 예상.

```sh
./.venv/Scripts/python.exe -m pytest tests/learning/personalization/evaluation/risk/test_intent.py tests/learning/personalization/evaluation/risk/test_intent_policy.py -v --tb=short -k 'failure_kinds or detailed_findings or mismatch_details or required_optional'
```

완료 기준 3: 모든 GPT 상태에서 행동 점수 75점·HIGH 하한을 보존하고, MATCH로 계산 실패를 지우거나 결측을 0점으로 바꾸지 않는다. **7 passed** 예상.

```sh
./.venv/Scripts/python.exe -m pytest tests/learning/personalization/evaluation/risk/test_intent.py tests/learning/personalization/evaluation/risk/test_intent_policy.py -v --tb=short -k 'never_discounts or match_preserves or unknown_preserves'
```

완료 기준 4: 다른 사용자·구매·주문/의도 버전·상품 참조·정책의 결과 연결을 거절하고, API 클라이언트와 Feature 점수 계산을 다시 호출하지 않는다. **9 passed** 예상.

```sh
./.venv/Scripts/python.exe -m pytest tests/learning/personalization/evaluation/risk/test_intent.py tests/learning/personalization/evaluation/risk/test_intent_policy.py -v --tb=short -k 'target_binding or foreign_item or source_and_policy or cross_input or mismatch_details'
```

검증 오류를 예상하는 테스트는 해당 오류가 발생할 때 PASSED다. 자동 테스트 통과와 사용자의 완료 기준 확인은 별개다. 이 검증은 합성 사례에 대한 정책 재현성을 보여주며 실제 사기 탐지 성능을 측정하지 않는다.
