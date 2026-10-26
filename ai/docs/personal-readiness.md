# Feature별 개인 이력 충분도

`S15P21A207-268`의 `evaluate_personal_readiness(PersonalizedScoringInput)`은 published
개인 프로필에서 각 Feature의 최근·장기 충분도를 계산한다. 이상점수와 최종 위험등급은
계산하지 않는다. 정책 버전은 `feature-readiness-min-v1`이며 입력 설정의
`transition_policy_version`에 동일한 값을 명시해야 한다. 충분도 경계값은 호출자가
`ReadinessThresholds`로 전달한다. 운영 기본값이나 학습된 확률을 제공하지 않는다.

## 네 가지 축

| 축 | 측정값 | 개인 비중을 제한하는 이유 |
| --- | --- | --- |
| 표본 수 | 해당 Feature의 유효 표본 n | 한두 번의 거래를 안정적인 개인 패턴으로 간주하지 않음 |
| 관측 기간 | 마지막 유효 관측 - 첫 유효 관측 | 같은 시각에 몰린 거래 수만으로 장기간 패턴을 주장하지 않음 |
| 최근성 | 평가 시각 - 마지막 유효 관측 | 갱신되지 않은 프로필은 평가 시점이 늦어질수록 비중 축소 |
| 가용 비율 | n / (n + 해당 Feature 미산출 표본 수) | 일부 거래에서만 수집된 Feature의 개인 기준을 제한 |

가용 비율 분모에는 published 통계 창의 미수집·이력 부족·적용 불가 표본을 포함한다.
정책이 보류하여 published에서 제외한 후보 거래나 정답 라벨을 추가하지 않는다.
관측 기간은 관측이 퍼진 기간이며, 기다리기만 해서 늘어나지 않는다.
저장된 분포의 `recency_seconds`는 프로필 기준 시각의 값이므로 평가에 그대로 쓰지 않는다.

증가할수록 충분한 축은 다음 식으로 0~1에 매핑한다.

```text
ramp(x, minimum, full) = clamp((x-minimum)/(full-minimum), 0, 1)
samples     = ramp(n, effective_minimum, effective_full)
period      = ramp(observed_seconds, minimum_observed_seconds, full_observed_seconds)
availability = ramp(available_fraction, minimum_available_fraction, full_available_fraction)
freshness   = 1 - ramp(recency_seconds, fresh_for_seconds, stale_after_seconds)
personal_weight = min(samples, period, availability, freshness)
```

최소 경계와 같으면 증가 축은 0, 충분 경계 이상이면 1이다. 최근성은 신선 경계까지 1,
오래됨 경계부터 0이다. 한 축의 큰 값으로 다른 축의 부족을 보상하지 않는 보수적 설계다.

계산기의 최소 표본과 일치하도록 `effective_minimum=max(2, published.config.min_samples,
readiness.minimum_samples)`를 사용한다. `effective_full=max(effective_minimum+1,
readiness.full_samples)`로 전환 구간을 보존하고 실제 적용 경계를 결과에 기록한다.
이는 높은 프로필 최소 표본 설정을 우회하거나 최소 표본에서 바로 100%로 전환하지 않기 위함이다.

## 수집 범위의 사용 조건

구매·시도 횟수는 각 유효 학습 표본의 수집 범위를 추가 확인한다.

- 구매 횟수는 `purchase_coverage`, 시도 횟수는 `attempt_coverage`를 사용한다.
- 과거 표본의 평가 시각 t에서 `COLLECTED`이고, 수집 시작이 `t-window_seconds` 이하,
  종료가 t 이상이어야 해당 10분·1시간·24시간 Feature를 뒷받침한다.
- 검사한 표본 수와 충족 표본 수를 기록한다. 유효 값으로 표시된 횟수 중 하나라도
  수집 범위가 부족하면 해당 개인 통계 창의 비중은 0이다. 이미 만들어진 분포에서
  잘못된 표본만 임의로 제거하거나 원천 횟수를 재집계하지 않는다.
- 금액·시간대·요일은 개별 구매 사실의 값이므로 과거 횟수 스트림 검사를 요구하지 않으며
  `NOT_REQUIRED`로 표시한다. 연속적인 전체 기간 수집을 보증한다는 의미가 아니다.

이 검사는 제공자가 선언한 coverage와 값의 연결을 확인하며 실제 수집 시스템의 완전성을
인증하지 않는다. 현재 Raw Feature도 기존 변환기로 생성해야 한다. 현재 입력의 실제
0건은 값이 있는 상태이며 이력 미수집과 다르다.

## 누락과 0의 차이

| 상태 | 출력 |
| --- | --- |
| 프로필 미제공 | 모든 측정 축과 축별 계수 null, 개인 비중 0 |
| 제공된 프로필의 표본 0개 | n=0, 관측 기간/최근성 null |
| 해당 창의 고려 표본도 0개 | 가용 비율 null (0으로 나누거나 0%로 가정하지 않음) |
| 고려 표본은 있으나 해당 Feature 전부 누락 | 가용 비율 0, 관측 기간/최근성 null |
| 여러 유효 관측의 시각이 같음 | 관측 기간 0초 (누락이 아님) |
| 프로필 만료 | 측정 근거는 보존하고 개인 비중 0 |

최근·장기 양쪽 창의 분포·축·계수·수집 범위·제한 사유를 반환한다. 정확한 분수 연산 후
비중을 실수로 변환하며, 0 또는 1로 반올림된 전환 비중은 열린 구간 안에 보존한다.
재실행/JSON 복원 검증은 `PersonalReadinessResult.validate_for_input(supplied)`를 사용한다.

```python
from copenhagen_fds.personal_readiness import evaluate_personal_readiness

support = evaluate_personal_readiness(supplied)
for feature in support.features:
    for window in feature.windows:
        print(feature.feature_id, window.window, window.personal_weight, window.reasons)
```

가상환경을 활성화한 `ai/`에서 `python -m pytest tests/learning/personalization/readiness/test_readiness.py -v --tb=short`
를 실행한다. 예상 결과는 **45 passed**다. 단계 선택과 점수 혼합은 별도 모듈이 담당한다.
