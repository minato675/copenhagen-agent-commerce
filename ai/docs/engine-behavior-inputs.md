# 통합 요청에서 행동 평가 입력 구성

`build_engine_behavior_input(request)`는 [통합 요청](engine-inputs.md)에 담긴 관측 이력과
게시 프로필을 기존 `PersonalizedScoringInput`으로 연결한다. 점수 계산 전에 입력과
기준 선택을 따로 점검할 때 사용할 수 있다.

```python
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.engine_behavior_inputs import build_engine_behavior_input

request = EngineEvaluationRequest.model_validate(payload)
supplied = build_engine_behavior_input(request)
# supplied.cold.raw_features: 제공 이력에서 변환한 원시 Feature
# supplied.cold.selection: 선택한 기준과 선택 불가 사유
# supplied.population_baseline: 게시된 집단 통계에서 유도한 기준과 변환 진단
```

함수는 입력을 재검증한 뒤 제공된 집단 게시본의 기준 변환, Feature 변환, Cold 기준 선택을
실행한다. 개인·집단 프로필과 개인화 설정은 그대로 연결한다. 개인/집단 통계를 다시 학습하거나
게시 상태를 갱신하지 않으며, 서비스 DB·합성 데이터 로더·파일·환경 변수·외부 API를 읽지 않는다.

집단 통계의 참조와 Cold 점수용 기준의 참조는 서로 다르다. 원본 게시본은 `population`에,
원본에서 유도한 기준과 출처 해시는 `population_baseline`에 보존한다. Cold 입력은 유도한
기준의 참조를 사용한다. 일부 Feature의 분포만 변환 가능하면 가능한 기준을 사용하고 나머지는
기존 초기 정책 선택 규칙을 따른다. `ZERO_SCALE`, `EMPTY`, `CIRCULAR_NOT_REPRESENTABLE` 같은
변환 진단을 없애거나 임의의 양수 척도로 채우지 않는다.

## 결측·만료·오류의 차이

| 입력 상태 | 처리 |
| --- | --- |
| 개인 프로필 없음 | `NOT_SUPPLIED` 보존; 제공된 Cold 기준으로 평가 가능 |
| 개인 프로필 만료 | 게시본과 `EXPIRED` 보존; 기존 개인화 함수가 Cold 대체 여부 판단 |
| 이력 없음·미수집 | 원시 `NOT_COLLECTED`, 값 `null` 유지 |
| 수집 구간 부족 | `INSUFFICIENT_HISTORY`, 값 `null` 유지 |
| 해당하지 않는 스트림 | `NOT_APPLICABLE` 유지 |
| 완전히 수집한 구간에 거래 없음 | 실제 관측값 `0` |
| 초기 정책 없음 또는 만료 | 기존 기준 선택의 `MISSING`/`EXPIRED` 사유 보존 |
| 제공된 Cold 집단 기준이 행동 시점에 만료 | `EXPIRED`와 선택 불가 보존; 초기 정책으로 오류를 숨기지 않음 |
| 집단 기준을 만들 유효 표본 없음 | 변환 결과 `UNAVAILABLE` 보존; 기준이 없으므로 명시적인 초기 정책 선택 가능 |
| 집단 기준 유도 시각 자체가 게시본의 유효 기간 밖 | `ValueError`와 `INVALID_AVAILABILITY` 반환 |
| 잘못된 사용자·통화·게시 버전·미래 게시본 | 입력 검증 오류; 정상 점수나 임의의 Cold 결과로 대체하지 않음 |

현재 구매가 게시 정책 상태의 관측 목록에 있으면 거절한다. 아직 게시에 반영되지 않은 후보도
정책 상태에 영향을 줄 수 있으므로 검사한다. 게시 상태의 미래 확인 정보는 기존 프로필 계약이
거절한다. 원천 거래 이력에서는 기존 Feature 변환이 현재 구매와 아직 관측할 수 없는 이벤트를
빈도에서 제외하고, 판매처별 구매 복사본을 중복 집계하지 않는다. 과거에 실제 발생한 서로 다른
결제 시도는 별개의 빈도로 유지한다.

`validate_engine_behavior_input(request, supplied)`는 이 구성 결과와 기록된 입력을 대조한다.
통합 결과를 복원할 때도 같은 규칙을 사용한다. 원시 Feature와 기준 선택은 대조하지만
점수 계산기는 실행하지 않는다. 입력 오류 문자열에는 원문 대신 고정된 사유만 남기며,
구조화된 Pydantic 오류를 로깅할 때는 [입력 문서의 진단 방법](engine-inputs.md)을 따른다.

## 직접 검증 — S15P21A207-288, 완료 기준 1·2·3

가상환경을 활성화하고 `ai/`에서 실행한다.

```bash
python -m pytest tests/learning/personalization/replay/engine/behavior/test_preparation.py -v --tb=short
```

기존 단계별 입력 일치, 집단 기준 변환/만료, 이력 상태 구분, 중복·미래 이벤트 처리,
잘못된 게시본 및 현재 구매·미래 확인 정보 거절이 모두 `PASSED`여야 한다.
이 명령은 점수를 실행하기 전의 입력 구성과 검증 경계를 확인한다.
