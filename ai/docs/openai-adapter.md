# GMS 분석 어댑터 사용

## 검증 실패 진단

`SCHEMA_ERROR`의 `failure.diagnostics`에는 최대 8개의 고정 분류와 필드 경로가 포함됩니다. 기존 관측 파일처럼 이 필드가 없으면 빈 배열로 읽습니다. 공통 FDS 결과의 실패·사유 매핑은 유지하며 진단 정보는 분석 실행 결과에서 확인합니다.

| phase | 확인할 경계 |
|---|---|
| RESPONSE_JSON / RESPONSE_ENVELOPE | HTTP 본문의 JSON 및 Chat Completions 응답 구조 |
| USAGE | 제공자가 보고한 토큰 사용량의 타입·일관성 |
| PAYLOAD_JSON / PAYLOAD_SCHEMA | 모델 메시지의 JSON·필수 필드·타입·추가 필드 |
| COMPARISON_STATE | MATCH/MISMATCH/UNKNOWN과 사유 배열의 모순 |
| ITEM_REFERENCE | 중복 참조 또는 현재 주문에 없는 상품 참조 |
| SEMANTIC_SCOPE / UNRESOLVED_INPUT | 숫자 정책 판정 또는 미해결 입력의 MATCH 축약 |
| COMPLETION_CONTEXT | 다른 단계·모델·프롬프트·출처의 응답 연결 |

경로는 알려진 필드명과 제한된 배열 인덱스만 포함하며 알 수 없는 이름은 `*`로 치환합니다. 원문·값·Pydantic 메시지/입력/컨텍스트·인증 정보는 복사하지 않습니다. 단계·모델/프롬프트 버전·추적 정보는 기존 결과 메타데이터와 함께 확인합니다. 진단 정보를 얻기 위해 오류를 성공으로 바꾸거나 자동 재시도하지 않습니다. 과거 진단 없는 SCHEMA_ERROR의 원인을 소급 추정하지 않습니다.

`copenhagen_fds.analysis.OpenAIAnalysisAdapter`는 의도 추출·주문 비교에서 사용할 구조화 응답 호출 경계입니다. 입력은 `CompletionPrompt(stage, developer_message, user_message, version)`이며 출력은 `StructuredCompletion`입니다. 구매 의도를 해석하는 프롬프트, 주문 비교 정책, 결제 권고나 실행은 포함하지 않습니다.

구매 의도 추출은 이 어댑터를 사용하는 별도 [IntentExtractor](intent-extractor.md)가 제공합니다.

## SDK와 연결

직접 의존성은 OpenAI 3.10.0, HTTPX2 2.12.0으로 고정했습니다. [OpenAI Python SDK 공식 문서](https://developers.openai.com/api/reference/python)에 따라 비동기 클라이언트와 Chat Completions를 사용합니다. SDK의 기본 재시도는 끄고(`max_retries=0`) 호출마다 제한 시간을 전달합니다.

- 주소: `https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions`
- 인증: 설정의 `GMS_KEY`만 Bearer 헤더에 사용합니다.
- 요청: 설정된 모델, `developer`/`user` 메시지 2개, 단계별 `response_format=json_schema`, `stream=False`입니다. 결제·DB 도구, 사용자 ID 등의 추가 요청 인자는 제공하지 않습니다.
- 기본 모델은 `gpt-5.4-mini`입니다. 명시적으로 바꾼 값은 그대로 전달하며, 미지원 모델·스키마는 실패로 반환합니다. 직접 OpenAI 주소, 다른 모델, Responses API, JSON mode 또는 Mock으로 우회하지 않습니다.
- LIVE 클라이언트는 리디렉션과 환경변수 프록시를 사용하지 않습니다. `OPENAI_API_KEY`, 조직·프로젝트, `OPENAI_CUSTOM_HEADERS`의 인증·추가 헤더를 전송하지 않습니다.

[Chat Completions 명세](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)와 [모델 명세](https://developers.openai.com/api/docs/models/gpt-5.4-mini)는 OpenAI 측 사양입니다. GMS 인증·모델 접근·Structured Outputs 지원은 실제 게이트웨이 검증이 필요합니다. 현재 자동 테스트는 모두 Mock 전송이며 실제 GMS 호출 성공을 뜻하지 않습니다.

## 설정과 수명

설정은 [README의 OpenAI 단독 설정](../README.md#openai-단독-설정과-명시적-환경-파일)을 따릅니다. Sift 설정을 더미로 채울 필요가 없습니다.

LIVE는 `async with OpenAIAnalysisAdapter(settings)`로 사용하여 소유한 HTTP 클라이언트를 닫습니다. MOCK은 `OpenAIAnalysisAdapter(settings, client=mock_sdk)`처럼 SDK를 명시적으로 주입해야 합니다. 주입한 SDK의 수명과 오프라인 전송은 호출자가 관리하며, LIVE 모드에 SDK를 주입하면 거부합니다. 실서비스의 `settings.mode`를 MOCK으로 바꾼 뒤 실제 네트워크 클라이언트를 주입하는 용도로 사용하지 않습니다.

## 두 단계의 시간 예산

평가를 시작할 때 `adapter.new_budget()`을 **한 번** 생성하고 추출·비교 단계에 같은 객체를 전달합니다. `budget.remaining_seconds`는 단조 시계를 기준으로 두 단계 사이의 처리 시간도 포함합니다. 더 짧은 외부 제한이 있다면 `AnalysisBudget.start(remaining_seconds)`를 사용할 수 있으며, 설정된 전체 시간보다 큰 예산은 거부합니다.

```python
from copenhagen_fds.analysis import OpenAIAnalysisAdapter

# extract_prompt와 build_comparison_prompt는 별도 분석 계층에서 제공합니다.
async def analyze(settings, extract_prompt, build_comparison_prompt):
    async with OpenAIAnalysisAdapter(settings) as adapter:
        budget = adapter.new_budget()
        extracted = await adapter.complete(extract_prompt, budget=budget)
        if extracted.status == "FAILED":
            return extracted, None
        comparison_prompt = build_comparison_prompt(extracted.payload)
        compared = await adapter.complete(comparison_prompt, budget=budget)
        return extracted, compared
```

각 호출 제한은 `min(OpenAI 제한 시간, 공유 예산의 남은 시간)`입니다. SDK의 HTTP 동작 제한과 `asyncio.timeout`의 전체 호출 제한을 함께 적용합니다. 예산이 소진되면 SDK를 호출하지 않고 TIMEOUT을 반환합니다. 진행 중인 비동기 요청은 시간 초과 시 취소하고, 동기 응답 검증 중 기한이 지난 경우에도 성공을 반환하지 않습니다. 호출자가 취소한 `CancelledError`는 그대로 전파합니다.

재시도는 없습니다. `failure.retryable`은 오류의 재시도 가능성에 대한 힌트이며 자동 재호출이나 재시도 예산을 부여하지 않습니다.

## 응답과 실패

성공에는 단계별 검증된 `payload`, 실제 응답의 `response_model`, 호출자가 준 `prompt_version`, 완료 시각, 측정 정보가 있습니다. 의미 비교의 UNKNOWN은 성공한 분석의 불확실한 판단으로 유지합니다.

| 상황 | `failure.kind` | 세부 정보 |
| --- | --- | --- |
| HTTP 401 / 403 | API_ERROR | AUTHENTICATION / PERMISSION |
| HTTP 429 / 5xx | API_ERROR | RATE_LIMIT / SERVER |
| 다른 HTTP 오류·연결 오류 | API_ERROR | REQUEST / CONNECTION, 알 수 없는 SDK 오류는 UNKNOWN |
| HTTP 또는 전체 시간 초과 | TIMEOUT | 실패를 MATCH로 대체하지 않음 |
| `refusal` 또는 `content_filter` | REFUSAL | 거부 원문은 반환하지 않음 |
| choices 누락·여러 개, `length`, 미완료·빈 내용 | INCOMPLETE_RESPONSE | 부분 응답으로 성공 결과를 만들지 않음 |
| 잘못된 JSON·중복 키·스키마·사용량·모델 식별자 | SCHEMA_ERROR | 원본 오류와 입력값은 반환하지 않음 |

실패에는 분석 payload를 넣지 않습니다. HTTP 상태와 제공자 요청 ID는 제공된 경우 보존합니다. `telemetry`는 GMS/MOCK, 요청 모델, 응답 ID, `x-request-id`, 밀리초 처리 시간, 제공된 토큰 수를 담습니다. 요청 모델로 실제 응답 모델을 대체하거나 누락된 토큰 수를 0 또는 합계로 채우지 않습니다.

호출 계층이 공통 `IntentExtractionResult`/`IntentComparisonResult`로 연결할 때 `failure`, `telemetry`를 그대로 사용하고, 실제 모델·프롬프트 버전·완료 시각을 `EvaluationMetadata`에 넣습니다. 대상 ID·스냅샷·의도 버전·trace ID는 호출자가 보유한 입력에서 연결합니다. 실패 사유는 `failure.reason_code`, 성공 비교 사유는 payload의 findings를 사용합니다. 비교 항목 참조는 `validate_for_request()`로 해당 주문에 다시 대조해야 합니다.

어댑터는 요청·응답 원문이나 SDK 예외를 로깅하지 않습니다. SDK/HTTP 전송 로그는 해당 비동기 호출 문맥에서 억제하여 헤더·오류 본문이 로그에 섞이지 않게 합니다. 반환 payload는 구매 내용을 포함하므로 응용 계층에서도 결과 전체를 로그에 출력하지 않습니다.

## 오프라인 검증

```powershell
# ai 디렉터리에서 실행
.\.venv\Scripts\python.exe -m pytest tests/test_openai_settings.py tests/analysis/test_openai_adapter.py -q
```

실제 SDK에 HTTPX2 MockTransport를 연결해 요청 주소·인증·메시지·스키마·모델·시간 제한, 오류 분기, 공유 예산·취소, 클라이언트 수명과 로그 보호를 확인합니다. 외부 소켓 연결은 차단하며 Windows asyncio 자체의 내부 깨우기용 소켓 쌍만 차단 전에 생성합니다. 테스트는 개인 `.env`나 실제 키를 읽지 않습니다.
