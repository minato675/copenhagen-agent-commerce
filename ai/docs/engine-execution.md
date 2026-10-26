# 행동·GPT 통합 실행기

`execute_engine(request, adapter, budget=...)`는 제공된 이력과 게시 프로필로 행동을 평가한 뒤
기존 GPT 추출·비교를 순서대로 실행한다. 각 단계는 한 번만 실행하고 프로필은 갱신하지 않는다.
반환값은 [정책 적용 전 실행 기록](engine-execution-stages.md)이다. 최종 위험 분류와 BE 응답
조립은 이 함수의 범위 밖이다.

## 호출과 취소

```python
from copenhagen_fds.analysis import OpenAIAnalysisAdapter
from copenhagen_fds.engine import EngineExecutionCancelled, execute_engine

# request: EngineEvaluationRequest
# settings: 요청의 gateway·모델·제공자/전체 제한 시간과 일치하는 OpenAISettings
# offline_sdk: MOCK에만 주입하는 오프라인 SDK. LIVE에서는 None.
async with OpenAIAnalysisAdapter(settings, client=offline_sdk) as adapter:
    try:
        execution = await execute_engine(request, adapter)
    except EngineExecutionCancelled as error:
        partial = error.result  # 필요한 호출자가 명시적으로 보관. 일반 로그에 출력하지 않는다.
        raise                  # 취소를 성공 응답으로 바꾸지 않는다.
```

키·환경 파일은 호출자가 기존 설정 로더로 명시적으로 읽는다. 실행기는 환경 파일 탐색이나
어댑터 생성·종료를 대신하지 않는다. 어댑터 설정과 요청이 다르면 행동 계산 전에 거절한다.
손상된 입력, 미래의 행동 기준 시각, 요청보다 큰 공유 예산도 실행 전에 `ValueError`로 거절한다.
입력 오류의 안전한 표시 방식은 [통합 입력](engine-inputs.md)을 따른다.

## 시간 예산

입력·설정 사전 검증 후 `AnalysisBudget`을 한 번 생성하고 행동 계산부터 결과 검증까지
같은 예산을 소비한다. 이미 시작한 예산을 전달하면 원래 시작점과 객체를 유지한다.
이 경우 `elapsed_seconds`는 실행기 호출 전 소비한 시간까지 포함하며, UTC 실행 구간의
길이와 같다고 가정하지 않는다. 실행기가 새로 만든 예산에는 사전 입력 검증 시간은 포함되지 않는다.

GPT 단계의 비동기 실행과 기존 어댑터는 각각 `min(제공자 제한, 공유 잔여 시간)`으로
제한한다. 행동 계산·추출·비교·결과 검증 사이에 예산을 초기화하지 않는다. 단계 전환마다
잔여 시간을 확인하고, 프롬프트 구성 중 소진되더라도 실제 어댑터가 HTTP 호출 직전에 다시
확인한다. 재시도·모델 대체·Mock 대체를 추가하지 않는다.

행동 계산과 일부 입력/결과 검증은 동기 Python 코드다. 실행 중간에 강제로 중단하지 못하며,
완료 직후 초과를 기록하고 후속 API 호출을 차단한다. 늦게 얻은 유효 결과도 보존하지만
전체 실행을 성공으로 처리하지 않는다. 결과를 받은 뒤 호출자가 수행하는 JSON 직렬화·전송은
실행기의 예산 측정 밖이다. 강제 CPU 중단이나 엄격한 응답 지연 SLA를 보장하지 않는다.

호출자 취소는 단계 경계와 비동기 대기에서 전달된다. 진행 중인 비동기 작업을 취소하고
후속 단계를 생략하며, `asyncio.CancelledError`의 하위 클래스인 `EngineExecutionCancelled`로
부분 결과와 함께 전파한다. 별도 백그라운드 스레드를 만들지 않는다. 외부 제공자에 이미 전달된
요청의 서버 처리나 과금 취소까지 보장하지는 않는다. 예산 소진 뒤 취소가 도착하면 기존
예산 소진 기록을 유지하되 종료 원인은 `CANCELLED`로 전파한다.

## 실패와 자료 부족

- 원래 행동 결과의 계산 실패는 `CALCULATION_FAILED`로 남긴다. 시간 여유가 있으면 독립적인
  GPT 근거를 수집하고 두 경로의 실패를 함께 보존한다.
- 행동 입력 검증의 `ValueError`는 `INVALID_INPUT`으로 기록하고 GPT를 중단한다.
  예상하지 못한 행동 예외는 `INTERNAL_ERROR`로 기록하며 원문 오류를 결과에 복사하지 않는다.
- GPT는 기존 API 오류·시간 초과·거절·응답 불완전·스키마 오류 등 상세 결과를 보존한다.
  외부 비동기 제한이 먼저 만료되어 원본 결과를 받지 못했다면 단계에 `TIMEOUT`을 기록하고
  제공자 응답이나 측정값을 만들어 넣지 않는다.
- 추출 실패 후 비교 API는 호출하지 않는다. 비교 입력 변환 실패도 원래 추출과
  `INVALID_INPUT` 비교 결과를 보존한다.
- 이력 부족은 원래 충분도/결측으로, 구매 요청 텍스트 미수집은 추출·비교 `NOT_RUN`으로
  보존한다. 실행 상태와 실제 거래 위험 판정은 별개다.

## 직접 검증 — S15P21A207-290

가상환경을 활성화하고 `ai/`에서 아래 명령을 실행한다. PowerShell과 Git Bash 모두
`python -m pytest`를 사용한다. 테스트 파일을 `python 파일명.py`로 실행하지 않는다.

| 완료 기준 | 검증 파일 | 예상 |
| --- | --- | --- |
| 1. 동일 요청·기준 시각과 실제 단계 상태 | `test_stages.py` | 20개 PASSED |
| 2. 공유 예산·제공자 제한과 추가 호출 차단 | `test_budget.py` | 11개 PASSED |
| 3. 취소 전파·단일/복합 실패·기존 근거 보존 | `test_control.py` | 16개 PASSED |
| 4. 결정적 Mock 검증과 직접 실행 | 세 파일 전체 | 47개 PASSED |

```bash
python -m pytest tests/learning/personalization/replay/engine/analysis/execution/test_stages.py -v --tb=short
python -m pytest tests/learning/personalization/replay/engine/analysis/execution/test_budget.py -v --tb=short
python -m pytest tests/learning/personalization/replay/engine/analysis/execution/test_control.py -v --tb=short
```

전체를 한 번에 확인하려면 다음 명령만 실행해도 된다.

```bash
python -m pytest tests/learning/personalization/replay/engine/analysis/execution -v --tb=short
```

정상 실행은 행동 1회·추출 1회·비교 1회다. 공유 예산 6초 중 행동이 2초, 추출이 추가로
3.75초를 소비한 합성 시계에서는 SDK 제한이 추출 4초·비교 0.25초가 된다. 소진 후 HTTP는
0회다. 취소 대기 작업의 정리, 이전 성공 근거와 최초 실패, 결과 검증 중 예산 초과도 확인한다.
시간 초과 테스트는 이벤트 루프의 만료 시점을 결정적으로 주입한다. 실제 지연시간을 측정하지 않는다.

기존 분석·통합 계약·위험 정책의 회귀 검증 명령:

```bash
python -m pytest tests/analysis tests/learning/personalization/replay/engine tests/learning/personalization/evaluation/risk -q --tb=short
```

모두 오프라인 합성 데이터와 전송 Mock으로 검증한다. 실제 GMS 가용성·판단 정확도·탐지 성능·SLA를
입증하는 테스트가 아니다. 실제 GMS 검증이 필요하면 별도 요청 후 수행한다.
