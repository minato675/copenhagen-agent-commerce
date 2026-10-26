# FDS 정책 입력과 설정

`RiskPolicyInput`은 이미 생성한 `PersonalizedEvaluationResult`와
`IntentComparisonResult`를 연결하는 자체 FDS 계약이다. 계산이나 GMS 호출을 하지 않는다.
`request`, `scoring_input`, `behavior`, `intent`, `policy`, `evaluated_at`을 전달한다.
아직 행동 평가를 실행하지 않았다면 `behavior=null`, GPT를 실행하지 않았다면 기존
`IntentComparisonResult(status="NOT_RUN", ...)`을 명시한다. 누락을 성공 또는 0점으로 바꾸지 않는다.

```python
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput, RiskInputReference

supplied = RiskPolicyInput.model_validate(payload)
assert RiskPolicyInput.model_validate_json(supplied.model_dump_json()) == supplied
reference = RiskInputReference.from_input(supplied)
```

## 대상·시각·버전

사용자·평가 ID·구매·주문 스냅샷 ID/버전/해시·의도 버전·trace를 대조한다.
별도로 제공한 요청은 개인화 입력의 요청 내용 전체와 같아야 한다. 개인화 결과의
`result.validate_for_input`으로 설정·프로필 참조/정책 상태/내용 해시·원시 값을 연결한다.
기록된 계산 실패를 보존하기 위해 점수 계산기는 재실행하지 않는다.

행동 결과는 원래 평가 시각에만 사용할 수 있다. 다른 시각이면 과거 빈도/시간대 점수도
달라질 수 있으므로 새 입력으로 재평가한다. 기본적으로 GPT 시각은 행동 기준보다 미래일 수 없으며
`age < intent_max_age_seconds`, 명시한 `valid_until > evaluated_at`을 모두 만족해야 한다.
경계에 도달한 결과는 거절한다. 만료된 개인 프로필이 진단에 남아 있는 유효 Cold 대체 결과는
허용하되, 만료 프로필을 실제 개인 계산 근거로 사용하는 것은 기존 계약이 거절한다.

통합 실행에서 GPT가 행동 기준 이후에 완료되는 경우에는 `policy_evaluated_at`으로 실제
정책 판단 시각을 별도 제공한다. 이때 GPT 나이·미래 여부·만료는 정책 시각에서 확인하고
행동 기준은 유지한다. 생략/`null`이면 기존 JSON·해시·시각 검증을 유지한다.
구체적인 의미와 경계 검증은 [통합 요청과 시각](engine-inputs.md)을 참고한다.

지원하는 스키마·점수 척도·단계 정책·Feature 수식 버전을 검증한다. 배포별 설정 `version`은
제공자가 정하는 식별자이고, 결과와 입력의 내용 해시를 함께 보관하여 같은 이름의 설정 변경도
구분한다. 해시는 원천 데이터나 사용자 승인을 인증하는 서명이 아니다.

## 정책 설정

`RiskPolicyConfig` 자체는 가중치·임계값을 명시적으로 요구한다.
프로젝트 [기본 위험 정책](default-risk-policy.md)은 `default_risk_policy()`로 제공하며,
`EngineEvaluationRequest`에서 `risk_policy`를 생략하면 요청에 해당 설정을 채운다.
`RiskPolicyInput`을 직접 구성할 때는 `policy=default_risk_policy()`를 전달한다.

| 그룹 | 고정 Feature 구성 |
| --- | --- |
| AMOUNT | amount_minor |
| FREQUENCY | purchase_count와 attempt_count 각각 10m/1h/24h |
| TIME | kst_hour, kst_day_of_week |

각 Feature의 `required`로 평가 가능 여부에 필요한 신호를 선언한다. 선택 신호가 누락되어도
그 점수가 0점이라는 뜻은 아니다. 전체 점수는 모든 구성 점수가 있을 때만 제공하고,
필수 신호에 따른 평가 가능 범위는 별도로 다룬다.

현재 그룹 대표값 방식은 `MAX`로 한정한다. 같은 신호의 중복 누적을 제한하기 위한 초기
설계이며 최적 방식이라는 증거는 없다. 가중치는 각각 0 초과 1 이하, 합은 1이어야 한다.
금액·빈도·시간 그룹과 내부 Feature 순서는 위 표와 기존 Feature 스키마 순서를 따른다.
중복·잘못된 그룹 배정·비유한 수·잘못된 합은 거절하며 자동 재정규화하지 않는다.

위험 경계는 `0 < medium_from < high_from <= 100`이다. `floor_rules`는 하나의 강한 그룹
신호 또는 서로 다른 그룹의 동시 이상에 대한 최소 MEDIUM/HIGH 규칙을 명시한다. 각 조건은
최소 점수 이상이어야 한다는 선언이며, 이 Task에서는 규칙을 실행하지 않는다.
GPT는 별도 정책 신호이고 수치 점수와 평균내거나 MATCH로 행동 점수를 할인하지 않는다.
개인화 단계에서 이미 정한 최종 Feature 점수를 그대로 사용하여 충분도를 이중 할인하지 않는다.

모든 정책은 `UNVALIDATED`이며 실거래 라벨로 검증한 사기 확률·최적 가중치를 뜻하지 않는다.
예전 Sift 필수 `FraudDecision`은 호환성을 위해 그대로 유지한다. 새 계약에 Sift 응답이나 키는 필요 없다.

## 직접 검증 — S15P21A207-271

가상환경을 활성화한 상태에서 `ai/`에서 실행한다.

```bash
python -m pytest tests/learning/personalization/evaluation/risk/test_inputs.py -v --tb=short
```

정상 왕복, 대상·내용·프로필 버전·시각·지원 버전·설정 오류가 예상대로 허용/거절되면 PASSED다.
`python test_inputs.py`는 pytest를 실행하는 명령이 아니다.
