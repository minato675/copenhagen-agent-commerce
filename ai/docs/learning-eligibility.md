# 정상 패턴 학습의 기본 적격 정책

`assess_learning_eligibility`는 [학습 입력 계약](learning-contracts.md)을 받아 구매를 정상 패턴 학습에 반영할지 결정합니다. 프로필 통계나 FDS 위험 점수는 계산하지 않습니다.

```python
from copenhagen_fds.contracts.learning import LearningInput
from copenhagen_fds.learning import assess_learning_eligibility

request = LearningInput.model_validate(payload)
decision = assess_learning_eligibility(request)
print(decision.model_dump_json(indent=2))
```

## 정책 `confirmed-normal-v1`

| 알려진 상태 | 결과 | 사유 |
| --- | --- | --- |
| 확인 결과 없음·미확인 | `DEFER` 보류 | `UNCONFIRMED` |
| 의심 | `DEFER` 보류 | `SUSPECTED` |
| 정상 확인, 검증 수준 `VERIFIED` | `INCLUDE` 반영 | `NORMAL_CONFIRMED` |
| 사기 확인, 검증 수준 `VERIFIED` | `EXCLUDE` 제외 | `FRAUD_CONFIRMED` |
| 정상·사기 확인, 검증 수준 `UNVERIFIED` | `DEFER` 보류 | `UNVERIFIED_FEEDBACK` |
| 구매가 아직 발생하지 않았거나 관측되지 않음 | `DEFER` 보류 | `PURCHASE_NOT_OBSERVED` |
| 입력에 지정한 현재 평가 대상 구매 | `DEFER` 보류 | `CURRENT_PURCHASE` |

결제 성공과 실패는 관측 결과로 보존하지만 정상성의 증거로 사용하지 않습니다. 이 정책은 **확인된 정상 구매 요청 패턴**을 학습 대상으로 삼습니다. 정상으로 확인된 구매라면 결제 실패가 있어도 학습 대상이 될 수 있으며, 실제 결제 완료 소비액을 집계하는 정책과 구분합니다.

아직 미확인인 구매를 일정 시간이 지났다는 이유만으로 정상으로 바꾸지 않습니다. 정상 확인 비율이 낮으면 학습 표본이 적어질 수 있습니다. 후속 보류 해제·반영 철회·습관 변화 정책은 별도 작업에서 확장합니다.

## 시각과 중복 금액 처리

구매는 `occurred_at < as_of`, `available_at <= as_of`여야 하며, 구매 시작 사건도 적어도 하나 도착해 있어야 합니다. 현재 구매는 제외합니다. 시도 역시 기준 시각보다 전에 발생하고 그 시각까지 도착한 것만 반환합니다.

후속 확인은 `available_at <= as_of`인 경우에만 사용합니다. 기준 시각 뒤에 도착하는 사기 확인을 넣어도 그 이전 결과는 미확인 보류이며, 결과에 해당 확인 ID를 노출하지 않습니다. `requested_at`이나 실행하는 컴퓨터의 현재 시각으로 이를 앞당기지 않습니다.

반영할 금액 `learning_amount`는 구매 전체의 `order.total` 하나입니다. 예를 들어 판매처별 6,000원과 4,000원, 구매 전체 10,000원이 주어지면 학습할 금액은 **10,000원**입니다. 보류·제외 결과에는 학습 금액을 넣지 않습니다.

`observed_purchase`와 `observed_attempt_ids`는 정상 패턴 학습 여부와 별개입니다. 관측된 사기 구매를 학습에서 제외해도 그 구매와 시도가 사라지지 않습니다. 반환한 ID를 단순 누적 합산하는 것은 허용되지 않으며, 후속 시간순 표본 구성에서 원천 사건을 기준으로 구매·시도 중복과 수집 범위를 처리해야 합니다.

## 직접 검증

가상환경을 활성화한 Git Bash에서 **저장소의 `ai` 폴더**로 이동합니다. `ai/tests`에 있다면 `cd ..`로 이동합니다.

```bash
python -m pytest tests/learning/test_contracts.py tests/learning/test_eligibility.py -v --tb=short
```

전체 49개 사례가 `PASSED`여야 합니다. 49개의 별도 기능이 아니라 참조·시각·상태 조합을 포함한 자동 검증 사례입니다. 외부 네트워크는 기존 테스트 설정으로 차단합니다.

완료 기준별로 나누어 확인할 수도 있습니다.

| 완료 기준 | 명령 (`ai`에서 실행) | 예상 결과 |
| --- | --- | --- |
| 1. 참조·버전·시각 오류 거부 | `python -m pytest tests/learning/test_contracts.py::TestReferencesAndTime -v --tb=short` | 19 passed |
| 2. 반영·보류·제외 및 사유 | `python -m pytest tests/learning/test_eligibility.py -v --tb=short` | 15 passed |
| 3. 상태·프로필 JSON 보존과 기대 버전 검사 | `python -m pytest tests/learning/test_contracts.py::TestSerializationAndState -v --tb=short` | 11 passed |
| 4. 정답 격리·후속 확인 가용 시각 | `python -m pytest tests/learning/test_contracts.py::TestInputIsolation tests/learning/test_eligibility.py -k 'InputIsolation or future_feedback' -v --tb=short` | 6 passed, 나머지 deselected |

오류를 거부하는 테스트도 기대한 `ValidationError`가 발생하면 `PASSED`입니다. 미래 확인 정보 테스트는 정상·사기 확인 각각에 대해 도착 전에는 보류, 기준 시각에 도착하면 반영 또는 제외가 되는지 검사합니다.

기존 기능까지 확인하려면 `python -m pytest -q`를 실행합니다. 테스트 파일을 `python test_contracts.py`로 직접 실행하는 대신 반드시 `python -m pytest`를 사용합니다. PowerShell에서는 `python`을 `.\.venv\Scripts\python.exe`로 바꾸면 활성화 없이 실행할 수 있습니다.

이 검증은 입력 계약과 기본 학습 적격 정책의 검증입니다. 개인·집단 통계 프로필 학습, 오염 방지 정책 전체, 최종 GPT·FDS 통합이나 실제 사기 탐지 성능을 검증한 것은 아닙니다. MR 체크박스는 사용자가 직접 확인한 뒤 체크합니다.
