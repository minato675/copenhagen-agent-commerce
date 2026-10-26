# FDS 그룹 가중합과 결과 재현

`aggregate_risk_behavior(RiskPolicyInput)`는 기존 Cold·개인화 최종 Feature 점수를
[그룹별 MAX](risk-groups.md)로 묶고 정책에 주어진 비중으로 종합 행동 이상점수를 계산한다.
이 함수는 위험 등급·확인 권고를 결정하지 않으며 외부 API/DB나 원래 점수 계산기를 호출하지 않는다.

```python
from copenhagen_fds.risk_aggregation import aggregate_risk_behavior
from copenhagen_fds.contracts.risk_aggregation import RiskAggregationResult

result = aggregate_risk_behavior(supplied)
restored = RiskAggregationResult.model_validate_json(result.model_dump_json())
assert restored.validate_for_input(supplied) == result
```

## 계산 예시

다음 비중은 테스트용 초기 설정의 예시이며 함수에 고정된 기본값이나 학습된 최적값이 아니다.

| 그룹 | Feature 점수 예시 | MAX | 설정 비중 | 최종 기여점수 |
| --- | --- | --- | --- | --- |
| 금액 | 80 | 80 | 0.4 | 32 |
| 빈도 | 10, 60, 60, 20, 30, 40 | 60 | 0.35 | 21 |
| 시간 | 20, 40 | 40 | 0.25 | 10 |

종합 점수는 `80×0.4 + 60×0.35 + 40×0.25 = 63`이다. 빈도의 두 60점 중
고정 순서에서 먼저인 `purchase_count_1h`에 21점을 배정하며 나머지는 0점이다.
0점 기여도는 원래 Feature가 정상이라는 뜻이 아니라 MAX 대표로 선택되지 않았다는 뜻이다.
이 배분은 정책 계산 내역이며 인과적 설명이나 SHAP 중요도가 아니다.

`math.fsum`으로 그룹 기여점수를 합산하고 중간 반올림은 하지 않는다. 그룹 비중은 양수이고
합이 1이어야 한다. 기존 설정 계약의 `1e-12` 합계 허용오차로 발생할 수 있는 100 초과만
최종 `min(100, 합계)`로 제한하며 가중치를 재정규화하지 않는다. 그룹별 원래 기여점수는
보존한다. 설정·결과 수치에 NaN·무한대·음수·100 초과 점수를 허용하지 않는다.

## 평가 범위와 실패

- `assessment`에는 필수/선택 결측·요청 누락·실행 상태·개인 충분도·실제 Cold/개인 비중을 보존한다.
- 전체 9개 점수가 있어야 `behavior_score`를 반환한다. 선택 항목만 부족해도 종합 점수는 null이다.
- 불완전한 그룹의 점수·기여점수는 null이지만 해당 그룹의 유효한 개별 점수·근거는 남는다.
  완전한 다른 그룹의 기여점수도 남기며, 이를 부분 종합 점수로 합산하거나 재정규화하지 않는다.
- 개인 계산 실패 후 유효한 Cold 점수로 복귀하면 종합 점수가 존재할 수 있다. 동시에
  `assessment.status=FAILED`와 원래 실패 근거를 유지하므로 점수 존재를 실행 성공으로 해석하지 않는다.
- 충분도가 이미 반영된 최종 Feature 점수를 그대로 사용한다. 예를 들어 Cold 0·개인 100을
  비중 0.5로 합친 금액 50점에 그룹 비중 0.4를 적용하면 20점이며 충분도 0.5를 다시 곱하지 않는다.

## 재현과 해시

`aggregation_version=risk-group-max-first-v1`은 MAX·고정 순서 첫 동률 선택·결측 처리·수치 규칙을
식별한다. `aggregation_sha256`은 이 버전, 정확한 정책 내용, 정렬된 그룹/Feature 기여도와
종합 점수의 해시다. 입력 배열 순서만 달라도 같은 계산 근거를 반환하면 같은 계산 해시를 갖는다.

`assessment.coverage.input_reference`는 사용자·거래·시각·원래 입력/프로필/정책/GPT 결과를
별도로 연결한다. 원본 배열 순서가 바뀌면 이 원본 내용 해시는 달라질 수 있다. 계산 해시는
요청의 동일성이나 진위를 증명하지 않으므로 결과를 원본에 연결할 때 `validate_for_input()`을
호출한다. JSON 검증은 MAX 동률 배분·가중치·합계·근거와 해시의 조작을 거절한다.

## 완료 기준별 직접 검증 — S15P21A207-273

가상환경을 활성화한 뒤 `ai/`에서 실행한다. 각 명령의 선택 사례가 모두 PASSED이면 통과다.

| 완료 기준 | 명령 | 기대 결과 |
| --- | --- | --- |
| 1. 손계산·기여도·0~100 | `python -m pytest tests/learning/personalization/evaluation/risk/test_aggregation.py -k 'hand or bounds or weights' -v --tb=short` | 그룹별 합계·63점 손계산·경계값 일치 |
| 2. 빈도 중복 영향·중복 배치 거절 | `python -m pytest tests/learning/personalization/evaluation/risk/test_groups.py -k 'cap or duplicate or mapping' -v --tb=short` | 빈도 최대 비중 고정·중복/잘못된 구성 거절 |
| 3. 순서·동률·해시 재현 | `python -m pytest tests/learning/personalization/evaluation/risk/test_groups.py tests/learning/personalization/evaluation/risk/test_aggregation.py -k 'order or tie or roundtrip or hash' -v --tb=short` | 고정 순서 동률 배분·계산 해시·JSON 왕복 일치 |
| 4. 결측 보존·설정/수치 오류 | `python -m pytest tests/learning/personalization/evaluation/risk/test_groups.py tests/learning/personalization/evaluation/risk/test_aggregation.py -k 'missing or invalid or failure or tamper' -v --tb=short` | null·유효 근거·실패 보존·잘못된 값 거절 |

Task 전체 검증:

```bash
python -m pytest tests/learning/personalization/evaluation/risk/test_groups.py tests/learning/personalization/evaluation/risk/test_aggregation.py -v --tb=short
```

관련 회귀: `python -m pytest tests/learning/personalization -q --tb=short`.
통과는 정책 수식과 상태 처리의 검증이며 실거래 사기 탐지 성능을 입증하지 않는다.
