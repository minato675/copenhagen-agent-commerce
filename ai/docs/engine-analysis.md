# 통합 요청의 GPT 분석 실행

`evaluate_engine_analysis(request, adapter, budget=...)`는 [통합 요청](engine-inputs.md)의
원문과 실제 주문을 기존 `IntentAnalyzer`에 전달한다. 추출·비교의 프롬프트나 추론 규칙을
새로 만들지 않고 두 단계의 상세 결과를 `EngineAnalysisResult`에 보존한다.

## 설정과 호출

```python
from copenhagen_fds.analysis import OpenAIAnalysisAdapter
from copenhagen_fds.engine_analysis import evaluate_engine_analysis

# request: 검증된 EngineEvaluationRequest
# settings: 호출자가 명시적으로 로드한 기존 OpenAISettings
# offline_sdk: MOCK에서만 제공하는 전송 Mock SDK. LIVE에서는 None.
async with OpenAIAnalysisAdapter(settings, client=offline_sdk) as adapter:
    record = await evaluate_engine_analysis(request, adapter, budget=shared_budget)
```

`request.execution`의 gateway, 요청 모델, 제공자 제한 시간과 전체 제한 시간은 어댑터의
설정과 정확히 일치해야 한다. GMS는 기존 설정의 `mode=LIVE`, Mock은 `mode=MOCK`에 대응한다.
지원하는 추출·비교 프롬프트 버전도 통합 입력에서 고정한다. 서로 다른 설정을 덮어써서
실행하지 않고 호출 전에 `ValueError`로 거절한다. 키는 기존 `OpenAISettings.gms_key`에만
있으며 통합 요청·참조·결과에 복사하지 않는다. 환경 파일은 이 함수가 읽지 않는다.

어댑터와 SDK의 소유·종료 방식은 [기존 GMS 어댑터](openai-adapter.md)를 따른다.
Mock에는 명시적인 오프라인 SDK가 필요하고, LIVE에는 주입 SDK를 사용할 수 없다.

공유 `AnalysisBudget`을 전달하면 같은 객체를 추출·비교에 전달한다. 생략하면 기존 분석기가
설정된 전체 분석 예산을 한 번 만든다. 통합 실행기에서는 이미 시작한 예산을 전달해야 하며
새로 시작하면 안 된다. 각 제공자 호출은 기존 어댑터가 제공자 제한과 잔여 시간 중 작은 값을
사용한다. 전체 엔진의 단계 배치·시간 초과·취소 결과 조립은 이 함수 밖의 책임이다.

## 원본과 실행 기록

- `request`, `reference`: 원래 통합 요청과 내용·대상·버전·trace 해시.
- `started_at`, `completed_at`: 분석 실행 전체의 실제 UTC 시각. 행동 기준 시각을 바꾸지 않는다.
- `analysis.extraction`, `analysis.comparison`: 원래 단계별 상태·실패·출처·모델/프롬프트·측정값·확인 근거.

추출기는 원문만 받고 비교기는 추출된 의도와 원래 주문의 의미 비교용 필드를 받는다.
추출 결과를 사용자 직접 발언이나 승인으로 승격하지 않는다. 원래 통합 요청도 수정하지 않는다.
사용자·구매·주문·의도·trace와 실행 설정이 맞는지, 완료 시각이 실행 구간과 단계 순서에
맞는지 검증한다. 원문 내용만 바뀌고 버전 이름이 그대로여도 기존 기록의 참조와 다르면 거절한다.

추출 실패 시 기존 분석기가 비교 호출을 중단한다. 요청 텍스트가 없으면 두 단계 모두
`NOT_RUN`이며 호출 시간·사용량·응답 모델을 만들어 넣지 않는다. 추출 성공 후 통화 등
비교 입력 변환이 실패하면 원래 추출 내용과 비교의 `INVALID_INPUT`을 보존한다.
호출자 취소는 전파하며 재시도나 다른 모델로의 대체 호출은 추가하지 않는다.

JSON 복원은 기록만 검증한다. GPT를 재호출하지 않는다. 전체 기록에는 원문·주문·이력이
포함되므로 일반 로그에 출력하지 않으며, 오류는 [통합 입력의 진단 방법](engine-inputs.md)을 따른다.

## 직접 검증 — S15P21A207-289, 완료 기준 2·3·4

가상환경 활성화 후 `ai/`에서 실행한다. 실제 GMS 키나 외부 네트워크는 필요하지 않다.

```bash
python -m pytest tests/learning/personalization/replay/engine/analysis/test_execution.py -v --tb=short
```

예상 결과는 전부 `PASSED`다. 정상 호출은 추출 1회·비교 1회, 추출 실패는 추출 1회,
텍스트 미수집은 0회다. 실제 SDK와 전송 Mock에서도 기존 GMS 주소·스키마·모델·측정값과
잔여 예산을 확인한다. 이 검증은 실제 GMS의 가용성이나 의미 판단 정확도를 확인하지 않는다.
