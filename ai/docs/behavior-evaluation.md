# 자체 FDS 행동 평가 계약

`BehaviorEvaluationRequest`와 `BehaviorEvaluationResult`는 제공된 구매·프로필·Feature 평가 결과를 검증한다. 계산식·학습·평가 호출·가중치·위험등급은 실행하지 않는다. 실제 Feature 계산과 기준 로딩은 별도 구현에서 이 계약을 사용한다.

## 요청과 결과의 연결

요청에는 기존 `FraudEvaluationRequest`, 시간대가 있는 `evaluated_at`, `feature_schema_version`, 비어 있지 않고 중복 없는 `requested_features` 목록, 선택적인 `profiles`를 전달한다. 프로필이 없으면 빈 목록으로 표현한다. 요청은 프로필의 개인 소유자·통화·스키마·생성 시점·만료를 검사한다. 집단 선택과 원천 데이터 인증은 BE 책임이다.

결과는 실행 상태·기존 평가 메타데이터·공통 사유에 다음 필드를 연결한다.

| 필드 | 의미 |
|---|---|
| `feature_schema_version` | 계산 명세 버전. 실제 Feature 이름·계산식의 허용 목록은 해당 명세에서 정의 |
| `requested_features` | 호출자가 요청한 Feature 목록. 결과 일부 누락으로 평가 범위가 축소되지 않도록 사용 |
| `scoring_version` | 실행한 점수 함수 버전. 성공 및 부분 실패에 남는 계산 결과에는 필수 |
| `coverage` | 요청 Feature 중 점수가 산출된 범위: COMPLETE / PARTIAL / UNAVAILABLE |
| `features` | Feature별 상태·점수·기준·사유 |
| `profiles` | Feature의 기준으로 실제 참조한 프로필 관측 메타데이터 |

개별 역직렬화 후 **`result.validate_for_request(request)`를 호출해야 한다.** 이 메서드는 사용자·평가·구매 요청·현재 주문 스냅샷 ID/버전/해시·의도 버전, 평가 시각, Feature 명세·요청 목록을 대조한다. 결과에 사용된 프로필은 요청에 제공된 전체 스냅샷과 일치해야 하며 버전 문자열만 같고 표본 수·출처 등이 달라진 스냅샷도 거부한다. 프로필 ID와 Feature ID의 중복도 거부한다.

결과만 역직렬화하면 실제 호출 요청과의 일치 여부까지 확인되지는 않는다. `model_construct`나 `model_copy(update=...)`로 외부 입력의 검증을 우회하지 않는다. 입력·프로필·출처 참조는 BE가 제공한 선언이며 계약 검증이 원천 데이터나 사용자 승인 진위를 증명하지 않는다.

## Feature 상태와 점수

| 상태 | 점수 | 사유 코드 |
|---|---|---|
| `SCORED` | `AnomalyScore` 필수 | WITHIN_REFERENCE 또는 PATTERN_DEVIATION |
| `INSUFFICIENT_DATA` | 없음 | INSUFFICIENT_HISTORY, REFERENCE_UNAVAILABLE |
| `NOT_COLLECTED` | 없음 | MISSING_INPUT |
| `NOT_APPLICABLE` | 없음 | NOT_APPLICABLE (수집 대상이 아닌 Feature) |
| `FAILED` | 없음 | CALCULATION_FAILED |
| `NOT_RUN` | 없음, 사용한 기준도 없음 | NOT_EXECUTED |

`AnomalyScore`는 `{"value": 0.0, "unit": "BEHAVIOR_ANOMALY_0_100"}` 형식이며 유한한 0~100 값만 받는다. 0은 계산된 실제 결과이고, 계산하지 못한 상태는 `score=None`이다. 이 값은 행동의 이상 정도이며 보정된 사기 확률이 아니다. Sift 단위 `SIFT_PAYMENT_ABUSE_0_1`을 받거나 자동으로 100배 변환하지 않는다.

사유 코드는 계산자가 제공하는 설명이다. 계약은 사유와 상태의 일관성을 검사하며 점수 크기만으로 사유나 임계값을 추정하지 않는다. 실제 값·계산식과 설명의 일치는 계산기 테스트에서 검증해야 한다.

점수가 존재하면 `bases`에 사용 기준을 명시한다.

- `INITIAL_POLICY`: `policy_version` 필수, 프로필 참조 없음. 집단 데이터가 없어 설정한 초기 정책임을 드러낸다.
- `POPULATION_PROFILE`: 집단 `ProfileReference` 필수.
- `PERSONAL_PROFILE`: 개인 `ProfileReference` 필수. 계약으로 표현할 수 있지만 개인화 계산은 별도 구현 범위다.

프로필 기준은 결과의 `profiles`에 동일 참조·버전으로 존재해야 하고 해당 Feature의 관측 정보를 가져야 한다. 프로필로 계산한 점수에는 양수 표본이 필요하다. 관측이 없는 프로필을 근거로 계산 완료를 주장할 수 없다. 결과에는 실제 참조한 프로필만 포함한다. 평가 유효기간은 사용한 프로필의 유효기간을 넘을 수 없다. 여러 기준의 가중 혼합이나 최신 버전 선택은 이 계약에서 수행하지 않는다.

## 실행 상태와 데이터 가용 범위

`coverage`는 요청한 Feature를 분모로 산출 가능 범위를 검사하는 값이며 개인 이력의 통계적 충분도나 신뢰확률이 아니다. 초기 정책만으로 모든 요청 Feature를 계산하면 COMPLETE가 될 수 있으므로 `bases`와 프로필의 표본 수·관측 범위를 함께 해석해야 한다.

- `SUCCESS`: 요청한 모든 Feature의 상태와 점수 함수 버전이 필요하다. 데이터 부족·미수집 Feature는 있을 수 있지만 실패·미실행 Feature는 포함하지 않는다.
- `SUCCESS`이면서 PARTIAL/UNAVAILABLE: 공통 사유 `INSUFFICIENT_DATA`가 필요하고 결과 유효기간은 부여하지 않는다. 실행에 성공했다는 이유로 정상 결제라고 판단하지 않는다.
- `FAILED`: 실행 시각과 공통 실패 사유가 필요하다. 오류 전에 계산한 Feature는 근거로 보존할 수 있으며 누락된 요청 Feature가 있어도 coverage를 COMPLETE로 축소 계산하지 않는다. 실패 결과에는 유효기간이 없다.
- `NOT_RUN`: 실행 시각·적용 점수 버전·사용 프로필이 없다. Feature 목록은 비어 있거나 NOT_RUN 항목으로만 구성된다.

최종 위험 분류는 별도 정책에서 실행 상태·coverage·기준·점수를 함께 처리한다. 이 결과에는 위험등급·승인·실행 명령이 없다.

## 호출 경계 예시

아래 0점은 계약 사용법을 위한 직접 지정한 합성 값이다. 점수 계산 예제가 아니다. `purchase_request`는 검증된 기존 `FraudEvaluationRequest`다.

```python
from copenhagen_fds.contracts import BehaviorEvaluationRequest, BehaviorEvaluationResult

request = BehaviorEvaluationRequest(
    request=purchase_request,
    evaluated_at="2026-09-10T10:00:00+09:00",
    feature_schema_version="example-features-v1",
    requested_features=["amount"],
    profiles=[],
)
result = BehaviorEvaluationResult.model_validate({
    "status": "SUCCESS",
    "metadata": {
        "target": request.target,
        "trace_id": "example-trace",
        "sources": ["MOCK"],
        "evaluated_at": request.evaluated_at,
    },
    "reasons": [],
    "feature_schema_version": request.feature_schema_version,
    "requested_features": ["amount"],
    "scoring_version": "example-scorer-v1",
    "coverage": "COMPLETE",
    "features": [{
        "feature_id": "amount",
        "status": "SCORED",
        "score": {"value": 0.0, "unit": "BEHAVIOR_ANOMALY_0_100"},
        "bases": [{"kind": "INITIAL_POLICY", "policy_version": "example-policy-v1"}],
        "reasons": ["WITHIN_REFERENCE"],
    }],
})
result.validate_for_request(request)
restored = BehaviorEvaluationResult.model_validate_json(result.model_dump_json())
restored.validate_for_request(request)
```

기존 소비자 호환용 `SiftScore`·`SiftFraudResult`·`FraudDecision`으로 이 결과를 자동 변환하지 않는다. 기존 최종 판정 계약의 교체는 통합 구현에서 수행한다.
