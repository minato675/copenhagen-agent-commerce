# 집단 기준과 초기 정책 계약

이 계약은 제공된 기준값과 출처·버전·시각을 표현한다. 집단 분포를 추정하거나 프로필을 학습하지 않으며, 이상점수·위험등급·그룹 가중치도 계산하지 않는다. 초기 정책의 값은 검증되지 않은 설정이다.

## 기준값과 단위

`FeatureReference`는 `feature_id`, `unit`, 선택적 `window_seconds`, `method`, `center`, `scale`, 선택적 `period`를 가진다. `center`는 음수 없는 유한수, `scale`은 양의 유한수다. 이력 표본 수만으로 이 수치를 만들거나 `scale=0`에 임의 epsilon을 넣지 않는다.

| method | 기준값의 의미 | 적용 Feature |
|---|---|---|
| `UPPER_DISTANCE` | center를 초과한 원시 값의 차이를 scale로 정규화할 기준 | 금액·빈도 |
| `ABSOLUTE_DISTANCE` | center와의 양방향 절대 차이를 scale로 정규화할 기준 | 금액·빈도 |
| `CIRCULAR_DISTANCE` | center와 주기상 최단 거리를 scale로 정규화할 기준 | KST 시각·요일 |

`scale`은 원시 값과 같은 단위의 거리이며 관측 표준편차라고 자동 해석하지 않는다. 실제 기준 생성 과정과 통계 방법은 원천의 버전·출처로 추적해야 한다. 점수 변환과 보정은 후속 계산기에서 수행한다. 합성 예제의 center/scale는 계약 시연용이며 운영 임계값이 아니다.

`cold-features-v1`의 9개 Feature 이름·단위·시간 창을 그대로 사용한다. 금액은 통화별 최소 단위, 빈도는 동일 통화의 구매 요청/시도 건수다. 시간대 기준은 `period=24`와 `0 <= center < 24`, 요일은 `period=7`과 `0 <= center < 7`이어야 한다. 시간대에 UPPER_DISTANCE를 사용하거나 수치 Feature에 임의 주기를 붙이지 않는다.

`FeatureReference` 단독 파싱은 구조·수치 형식을 검증하는 단계다. 후보 로더가 명세 일치를 검사해 오류 코드를 반환하며, 선택된 결과 계약에서도 동일 검사를 수행한다. 후보 객체를 파싱했다는 이유만으로 평가 기준으로 사용하지 않는다.

## 집단 기준

`PopulationBaseline`에는 기존 `BehaviorProfile`, 기준을 사용할 수 있게 된 `available_at`, Feature별 `parameters`가 필요하다. 프로필은 POPULATION이어야 하며 참조 ID·버전·집단 ID를 보존한다. Feature마다 프로필에 양수 표본 수를 가진 관측 정보가 있어야 한다. 현재 표본 수와 관측 시각은 제공자가 선언한 정보로, 통계값 생성의 진위를 증명하지 않는다.

기존 `observed_from <= last_observed_at < as_of <= generated_at < valid_until` 관계를 유지하고, `generated_at <= available_at`이어야 한다. 실제 선택에서는 생성·기준·가용 시각이 평가 시각보다 미래가 아닌지와 만료 여부를 검사한다.

출처 `OBSERVED`/`SYNTHETIC`와 `source_reference`를 그대로 보존한다. OBSERVED는 제공자가 실관측 자료라고 표시한 것이며, 검증된 사기 탐지 성능을 뜻하지 않는다. 양쪽 모두 `validation_status=UNVALIDATED`다. 이 계약은 데이터 출처를 인증하는 기능을 제공하지 않는다.

## 초기 정책

`InitialPolicy`는 `policy_id`, `version`, `source_reference`, 통화, Feature 명세 버전, 생성·가용 시각, `valid_from`/`valid_until`, Feature별 기준값을 포함한다. `origin=INITIAL_POLICY`, `validation_status=UNVALIDATED`만 허용하며 관측·학습 프로필로 표시하지 않는다.

정책 ID와 버전을 함께 고정한다. 서로 다른 정책이 같은 `v1`을 사용해도 같은 기준으로 취급하지 않는다. 생성 시각이 가용 시각보다 늦거나 적용 기간이 뒤집힌 설정은 거부한다. 과거로 지정한 `valid_from`만으로 미래에 생성·공개된 정책을 소급 적용할 수 없다.

두 후보의 문서 형식 버전은 `cold-reference-v1`이다. 실제 기준 버전(프로필 참조 버전 또는 정책 ID·버전), Feature 명세 버전, 문서 형식 버전은 서로 다른 의미다.

## 선택 결과의 계약

`BaselineSelectionRequest`는 평가 요청과 명시적인 `population_reference`, `policy_id`, `policy_version`을 받는다. 사용하지 않는 출처는 명시적 null로 표현하며 최신 버전을 임의로 추정하지 않는다. 정책 ID와 버전은 함께 존재하거나 함께 null이어야 한다.

`SelectedFeatureBasis`는 SELECTED/UNAVAILABLE, 기준값, 기존 `ScoringBasis`, 출처·정책 ID·검증 상태, 생성/가용/적용/만료 시각과 진단 코드를 보존한다. SELECTED는 기준을 선택했다는 뜻이며 Feature 값이나 이상점수가 계산됐다는 뜻이 아니다. UNAVAILABLE에는 적용 기준이 없고 진단이 필요하다.

`BaselineSelection`은 평가 대상·시각·통화·Feature 명세와 선택 Feature, 실제 사용한 집단 프로필을 보존한다. 역직렬화할 때 단위·방법·주기, 관측 근거, 출처, 시간 유효성을 다시 검사한다. 이후 `validate_for_request(selection_request)`로 사용자·구매 요청·주문 버전, 요청 Feature 집합 및 고정한 기준 참조를 대조한다. 기존 요청에 같은 프로필 ID가 이미 있다면 내용도 일치해야 한다.
