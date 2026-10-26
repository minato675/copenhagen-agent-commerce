# 프로필 학습 입력과 상태

`copenhagen_fds.contracts.learning`은 한 구매의 관측 기록과 정상 패턴 학습 판단을 연결합니다. 기존 `OrderSnapshot`, `HistoryEvent`, `StreamCoverage`, `BehaviorProfile`을 재사용합니다.

| 계약 | 역할 |
| --- | --- |
| `LearningPurchaseReference` | 사용자·구매 요청·주문 스냅샷 ID/버전/해시·통화 연결 |
| `PaymentOutcome` | 결제 시도별 성공·실패와 발생/가용 시각 |
| `LearningFeedback` | 후속 확인 분류·출처·검증 수준과 발생/가용 시각 |
| `LearningPurchase` | 구매 전체 주문·금액, 사건, 결제 결과, 선택된 후속 확인 결과 |
| `LearningInput` | 학습 기준 시각·실행 요청 시각·현재 구매·수집 범위·지원 버전 |
| `LearningDecision` | 반영/보류/제외와 이유, 관측 구매·시도, 반영할 구매 전체 금액 |
| `ProfileLearningState` | 사용자·통화별 판단 기록, 선택적 개인 프로필, 상태/정책/스키마 버전 |

## 참조와 집계 단위

한 `LearningPurchase`는 하나의 구매 요청과 주문 스냅샷 버전을 나타냅니다. 모든 사건·결제 결과·후속 확인은 해당 사용자·구매·주문·통화에 속해야 합니다. 스냅샷 버전과 해시는 그대로 비교하며 원문 해시를 재계산하거나 사용자를 인증하지 않습니다.

판매처별 구매 사건이 여러 개 있어도 금액 학습의 단위는 `order.total` 하나입니다. 판매처별 `orders[].total`을 전체 금액에 다시 더하지 않습니다. 배송·세금·할인도 기존 주문 계약과 같이 설명 정보이며 재합산하지 않습니다. 서로 다른 주문 버전을 서로 다른 구매 표본으로 저장할 수 없도록 상태 안의 구매 요청 ID를 유일하게 유지합니다.

입력 사건 ID와 결제 시도 ID는 중복되지 않아야 하며, 시도마다 결제 결과를 최대 하나 전달합니다. 원천 중복 제거·주문 버전 선택·여러 후속 확인의 시점별 선택·정정 재생은 입력을 만드는 계층이 수행합니다. 이 계약이 임의로 마지막 기록을 선택하지 않습니다.

## 시각과 수집 범위

- `occurred_at`: 구매·사건·결제 결과·후속 확인이 발생한 시각. 후속 확인의 경우 검토 결과를 확정하거나 의심 상태를 기록한 시각입니다.
- `available_at`: 해당 정보가 FDS에 제공 가능해진 시각. 발생 시각보다 이를 수 없습니다.
- 구매의 `evaluated_at`: 저장된 기존 평가 시각. 모르면 명시적으로 `null`을 전달하며 추정하지 않습니다. 지정하면 구매 가용 시각 이후여야 합니다.
- `as_of`: 이번 학습 판단에서 사용할 정보의 상한입니다.
- `requested_at`: 학습 실행을 요청한 시각이며 `as_of` 이상이어야 합니다. 과거 시점 재생 시에도 실제 실행 시각으로 정보 상한을 바꾸지 않습니다.
- `current_purchase_request_id`: 평가 직전 프로필에 들어가면 안 되는 현재 구매 ID입니다. 현재 평가와 무관한 학습 실행에는 명시적으로 `null`을 전달합니다.

구매·시도 수집 범위는 각각 `StreamCoverage`로 전달합니다. 제공된 사건의 스트림은 `COLLECTED`여야 하고, 완전 수집을 주장하는 구간은 `as_of`를 넘을 수 없습니다. 완전 수집 구간 바깥의 관측 사실도 존재할 수 있으며, 사건이 있다는 사실만으로 그 구간 전체가 수집됐다고 판단하지 않습니다. 빈도 Feature와 수집 공백의 계산은 후속 표본 구성에서 수행합니다.

시각은 시간대가 있는 ISO 문자열이나 datetime을 사용합니다. 잘못된 순서·시간대 누락·미지원 버전은 거부합니다. 미래에 도착할 원천 기록 자체는 표현할 수 있지만, 판단 함수는 기준 시각에 이용 가능했던 정보만 사용합니다.

## 상태 저장과 버전 확인

```python
from copenhagen_fds.contracts.learning import LearningInput, ProfileLearningState

request = LearningInput.model_validate(payload)
restored_request = LearningInput.model_validate_json(request.model_dump_json())

state = ProfileLearningState.model_validate_json(state_json)
state.validate_for_update(
    user_id=user_id,
    currency=currency,
    expected_state_version=expected_state_version,
    expected_profile_reference=expected_profile_reference,
)
```

현재 지원 값은 입력 `profile-learning-input-v1`, 상태 `profile-learning-state-v1`, Feature `cold-features-v1`, 정책 `confirmed-normal-v1`입니다. 상태 버전과 프로필 버전은 호출자가 제공하는 불투명 문자열로 보존하고, 갱신 시 기대한 값과 비교합니다.

`ProfileLearningState`는 사용자별 학습 판단 기록의 기본 형식입니다. 개인 프로필이 아직 없으면 `profile=null`입니다. 개인 프로필이 있으면 소유자·통화·Feature 스키마·기준 시각을 확인합니다. 평가 유효기간이 지난 프로필도 갱신의 이전 상태로는 전달할 수 있습니다. 집단 프로필 생성은 사용자별 자료를 별도로 집계하며, 집단 프로필을 이 개인 상태의 `profile`에 넣지는 않습니다.

이 상태만으로 전체 통계·분위수·기간 만료를 재계산할 수는 없습니다. 원천 이력 및 이후 정의할 학습 표본·통계 상태가 추가로 필요합니다. 프로필 통계 계산, 상태 갱신 실행, DB 저장과 원자적 버전 비교는 이 계약의 동작에 포함되지 않습니다.

## 검증 데이터와 운영 입력

모든 계약은 알 수 없는 필드를 거부합니다. `truth`, `is_fraud`, `scenario`, `persona` 등의 정답·시나리오·숨겨진 분포는 입력에 넣지 않습니다. `LearningFeedback`은 별도로 도착하는 후속 검토 기록이며, `source`와 `verification`은 호출자가 명시합니다. `VERIFIED`라는 문자열 자체가 검토자의 신원이나 결과의 진위를 증명하지는 않습니다.

기존 `fds-behavior-v1`의 구매·사건·결제 결과·후속 확인을 재사용할 때 어댑터가 실제 구매 참조를 연결하고 명시적으로 검증 수준을 전달해야 합니다. 합성 검토 출처는 `SYNTHETIC_REVIEW`로 보존합니다. 전체 이력을 계약에 연결하는 [합성 학습 표본 어댑터](synthetic-learning.md)를 제공하며, 통계 프로필 학습은 별도 단계입니다.

검증 오류 출력에는 원문 입력을 포함하지 않습니다. 외부 입력에 검증을 우회하는 `model_construct`나 `model_copy(update=...)`를 사용하지 않습니다.
