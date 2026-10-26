# 시간순 Feature 재생과 학습 표본

`LearningReplayInput`은 거래 당시의 `FeatureTransformInput`과 학습 기준 시각의 `LearningInput`을 연결합니다. `replay_learning_samples`는 기존 `transform_features`와 `assess_learning_eligibility`를 호출해 구매당 하나의 `LearningSample`을 반환합니다.

```python
from copenhagen_fds.contracts.learning_replay import LearningReplayInput
from copenhagen_fds.learning_replay import replay_learning_samples

inputs = [LearningReplayInput.model_validate(payload) for payload in payloads]
samples = replay_learning_samples(
    inputs, origin="OBSERVED", source_reference="supplied-history-version",
)
eligible_samples = [sample for sample in samples if sample.eligibility.status == "INCLUDE"]
```

## 두 기준 시각

예를 들어 구매 당시 10:05에 직전 10분 구매가 1건이었고, 정상 확인이 10:11에 도착했다고 가정합니다.

| 학습 기준 시각 | 당시 Feature | 학습 적격 상태 |
| --- | --- | --- |
| 10:09 | 10:05 당시 구매 횟수 1건 | 미확인 `DEFER` |
| 10:11 | 같은 10:05 구매 횟수 1건 | 정상 확인 `INCLUDE` |

후속 확인은 학습 적격 여부를 바꿀 수 있지만 과거 Feature를 다시 쓰지 않습니다. `raw_features.evaluated_at`은 거래의 평가 시각이고, `eligibility.as_of`는 이번 학습의 정보 상한입니다. Feature 평가 시각은 학습 상한보다 앞서야 합니다. 현재 평가 대상 구매는 해당 평가 이전의 학습 표본에서 제외합니다.

원천 이벤트는 각 Feature 평가 시각에 `occurred_at < evaluated_at`, `available_at <= history.as_of`인 것만 사용합니다. 학습용 결제 결과·후속 확인 역시 학습 기준 시각까지 도착한 기록만 전달합니다. 시스템의 현재 시각은 판단에 사용하지 않습니다.

## 집계와 학습의 분리

- Feature 입력에는 당시 관측한 전체 구매·시도 이력을 넣습니다. 나중에 사기로 확인되거나 학습 보류된 구매도 활동 횟수에서 사라지지 않습니다.
- 구매 횟수는 구매 요청 ID, 시도 횟수는 구매 요청 ID와 시도 ID 조합으로 집계합니다. 판매처별 구매 사건과 동일 사건의 재전달을 별도 구매로 세지 않습니다.
- 금액은 구매 전체 `order.total` 하나입니다. 재시도마다 금액 표본을 다시 만들지 않으며, 이번 표본 형식은 구매당 하나의 구매 평가점을 사용합니다.
- 정상 표본 선택은 반환된 `eligibility.status`로 별도 수행합니다. `EXCLUDE`는 이 기본 정책의 정상 패턴 학습 제외이며 최종 결제 차단이나 모델이 탐지한 사기 건수를 뜻하지 않습니다.

완전하게 수집한 빈 구간은 `AVAILABLE / 0`, 수집 정보가 없으면 `NOT_COLLECTED / null`, 구간 일부가 부족하면 `INSUFFICIENT_HISTORY / null`입니다. 모든 표본에는 금액·구매 빈도·시도 빈도·시간대·요일의 기존 Feature 9개가 들어가며, 모르는 Feature를 누락하거나 0으로 채우지 않습니다. 거래 당시 구매·시도 수집 범위도 표본에 보존합니다.

## 충돌과 재현성

동일한 구매의 동일 입력은 한 번만 반환합니다. 같은 구매의 주문 스냅샷이나 재생 컨텍스트가 다르면 임의로 하나를 선택하지 않고 거부합니다. 한 배치의 학습 정보 상한은 같아야 합니다. 결과는 평가 시각 → 사용자 ID → 구매 요청 ID 순서로 정렬합니다.

표본 스키마는 `purchase-learning-sample-v1`, Feature는 `cold-features-v1`, 기본 정책은 `confirmed-normal-v1`입니다. 출처 구분과 원천 참조를 포함하며 JSON 왕복으로 보존합니다. 동일한 입력·수집 범위·정보 상한·정책·출처 참조에서 같은 표본을 반환합니다.

원천 중복과 가용 시각 처리, 합성 데이터 내보내기는 [합성 학습 표본 사용법](synthetic-learning.md)에 설명합니다. 이 단계는 통계 프로필 생성·갱신, 가중치 학습이나 GPT·FDS 최종 통합을 실행하지 않습니다.

## 직접 검증

기존 가상환경을 활성화하고 저장소의 `ai` 폴더에서 실행합니다.

```bash
python -m pytest tests/learning/test_replay.py tests/learning/test_synthetic_learning.py -v --tb=short
```

예상 결과는 **27 passed**입니다. 완료 기준별로 나누어 실행하려면 다음 명령을 사용합니다. 일부 테스트는 여러 완료 기준을 함께 검증하므로 그룹 사이에 겹칠 수 있습니다.

| 완료 기준 | 명령 (`ai`에서 실행) | 확인 내용 |
| --- | --- | --- |
| 1. 현재·미래·지연 정보 격리 | `python -m pytest tests/learning/test_replay.py tests/learning/test_synthetic_learning.py -k 'clock or current_purchase or cutoff or late or unavailable' -v --tb=short` | Feature 시각 고정, 미래·지연 정보 차단, 현재 구매 제외 |
| 2. 중복·판매처·재시도 집계 | `python -m pytest tests/learning/test_replay.py tests/learning/test_synthetic_learning.py -k 'duplicate or merchant or attempt_identity' -v --tb=short` | 구매와 시도 단위, 충돌 거부, 학습 제외 거래의 활동 집계 유지 |
| 3. 실제 0과 수집 부족 구분 | `python -m pytest tests/learning/test_replay.py tests/learning/test_synthetic_learning.py -k 'collection or missing_values' -v --tb=short` | 0 / 미수집 / 불완전 수집, 명시적인 결측값 |
| 4. 재현성·정답 격리 | `python -m pytest tests/learning/test_replay.py tests/learning/test_synthetic_learning.py -k 'deterministic or private_truth or observable_only' -v --tb=short` | 입력 순서·중복 독립성, 정답 파일 미열람, 재실행 파일 일치 |

각 명령에서 선택된 테스트가 모두 `PASSED`여야 합니다. 거부 테스트는 예상한 오류가 발생하면 통과하며, `deselected`는 이번 명령에서 선택하지 않은 테스트입니다. PowerShell에서는 `python` 대신 `.\.venv\Scripts\python.exe`를 사용할 수 있습니다.

MR 체크박스의 문구·순서와 Task 연결은 유지합니다. 이 단계의 자동 테스트 통과는 사용자 직접 검증이나 후속 통계·갱신 Task의 검증 완료를 대신하지 않습니다.
