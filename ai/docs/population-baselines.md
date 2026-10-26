# 학습 집단 프로필의 Story 4 기준 변환

`derive_population_baseline(snapshot, request)`는 집단 프로필의 장기 분포에서
기준 계약 산출물과 Feature별 호환/거부 사유를 만든다. 기존 Cold 평가 함수는 호출하지
않는다. 통계 생성과 기준의 실효성 검증은 별개이며 출력은 `UNVALIDATED`다.

## 명시적 변환 정책

정책 버전은 `population-cold-quantiles-v1`이다. 이 정책은 관측 자료를 기존 거리 점수
계약으로 표현하는 초기 구현이며 사기 확률 보정이나 운영 임계값 학습이 아니다.

| 대상 | 중심과 거리 |
| --- | --- |
| 금액·빈도 | `UPPER_DISTANCE`, 중심 = 중앙값, 거리 = q95 - 중앙값 |
| KST 시간대·요일 | `CIRCULAR_DISTANCE`, 중심 = 순환 평균, 거리 = 중심으로부터 최단 거리의 가중 q95 |

숫자 Feature는 관측 집단보다 큰 값의 이탈을 표현한다. 여러 금액 군집 전체를 복원하는
밀도 모델이 아니므로 상세한 분포는 원본 집단 프로필에서 확인한다. 거래 집계에서는
개인과 같은 선형 보간 분위수, 사용자 균등 집계에서는 역경험누적분포 분위수를 사용한다.
시간대 거리는 히스토그램의 가중 질량을 사용한 역경험누적분포 분위수다.

기존 점수식에서 이 거리는 100점에 도달하는 거리다. 예를 들어 중앙값 100, q95 300이면
중심 100, 거리 200을 반환한다. 높은 점수가 사기임을 뜻하지 않으며 실제 성능 평가와
정책 조정이 필요하다. 현재 Task에서는 점수식 실행과 모델 결합을 진행하지 않는다.

시간대는 비어 있는 중간 bin이 없는 단일 연속 구간이고, 구간 길이가 반 주기보다
짧으며, 가중 질량이 한 번 상승 후 하강하는 경우만 변환한다. 자정·주말 경계는
순환 구간으로 처리한다. 분리된 아침/저녁 소비나 구멍 있는 희소 분포, 다봉 분포를
임의의 한 시간대로 정상화하지 않는다. 이 판정은 보수적인 지원 조건이며 통계적
단봉성 검정이 아니다. 단일 값만 관측됐거나 거리 0이면 양의 임의 epsilon을 넣지 않는다.

## 참조·버전과 결과

요청에는 `expected_source_reference`, `currency`, `feature_schema_version`, `available_at`을
명시한다. 원본 프로필의 참조와 버전을 먼저 대조하고 생성 이후, 만료 이전에만 기준을
내보낸다. 원본 통계의 잘못된 참조는 `REFERENCE_MISMATCH` 또는 `VERSION_MISMATCH`,
통화 오류는 `CURRENCY_MISMATCH`, 가용 시각 오류는 `INVALID_AVAILABILITY`다.

`PopulationBaselineResult.baseline`은 기존 `PopulationBaseline`으로 바로 JSON 파싱할 수
있다. 원본 출처·집단·통화·Feature 단위·창·관측 표본·시간을 보존한다. 변환된 기준에는
`cold-`로 시작하는 별도 프로필 ID와 원본 스냅샷·정책·파라미터·가용 시각에서 계산한
내용 버전을 부여한다. 후속 연결 시 원본 통계의 참조 대신 **반환된 baseline의 참조**를
사용해야 한다. 원본 참조와 전체 스냅샷 해시는 변환 보고서에도 남긴다.

| 결과 | 의미 |
| --- | --- |
| `COMPATIBLE` | 9개 Feature 모두 계약 파라미터 생성 가능 |
| `PARTIAL` | 일부만 가능. 가능한 Feature만 baseline.parameters에 포함 |
| `UNAVAILABLE` | 가능한 Feature가 없거나 참조/시각 오류. baseline은 null |

Feature별 사유는 `EMPTY`, `INSUFFICIENT_SAMPLES`, `INSUFFICIENT_USERS`, `ZERO_SCALE`,
`CIRCULAR_NOT_REPRESENTABLE`이다. 사용자 수/표본 수 기준은 Feature마다 적용한다.
실패 Feature를 점수 0이나 가짜 기준으로 채우지 않는다. 기존 정책 대체 여부는 후속
연결에서 기존 기준 선택기의 명시적 규칙을 따르며 이 변환기는 대체 정책을 실행하지 않는다.

## 직접 검증

가상환경을 활성화하고 `ai` 폴더에서 실행한다.

```bash
python -m pytest tests/learning/test_population_baselines.py -v --tb=short
```

16개 검증이 통과해야 한다.

| 확인할 내용 | 위 명령에 추가할 옵션 | 예상 |
| --- | --- | --- |
| 9개 Feature의 기존 계약·단위·JSON | `-k story4` | 1 passed |
| 표현 불가·부족·분산 0 | `-k 'multimodal or unavailable_sample'` | 7 passed |
| 잘못된 참조·버전·통화·가용 시각 | `-k invalid_reference` | 6 passed |
| 동일 결과·내용 버전·변조 검증 | `-k 'reproducible_versions or serialized'` | 2 passed |
