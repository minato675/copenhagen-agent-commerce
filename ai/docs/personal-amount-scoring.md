# 개인 금액 이상점수

`score_personal_amount`는 `PersonalizedScoringInput`의 현재 구매 금액을 정책 적용 후
공개된 개인 프로필과 비교한다. 최근·장기 창마다 점수 후보와 계산 근거를 반환한다.
수식 버전은 `personal-amount-upper-linear-v1`이다.

## 수식과 해석

현재 금액 `x`, 개인 금액 중앙값 `m`, 95분위수 `q95`, 사분위 범위 `IQR=q75-q25`,
중앙값 절대 편차 `MAD=median(abs(x_i-m))`를 사용한다.

```text
기본 변동 폭 = max(q95 - m, IQR, MAD, m × relative_scale_floor, minimum_scale_minor)
100점 도달 거리 = 기본 변동 폭 × spread_multiplier
개인 금액 점수 = 100 × min(max(x - m, 0) / 100점 도달 거리, 1)
```

기본 설정은 `spread_multiplier=3.0`, `relative_scale_floor=0.1`,
`minimum_scale_minor=1`이다. 상위 금액 폭·중간 분포 폭·MAD 중 큰 값을 사용해 한 가지
분포 요약만으로 척도를 결정하지 않는다. 개인 금액 규모에 대한 최소 폭은 동일 금액
반복 시 아주 작은 증가가 곧바로 100점이 되는 현상을 완화한다. 1 최소 통화 단위는
0원 이력에서도 0으로 나누지 않기 위한 하한이다. 이 설정은 검증 전 설계 정책이며
실거래에서 학습하거나 성능을 보정한 임계값이 아니다.

중앙값 이하 금액은 이 **상방 이탈 수식에서** 0점이다. 정상 거래라는 확정이나
소액 테스트 결제의 부재를 뜻하지 않는다. 다른 행동 Feature와 최종 결합은 후속
작업에서 다룬다. 정상 고액 구매도 높은 점수를 받을 수 있으며 사기 확률이 아니다.

| 개인 이력 | 현재 금액 | 계산 | 점수 |
| --- | --- | --- | --- |
| 100, 100, 100 | 100 | 중앙값 이하 | 0 |
| 100, 100, 100 | 115 | 기본 폭 10, 도달 거리 30, 이탈 15 | 50 |
| 100, 100, 100 | 130 | 이탈이 도달 거리와 같음 | 100 |
| 1000, 1000, 1000 | 200 | 해당 개인 중앙값 이하 | 0 |
| 80, 90, 100, 110, 120 | 115 | q95=118, IQR=20, MAD=10, 도달 거리 60 | 25 |

모든 금액은 같은 통화의 최소 단위다. 예를 들어 USD의 1은 1센트이며 환율 변환을
하지 않는다. 입력이 원/달러 등의 주 단위로 잘못 작성됐지만 유효한 정수인 경우에는
그 오류를 자동으로 추정할 수 없으므로 호출자가 단위를 지켜야 한다.

## 정확한 계산과 근거

프로필의 정수 히스토그램에서 기존 `LINEAR_N_MINUS_ONE` 정의로 분위수를 계산한다.
순위 `(n-1)×p`의 양옆을 보간하며, 출현 횟수만 사용하므로 히스토그램을 표본 수만큼
펼치지 않는다. MAD도 정확한 중앙값에서 다시 계산한다. 이는 새 거래를 학습하는
동작이 아니라 이미 공개된 분포를 점수 수식에 대입하는 과정이다.

요약 통계의 float에서 먼저 빼면 큰 금액의 1 단위 차이가 없어질 수 있으므로 계산은
`Fraction`으로 한다. `AmountCalculation`은 중앙값·q95·IQR·MAD·100점 도달 거리를
`{numerator, denominator}` 형태의 기약분수로 보존한다. 통계 창의 기존 요약값도
`evidence.distribution.numeric`에 남지만 계산을 복원할 때는 정확한 분수/히스토그램을 쓴다.
예를 들어 `{numerator: 125, denominator: 2}`는 62.5 최소 통화 단위다.

최종 비율을 0~1로 제한한 뒤 float 점수로 변환한다. 표현 한계 때문에 양수 이탈이
0으로 반올림되면 최소 양의 float로 보존한다. 충분한 동일 금액 이력은
`zero_spread=true`와 명시한 하한을 사용하며, 데이터 부족으로 처리하지 않는다.

## 입력과 반환 상태

금액의 `PersonalFeatureConfig.score_version`에 `personal-amount-upper-linear-v1`을
명시해야 한다. 다른 Feature의 버전이나 단계 설정을 이 함수가 바꾸지는 않는다.
함수 호출 시 입력을 다시 검증하므로 `model_copy`로 만든 잘못된 대상·통화·단위·참조·
미래 시각도 거부한다. 기존 입력 계약대로 현재 구매가 프로필 이력에 포함되면 거부한다.

계산 최소 표본은 `max(2, published.config.min_samples, 금액 readiness.minimum_samples)`다.
최소 2개는 한 표본을 개인 분포로 취급하지 않는 계산 조건이며, 충분한 관측 기간이나
개인화 단계 진입을 보장하는 조건은 아니다.

| 조건 | diagnostic | 점수 |
| --- | --- | --- |
| 현재 금액 미수집/이력 부족/적용 불가 | INPUT_NOT_COLLECTED / INPUT_INSUFFICIENT_HISTORY / INPUT_NOT_APPLICABLE | null, 기존 입력 원인 유지 |
| 개인 프로필 미제공/만료 | PROFILE_NOT_SUPPLIED / PROFILE_EXPIRED | null, REFERENCE_UNAVAILABLE |
| 해당 창의 표본 0개 | EMPTY | null, INSUFFICIENT_HISTORY |
| 해당 창의 표본이 최소 미만 | INSUFFICIENT_SAMPLES | null, INSUFFICIENT_HISTORY |
| 유효 기준으로 계산 | SCORED | 0~100 |
| 계산 중 수치 오류 | CALCULATION_FAILED | null, FAILED; 다른 창의 결과 보존 |

만료 경계는 `valid_until <= evaluated_at`이다. 현재 입력이 없는 경우 그 원인을
우선 반환한다. 프로필이 있다면 만료/미산출이어도 두 창의 근거는 진단용으로 남지만
실제 적용 basis는 계산을 시도한 유효 기준에만 붙는다.

이력의 금액 누락은 기존 정상 학습 계약에서 허용되지 않는다. 학습 가능한 금액 없이
표본을 만들어 0원으로 채우지 않는다. 빈 창과 현재 금액의 누락은 위 상태로 구분한다.

## 사용과 재현

```python
from copenhagen_fds.contracts.personal_amount import PersonalAmountPolicy, PersonalAmountResult
from copenhagen_fds.personal_amount import score_personal_amount

# supplied는 검증된 PersonalizedScoringInput이며 금액 score_version을 지정해야 한다.
result = score_personal_amount(supplied, PersonalAmountPolicy())
recent, long_term = result.windows
print(recent.result.score, long_term.result.score)

restored = PersonalAmountResult.model_validate_json(result.model_dump_json())
restored.validate_for_input(supplied)
```

JSON 복원 후 `validate_for_input`은 동일 입력과 결과에 담긴 정책으로 다시 계산해
점수·분포 근거·참조를 대조한다. 입력 전체와 정책 내용의 SHA-256을 별도로 기록하므로
수식 버전이 같아도 정책 값이 달라지면 정책 해시가 달라진다. 입력 출처의 진위를
인증하는 서명은 아니며, 호출자가 의도한 정책 해시인지도 별도로 확인해야 한다.

이 함수는 창을 선택하거나 개인/Cold를 혼합하지 않는다. 표본 수 이외의 충분도 및
창 선택은 Task 268, 혼합·평가 결과 조립은 Task 269다. 기존 Cold와 같은 0~100 범위를
사용해도 도달 거리 정의가 다르므로 두 점수의 통계적 척도가 같다고 가정하지 않는다.
GMS 호출·DB 저장·최종 위험등급·실거래 탐지 성능 평가는 포함하지 않는다.

## 직접 검증

관련 Task: **S15P21A207-265**. 가상환경을 활성화하고 `ai/`에서 실행한다.

```bash
# 손계산, 개인별 차이, 단조성, 0원·통화 최소 단위·극단 수치: 18 passed
python -m pytest tests/learning/personalization/test_amount_formula.py -v --tb=short
# 창별 충분도, published 기준, 입력 거부, 설정 및 결과 재현: 41 passed
python -m pytest tests/learning/personalization/test_amount_candidates.py -v --tb=short
# 기존 FDS/Cold/GMS Mock 회귀: Task 265 완료 시점 1898 passed
python -m pytest -q --tb=short
```

`PASSED`는 예상한 점수/상태이거나 잘못된 입력이 예상대로 거부됐다는 뜻이다.
자동 검증 통과와 사용자의 완료 기준 확인은 구분한다.
