# 개인화 평가 입력 및 설정 계약

`PersonalizedScoringInput`은 기존 `ColdScoringInput`, 개인화 설정, 선택적인
개인·집단 `PublishedProfileInput`을 받는다. 입력 검증만 수행하며 개인 점수 계산,
단계 결정, 학습 갱신, DB/API 호출은 하지 않는다.

```python
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput

supplied = PersonalizedScoringInput.model_validate(payload)
restored = PersonalizedScoringInput.model_validate_json(supplied.model_dump_json())
assert restored == supplied
availability = supplied.profile_availability()
```

## 기존 계약 연결

| 입력 | 의미 |
| --- | --- |
| `cold` | 기존 구매·Raw Feature·선택 기준·trace ID. 새 변환기나 점수 계산기를 만들지 않는다 |
| `config` | 설정 버전, 점수 척도 버전, 단계 정책 버전, Feature별 점수 함수 버전·충분도 경계 |
| `personal`, `population` | 정책 적용 후 통계와 확인 근거가 들어 있는 Story 5의 `ProfilePolicyState` 및 기대 참조·정책 상태 버전·가용 시각 |
| `population_baseline` | 집단 통계를 Cold에 연결할 때 사용하는 기존 `PopulationBaselineResult` 변환 산출물 |

`PublishedProfileInput`은 `state`, `expected_reference`, `expected_policy_state_version`,
`available_at`을 필수로 받는다. `state.published`만 통계 평가 대상으로 사용한다.
`candidate`는 적격 판단상 정상이나 정책으로 보류한 구매도 포함하므로 점수 기준으로
바꾸어 끼울 수 없다. 후보 스냅샷이나 정책 근거가 없는 통계 JSON만 전달하면 거부한다.
정책 설정의 내용 해시도 대조한다.

집단 통계와 Cold 변환 기준은 **서로 다른 ID·버전**을 갖는다. 집단 통계를 연결한다면
`population_baseline`의 원본은 `population.state.published`여야 하고, `cold`는 변환 결과의
참조를 고정한다. 기존 `derive_population_baseline`으로 재구성하여 원본·파라미터·변환
버전·가용 시각을 대조한다. 현재 선택한 집단 파라미터와 메타데이터도 그대로 일치해야 한다.
분산 0 등으로 변환하지 못한 Feature는 기존 Cold의 초기 정책/기준 부재 진단을 유지한다.

집단 통계를 제공하되 Cold에는 연결하지 않는 경우 변환 산출물을 생략하고
`cold.request.population_reference=null`로 명시할 수 있다. 통계를 제공하지 않는 기존
Cold 집단 기준은 기존 계약으로 검증하며, 정책 적용 후 학습 프로필이라고 보증하지 않는다.
학습 집단의 구성원 목록과 평가 사용자에게 어떤 집단 기준을 적용할지는 별개이며,
집단 배정의 타당성은 제공자가 확인한다.

## 시각과 대상 검증

* 사용자·통화·Feature 명세·구매/주문 참조·평가 시각은 기존 입력과 일치해야 한다.
  금액 Raw Feature가 가용하면 현재 주문의 최종 금액과도 같아야 한다.
* 학습 기준 시각은 `profile.as_of < evaluated_at`이다. 생성 이후 전달된 프로필만 사용하며
  `generated_at <= available_at <= evaluated_at`을 요구한다. 평가 시각에 이미 제공된
  자료는 허용하지만 평가 시각 이후 전달 자료와 같은 시각/미래 기준의 학습 결과는 거부한다.
* 현재 구매 ID가 개인 또는 집단의 정책 관측 이력에 존재하면 거부한다. 정책으로 보류하여
  `published`에서 빠진 경우도 후보 이력의 영향이 있을 수 있으므로 거부한다.
* 만료된 개인/집단 통계는 입력에서 진단용으로 보존할 수 있다. 결과의 가용 상태는
  `EXPIRED`이고 후속 개인 계산에 사용할 수 없다. 만료된 자료를 기존
  `BehaviorEvaluationRequest.profiles`에 사용 가능한 자료로 넣으면 기존 계약이 거부한다.
* 미제공은 `NOT_SUPPLIED`, 시간상 사용 가능은 `AVAILABLE`이다. AVAILABLE은 충분한
  표본이나 좋은 탐지 성능을 뜻하지 않는다. 현재 단계에서는 단계를 결정하지 않는다.

이 검증은 제공된 보관 이력·시각·버전의 일관성을 확인한다. DB 원본의 진위, 누락되지 않은
전체 이력, 사용자 인증, 보관 기간 이전의 모든 사건을 인증하지 않는다. 프로필은 기존
생성·정책 모듈의 산출물로 제공하고 BE에서 실제 가용 시점과 원본 상태를 관리해야 한다.

## 설정의 의미

`PersonalizationConfig`에 운영 기본값은 부여하지 않는다. 현재 테스트의 수치와
`fixture-*` 버전은 계약을 검증하기 위한 가정이며 구현된 점수 함수나 검증된 운영 정책을
뜻하지 않는다. 실제 점수 수식은 금액·빈도·시간대 계산기에서 정의하며, 단계 정책은
[개인 이력 충분도](personal-readiness.md)의 `feature-readiness-min-v1`을 사용한다.

각 Feature의 `ReadinessThresholds`는 다음 경계쌍을 명시한다.

* 최소/충분 유효 표본 수
* 최소/충분 관측 기간(초)
* 최근 관측 경과 시간의 신선/오래됨 경계(초)
* 최소/충분 Feature 가용 표본 비율(0~1)

하한은 상한보다 작아야 한다. 음수, 비유한 수, 범위를 벗어난 비율, 0개 이하 표본 수,
알 수 없는 Feature, 중복/순서가 뒤섞인 설정을 거부한다. 요청한 Feature의 설정은 모두
있어야 하며 추가 설정도 기존 9개 Feature 안에서만 허용한다.

관측 기간은 관측값이 퍼져 있는 기간이고 가용 표본 비율은 학습 표본 내 Feature의 가용성이다.
둘만으로 원천 스트림이 모든 시간에 연속 수집되었다고 해석하지 않는다. 원천 수집 범위는
기존 보관 표본의 구매/시도 coverage에 별도로 남으며 충분도 정책에서 횟수 표본별로 확인한다.
저장된 `recency_seconds`는 **프로필 기준 시각**의 값이다. 평가 시점의 최근성을 판단할 때는
마지막 관측 시각과 실제 평가 시각을 사용해야 한다.

## 직접 검증

가상환경을 활성화하고 `ai/`에서 실행한다.

```bash
python -m pytest tests/learning/personalization/test_input_contracts.py -v --tb=short
```

`PASSED`에는 잘못된 입력을 예상대로 거부한 사례도 포함된다. 정상 입력과 JSON 왕복,
candidate/published 구분, 실제 집단 변환 연결, 시각·만료·참조·설정 경계를 확인한다.
결과 계약과 손계산 예제는 [개인화 결과 계약](personalization-results.md)을 참고한다.
