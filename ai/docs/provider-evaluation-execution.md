# 제공자 FDS 평가 실행

관련 Task: S15P21A207-301. [제공자 실행 기반](provider-runtime.md)과 [평가 계약](provider-evaluation-contract.md)을 사용한다.

## 내부 기록의 응답 변환

`project_evaluation_response(request, record)`는 신뢰된 AI 코드에서 이미 실행한 `NativeEvaluationResult`를 요청에 연결하고 `ProviderEvaluationResponse`로 변환한다. 별도 엔진·GPT 실행이나 프로필 갱신은 하지 않는다. 이 함수는 외부에서 계산 결과를 제출하는 API가 아니다.

정책 점수·기여도·사유, 피처별 단계·혼합 가중치·충분도, 각 실행 단계의 실패와 미실행, 요청/실제 모델 및 프로필 버전을 보존한다. 정책 계산에 실패하면 최종 점수와 그룹은 비어 있어도 이미 계산한 피처 근거는 남는다. 자료 부족 점수를 0으로 채우지 않는다.

`record_sha256`은 검증한 전체 내부 기록의 해시다. 원문·주문 상세·프로필 원장·GPT payload·telemetry는 요약 응답에서 제외한다. 원본 기록을 보관할 저장 계층이나 외부 전송 경로는 이 변환 함수가 제공하지 않는다.

만료 발급 계약이 없으므로 `result_valid_until=null`, `reuse_status=NOT_ASSESSED`, `storage_status=NOT_PERSISTED`를 유지한다. 재사용 검사는 기존 `check_engine_result_reuse`의 명시적 유효성 근거가 필요하며, 낮은 위험이나 재사용 가능 결과는 결제 승인이 아니다.

## 실제 엔진 연결

`ProviderRuntime`의 `handlers`에 `{"EVALUATE": evaluate_handler}`를 등록한다. 인증·범위·신뢰 설정 검사가 통과한 입력의 `evaluation`을 기존 `evaluate_fraud`에 그대로 전달한다. 현재 AI 계산용 DTO를 사용하는 연결이며, Core의 참조 중심 요청을 AI 저장소와 조합하는 계층이나 HTTP 서버를 새로 제공하지 않는다.

`ExecutionContext` 자체를 모델 어댑터로, `context.budget`을 공유 예산으로 넘겨 행동→GPT 추출→주문 비교→위험 정책을 한 번 실행한다. LIVE 연결에는 기존 `GmsResourceFactory`를 명시적으로 주입한다. 재시도·Mock 대체·프로필 생성/갱신·DB 조회/저장을 자동 수행하지 않는다.

신뢰된 내부 호출자가 전체 기록도 필요하면 자신의 handler 안에서 `evaluate_provider_request(request, context)`를 호출해 `EvaluationExecution.record`를 별도로 취급하고 `.response`만 Runtime에 반환한다. 기본 `evaluate_handler`는 요약만 반환하고 기록을 저장하지 않는다. 이 내부 함수는 인증된 Runtime 문맥을 전제로 하며 직접 공개 API에 노출하지 않는다.

요청의 `sent_at`이 실행 시작보다 미래이면 기존 응답의 시각 순서를 만족할 수 없어 GMS 호출 전에 `INVALID_CONTRACT`로 거절한다. 시계 오차 보정이나 허용 범위를 임의 적용하지 않는다. 전체 예산 만료는 Runtime의 `DEADLINE_EXCEEDED`, 취소는 `CancelledError`로 전달된다. 내부 엔진의 `NativeEvaluationCancelled.result`에 남은 부분 기록은 신뢰된 내부 코드만 취급하며 일반 오류 응답에 싣지 않는다.

기존 Runtime 초기화 코드에서 다음 handler를 등록한다. 인증·한도·예산·자원 설정은 [실행 기반 문서](provider-runtime.md)의 명시적 주입 방식을 유지한다. 다른 프로필 작업은 후속 Task에서 연결한다.

```python
from copenhagen_fds.provider_runtime.evaluation import evaluate_handler

operation_handlers = {"EVALUATE": evaluate_handler}
# ProviderRuntime(..., handlers=operation_handlers, resource_factory=...)에 전달
```

## 완료 기준별 직접 검증

아래 검증은 모두 **S15P21A207-301**에 해당한다. 기존 합성 이력으로 게시 프로필을 준비한 뒤, 평가 중에는 실제 FDS 계산과 합성 GPT 응답을 사용한다. 실제 GMS 호출이나 실서비스 탐지 성능 평가가 아니다.

Git Bash에서 저장소의 `ai/`로 이동하고 가상환경을 활성화한다.

```bash
source .venv/Scripts/activate
python -m pytest tests/provider/runtime/test_evaluation_projection.py tests/provider/runtime/evaluation -v --tb=short
```

전체 **35 passed**가 예상된다. 파일별로 확인하려면 아래 경로를 `python -m pytest <경로> -v --tb=short`에 넣는다.

| 완료 기준 | 검증 파일 (`tests/provider/runtime/` 기준) | 예상 결과 |
| --- | --- | --- |
| 원래 계산의 점수·위험·권고·근거·버전 보존 | `test_evaluation_projection.py`, `evaluation/test_execution.py` | 각각 9개. 기존 계약 예제와 일치하고 엔진 1회 호출, 입력/예산/기록 해시 연결 보존 |
| Cold·전환·개인화·만료 | `evaluation/test_stages.py` | 5개. 만료된 개인 프로필은 Cold, 초기 기준까지 만료되면 점수 없음/자료 부족 |
| GPT MATCH/MISMATCH/UNKNOWN·원문/이력 부족 | `evaluation/test_execution.py` | 위험·자료 부족·실패를 구분하고 실제 호출 횟수와 후속 미실행을 보존 |
| 단일/복합 장애와 시간 제한·취소 | `evaluation/test_failures.py`, `evaluation/test_interruption.py` | 각각 6개·5개. 최초 실패·남은 피처·후속 미실행 유지, 만료 후 추가 호출 금지, 취소 전파와 자원 정리 |
| 불변성·비노출·저장/갱신 없음·임의 TTL 없음 | projection, execution, stages 파일 | 원본 JSON 보존, 갱신 함수 미호출, 일반 응답의 원문/payload/telemetry 제외, 만료 `null`·재사용 미판정·미저장 유지 |
| 실행된 요청·응답 예제 | `evaluation/test_examples.py` | 1개. 정상·자료 부족·GPT 실패의 실제 실행 결과를 요청과 연결 |

직접 읽을 JSON은 다음 명령으로 만든다.

```bash
python -m pytest tests/provider/runtime/evaluation/test_examples.py --provider-evaluation-examples results/evaluation/provider-evaluation-301 -v --tb=short
```

`results/evaluation/provider-evaluation-301/`에 요청·응답 JSON 6개와 `index.json`이 생성된다. `normal-response.json`은 `SUCCESS`, `insufficient-response.json`은 `INCOMPLETE`/점수 `null`, `gpt-failed-response.json`은 `FAILED`이며 추출 실패와 비교 미실행을 표시한다. 합성 입력과 API 키가 없는 결과만 내보내며 Git 제외 경로를 사용한다. 실제 완료 시각과 실행 기록 해시는 실행마다 달라질 수 있다.

PowerShell에서는 같은 `ai/` 위치에서 `python` 대신 `.\.venv\Scripts\python.exe`를 사용한다. 자동 검증 결과는 사용자의 직접 검증이나 Jira/MR 체크박스 체크를 대신하지 않는다.
