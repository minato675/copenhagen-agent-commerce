# 행동 프로필 참조와 관측 메타데이터

`copenhagen_fds.contracts.profiles`는 BE가 제공하는 개인·집단 행동 프로필의 식별자, 출처, 유효 기간과 Feature별 유효 표본 수를 검증한다. 현재 계약은 **관측 메타데이터와 참조**다. 평균·분산·분포·모델 가중치 등 실제 비교 계산에 필요한 통계값은 포함하지 않으며, 이 객체만으로 행동 유사도나 위험 점수를 계산할 수 없다. 프로필 생성·집계·학습·DB 조회·위험 분류는 수행하지 않는다.

## 공개 계약

| 계약 | 필드와 의미 |
|---|---|
| `ProfileScope` | `PERSONAL` 개인, `POPULATION` 집단 |
| `ProfileOrigin` | `OBSERVED` 관측 이력, `SYNTHETIC` 합성 이력이라는 제공자의 출처 표시 |
| `ProfileReference` | `profile_id`, `version`, `scope`, 개인용 `user_id` 또는 집단용 `cohort_id` |
| `FeatureObservation` | `feature_id`, `valid_sample_count`, `observed_from`, `last_observed_at` |
| `BehaviorProfile` | `reference`, `origin`, `source_reference`, `feature_schema_version`, `currency`, `as_of`, `generated_at`, `valid_until`, `observations` |

개인 프로필에는 `user_id`가 필요하고 `cohort_id`는 허용하지 않는다. 집단 프로필에는 `cohort_id`가 필요하고 `user_id`는 허용하지 않는다. 집단에 해당 사용자가 포함될 자격이 있는지, 올바른 집단을 선택했는지는 BE의 책임이다. 집단 프로필에 개인 ID를 넣어 소유 관계로 표현하지 않는다.

`source_reference`는 이력 배치 등을 가리키는 불투명한 참조값이다. 형식 검증을 통과해도 해당 데이터의 존재·진위·무결성이 증명되지 않는다. `OBSERVED` 역시 제공자의 선언이며 계약 자체가 실제 관측 여부를 인증하지 않는다. 합성 출처를 관측 출처로 자동 전환하지 않는다.

Feature마다 유효 표본 수가 다를 수 있다. `valid_sample_count`는 0 이상의 정수이며 문자열·실수·불리언을 수로 변환하지 않는다. 표본 수가 0이면 관측 시작·마지막 시각은 모두 없어야 한다. 양수이면 둘 다 필요하고 시작 시각이 마지막 시각보다 늦을 수 없다. Feature별 표본 수를 합쳐 전체 거래 수나 신뢰도로 해석하지 않는다. 신뢰도 계산과 최소 표본 기준은 별도 정책에서 정의한다.

`observations`는 필수 배열이다. 빈 배열은 제공된 관측 메타데이터가 없다는 뜻이며, 모든 Feature의 실제 이력이 0개임을 증명하지 않는다. 특정 Feature를 명시적으로 확인했지만 표본이 없다면 해당 Feature를 `valid_sample_count=0`으로 전달한다. 같은 프로필 안에서 `feature_id`는 중복될 수 없다. Feature ID의 의미와 허용 목록은 `feature_schema_version`에 대응하는 계산 명세에서 정의한다.

## 시간과 평가 대상 검증

모든 시각은 시간대가 있는 datetime 또는 ISO 문자열이어야 한다. epoch 숫자와 시간대가 없는 시각은 받지 않는다. 프로필의 시간 관계는 다음과 같다.

```text
observed_from <= last_observed_at < as_of <= generated_at < valid_until
```

`as_of`는 이력 포함 범위의 배타적 상한이다. 같은 시각에 발생한 현재 거래까지 과거 이력으로 섞지 않도록 관측 시각이 `as_of`와 같아도 거부한다. 실제 집계가 이 경계를 지켰는지 원시 데이터까지 조사하는 기능은 없으며, 제공된 메타데이터의 관계를 확인한다.

평가 시에는 `validate_for_evaluation`을 호출한다.

```python
profile.validate_for_evaluation(
    user_id=request.context.user_id,
    currency=request.order.total.currency,
    evaluated_at=evaluation_time,
    expected_reference=expected_profile_reference,
    feature_schema_version=expected_feature_schema_version,
)
```

메서드는 호출 인자의 형식도 검증하며 성공하면 같은 프로필 객체를 반환한다. 개인 소유자·통화가 다르거나, 기대한 전체 프로필 참조·Feature 스키마 버전이 다르면 거부한다. `as_of` 또는 `generated_at`이 평가 시점보다 미래인 경우와 평가 시점이 `valid_until` 이상인 만료 프로필도 거부한다. 생성 시점과 같은 평가 시각은 허용한다. 프로필을 사용하기 전에 비교할 버전이 알려져 있으면 선택 인자인 두 기대값을 전달해야 한다. 생략 시 최신 버전을 조회하거나 자동으로 추정하지 않는다.

모든 모델은 기존 `InputModel` 규칙에 따라 추가 필드를 거부하고 변경할 수 없다. API 키·원시 결제 정보·개인별 거래 목록을 이 메타데이터에 넣지 않는다. 평가 대상 검증은 호출자 인증·집단 가입 확인·사용자 승인·결제 실행 권한을 대신하지 않는다.
