# 개인 시간대·요일 이상점수

`S15P21A207-267`은 기존 `kst_hour`(0~23)와 `kst_day_of_week`(월요일 0~일요일 6)를
published 개인 프로필의 분포와 비교한다. 최근·장기 통계 창의 점수 후보를 각각 반환한다.
입력은 `PersonalizedScoringInput`, 실행 함수는 `score_personal_times`, 결과는
`PersonalTimeResult`다. 원천 이벤트 재집계, 프로필 갱신, 외부 API 호출은 수행하지 않는다.

## 계산 정책과 근거

시간의 선형 평균이나 원형 평균을 정상 활동 중심으로 사용하지 않는다. 06시와 18시에
활동하는 이용자의 12시를 정상 중심으로 만들지 않도록 전체 히스토그램을 사용한다.
밤이나 주말이라는 이유만으로 점수를 추가하지 않는다.

주기 K의 각 구간 관측 횟수를 c[i]라 하면 다음과 같이 계산한다.

```text
m[i] = c[i] + w * (c[(i-1) % K] + c[(i+1) % K]) + alpha
peak = max(m)
score = 100 * (1 - m[current] / peak)
```

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `hour_neighbor_weight` | 0.25 | 시간대의 양쪽 이웃 관측을 각각 25% 반영 |
| `weekday_neighbor_weight` | 0.25 | 요일의 양쪽 이웃 관측을 각각 25% 반영 |
| `pseudocount` | 1.0 | 모든 구간에 더하는 양의 보정값 alpha |
| `version` | `personal-time-circular-rarity-v1` | 원형 이웃 평활화와 최빈 구간 상대 희소성 수식 |

이웃 가중치는 각각 0~0.5이며 0으로 끌 수 있다. 23시와 0시, 일요일과 월요일은
원형 이웃이다. 요일 평활화는 인접 요일의 행동이 일부 비슷할 수 있다는 **설계 가정**이다.
평일/주말 차이를 흐릴 수 있으므로 별도 설정으로 관리한다. 기본값은 운영 데이터로
보정한 값이 아니며 `validation_status=UNVALIDATED`다.

alpha는 미관측 구간의 계산을 유한하게 유지하고 적은 표본의 극단 점수를 완화한다.
전체 표본 부족을 대체하는 가상 학습 이력은 아니다. 가장 큰 평활화 값 대비 비율을
쓰므로 균등하게 활동하는 사람의 모든 구간은 0점이다. 단순 `100*(1-구간 확률)`처럼
시간대가 24개라는 이유로 모든 구간에 큰 점수를 주지 않는다.

`total_mass`는 `n*(1+2*w)+K*alpha`이며, 구간 질량을 확률 형태로 확인하려면
`smoothed_mass/total_mass`를 쓸 수 있다. 실제 점수 분모는 `peak_mass`다.
점수는 개인 시간 분포에서의 상대적 희소성이고 사기 확률이 아니다. 0점도 정상 거래
확정이 아니며, 다른 Feature와의 척도 호환 및 혼합은 Task 269에서 확인한다.

### 손으로 확인하는 예

과거 00시 구매 4건, 나머지 시간대 0건이면 기본 설정에서 다음과 같다.

| 현재 시간 | 직접 관측 횟수 | 평활화 값 | 점수 |
| --- | --- | --- | --- |
| 00시 | 4 | 5 | 0 |
| 23시 또는 01시 | 0 | 2 | 60 |
| 12시 | 0 | 1 | 80 |

미관측 구간도 인접 활동의 영향을 받을 수 있다. `bin_status=UNOBSERVED`는 보정 후 값이
아닌 직접 관측 0회를 뜻한다. 평소 00시 이용자와 12시 이용자의 같은 00시 거래는
서로 다른 점수를 받는다. 시간대와 요일은 각각의 주변 분포로 계산하므로
‘특정 요일의 특정 시간’ 조합에만 나타나는 이상은 이번 계산기에서 탐지하지 않는다.
시간대는 1시간 단위 구간이며 분·초에 따른 연속 점수는 제공하지 않는다.

## 입력·상태·출력

- 현재 구간은 기존 `transform_features`가 **구매 발생 시각**을 KST로 변환한 결과를
  사용한다. 평가 시각이나 서버 시계로 대체하지 않는다. 원시 Feature만 전달할 때는
  호출자가 기존 변환기와의 연결을 보장해야 한다.
- 요청한 시간 Feature만 평가하며 결과 순서는 시간대, 요일이다. 모든 요청 시간
  Feature의 `score_version`은 `personal-time-circular-rarity-v1`이어야 한다.
- 각 통계 창의 계산 최소 표본은 `max(2, published.config.min_samples,
  해당 Feature.readiness.minimum_samples)`다. 관측 기간·최근성·가용 비율과 최근/장기
  창 선택 및 단계 결정은 Task 268의 범위다.
- 현재 입력 미수집, 프로필 미제공/만료, 표본 0개/최소 미달, 계산 실패는 `score=null`과
  진단값을 반환한다. 프로필 표본이 충분하고 특정 구간만 0건이면 점수를 계산한다.
- 각 후보는 전체 분포 근거, 현재/양쪽 이웃의 직접 횟수, 표본 수, 원형 주기,
  보정 후 현재/최대/전체 질량을 보존한다. 질량은 기약분수로 계산하고 최종 0~100
  점수만 실수로 변환하므로 큰 횟수도 정밀도를 잃은 중간 실수에 의존하지 않는다.
- 입력·프로필·설정 해시와 수식 버전을 반환한다. 시각의 동등한 표기는 UTC로 정규화한
  해시에 반영된다. `validate_for_input`은 별도로 JSON 복원한 결과를 원입력으로
  재계산해 대조하며, 이 해시는 인증 서명을 대신하지 않는다.
- 일부 후보의 계산 실패가 다른 창이나 Feature의 성공 결과를 지우지 않는다.
  잘못된 입력/설정 계약은 실행 전에 예외로 거절한다.

```python
from copenhagen_fds.contracts.personal_time import PersonalTimePolicy, PersonalTimeResult
from copenhagen_fds.personal_time import score_personal_times

# supplied: 기존 계약과 공개된 프로필을 연결한 PersonalizedScoringInput
result = score_personal_times(supplied, PersonalTimePolicy())
restored = PersonalTimeResult.model_validate_json(result.model_dump_json())
restored.validate_for_input(supplied)
```

## 직접 검증

가상환경을 활성화한 `ai/` 위치에서 실행한다. Git Bash에서는
`source .venv/Scripts/activate`로 활성화한다. 테스트 파일을 Python으로 직접 실행하지
않고 `python -m pytest`로 실행한다. 외부 API 키나 서비스 DB는 필요 없다.

| Task 267 완료 기준 | 실행 명령 | 예상 결과 |
| --- | --- | --- |
| 1. 순환 경계·다중 활동·야간/주말 패턴 | `python -m pytest tests/learning/personalization/time -k "boundary or rotating or multimodal or night_user or weekend" -v --tb=short` | 11 passed |
| 2. 미관측 구간·표본 부족·점수 안정성 | `python -m pytest tests/learning/personalization/time -k "sample_shortage or missing_history or unavailability or extreme_counts or single_window_failure or missing_purchase_time" -v --tb=short` | 14 passed |
| 3. 자주 관측/드문 구간 점수·설정 근거 | `python -m pytest tests/learning/personalization/time/test_time_formula.py -v --tb=short` | 15 passed |
| 4. 재현성·동일 시각의 시간대 표현 | `python -m pytest tests/learning/personalization/time -k "roundtrip or same_instant or detached" -v --tb=short` | 15 passed |

Task 전체: `python -m pytest tests/learning/personalization/time -v --tb=short` → **61 passed**.
전체 회귀: `python -m pytest -q --tb=short` → Task 267 완료 시점 **2019 passed**.
선택 명령은 일부 테스트가 겹치며 `deselected`는 실패가 아니다. 이 테스트는 설계한 계산과
계약의 재현성을 확인한다. 실제 거래의 탐지율·오탐률 또는 GMS 호출 성공률을 의미하지 않는다.
