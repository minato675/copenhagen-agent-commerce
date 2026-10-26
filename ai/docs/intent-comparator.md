# 주문 의미 비교기 사용

## 프롬프트 v4의 판정 기준

`fds-intent-comparison-v4`는 추가 조건이 없는 상태와 필수 조건의 근거가 부족한 상태를 구분합니다. 조건·목적·브랜드·카테고리의 null만으로 UNKNOWN을 생성하지 않으며, 실제 요구된 조건을 확인할 근거가 부족할 때만 보류합니다. 지정 브랜드 위반·미요청 상품 등 독립적인 확정 불일치가 우선하며 실제 모호함·대체 확인은 보존합니다.

숫자·출처·사용자 식별자를 제외하는 입력 투영과 엄격한 응답 검증은 유지합니다. 제외된 숫자를 다시 요구하거나 수량·예산 정책 판단을 생성하지 않습니다. 입력에 남아 있는 확인 사항을 무시한 MATCH는 계속 실패 처리합니다.

v3는 숫자 필드 제외와 실제 수량·통화 확인 사항 보존을 구분합니다. 필수 속성을 배제하는 설명은 위반 근거로 취급하고, 요청 자체의 충돌에는 빈 상품 참조를 사용합니다. 상품명·설명 속 공격 지시가 분리 가능하면 지시를 제외한 상품 사실을 비교하며 공격 문구만으로 의미 불일치를 만들지 않습니다. 분리할 수 없는 내용의 불확실성은 유지합니다. 별도 수량 표현이 있어도 모든 주문의 미요청 상품 검사는 계속 수행합니다.

v4는 같은 상품군의 모델명 차이만 있는 대체 후보와 명시적 대체 금지를 구분합니다. 다른 필수 조건 위반이 없는 대체 후보는 UNKNOWN과 CONFIRM_SUBSTITUTION으로 확인합니다. 동일 모델만 허용한다고 명시했거나 브랜드·필수 속성이 위반된 경우는 MISMATCH이며, 다른 상품군이 추가된 경우도 미요청 상품 비교를 유지합니다. 두 경우의 예시가 같은 상품·참조를 사용해 조건 차이를 드러냅니다.

정상·브랜드 위반·속성 누락/위반·다중 판매처·통화 누락·조건 충돌·공격 지시가 섞인 상품명을 대조하는 입력·출력 예시를 developer 메시지에 포함합니다. 예시의 출력 계약과 상품 참조는 오프라인 테스트로 확인합니다. 이 테스트는 모델 정확도 검증이 아니며 실제 효과·지연·토큰 변화는 GMS 평가로 측정합니다. 예시를 통한 프롬프트 조정은 [OpenAI 프롬프트 지침](https://developers.openai.com/api/docs/guides/prompt-engineering#few-shot-learning)을 참고합니다.

`IntentComparator`는 검증된 `FraudEvaluationRequest`의 구매 의도와 주문을 비교하여 `IntentComparisonResult`를 반환합니다. 실행 상태와 의미 판단을 따로 유지하며, 상품·브랜드·카테고리·목적·명시 조건·대체 가능성을 분석합니다.

## 호출

```python
from copenhagen_fds.analysis import IntentComparator, OpenAIAnalysisAdapter

async def compare_order(settings, request, trace_id):
    async with OpenAIAnalysisAdapter(settings) as adapter:
        result = await IntentComparator(adapter).compare(
            request, trace_id=trace_id, budget=adapter.new_budget(),
        )
        common = result.to_common_result(request)
        return result, common
```

호출자가 어댑터와 시간 예산의 수명을 관리합니다. 이미 추출 단계에서 사용한 예산이 있으면 같은 객체를 전달합니다. 비교기는 예산을 새로 만들거나 재시도하지 않습니다. 클라이언트 구성은 [GMS 어댑터 문서](openai-adapter.md)를 따릅니다.

## 프롬프트와 입력 범위

프롬프트 버전은 `fds-intent-comparison-v4`, 응답 스키마 이름은 `fds_intent_comparison_v1`입니다. 기존 Pydantic 출력 계약으로 스키마를 생성합니다.

고정 developer 지침과 JSON user 데이터로 메시지를 분리합니다. [OpenAI의 비신뢰 입력 지침](https://developers.openai.com/api/docs/guides/agent-builder-safety)에 따라 상품명·설명·조건·확인 사항·항목 ID의 지시를 developer 메시지에 삽입하지 않습니다. 모델에도 역할 변경·승인 주장·출력 강제를 따르지 않도록 명시합니다.

| 전송 데이터 | 내용 |
| --- | --- |
| `intent` | 상품명·브랜드·카테고리, 구매 목적, 명시 조건, 참고 선호, 모호한 항목 |
| `orders` | 각 주문 ID, 주문별 항목 ID, 상품명·브랜드·카테고리·설명 |
| `clarifications` | 호출자가 전달한 확인 사항. 기본값은 빈 튜플 |

사용자·평가·구매 요청 ID, 승인 참조, trace ID, 출처, 의도 버전, 주문 버전·해시는 모델에 보내지 않고 로컬 결과에 연결합니다. 상품·판매처 ID의 결정적 검증, 금액·수량·통화·총액 정책 검증은 이 의미 비교의 범위가 아닙니다. 수치 계산용 필드와 상품·판매처 ID를 별도로 전송하지 않습니다. 텍스트에 포함된 숫자도 정책 판단에 사용하지 않도록 지시합니다.

원본 의도와 주문은 변경하지 않습니다. 미지정 값은 그대로 유지하며 상품 속성을 조회하거나 카탈로그 지식으로 보완하지 않습니다.

## 판단과 실패 구분

| 결과 | 의미 |
| --- | --- |
| `SUCCESS / MATCH` | 제공된 범위에서 의미 조건이 일치하고 미해결 사유가 없음 |
| `SUCCESS / MISMATCH` | 명시 조건 이탈·미요청 상품 등 확정 불일치 근거가 있음 |
| `SUCCESS / UNKNOWN` | 확정 불일치 근거는 없지만 정보 부족·불확실성·대체 확인이 필요함 |
| `FAILED` | API 오류·시간 초과·거부·불완전 응답·스키마 또는 참조 검증 실패. 의미 판단은 None |

참고 선호만 다른 것을 필수 조건 위반으로 승격하지 않습니다. 대체 가능하다는 추론은 사용자의 대체 승인으로 취급하지 않습니다. 확인이 필요한 대체는 `CONFIRM_SUBSTITUTION`, 누락 정보는 `PROVIDE_MISSING_DATA`, 의도 모호함은 `CLARIFY_INTENT`, 충돌은 `RESOLVE_CONFLICT`로 표현하도록 지시합니다.

모든 주문을 비교하며 같은 `item_id`라도 `order_id`가 다르면 다른 항목입니다. 관련 항목은 실제 입력에 있는 `(order_id, item_id)` 쌍이어야 합니다. 전체 요청이나 주문에 없는 요청 상품에 관한 사유에는 빈 참조 배열을 쓸 수 있습니다. 없는 항목 참조·중복 참조·모델이 추가한 평가 대상·승인 필드는 거부합니다.

명시된 모호함·추출 확인 사항·상품 미지정이 있는데 모델이 MATCH를 반환하거나 QUANTITY/BUDGET 차원에서 결정적 불일치를 반환하면 `SCHEMA_ERROR`로 처리합니다. 출력값을 임의의 UNKNOWN으로 고쳐 성공 처리하지 않습니다. 이는 일부 계약 위반 검사이며 모델의 모든 의미 오류를 검출하는 장치는 아닙니다.

## 공통 결과와 측정 정보

`to_common_result(request)`는 대상·사용자·구매 요청·의도 버전·주문 참조가 원래 입력과 같은지 검증한 뒤 `IntentAnalysisResult`로 변환합니다. 변환한 결과에는 의미 판단과 공통 사유가 들어갑니다. 차원·관련 항목·확인 요청·세부 실패·토큰 정보는 원래 `IntentComparisonResult`를 함께 보존해야 확인할 수 있습니다.

제공자 측정 시간, 요청·응답 식별자, 사용량, 응답 모델·프롬프트 버전은 어댑터 값을 유지합니다. 누락한 사용량을 추정하지 않습니다. 참조·범위 검증 실패에는 FDS 출처, 제공자 실패와 비교 사유에는 실제 실행 경로의 출처를 붙입니다. 호출 전 예산이 소진되면 GMS에 연결하지 않고 로컬 TIMEOUT을 반환하며 호출 측정값은 None입니다.

MATCH는 의미 비교 결과입니다. 금융 Fraud 확률·위험 등급·최종 결제 권고·사용자 승인·결제 실행 결과로 사용하지 않습니다. BE의 입력 버전·해시 관리나 인증 진위를 이 모듈이 검증하지 않습니다.

## 검증

```powershell
# ai 디렉터리에서 실행
.\.venv\Scripts\python.exe -m pytest tests/analysis/comparison/test_comparator.py -q
```

합성 Mock 결과로 매핑·참조·실행 경계와 메시지 구성을 검증합니다. [Structured Outputs 문서](https://developers.openai.com/api/docs/guides/structured-outputs)에서도 스키마 준수와 의미 정확성을 구분합니다. 테스트의 사례명은 실제 모델 추론 성능을 입증하지 않으며, 비교용 GMS 실제 호출과 공격 입력 방어 성능은 별도 검증 대상입니다.
