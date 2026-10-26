# 개인 구매·시도 빈도 이상점수

`score_personal_frequencies`는 기존 `PersonalizedScoringInput`에서 요청된 빈도 Feature만
평가한다. 수식 버전은 `personal-frequency-upper-linear-v1`이며, Feature마다 최근·장기
published 개인 분포의 점수 후보를 각각 반환한다.

| Feature | 원시 단위 | 활동 집계 구간 |
| --- | --- | --- |
| purchase_count_10m / purchase_count_1h / purchase_count_24h | PURCHASE_COUNT | 600 / 3600 / 86400초 |
| attempt_count_10m / attempt_count_1h / attempt_count_24h | ATTEMPT_COUNT | 600 / 3600 / 86400초 |

위 6개는 서로 다른 기준을 사용한다. 초당/분당 비율이나 실패 횟수, 구매 간격으로 바꾸지
않는다. `RECENT`/`LONG_TERM`은 개인 분포를 학습한 통계 창이고, 10분/1시간/24시간은
각 구매 평가 직전의 활동 집계 구간이다. 기존 프로필의 기본 통계 창은 최근 7일·장기 90일이다.

## 수식

현재 횟수 `x`, 해당 Feature의 개인 중앙값 `m`, 95분위수 `q95`, 사분위 범위 `IQR`,
중앙값 절대 편차 `MAD`를 사용한다.

```text
기본 변동 폭 = max(q95 - m, IQR, MAD, m × relative_scale_floor, minimum_scale_count)
100점 도달 거리 = 기본 변동 폭 × spread_multiplier
개인 빈도 점수 = 100 × min(max(x - m, 0) / 100점 도달 거리, 1)
```

기본 정책은 `relative_scale_floor=0.1`, `minimum_scale_count=1`,
`spread_multiplier=3.0`이다. 횟수가 평소보다 증가하는 방향을 평가하며, 동일 횟수만
관측된 경우에도 1건 이상의 명시한 최소 폭으로 계산한다. 금액과 공통인 정확한 분위수·
MAD·상방 점수 계산은 `personal_numeric.py`에서 재사용하며 단위와 정책은 분리한다.
금액 계산기의 기존 수식 버전·결과 형식은 유지한다.

| 개인 기준 이력 | 현재 횟수 | 결과 |
| --- | --- | --- |
| 0, 0, 0 | 0 | 0점: 관측된 실제 0건 |
| 0, 0, 0 | 1 / 2 / 3 | 약 33.33 / 66.67 / 100점 |
| 2, 2, 2 | 5 | 100점 |
| 20, 20, 20 | 5 | 0점: 해당 개인 중앙값 이하 |

구매 요청 수가 평소 수준이어도 결제 시도 수가 증가하면 시도 Feature만 높은 점수를
낼 수 있다. 점수는 상방 이탈 정도이며 사기 확률이 아니다. 감소한 활동을 이상으로
탐지하지 않으며 정상적인 반복 구매·재시도도 기준보다 많으면 높은 점수가 나올 수 있다.
정책 기본값은 검증 전 설계값이며 실제 사기 라벨로 보정한 임계값이 아니다.

## 기존 변환기와의 연결

운영 입력은 기존 `transform_features`의 결과를 사용한다. 점수 계산기는 이벤트를
다시 읽거나 집계하지 않는다. 기존 변환 규칙을 연결 테스트로 확인한다.

- 집계 구간은 `[평가 시각 - window_seconds, 평가 시각)`이다.
- 구매 요청은 purchase_request_id로 구분하므로 판매처별 복제 이벤트를 중복 계산하지 않는다.
- 시도는 (purchase_request_id, attempt_id)로 구분하며 동일 시도 이벤트 복제는 한 번만 센다.
- 현재 구매 요청과 현재 활성 시도는 제외한다. 현재 구매의 이전 재시도는 시도 수에 포함한다.
- 평가 시점에 알려진 동일 사용자·통화의 이벤트를 사용한다. 늦게 도착한 이벤트를 미리 반영하지 않는다.
- 수집 구간이 전체 집계 창을 덮을 때만 실제 0건을 반환한다. 10분만 수집됐다면 1시간/24시간을 0건으로 채우지 않는다.

개인 분포는 정상 확인 후 학습에 포함된 구매들의 평가 시점별 활동량 분포다.
그 활동량 안의 모든 이전 시도가 정상 확인됐다는 의미나 달력상의 일평균 구매 횟수는 아니다.

## 상태·근거·재현

각 Feature의 계산 최소 표본은 `max(2, published.config.min_samples,
해당 Feature readiness.minimum_samples)`다. 유효 표본 수는 Feature별로 다르므로
구매 횟수 통계가 충분해도 시도 횟수 통계는 부족할 수 있다.

| 조건 | diagnostic / 점수 |
| --- | --- |
| 현재 값 미수집 / 불완전한 이력 / 적용 불가 | INPUT_NOT_COLLECTED / INPUT_INSUFFICIENT_HISTORY / INPUT_NOT_APPLICABLE, null |
| 개인 프로필 미제공 / 만료 | PROFILE_NOT_SUPPLIED / PROFILE_EXPIRED, null |
| 해당 통계 창의 표본 0개 / 최소 미만 | EMPTY / INSUFFICIENT_SAMPLES, null |
| 유효한 분포, 동일 값 반복 포함 | SCORED, 0~100; 동일 값 반복은 zero_spread=true |
| 해당 후보의 계산 오류 | CALCULATION_FAILED, null; 다른 Feature와 통계 창 결과 보존 |

현재 입력의 누락 원인을 먼저 반환한다. 만료 경계는 `valid_until <= evaluated_at`이며
제공된 프로필의 창별 통계는 진단용으로 보존하되 미사용 기준에는 적용 basis를 붙이지 않는다.

원시 횟수·단위·집계 구간, 통계 창·유효/누락 표본 수, published 참조·정책 출처,
입력/수식 정책 해시와 분수 계산 근거를 남긴다. 정수 히스토그램의 분위수는 기존
`LINEAR_N_MINUS_ONE` 정의를 사용하며 표본 수만큼 히스토그램을 펼치지 않는다.
`ExactCount`의 `{numerator, denominator}`는 정확한 횟수 단위의 분수다.
범위를 제한한 뒤 float 점수로 변환하므로 큰 횟수도 중간 계산에서 넘치지 않는다.

```python
from copenhagen_fds.contracts.personal_frequency import PersonalFrequencyResult
from copenhagen_fds.personal_frequency import score_personal_frequencies

# 요청한 빈도 Feature의 score_version에 personal-frequency-upper-linear-v1을 명시한다.
result = score_personal_frequencies(supplied)
for feature in result.features:
    print(feature.raw.feature_id, feature.raw.value,
          [(row.window, row.diagnostic, row.result.score) for row in feature.windows])

restored = PersonalFrequencyResult.model_validate_json(result.model_dump_json())
restored.validate_for_input(supplied)
```

복원 뒤에는 `validate_for_input`으로 입력·정책·요청 Feature·결과를 다시 계산해 대조한다.
이는 입력 출처의 진위를 보증하는 서명이 아니다. 원천 수집 정보와 의도한 정책 해시는
호출자가 확인한다. 이 함수는 학습 상태를 변경하지 않으며 통계 창 선택·단계는 Task 268,
Cold 혼합·평가 결과 조립은 Task 269에서 처리한다. 최종 위험등급·GMS·DB 연결은 하지 않는다.

## 직접 검증

관련 Task: **S15P21A207-266**. 가상환경을 활성화하고 `ai/`에서 실행한다.

```bash
# 개인별 점수·6개 Feature 구분·상태·설정·JSON 재현: 50 passed
python -m pytest tests/learning/personalization/frequency/test_frequency_scoring.py -v --tb=short
# 기존 변환기 연결·판매처 복제·재시도·각 구간 경계·불완전 수집: 10 passed
python -m pytest tests/learning/personalization/frequency/test_frequency_history.py -v --tb=short
# Task 266 신규 전체: 60 passed
python -m pytest tests/learning/personalization/frequency -v --tb=short
# 기존 FDS/Cold/금액/GMS Mock 전체 회귀: Task 266 완료 시점 1958 passed
python -m pytest -q --tb=short
```

테스트는 기존 학습/정책 생성기와 고정 정상 Fixture, 변환기를 사용한다. 실제 GMS 호출이나
실거래 탐지 성능 평가가 아니다. 완료 기준 및 Draft MR 체크박스는 사용자가 직접 확인한다.
