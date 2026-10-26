# 통합 요청의 행동 평가 실행

`evaluate_engine_behavior(request)`는 [행동 입력 구성](engine-behavior-inputs.md)과 기존
`evaluate_personalized_features`를 연결한다. 제공한 요청·이력·게시 프로필·설정만으로 실행하며,
합성 데이터 폴더나 서비스 DB에 의존하지 않는다.

```python
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.engine_behavior import EngineBehaviorResult
from copenhagen_fds.engine_behavior import evaluate_engine_behavior

request = EngineEvaluationRequest.model_validate(payload)
record = evaluate_engine_behavior(request)

features = record.evaluation.result.features
behavior = record.evaluation.result.behavior
restored = EngineBehaviorResult.model_validate_json(record.model_dump_json())
restored.validate_for_request(request)
assert restored == record
```

`payload`는 호출자가 제공한 통합 요청 스냅샷이다. 함수가 예제 데이터나 실제 원천을 자동으로
불러오지는 않는다. 결과에서 다음 정보를 확인할 수 있다.

| 경로 | 의미 |
| --- | --- |
| `request`, `reference` | 원본 통합 요청과 내용·버전·trace 참조 |
| `scoring_input` | 실제 점수 계산에 전달한 이력 변환값·선택 기준·게시본·충분도 설정 |
| `evaluation.components` | 선택된 개인 계산, 충분도와 단계 판단 근거 |
| `evaluation.scale_agreements` | 실제 사용한 Cold·개인 점수의 수식 및 방향 호환성 |
| `evaluation.result.features` | Feature별 Cold/개인/최종 점수, 단계, 적용 비중과 사유 |
| `evaluation.result.profiles` | 개인·집단 게시본 참조·정책 상태 및 가용성 |
| `evaluation.result.behavior` | 행동 실행 상태, 점수 제공 범위, Feature 결과와 평가 대상 |

점수 함수에는 요청의 `scoring` 설정을 그대로 전달한다. 이미 계산된 충분도를 다시 할인하거나
가중치를 재정규화하지 않는다. 실행 중 개인 점수 계산기는 한 번 호출하며, 결과 연결과 JSON
복원에서는 원본 입력과 기록된 근거를 검증한다. 복원 과정에서 점수를 다시 계산하지 않는다.

## 부족과 실패

행동 실행 상태와 점수 제공 범위는 별개다. 수집이 부족해 일부 점수를 만들 수 없어도 계산
실행 자체가 성공하면 `behavior.status=SUCCESS`, `coverage=PARTIAL` 또는 `UNAVAILABLE`이다.
이는 거래가 정상이라는 뜻이 아니다. 사용할 수 없는 Feature의 점수는 `null`로 남는다.

개인 또는 Cold의 일부 계산이 실패하면 기존 Feature 결과의 `FAILED`와 사유를 보존한다.
다른 정상 Feature는 그대로 유지하며, 개인 계산 실패 후 Cold 점수가 사용되어도 행동 실행
상태는 `FAILED`를 유지한다. 잘못된 입력·설정은 계산 전에 `ValueError`로 거절한다.
기존 계산기가 구조화하지 않는 예외는 호출자에게 전달한다. 실행기의 시간 초과·취소 처리는
별도 책임이며 이 함수가 정상 점수로 바꾸지 않는다.

평가 전후 제공된 프로필·게시 정책 상태를 변경하지 않는다. 미래 확인 정보를 게시본에
추가하거나 현재 구매를 이미 학습한 상태로 제공하면 입력 검증에서 거절한다. 통합 요청의
게이트웨이가 `GMS`여도 이 행동 함수는 GPT를 호출하지 않으며 키가 필요하지 않다.

## 통합 결과 연결

반환한 `scoring_input`과 `evaluation`은 `EngineEvaluationResult`의 `behavior_input`과
`behavior`에 각각 대응한다. 같은 `request`와 `reference`를 사용하고 실행기는 실제 단계
시각을 기록한다. GPT·최종 정책 실행 전이면 해당 결과는 `null`과 미실행 단계로 유지한다.
이 행동 함수는 최종 위험 등급·결제 권고를 만들거나 전체 통합 실행을 수행하지 않는다.

## 직접 검증 — S15P21A207-288

가상환경을 활성화하고 `ai/`에서 실행한다. 실제 GMS 호출은 없다.

```bash
# 완료 기준 1·2·3: 단계별 입력, 게시/시각 오류, 이력 상태와 자기 학습 방지
python -m pytest tests/learning/personalization/replay/engine/behavior/test_preparation.py -v --tb=short

# 완료 기준 1·2·4: 기존 점수 일치, 부족·실패 보존, 외부 의존 없는 실행과 불변성
python -m pytest tests/learning/personalization/replay/engine/behavior/test_execution.py -v --tb=short

# 기존 점수·프로필·위험 정책·통합 계약 회귀
python -m pytest tests/learning/personalization tests/learning/test_population_baselines.py tests/test_cold_scoring.py tests/test_cold_evaluation.py -q --tb=short
```

모든 테스트가 `PASSED`여야 한다. 고정 합성 사례의 금액 단계는 Cold → Cold → 전환 → 개인화 →
만료 후 Cold이며, 최종 점수는 기존 함수와 동일하게 0·0·50·100·0이다. 이는 해당 Fixture와
명시적인 정책의 계산 결과이며 실제 사기 탐지 성능을 뜻하지 않는다.

반복 실행 검증은 GMS 호출·소켓 연결·파일 읽기·합성 데이터 로더·프로필 생성/갱신을 금지한
상태에서 수행한다. 실행 전후 요청 전체와 게시 상태가 동일한지 함께 확인한다. 사용자 직접
검증은 자동 테스트와 별개이며 Jira와 Draft MR의 체크박스는 사용자가 체크한다.
