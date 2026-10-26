# 통합 실행의 최종 위험 정책 조립

`finalize_engine_execution(execution, budget=...)`는 행동·GPT 실행 기록을 기존
`evaluate_risk_policy`에 연결한다. 행동 점수나 GPT를 재실행하지 않으며 프로필도 갱신하지 않는다.
최종 반환 계약은 `NativeEvaluationResult`다.

```python
from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.engine import execute_engine
from copenhagen_fds.engine_finalization import finalize_engine_execution

budget = AnalysisBudget.start(request.execution.total_timeout_seconds)
execution = await execute_engine(request, adapter, budget=budget)
result = finalize_engine_execution(execution, budget=budget)
```

동일한 예산 객체를 전달해야 한다. 조립 함수는 전체 예산과 기존 소비 시간이 일치하는지 확인하지만,
JSON만으로 단조 시계 객체의 정체를 복원하지 않는다. 이미 지나간 시간을 초기화한 새 예산으로
후속 처리를 시작하는 용도가 아니다.

## 반환 필드와 원본 추적

| 필드 | 의미 |
| --- | --- |
| `outcome` | 호출자가 사용할 최종 상태·위험 수준·확인 권고·후속 확인 작업 |
| `execution` | 원래 통합 요청과 행동·추출·비교 전체 기록. 부분 결과와 최초/각 단계 실패 포함 |
| `policy_stage` | 정책 입력 구성·계산의 실제 실행 구간, 성공/실패 또는 생략 원인 |
| `policy_input` | 실제 정책에 연결한 원래 행동 입력/결과, GPT 정책 신호, 정책·판단 시각 |
| `policy_result` | 기존 정책이 계산한 분류와 NativeFraudDecision 전체 근거 |
| `completed_at`, `elapsed_seconds`, `termination` | 최종 완료 시각, 공유 소비 시간, 완료/예산 소진/취소 |

정책이 정상적으로 끝나고 종료 상태가 `COMPLETED`이면 `outcome`은 기존 정책 판단과 일치한다.
정책 함수가 반환한 `SUCCESS`는 계산 결과의 상태이고, 위험 수준은 별도다. 정상적으로 계산된
고위험 결과는 `SUCCESS / HIGH / REQUIRE_AUTH`가 될 수 있다.

`policy_result.decision`에서 전체 점수, 그룹/Feature 기여도, 충분도, 사유와 보완 규칙을 확인한다.
개별 Feature의 Cold/전환/개인화 단계·비중·실패·프로필 근거는
`execution.behavior.evaluation`에 원본 그대로 있다. 사용자·주문·의도·trace와 내용/버전 해시는
`execution.request`, `execution.reference`에, 실제 모델·프롬프트·요청 ID·사용량·확인 요청은
`execution.extraction`, `execution.comparison`에 있다. 최종 정책의 입력 해시는 이 근거와
정책 설정·실제 정책 판단 시각을 함께 연결한다.

## 기존 결정표와 실패 우선순위

- GPT `MATCH`가 높은 행동 점수나 보완 규칙의 위험 하한을 낮추지 않는다.
- 필수 의도의 `UNKNOWN`/`NOT_RUN`과 부족한 행동 점수는 `INCOMPLETE / UNKNOWN`이다.
- 행동 계산 또는 GPT 실패는 사용 가능한 점수가 일부 남아도 `FAILED / UNKNOWN`이다.
  Cold 복귀 성공으로 원래 개인화 계산 실패를 감추지 않는다.
- 선택 의도의 `UNKNOWN`/`NOT_RUN`은 기존 정책에 따라 실행 성공일 수 있지만 의도 확인 권고를 남긴다.
- 실패·미완성에는 확정 권고를 만들지 않는다. `review_actions`로 입력 확인·재평가 등의 후속 작업을 남긴다.

행동 결과나 두 GPT 단계 결과가 확보되지 않았으면 정책 입력·점수를 만들어 채우지 않는다.
정책을 생략하고 기존 부분 실행 기록을 보존한다. 추출 실패 뒤 비교 `NOT_RUN` 결과가 있는 경우는
두 단계의 기록이 확보된 상태이므로 최초 실패를 기존 정책에 전달할 수 있다.

GPT 신호를 만들 때 원래 BE 의도·주문은 유지한다. 두 GPT 단계의 실제 완료/만료 시각을 정책
판단 시점에서 검사하며, 오래된 추출을 최근 비교 시각으로 되살리지 않는다. 정책 입력을 거절하면
`POLICY / FAILED / INVALID_INPUT`을 기록하고 위험을 확정하지 않는다.

## 시간 초과와 취소

정책 입력 구성·계산·최종 계약 검증도 같은 예산을 사용한다. 입력 구성 중 예산이 소진되면
정책 계산을 시작하지 않는다. 계산/검증 중 소진되면 이미 얻은 정책 결과를 보존하되
`outcome`은 `FAILED / UNKNOWN`, 확정 권고는 `None`이 된다.

따라서 `policy_result`는 **보존한 계산 근거**다. 종료가 `BUDGET_EXHAUSTED` 또는 `CANCELLED`인
기록에서 그 안의 LOW/HIGH를 최종 판정으로 사용하지 않는다. 최종 판정은 항상 `outcome`을 읽는다.
동기 계산·검증은 중간에 강제로 중단하지 못한다. 완료 직후 초과를 기록하며 응답 지연 SLA를 보장하지 않는다.

조립 함수 자체는 동기 함수다. 취소된 실행 기록은 취소 상태로 조립하며, 비동기 호출자 취소의
전파는 통합 진입점이 담당한다. 조립 중 예외의 원문을 결과에 복사하지 않고 입력 오류와 내부
오류를 구분한다. 최종 기록 전체에는 구매 원문·이력이 있으므로 일반 로그에 출력하지 않는다.

JSON 복원과 `validate_for_request()`는 요청·근거·정책·시간·최종 판단의 연결을 검증한다.
행동/GPT/정책 평가 함수를 다시 호출하지 않는다. 이 검증은 후속 재사용 가능 여부나 실제 결제 권한을 부여하지 않는다.

## 직접 검증 — S15P21A207-291, 완료 기준 1·2·4

가상환경을 활성화하고 `ai/`에서 실행한다. 예상 결과는 25개 `PASSED`다.

```bash
python -m pytest tests/learning/personalization/replay/engine/analysis/finalization/test_policy.py -v --tb=short
```

정상·고위험·필수/선택 의도·자료 부족·개인화 계산 실패와 복합 실패를 기존 정책 결정표와 비교한다.
높은 행동 위험 + GPT MATCH는 HIGH를 유지해야 한다. 원본 Feature·기여도·버전 연결, JSON 왕복과
변조된 위험·권고·정책·시각의 거절도 확인한다. 이 결과는 합성 사례의 재현이며 실제 탐지 성능 측정이 아니다.
