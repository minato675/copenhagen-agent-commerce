# 통합 실행 결과와 원본 근거

`EngineEvaluationResult`는 [통합 요청](engine-inputs.md)에 대한 내부 실행 기록이다.
원본 입력·중간 결과·최종 정책을 연결해 JSON으로 복원할 수 있게 한다. 실제 실행기를
호출하는 함수나 BE 응답 DTO는 아니며, 계약의 통과가 실거래 탐지 성능을 뜻하지 않는다.

| 필드 | 보존하는 정보 |
| --- | --- |
| `request`, `reference` | 요청 스냅샷과 대상·시각·내용·버전 해시 |
| `timing` | 행동 기준, 전체 실행 시작/완료, 네 단계별 실행 상태와 시각 |
| `behavior_input`, `behavior` | 원래 개인화 점수 입력·평가 결과, Feature·충분도·게시본·Cold 대체·계산 실패 |
| `analysis` | 원본 `IntentAnalysisRun`의 추출·비교 결과, 출처·trace·모델·프롬프트·실패 |
| `policy_input`, `policy_result` | 정책에 연결한 원래 근거와 기존 `RiskEvaluationResult` 전체 |
| `status` | 통합 실행의 `SUCCESS`, `INCOMPLETE`, `FAILED` |

아직 만들지 못한 입력이나 결과는 해당 필드를 명시적인 `null`로 남긴다.
추출 실패 때문에 비교를 실행하지 않았다면 원래 추출 실패를 정책 입력에도 보존한다.
비교의 `NOT_RUN`으로 추출 실패를 덮어쓰면 거절한다. 응답의 실제 모델 이름이 요청한
모델 별칭과 달라도 보존하되, 제공된 `telemetry.requested_model`은 요청 설정과 같아야 한다.
게이트웨이와 프롬프트는 고정된 실행 설정에 맞아야 한다.

## 단계와 상태

`timing.stages`는 `BEHAVIOR`, `EXTRACTION`, `COMPARISON`, `POLICY` 순서로 각각 한 번 기록한다.

| 단계 상태 | 필수 기록 |
| --- | --- |
| `SUCCESS` | 시작·완료 시각과 해당 단계의 결과 |
| `FAILED` | 시작·완료 시각, 허용된 실패 코드, 생성할 수 있었던 원래 입력·결과 |
| `NOT_RUN` | 미실행 사유; 실행 시각·실패 코드는 없음 |

모든 실행 구간은 전체 실행 구간 안에 있어야 한다. 비교는 성공한 추출이 끝난 뒤,
정책은 실행된 근거 단계들이 끝난 뒤 시작한다. GPT 완료 메타데이터는 해당 단계의
실행 구간 안에 있어야 한다. 정책 입력의 유효성 검사 시각은 기록한 `POLICY.started_at`이다.

정책 계산 자체는 성공해도 근거가 부족하면 최종 판단은 `INCOMPLETE`, 근거에 실패가 있으면
`FAILED`다. 이 경우 `POLICY` 단계는 `SUCCESS`이고 통합 `status`는 최종 판단을 따른다.
어느 단계든 실행 실패가 있으면 통합 상태는 `FAILED`이며 성공한 최종 판단으로 숨길 수 없다.
정책 결과 없이 중단되고 실패도 없으면 `INCOMPLETE`다. 이력 부족은 계산 실행 실패와 구분한다.

예를 들어 GPT 시간 초과로 추출이 실패하면 추출 `FAILED`, 비교 `NOT_RUN`과 원래 `TIMEOUT`
근거를 보존한다. 정책 함수가 실패 상태를 포함한 최종 판단을 조립할 수는 있어도 정상 판단으로
바꾸지는 않는다. 정책 단계 자체가 시간 초과된 경우에는 이미 완료된 행동·GPT 결과를 보존하고
`policy_result=null`을 남길 수 있다.

## 검증과 복원

```python
from copenhagen_fds.contracts.engine_results import EngineEvaluationResult

# saved_json은 이 계약으로 저장한 내부 기록이다.
record = EngineEvaluationResult.model_validate_json(saved_json)
record.validate_for_request(original_request)
```

검증은 원시 Feature 변환과 제공한 Cold 기준 선택을 대조한다. 기록된 행동 결과는 원래
입력·정책·게시 상태와 대조하며 개인 점수 계산기를 다시 실행하지 않는다. 일시적인 계산
실패도 재시도 후의 성공으로 바뀌지 않는다. GMS 호출, 데이터 수집, 학습, DB 쓰기는 없다.

이 기록은 원문과 이력을 포함하는 내부 자료다. `repr`와 검증 오류 문자열은 불필요한 원문을
숨기지만, 전체 JSON을 공용 로그나 외부 응답으로 그대로 전달하지 않는다. 정책의
`decision.evaluated_at`은 계속 행동 기준이며 정책 시각은 `input_reference.policy_evaluated_at`에,
실행 완료 시각은 `timing.completed_at`에 따로 남는다. 새 시점의 결과 재사용 판단은 별도 경계다.

## 직접 검증 — S15P21A207-287, 완료 기준 2·3·4

가상환경을 활성화한 `ai/`에서 실행한다.

```bash
python -m pytest tests/learning/personalization/replay/engine/results -v --tb=short
```

정상·자료 부족·GPT 불확실·실패·미실행의 왕복, 단계 시각/상태 모순, 원본 변경 거절,
일시적인 행동 계산 실패 보존이 모두 `PASSED`여야 한다. GPT는 명시적인 합성 응답이고
행동 점수와 최종 정책은 기존 실제 계산기를 사용한다. GMS 키나 실제 API 호출은 필요 없다.

입력과 결과의 신규 계약을 함께 검증하려면 다음 명령을 사용한다.

```bash
python -m pytest tests/learning/personalization/replay/engine tests/learning/personalization/evaluation/risk/test_engine_time.py -v --tb=short
```

완료 기준 4의 기존 입력·GPT·개인화·위험 정책 회귀는 전체 테스트로 확인한다.

```bash
python -m pytest -q --tb=short
```

자동 테스트 결과와 사용자의 직접 검증은 별개이며 Jira·Draft MR의 체크박스는 사용자가 체크한다.
