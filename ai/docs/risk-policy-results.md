# 자체 FDS 최종 결과 계약

`NativeFraudDecision`은 기존 Sift 필수 `FraudDecision`과 구분되는 버전 계약이다.
이 모델 자체는 제공한 결과의 일관성을 검증한다.
그룹 계산·규칙 적용·GPT 분기·위험 분류를 실행하여 이 결과를 생성하려면
[최종 정책 함수](risk-evaluation.md)의 `evaluate_risk_policy(supplied)`를 사용한다.

```python
from copenhagen_fds.contracts.risk_results import NativeFraudDecision

result = NativeFraudDecision.model_validate_json(result_json)
result.validate_for_input(supplied)  # 별도 역직렬화 후 원래 RiskPolicyInput과 반드시 대조
```

## 출력 의미

| 필드 | 의미 |
| --- | --- |
| schema_version | native-fraud-decision-v1 |
| input_reference | 원래 평가 대상·trace·시각, 전체 입력 및 개인화 입력/결과·GPT 결과 내용 해시 |
| policy, policy_sha256 | 적용 설정 버전·파라미터 원문과 내용 해시 |
| status | SUCCESS / INCOMPLETE / FAILED / NOT_RUN |
| evaluation_scope | 필수 행동 Feature 기준 FULL / PARTIAL / UNAVAILABLE |
| score_coverage | 구성한 9개 Feature 점수 기준 COMPLETE / PARTIAL / UNAVAILABLE |
| groups | 원래 Feature 점수·상태·기준·사유, 그룹 대표 점수·가중치·기여 점수 |
| behavior_score | 전체 그룹의 가중 기여 합, 0~100 행동 이상 정도 |
| risk_level | LOW / MEDIUM / HIGH / UNKNOWN |
| rule_evidence, reasons | 발동했다고 보고한 규칙·관측 점수·최소 위험 수준·진단 근거 |
| recommendation, review_actions | 결과에 따른 확인 권고 및 후속 확인 작업 |

`input_reference`의 전체 입력 해시는 스키마·수식·프로필/정책 상태 버전과 원문을 함께 고정한다.
따라서 이 참조만 저장하는 경우 원래 `RiskPolicyInput`도 별도로 보관해야 설명을 재구성할 수 있다.
이 계약은 DB를 읽거나 저장하지 않는다. 해시를 일관되게 위조한 데이터의 진위를 인증하지도 않는다.

## 점수·상태 일관성

그룹 안의 모든 Feature 점수가 있어야 MAX 대표값과 가중 기여를 기록한다. 일부만 있으면
유효한 원래 Feature 점수를 보존하고 해당 그룹의 점수·기여도는 null이다. 전체 9개 점수가
있어야 종합 점수를 제공한다. 정상 0점과 null은 구분하며 누락 대체·재정규화는 허용하지 않는다.
MAX 동점 Feature끼리는 기여를 나눌 수 있지만 그룹 기여 합을 초과할 수 없다.

예를 들어 금액 100점·빈도 0점·시간 0점, 가중치 0.4/0.35/0.25라면 행동 종합 점수는 40점이다.
금액 90점 이상 시 HIGH 규칙의 근거를 명시하면 원래 40점을 보존한 채 위험 수준은 HIGH여야 한다.
이는 계약 검증용 가정이며 해당 가중치·규칙이 운영상 적합하다고 검증한 결과가 아니다.

`evaluation_scope=FULL`은 필수 행동 점수가 있다는 뜻이다. 선택 점수가 빠지면 scope는 FULL일
수 있어도 coverage는 PARTIAL이고 종합 점수·최종 위험 판단은 미완성으로 남는다.
실패 없는 완전한 점수와 필수 GPT 결과가 있어야 SUCCESS를 사용할 수 있다.
MISMATCH는 실행 성공인 의미 불일치이므로 확인 권고를 동반한 결과를 표현할 수 있다.
필수 GPT UNKNOWN/NOT_RUN은 INCOMPLETE이고, 실행 실패가 하나라도 있으면 FAILED다.
FAILED에서도 유효 점수와 COMPLETE coverage는 보존할 수 있다.

INCOMPLETE/FAILED/NOT_RUN은 risk_level=UNKNOWN, recommendation=null과 다음 확인 작업을
반환한다. NOT_RUN에는 정책 계산 시각이나 기여도를 넣을 수 없다. 구성 요소의 사전 평가
결과는 입력에 남길 수 있다. 낮은 행동 점수로 GPT 불확실성·실행 실패를 지우지 않는다.

`NO_ADDITIONAL_REVIEW`는 이 FDS가 추가 검토를 권고하지 않는다는 뜻이다. 결제 허가가 아니다.
`REVIEW_ORDER`, `VERIFY_USER_INTENT`, `REQUIRE_AUTH`도 BE가 검토할 권고이며 인증·승인·결제
차단이나 실행을 수행하지 않는다. 낮은 FDS 위험 수준으로 BE 승인 정책 위반을 해제할 수 없다.

## 직접 검증 — S15P21A207-271

가상환경을 활성화하고 `ai/`에서 실행한다. 모든 명령은 외부 네트워크 없이 실행된다.

| 완료 기준 | 명령 | 기대 결과 |
| --- | --- | --- |
| 1. 정상 JSON 왕복·입력/프로필/정책 연결 | `python -m pytest tests/learning/personalization/evaluation/risk -k 'roundtrip or binding' -v --tb=short` | PASSED, 입력·프로필·정책 내용 변경을 거절 |
| 2. 다른 대상·주문·시각·버전·잘못된 설정 | `python -m pytest tests/learning/personalization/evaluation/risk/test_inputs.py -v --tb=short` | PASSED, 정상 경계 허용 및 잘못된 값 거절 |
| 3. 0점/null·부분·실패·위험·권고·수치 | `python -m pytest tests/learning/personalization/evaluation/risk/test_results.py -v --tb=short` | PASSED, 실제 0점·부분 점수 보존 및 모순 거절 |
| 4. Sift 없는 자체 계약·기존 계약 호환 | `python -m pytest tests/contract tests/analysis tests/test_input_contracts.py tests/test_result_contracts.py tests/test_decision_contracts.py tests/test_behavior_contracts.py tests/test_cold_scoring.py tests/test_cold_evaluation.py tests/learning/personalization/evaluation/risk -q --tb=short` | 모든 테스트 통과 |

`PASSED`는 정의한 계약이 검증된 결과이며 탐지 정확도·최적 가중치·실거래 사기 탐지 성능을
의미하지 않는다. 이 단계의 예시 최종 결과는 테스트가 명시적으로 조립한 값이다.
