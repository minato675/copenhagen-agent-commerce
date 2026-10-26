# 통합 실행 단계와 부분 결과

`EngineExecutionResult`는 행동 평가 → 의도 추출 → 주문 비교의 실행 기록이다.
최종 위험 정책을 적용하기 전의 내부 결과이며, 결제 승인이나 LOW/HIGH 판정으로 사용하지 않는다.
기존 최종 결과 계약인 `EngineEvaluationResult`와 구분한다.

## 기록 구조

| 필드 | 의미 |
| --- | --- |
| `request`, `reference` | 원래 통합 요청과 대상·버전·trace·내용 해시 |
| `started_at`, `completed_at` | 실행 전체의 실제 UTC 구간 |
| `reference.behavior_as_of` | 요청에서 고정한 행동 평가 기준 시각 |
| `budget_seconds`, `elapsed_seconds` | 공유 단조 시계 예산과 초과분을 포함한 소비 시간 |
| `termination` | `COMPLETED`, `BUDGET_EXHAUSTED`, `CANCELLED` |
| `stages` | `BEHAVIOR`, `EXTRACTION`, `COMPARISON` 순서의 실행 상태 |
| `behavior`, `extraction`, `comparison` | 이미 얻은 각 단계의 검증된 원본 결과, 없으면 `None` |

실행한 단계는 실제 시작·완료 시각을 가진다. 실패에는 고정된 `failure_code`를,
실행하지 않은 단계에는 `skip_reason`을 기록한다. `CANCELLED`는 실제 취소된 단계의
구간을 남기지만 API 실패 코드를 붙이지 않는다. 후속 단계에는 호출 시각·사용량을 만들지 않는다.

추출이 성공하고 비교 전에 예산이 소진되면 `extraction`은 그대로 남고 `comparison=None`이다.
비교의 단계 기록은 `NOT_RUN / BUDGET_EXHAUSTED`가 된다. 반면 추출 자체가 실패했고 시간은
남아 있다면 기존 분석기의 비교 `NOT_RUN` 결과도 보존한다. 최초 추출 실패를 비교 생략으로 덮지 않는다.

행동 계산 실패와 GPT 실패가 함께 발생하면 각각의 결과와 실패 코드를 모두 남긴다.
행동 결과의 Feature별 점수·결측·충분도·Cold 복귀·실패, GPT의 원문 출처·확인 요청·실패
분류와 제공자 측정값도 그대로 유지한다. 확보하지 못한 점수를 0으로 채우지 않는다.

## 실행 상태의 해석

| `status` | 조건 |
| --- | --- |
| `CANCELLED` | 호출자 취소. 확보된 결과가 있어도 취소를 전파한다. |
| `FAILED` | 전체 예산 소진 또는 어느 실행 단계든 실패 |
| `INCOMPLETE` | 위 조건 없이 미실행 단계 또는 행동 범위 부족 |
| `SUCCESS` | 위 조건 없이 모든 단계 실행 성공과 행동 범위 충족 |

`SUCCESS`는 실행 성공을 뜻한다. GPT가 `UNKNOWN` 또는 `MISMATCH`를 반환해도 실행은
성공할 수 있으며, 이상거래 여부는 별도 위험 정책으로 판단한다. 마지막 결과 검증 중 예산을
넘었으면 각 단계가 성공이어도 실행 전체는 `FAILED / BUDGET_EXHAUSTED`다.

`to_analysis_result()`는 추출·비교 결과가 모두 있을 때만 기존 `EngineAnalysisResult`를
만든다. 부분 추출 결과만 있으면 `ValueError`다. 변환은 원본 보존을 위한 것이므로 변환
가능 여부가 시간 예산 준수나 최종 정책 사용 가능성을 뜻하지 않는다. 후속 정책 연결에서는
실행 전체의 종료 상태와 근거 유효 시각을 함께 판단해야 한다.

JSON 복원은 동일 요청·대상·버전·원문·trace, 단계 순서와 시각, 실패 분류를 검증한다.
평가기나 GPT를 재실행하지 않으며, 중단 뒤 후속 실행을 주장하는 기록은 거절한다.
전체 결과에는 구매 원문·주문·이력이 포함되므로 일반 로그에 출력하지 않는다.

## 기존 분석기 재사용

`IntentAnalyzer.extract()`와 `compare_extraction()`은 기존 추출·비교를 단계별로 실행한다.
두 번째 메서드에는 첫 번째 결과와 동일 `AnalysisBudget`을 전달한다. 추출을 다시 실행하지 않는다.
기존 `analyze()`도 같은 메서드를 사용하며 기존 원문 변환·비교 생략·실패 규칙을 유지한다.
`EngineStageRecorder`는 이 결과를 기록하는 내부 보조 객체다. 시간 예산 제어는 포함하지 않는다.

## 직접 검증 — S15P21A207-290, 완료 기준 1

가상환경 활성화 후 `ai/`에서 실행한다. 외부 네트워크와 실제 키는 사용하지 않는다.

```bash
python -m pytest tests/learning/personalization/replay/engine/analysis/execution/test_stages.py -v --tb=short
```

예상: 20개 `PASSED`. 동일 요청 연결, 순서·시각, 부분 추출 보존, 복합 실패, JSON 왕복과
변조된 상태·대상·시간의 거절을 확인한다. 이 테스트는 단계 실행 기록의 계약을 검증한다.
