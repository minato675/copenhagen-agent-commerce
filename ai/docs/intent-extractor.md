# 구매 의도 추출기 사용

## 프롬프트 v2의 해석 기준

`fds-intent-extraction-v2`는 모델명·모델 번호를 상품명에 보존하고 브랜드로 바꾸지 않습니다. `items[].brand`는 요청 상품의 지정 브랜드이며, 브랜드가 선호일 뿐이면 `brand=null`과 `preferences`로 표현합니다. 기존 응답 필드 구조는 유지합니다.

브랜드·카테고리·목적·추가 조건·선호·예산은 언급되지 않았다는 이유만으로 모호함을 생성하지 않습니다. 수량 누락, 언급된 예산의 통화/종류 불명확, 상품 미지정, 실제 충돌처럼 확인이 필요한 항목만 남깁니다. 숫자 수량 표현의 반복을 추가 의미 제약으로 만들지 않습니다. 근사 예산·통화 최소 단위·원문 격리 규칙은 유지합니다.

프롬프트 변경의 실제 정확도는 별도 GMS 평가로 확인해야 합니다. Mock 검증은 정보 보존과 계약 경계를 검증합니다.

`IntentExtractor`는 `IntentAnalysisRequest`에서 제공된 요청 텍스트를 분석하고 `IntentExtractionResult`를 반환합니다. SDK 연결·구조화 응답 검증·시간 제한은 기존 `OpenAIAnalysisAdapter`를 사용합니다. 주문과의 의미 비교, 위험 판단, 승인·결제 실행은 수행하지 않습니다.

## 호출과 시간 예산

```python
from copenhagen_fds.analysis import IntentExtractor, OpenAIAnalysisAdapter

# settings는 load_openai_settings로, request는 IntentAnalysisRequest로 검증합니다.
# 호출자가 가진 trace_id를 전달하며 추출기가 식별자나 승인 정보를 만들지 않습니다.
async def extract_purchase_intent(settings, request, trace_id):
    async with OpenAIAnalysisAdapter(settings) as adapter:
        shared_budget = adapter.new_budget()
        result = await IntentExtractor(adapter).extract(
            request, trace_id=trace_id, budget=shared_budget,
        )
        if result.status != "SUCCESS":
            return result, None
        intent = result.to_purchase_intent(request)
        return result, intent
```

후속 비교를 같은 평가에서 실행한다면 어댑터가 열린 범위에서 동일한 `shared_budget`을 전달합니다. 추출기가 시간 예산을 새로 생성하거나 초기화하지 않습니다. 어댑터가 닫힌 뒤 후속 호출을 수행하지 않습니다. MOCK 모드는 SDK 클라이언트를 명시적으로 주입하고 그 수명도 호출자가 관리합니다. 설정·키·클라이언트 구성은 [GMS 어댑터 문서](openai-adapter.md)를 따릅니다.

## 프롬프트와 입력 경계

버전은 `fds-intent-extraction-v2`이며 지침과 구성 함수는 `analysis/extraction_prompt.py`에 있습니다. 지침 의미를 바꿀 때는 프롬프트 버전과 합성 사례를 함께 검토합니다. 모델 출력은 기존 `fds_intent_extraction_v1` JSON Schema를 사용합니다.

developer 메시지에는 고정된 추출 지침만 넣고, user 메시지에는 `{"request_text": "..."}` 형태로 JSON 직렬화한 요청 텍스트만 넣습니다. 주문·상품 설명·금액·사용자 ID·출처·버전·추적 ID·키를 별도 문맥으로 보내지 않습니다. 주어진 요청 텍스트 안에 이미 있는 내용을 다른 서비스에서 보완하거나 조회하지 않습니다.

[OpenAI의 비신뢰 입력 지침](https://developers.openai.com/api/docs/guides/agent-builder-safety)에 따라 요청을 developer 메시지에 삽입하지 않습니다. 요청 안의 역할 선언·규칙 무시·승인 주장은 분석 데이터로 취급하고 이를 따르거나 구매 조건으로 복제하지 않도록 지시합니다. 이 구성과 테스트는 메시지 경계를 검증하며 실제 모델의 Prompt Injection 방어 성능을 보장하지 않습니다.

## 추출·미지정 값 처리

프롬프트는 다음 구분을 요구합니다. 추출기는 유효한 출력값을 임의로 보정하지 않고 공통 `PurchaseIntent`에 연결합니다.

| 요청 표현 | 출력 기준 |
| --- | --- |
| 명시된 상품·브랜드·수량·구매 목적 | 요청에서 확인되는 정보만 추출 |
| 수량 미지정 | quantity=null, 기본 수량 1 금지 |
| 최대·이하 등 분명한 상한 | budget.kind=maximum |
| 대략·정도·전후 예산 | budget.kind=approximate |
| 통화나 예산 종류가 모호함 | budget=null, 언급된 정보와 확인 필요를 ambiguities에 보존 |
| 필수 조건 / 참고 선호 | explicit_conditions / preferences로 분리 |
| 상충하는 조건 | 충돌하는 내용을 보존하고 확인 필요를 기록 |
| 단일 수량·예산으로 확정할 수 없는 충돌 | 해당 값은 null, 충돌을 임의 해소하지 않음 |

예산 금액은 기존 Money 계약의 ISO 4217 최소 단위 정수입니다. 주문 통화·언어·지역을 근거로 누락된 통화를 채우지 않으며 환산·반올림하지 않습니다. 예산 미지정을 0, 무제한 또는 주문 총액으로 바꾸지 않습니다.

이 표는 프롬프트가 모델에 요구하는 해석 기준입니다. Mock 응답을 사용하는 단위 테스트만으로 모델이 실제 문장에서 이 기준을 정확히 수행한다고 판단하지 않습니다.

## 실행 상태와 확인 사항

| 상황 | 결과 |
| --- | --- |
| 요청 텍스트 미수집(None) | SDK 호출 없이 NOT_RUN, NOT_EXECUTED·INSUFFICIENT_DATA 사유 |
| 일부 구매 내용과 모호한 항목이 추출됨 | SUCCESS, payload와 clarifications, INTENT_UNCERTAIN 사유 |
| 유효한 응답이지만 구매 내용 필드가 모두 null 또는 빈 목록 | FAILED / INSUFFICIENT_INPUT, payload=None, 확인 사항 유지 |
| API·Timeout·거부·불완전 응답·스키마 실패 | 어댑터 실패·측정 정보를 그대로 연결 |
| 단계·프롬프트 버전·요청 모델·경로가 일치하지 않는 응답 | FAILED / SCHEMA_ERROR |

구매 내용 필드는 items, budget, purpose, explicit_conditions, preferences입니다. ambiguities만 있고 구매 내용이 없으면 빈 PurchaseIntent를 성공 결과로 만들지 않습니다. INSUFFICIENT_INPUT은 로컬 추출 실패의 세부 분류이며, 공통 실행 실패 사유는 EVALUATION_FAILED입니다. API 오류와 스키마 오류는 별도 종류로 유지합니다.

모델이 보고한 ambiguities는 `payload.ambiguities`에 유지하고 `clarifications`에도 연결합니다. 입력 부족으로 payload를 반환하지 않는 경우에도 clarifications는 유지합니다. 구매 목적만 있고 상품이 없는데 모델이 확인 사항을 주지 않으면 FDS가 상품 확인 필요를 추가합니다. 모델에서 온 불확실성 사유와 FDS가 추가한 사유는 각각 제공자 출처와 FDS 출처로 표시합니다. 확인 사항이 있다는 사실은 사용자 승인이나 결제 실행 권한이 아닙니다.

출처·대상 버전·스냅샷·trace ID는 호출자의 입력에서 연결합니다. 응답 모델·완료 시각·측정 정보는 어댑터가 제공한 값을 보존하며 누락값을 추정하지 않습니다. 요청 텍스트가 없으면 실제 모델 버전·평가 시각·호출 측정값을 넣지 않습니다. 미실행 telemetry의 경로와 요청 모델은 설정된 호출 대상을 뜻합니다.

## 공통 계약으로 변환

`result.to_purchase_intent(request)`는 주문을 참조해 누락 정보를 채우지 않고 구매 의도만 반환합니다. 전달받은 input_source와 대상 참조가 기존 입력과 일치하는지 확인하며 다른 출처·버전의 요청에 결과를 연결할 수 없습니다.

`result.to_evaluation_request(request)`는 현재 주문·컨텍스트까지 묶어 기존 `FraudEvaluationRequest`를 생성합니다. 예를 들어 요청에 USD가 명시되고 주문이 KRW인 경우, 추출된 PurchaseIntent의 USD는 보존하지만 공통 비교 입력 변환은 기존 통화 일치 검증에서 거부됩니다. 변환 실패를 처리하거나 통화 불일치를 해결하는 책임은 호출 계층에 있으며 이 추출기는 환산하지 않습니다.

두 변환은 SUCCESS에서만 사용할 수 있습니다. `clarifications`, 세부 실패·측정 정보가 필요한 호출자는 변환한 PurchaseIntent와 함께 원본 결과도 보존합니다. 원문을 담는 별도 결과 필드나 로깅은 추가하지 않았으며 모델에도 원문 전체·개인정보·사고 과정을 복제하지 않도록 지시합니다. 추출된 구매 정보 자체도 응용 계층에서 전체 로그로 출력하지 않습니다.

## 검증

```powershell
# ai 디렉터리에서 실행
.\.venv\Scripts\python.exe -m pytest tests/analysis/test_intent_extractor.py -q
```

합성 요청과 Mock 출력으로 값·출처·버전·측정값 보존, 프롬프트 경계, 미수집·입력 부족·오류 전달, 공유 시간 예산, SDK부터 공통 계약까지의 연결을 확인합니다. [Structured Outputs 공식 문서](https://developers.openai.com/api/docs/guides/structured-outputs)에서도 스키마 준수와 의미적 정확성을 구분합니다. 실제 GMS 인증·모델 접근·구조화 출력 지원 및 실제 추출 정확도는 별도 연동·품질 검증 대상입니다.
