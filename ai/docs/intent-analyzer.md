# 추출·비교 분석 진입점

`IntentAnalyzer`는 AI 내부에서 요청 텍스트 추출과 주문 의미 비교를 순서대로 실행합니다. 반환값 `IntentAnalysisRun`에는 `extraction`과 `comparison` 두 단계의 결과가 들어갑니다. Core·Pay API, Sift·Rule 위험 통합, 결제 정책은 포함하지 않습니다.

## 사용

```python
from copenhagen_fds.analysis import IntentAnalyzer, OpenAIAnalysisAdapter

async def analyze_purchase(settings, request, trace_id):
    async with OpenAIAnalysisAdapter(settings) as adapter:
        run = await IntentAnalyzer(adapter).analyze(request, trace_id=trace_id)
        common = run.to_common_result(request)
        return run, common
```

입력은 기존 `IntentAnalysisRequest`입니다. 출처·대상·버전·주문 스냅샷은 호출자가 제공한 검증된 값을 사용합니다. 추출기는 원문 요청 텍스트만 분석하고 비교기는 추출된 내용과 주문의 의미 비교용 필드만 받습니다. 원문 텍스트를 비교 단계에 다시 보내지 않습니다. 추출 결과를 새 입력 출처나 사용자 승인으로 승격하지 않습니다.

## 전체 시간 예산

`budget`을 생략하면 설정의 전체 평가 제한 시간으로 `AnalysisBudget`을 한 번 생성합니다. 호출자가 진행 중인 평가의 `budget`을 주면 그대로 사용합니다. 추출·변환·비교 사이에 예산을 초기화하지 않습니다.

각 GMS 요청에는 `min(제공자 제한 시간, 전체 잔여 시간)`을 사용합니다. 예를 들어 제공자 제한 5초, 공유 예산 6초에서 추출 이후 0.25초가 남으면 비교 요청의 제한은 0.25초입니다. 이는 동작 설명용 숫자이며 운영 권장값이 아닙니다.

재시도나 실패 시 대체 호출은 추가하지 않습니다. 정상 경로는 추출 1회와 비교 1회입니다. 호출자 취소는 전파하고 후속 단계를 시작하지 않습니다. 어댑터는 호출자가 닫으며, 설정 및 키를 읽는 방법은 [GMS 어댑터 문서](openai-adapter.md)를 따릅니다.

## 단계 연결 결과

| 상황 | 추출 | 비교 | 공통 결과 |
| --- | --- | --- | --- |
| 명확한 입력과 정상 비교 | SUCCESS | SUCCESS | MATCH/MISMATCH/UNKNOWN 중 모델의 유효한 의미 결과 |
| 요청 텍스트 미수집 | NOT_RUN | NOT_RUN | NOT_RUN, 추출 단계의 미수집 사유 |
| 추출 API·스키마 오류 또는 구매 내용 부족 | FAILED | NOT_RUN | FAILED, 추출 단계의 사유·메타데이터 |
| 추출 성공 후 비교 API 실패 | SUCCESS | FAILED | FAILED, 비교 단계의 사유·메타데이터 |
| 추출된 의도를 공통 비교 입력으로 변환할 수 없음 | SUCCESS | FAILED / INVALID_INPUT | FAILED, 로컬 입력 실패 |
| 추출 성공 후 비교 시작 전에 예산 소진 | SUCCESS | FAILED / TIMEOUT | FAILED, 로컬 시간 예산 실패 |

추출이 실패하면 비교를 호출하지 않습니다. 미실행 비교 결과에는 평가 시각·실제 모델 버전·호출 시간·사용량을 만들어 넣지 않습니다. 로컬 비교 입력 검사나 시간 검사에서 실패한 경우에는 그 검사 시각과 FDS 사유를 기록하되 실제 API 호출 측정값은 None입니다.

예를 들어 추출에 USD 예산이 있고 주문은 KRW라면 기존 `FraudEvaluationRequest`의 통화 일치 계약 때문에 비교 입력 변환이 실패합니다. 이 경우 원래 USD 의도는 `run.extraction`에 보존하고 비교는 `INVALID_INPUT`으로 처리합니다. 환산·금액 정책을 구현하거나 이 실패를 의미 MISMATCH로 바꾸지 않습니다. `INVALID_INPUT`의 공통 사유는 `EVALUATION_FAILED`이며 API·스키마 실패와 별도 세부 종류로 구분합니다.

모델이 추출한 모호한 항목과 FDS가 보완한 확인 사항을 비교 입력에 전달합니다. 이 전달을 위해 원본 `PurchaseIntent`의 ambiguities를 덮어쓰지 않습니다.

## 결과 보존과 재사용

`run.extraction`과 `run.comparison`에서 각각 모델·프롬프트 버전, 완료 시각, 호출 식별자, 측정 시간, 토큰 사용량, 실패 종류를 확인합니다. 단계별 값을 합산하거나 누락값을 추정하지 않습니다.

`run.to_common_result(request)`는 성공적으로 수행한 비교, 또는 분석을 막은 단계의 실패를 공통 `IntentAnalysisResult`로 보여줍니다. 두 단계의 세부 정보·확인 사항·관련 항목을 보존하려면 `run`도 함께 보관해야 합니다. 결과 전체를 로그로 출력하지 않습니다.

두 단계는 같은 평가 대상과 trace, 실행 경로를 공유해야 합니다. 원래 입력과 사용자·구매 요청·의도 버전·출처·주문 버전·해시가 다르면 `validate_for_request` 및 공통 결과 변환이 거부됩니다. 입력 내용이 바뀌면 BE가 해당 버전·해시를 갱신해야 하며, 같은 참조를 유지한 채 내용을 바꾼 경우를 이 모듈이 해시 재계산으로 검출하지는 않습니다. 결과 캐시나 다른 요청의 추출 결과를 재사용하는 경로는 제공하지 않습니다.

## 검증

```powershell
# ai 디렉터리에서 실행
.\.venv\Scripts\python.exe -m pytest tests/analysis/comparison/test_analyzer.py -q
```

타입이 검증된 합성 응답과 실제 SDK + MockTransport로 두 단계의 스키마·메시지·상태 전달·잔여 시간 제한을 검증합니다. 실제 소켓과 환경 파일을 사용하지 않습니다. 비교 단계의 실제 GMS 지원·추론 정확도와 전체 모델 품질 검증은 별도 범위입니다.
