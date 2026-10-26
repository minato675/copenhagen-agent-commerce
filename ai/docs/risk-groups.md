# FDS 그룹 대표값과 기여도

`aggregate_risk_group(setting, features)`는 이미 계산된 최종 Feature 점수를 묶는다.
금액·빈도·시간별 점수를 다시 계산하거나 개인 충분도를 다시 적용하지 않는다.
정책의 그룹 비중과 기존 점수의 근거를 그대로 사용한다.

```python
from copenhagen_fds.risk_coverage import evaluate_risk_coverage
from copenhagen_fds.risk_groups import aggregate_risk_group

coverage = evaluate_risk_coverage(supplied)
frequency = supplied.policy.groups[1]
result = aggregate_risk_group(frequency, [row.feature for row in coverage.features
                                        if row.group_id == "FREQUENCY"])
```

## 고정 매핑과 수식

계산 버전은 `risk-group-max-first-v1`이다. 그룹과 Feature의 순서는 기존 정책
계약의 `GROUP_FEATURES`를 따른다. 지원하지 않는 그룹 구성·중복·추가·누락 ID는 거절한다.
입력 결과 배열의 순서는 자유이며 반환 결과는 아래 순서로 고정한다.

| 그룹 | Feature 순서 | 대표값 |
| --- | --- | --- |
| AMOUNT | amount_minor | 금액 점수 |
| FREQUENCY | purchase_count_10m, purchase_count_1h, purchase_count_24h, attempt_count_10m, attempt_count_1h, attempt_count_24h | 6개 점수의 MAX |
| TIME | kst_hour, kst_day_of_week | 2개 점수의 MAX |

그룹 점수 `G = max(s_i)`, 그룹 기여점수 `P = G × 정책의 그룹 비중`이다.
동률이면 위 순서의 첫 번째 MAX 항목을 대표로 선택한다. 그룹 내부 비중은 대표 항목 1,
나머지 0이므로 Feature의 최종 `weighted_points`는 대표 항목에 P, 나머지에 0이다.
각 Feature의 실제 점수·근거는 대표 선택 여부와 무관하게 보존한다.
0점만 있으면 그룹과 모든 기여점수는 정상 0이다.

유효한 Feature 점수는 0~100이므로 그룹 상한은 100, 그룹의 최대 영향력은
`100 × 그룹 비중`이다. 예를 들어 빈도 비중이 0.35일 때 6개 항목이 모두 100이어도
최종 기여점수는 35다. 6배로 가산하지 않는다. 시간·요일도 같은 방식으로 묶는다.
MAX는 중복 가산을 제한하기 위한 초기 정책이며 통계적 독립성이나 최적 탐지 성능을
입증한 방식은 아니다. 강한 신호·동시 이상 분류는 후속 정책에서 보완한다.

## 결측과 수치 처리

그룹의 항목 중 점수가 하나라도 없으면 그룹 점수와 그룹/Feature 기여점수는 모두 null이다.
유효한 개별 점수와 결측 사유는 남긴다. 요청 누락은 [평가 범위 판단](risk-coverage.md)에서
명시적인 NOT_RUN 결과로 확장한 뒤 전달한다. 일부 항목만으로 MAX를 확정하지 않는다.

NaN·무한대·범위 밖 점수·잘못된 비중은 거절한다. 양수 곱셈이 부동소수점의 최소 표현값보다
작아 0으로 반올림되면 기존 결과 계약에 따라 최소 양수로 보존한다. 일반 계산에서는
중간 반올림을 하지 않으며, 이 수치 예외를 사기 확률이나 새 위험 등급으로 해석하지 않는다.
