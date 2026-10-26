# FDS 평가 범위와 결측 판단

`evaluate_risk_coverage(RiskPolicyInput)`은 원래 요청 목록과 정책의 전체 9개 Feature를
대조하여 `RiskCoverageResult`를 반환한다. 점수 계산이나 외부 호출은 수행하지 않는다.

```python
from copenhagen_fds.risk_coverage import evaluate_risk_coverage
from copenhagen_fds.contracts.risk_coverage import RiskCoverageResult

coverage = evaluate_risk_coverage(supplied)
restored = RiskCoverageResult.model_validate_json(coverage.model_dump_json())
restored.validate_for_input(supplied)
```

## 판단 기준

| 조건 | evaluation_scope | score_coverage | can_aggregate |
| --- | --- | --- | --- |
| 전체 9개에 정상 0점을 포함한 유효 점수 존재 | FULL | COMPLETE | true |
| 필수 Feature 하나 누락, 다른 점수는 존재 | PARTIAL | PARTIAL | false |
| 선택 Feature만 누락 | FULL | PARTIAL | false |
| 점수가 하나도 없음 | UNAVAILABLE | UNAVAILABLE | false |

`evaluation_scope`는 필수 행동 신호의 가용성이고 `score_coverage`는 전체 정책에서 점수가
제공되는 범위다. 어느 것도 원천 스트림의 완전성이나 탐지 정확도를 보장하지 않는다.
필수 여부는 입력 정책에 명시한다. 선택 신호가 누락되어도 0으로 대체하거나 나머지 가중치를
늘리지 않는다. `can_aggregate=false`이면 완전한 종합 점수를 만들 수 없다.

이 함수는 합산 전 단계라 `behavior_score`는 항상 null이다. `can_aggregate=true`이면 다음
가중합 단계에서 계산할 수 있다는 뜻이며, 계산 성공이나 낮은 위험을 의미하지 않는다.
실패 후 유효 Cold 점수가 남아 있는 경우도 합산 가능할 수 있다. 실행 실패 판단은 별도로 보존한다.

## 항목별 근거

`features`는 원래 요청 개수와 관계없이 정책의 전체 9개 항목을 고정 순서로 반환한다.

- `source=RESULT`: 실제 결과의 `FeatureScoreResult`와 raw 값을 그대로 보존한다.
  정상 0점, 미수집, 이력 부족, 해당 없음, 기준 부재, 계산 실패는 원래 상태·사유로 구분한다.
- `source=NOT_REQUESTED`: 요청 목록에서 빠진 항목이다. raw는 null이고 결과는 NOT_RUN,
  점수는 null이다. 요청에서 빼는 것만으로 COMPLETE가 되지 않는다.
- `source=BEHAVIOR_NOT_RUN`: 요청과 raw 입력은 있지만 행동 평가를 아직 실행하지 않았다.
  raw 값이 존재해도 점수를 만들어 넣지 않는다.

`required_unavailable`, `optional_unavailable`, `unrequested_features`에 해당 ID를 남긴다.
기존 원시 입력의 수집 부족은 재해석하지 않고 원래 상태로 유지한다. 이 모듈은 누락된 원천
이벤트를 복원하거나 제공자가 실제로 수집했는지 검증하는 수집 시스템이 아니다.

결과는 정책 전체·정책 해시·원래 입력 참조를 보존한다. JSON을 별도로 복원했으면
`validate_for_input`으로 원래 요청·프로필/정책 버전·결과와 대조해야 한다. 이 검증은
점수 계산기를 재실행하지 않고 제공된 근거에서 가용성만 다시 도출한다.

## 직접 검증 — S15P21A207-272

가상환경을 활성화하고 `ai/`에서 실행한다.

```bash
python -m pytest tests/learning/personalization/evaluation/risk/test_coverage.py -v --tb=short
```

정상 0점·필수/선택 결측·해당 없음·기준 부재·일부만 요청·전체 미실행의 결정표와
JSON 왕복·대상/정책 연결·상태 조작 거절이 PASSED로 표시되어야 한다.
