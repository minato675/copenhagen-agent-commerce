# AI 작업 공간

Payment MCP의 FDS 평가 모듈입니다. 구매 의도·주문·컨텍스트 입력 계약, 개별·통합 평가 결과 계약, 환경설정 로더, GMS Chat Completions 어댑터, 구매 의도 추출·주문 의미 비교와 오프라인 테스트를 제공합니다.

BE 인수인계용 평가·개인/집단 프로필 생성·갱신 호출은 [제공자 Mock 실행과 연동 명세](docs/provider-handoff.md)에서 확인합니다. 키/네트워크 없이 실제 제공자 경계로 27개 합성 사례를 실행하고 요청·응답·스키마를 내보내며 결과를 재현할 수 있습니다. 평가는 [FastAPI 단독 서버](docs/http-api.md)로도 호출할 수 있으며, 실제 BE 통합·서비스 인증 배포·AI 저장소 연결은 별도 작업입니다.

[시간순 FDS 평가](docs/temporal-benchmark.md)는 행동 이력과 명시적 합성 원문·주문서를 연결하고, 관측 데이터와 정답을 분리한 평가 입력을 고정합니다. 준비·검증 명령은 실제 모델이나 GMS를 호출하지 않습니다.

의미 비교는 [주문 비교기 사용 문서](docs/intent-comparator.md), 두 단계의 연결과 공유 시간 예산은 [추출·비교 진입점 문서](docs/intent-analyzer.md)를 참고합니다.

고정 합성 사례와 전체 경로 회귀는 [평가셋 문서](docs/evaluation-cases.md), 출처를 구분한 집계와 실행 명령은 [평가 보고서 문서](docs/evaluation-report.md)에 설명합니다.

개인·집단 프로필의 참조·출처·관측 범위와 평가 시점 검증은 [행동 프로필 계약](docs/behavior-profiles.md)을 참고합니다. 이 계약은 관측 메타데이터를 표현하며 실제 구매 분포 학습이나 통계값 계산은 수행하지 않습니다.

구매·결제 결과·후속 확인을 연결하는 [프로필 학습 입력과 상태](docs/learning-contracts.md), 정상 패턴 학습의 반영·보류·제외를 판단하는 [기본 적격 정책과 직접 검증](docs/learning-eligibility.md)을 제공합니다. 결제 성공과 정상 확인을 구분합니다.

[시간순 학습 표본 재생](docs/learning-replay.md)은 거래 당시 Feature와 학습 기준 시각의 적격 여부를 분리합니다. [고정 합성 이력 내보내기](docs/synthetic-learning.md)에서 장기 스트림과 도전 세트의 실행 명령과 예상 결과를 확인할 수 있습니다.

[개인 행동 통계 프로필](docs/personal-profiles.md)은 적격 표본의 금액·빈도·시간대·요일 분포를 최근/장기 범위별로 생성합니다. 수집 부족과 원시 관측값을 보존하며 같은 시점에서 JSON 복원 후 재계산할 수 있습니다. [집단 행동 통계 프로필](docs/population-profiles.md)은 명시한 집단·통화를 분리하고 거래별 또는 사용자별 가중 통계를 생성합니다. 갱신 실행은 후속 구현 범위입니다.

자체 FDS 입력과 Feature별 이상점수·결측·실패 결과는 [행동 평가 계약](docs/behavior-evaluation.md)에 설명합니다. 금액·빈도·시간대의 원시 값 계산과 BE 입력 요구사항은 [Feature 변환 문서](docs/feature-transform.md), 보유 CSV의 사용 범위와 재현 명령은 [데이터 적합성 점검](docs/dataset-suitability.md)을 참고합니다. 제공된 집단 기준·초기 정책의 출처·버전·시각 검증은 [기준 로딩과 선택](docs/baseline-selection.md)에 설명합니다. [Cold 점수와 데이터 충분도](docs/cold-scoring.md)는 선택된 기준으로 Feature별 이탈 점수를 계산하며, [점수 수식](docs/feature-scoring.md)과 합성 예제로 직접 검증할 수 있습니다. [Cold 단독 평가](docs/cold-evaluation.md)는 제공된 원천 이벤트부터 Feature 변환·기준 선택·점수 반환까지 연결합니다. 그룹 가중합과 GPT를 포함한 최종 위험 분류는 별도 정책 함수에서 제공합니다.

자체 FDS의 최종 정책 계약은 [입력·설정](docs/risk-policy-inputs.md)과 [결과·권고 및 직접 검증](docs/risk-policy-results.md)에 설명합니다. 기존 개인화 평가와 GPT 결과의 대상·시각·버전을 연결하고 점수·기여도·상태의 모순을 검증합니다. [최종 정책 함수](docs/risk-evaluation.md)에서 위험 분류와 확인 권고를 조립합니다. [기본 위험 정책](docs/default-risk-policy.md)은 최종 피처 100점에 MEDIUM 하한을 적용하며, 통합 요청에서 위험 정책을 생략하면 사용됩니다.

[통합 요청과 시각](docs/engine-inputs.md), [통합 실행 결과와 원본 근거](docs/engine-results.md)는 제공된 이력·게시본·의도 원문·정책을 한 실행에 연결하는 내부 계약입니다. 고정된 행동 기준과 GPT 완료·정책 판단 시각을 구분하고 단계 실패·자료 부족을 보존합니다.

[행동 입력 구성](docs/engine-behavior-inputs.md)과 [행동 평가 실행](docs/engine-behavior.md)은 통합 요청에서 기존 Cold·개인화 점수까지 연결합니다. 원래 Feature별 단계·비중·충분도·실패를 보존하고 평가 중 프로필을 갱신하지 않습니다. 이 행동 경로에는 GMS 키나 데이터 로더가 필요하지 않습니다.

[통합 GPT 분석 실행](docs/engine-analysis.md)과 [GPT 정책 연결](docs/engine-intent.md)은 기존 추출·비교를 통합 요청에 연결합니다. 두 단계의 원본 결과·확인 요청과 최초 실패를 보존하며, 원래 BE 의도·주문을 유지한 채 기존 위험 정책 입력을 구성합니다.

[통합 실행기](docs/engine-execution.md)는 행동·추출·비교를 한 예산으로 실행하고 [단계별 부분 결과](docs/engine-execution-stages.md)를 반환합니다. 취소·시간 초과·복합 실패와 이미 얻은 근거를 보존합니다.

[자체 FDS 통합 진입점](docs/native-evaluation.md)은 이 실행에 [기존 위험 정책과 최종 결과 조립](docs/engine-finalization.md)을 연결합니다. 같은 예산으로 최종 상태·위험 수준·확인 권고를 반환하고 점수·기여도·단계·버전·실패 근거를 유지합니다. 기존 Sift 계약은 보존하며 새 경로에서 요구하지 않습니다. BE 서비스 인터페이스와 통합 CLI는 별도 범위입니다.

[FDS 평가 범위·결측 판단](docs/risk-coverage.md)은 요청에서 빠진 항목까지 정책 전체 기준으로 확인합니다. [행동 평가 상태·실패 보존](docs/risk-assessment.md)은 기존 점수·단계·충분도를 유지하고 Cold 복귀 후에도 계산 실패를 보존합니다.

[그룹 대표값과 기여도](docs/risk-groups.md)는 중복 가산을 제한하고, [그룹 가중합·결과 재현](docs/risk-aggregation.md)은 설정 비중으로 종합 행동 점수를 계산합니다. 전체 점수 제공 범위와 기존 실패를 보존하며 최종 위험 등급은 별도 분류 정책에서 결정합니다.

[강한 신호·동시 이상 조건](docs/risk-rule-checks.md)과 [규칙 우선순위·보완 근거](docs/risk-rules.md)는 원래 가중합을 보존하면서 관측된 규칙의 위험 하한을 별도로 제공합니다. 결측·실패를 확정 위험으로 바꾸지 않으며, 최종 위험 등급과 확인 권고는 최종 정책 함수에서 조립합니다.

[GPT 의미·실행 상태 정책 신호](docs/risk-intent.md)는 MATCH·MISMATCH·UNKNOWN과 실패·미실행을 구분하고 원본 상세 근거를 보존합니다. 만료와 잘못된 주문 연결은 입력 검증에서 거절합니다.

[GPT 확인·재평가 권고 정책](docs/risk-intent-policy.md)은 필수·선택 신호와 실패 종류에 따른 확인 순서를 제공합니다. 행동 점수·보완 규칙·계산 실패는 그대로 보존하며 최종 위험 분류는 별도 분류 정책에서 결정합니다.

[FDS 위험 등급·결정 우선순위](docs/risk-classification.md)는 실행 실패와 자료 부족을 우선 처리한 뒤 점수 임계값·보완 규칙으로 위험 수준을 결정합니다. GPT 의미 일치로 높은 행동 위험을 낮추지 않습니다.

## 개발 환경

장기 검증에 사용할 가상 구매 이력은 [합성 데이터 사용법](docs/synthetic-dataset.md)과 [패턴 선정 근거](docs/synthetic-patterns.md)를 참고합니다. 생성기·고정 저장·시점별 Feature 입력 내보내기를 제공하며, 실제 데이터는 Git에서 제외된 `ai/data/synthetic/`에 보관합니다. 이 도구는 프로필 학습이나 GPT·FDS 최종 통합을 실행하지 않습니다.

- Python 3.11 이상: FDS 모듈 실행 환경입니다. 로컬 검증은 Python 3.11.9에서 수행합니다.
- pip와 venv: `ai/.venv`에 프로젝트와 의존성을 설치합니다.
- Pydantic: 타입, 필수값, 금액·통화 및 입력 간 참조 관계를 검증합니다.
- iso4217: 실제 ISO 4217 코드와 최소 통화 단위가 정의되어 있는지 확인합니다.
- pytest: 외부 서비스 없이 입력 계약을 검증합니다.
- OpenAI SDK와 HTTPX2: GMS Chat Completions 호출, HTTP 제한 시간과 Mock 전송을 처리합니다.
- python-dotenv: 명시적으로 지정한 환경 파일을 프로세스 환경 변경 없이 읽습니다.

직접 사용하는 패키지 버전은 `pyproject.toml`에 고정되어 있습니다. 전이 의존성은 pip가 해당 패키지의 요구 범위에서 선택합니다.

저장소 루트에서 가상환경을 만든 후 **ai 디렉터리에서** 설치·테스트 명령을 실행합니다. PowerShell에서 가상환경 활성화 없이 실행할 수 있습니다.

```powershell
python -m venv ai/.venv
cd ai
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m pip check
```

테스트 도구가 필요 없는 환경에서는 `requirements.txt`를 사용합니다. Linux/macOS에서는 가상환경의 Python 경로가 `.venv/bin/python`입니다.

FastAPI 단독 서버의 실행·설정·Mock 평가 요청은 [HTTP API 사용법](docs/http-api.md)을 참고합니다. 기존 제공자 DTO를 사용하는 내부 API이며 Core/Pay의 최종 연동 계약과 운영 저장소 연결은 후속 작업입니다. 컨테이너 실행은 [AI Docker 실행 환경](docs/docker.md)에 설명합니다.

## 입력 계약 사용

```python
from copenhagen_fds.contracts import FraudEvaluationRequest

# BE 어댑터에서 전달한 dict 또는 JSON 문자열을 검증합니다.
request = FraudEvaluationRequest.model_validate(payload)
request_from_json = FraudEvaluationRequest.model_validate_json(json_payload)
```

전체 입력 예시는 `tests/test_input_contracts.py`의 `payload` fixture에 있습니다. 테스트 값은 모두 합성 데이터입니다.

| 모델 | 주요 내용 |
| --- | --- |
| `FraudEvaluationRequest` | `intent`, `order`, `context`를 묶는 평가 입력 |
| `PurchaseIntent` | 구매 요청 ID, 의도 원천·버전, 상품·수량·판매처, 예산, 명시 조건·선호·모호한 항목 |
| `IntentBudget` | 금액과 `maximum` 또는 `approximate` 구분 |
| `OrderSnapshot` | 구매 요청 ID, 스냅샷 참조, 판매처별 주문, 전체 결제액과 배송비·세금·할인 집계값 |
| `MerchantOrder` | 판매처 ID와 주문 ID, 상품 목록, 해당 주문의 결제액과 배송비·세금·할인 |
| `OrderItem` | 주문 안의 항목 ID, 상품 ID·이름·수량, 단가와 항목 결제액 |
| `SnapshotReference` | BE가 제공하는 스냅샷 ID, 문자열 버전, SHA-256 형식의 해시 |
| `EvaluationContext` | BE가 제공하는 평가 ID, 사용자 ID, 구매 요청 ID, 선택적인 승인 참조 |
| `ApprovalReference` | 승인 ID와 승인 대상 스냅샷 참조 |

### 금액과 미지정 값

모든 금액은 `{"amount_minor": 1000, "currency": "KRW"}`처럼 통화의 최소 단위로 전달합니다. KRW 1000은 1,000원, USD 1000은 10달러, KWD 1000은 1디나르입니다. 음수, 실수, 숫자 문자열, bool은 거부하며, 환산이나 반올림을 수행하지 않습니다. `XXX`, `XTS`, 귀금속 등 최소 통화 단위가 없는 코드는 거부합니다. 통화 코드의 형식 검증은 Pay 서비스의 결제 지원 통화 정책과 별개입니다.

`total`과 `line_total`은 BE가 전달한 최종 결제액입니다. `shipping`, `tax`, `discount`는 그 결제액을 설명하는 별도 구성 정보이며 자동으로 다시 더하거나 빼지 않습니다. 전체 스냅샷의 구성 정보는 전체 구매에 대한 집계값이고, 판매처별 구성 정보는 해당 주문의 값입니다. 값을 서로 합산하거나 임의로 배분하지 않습니다.

미지정 예산·수량·조건·배송비·세금·할인은 `None`으로 유지합니다. 명시적인 `0`, 빈 목록과 구분하며 기본 수량 1이나 무제한 예산을 채우지 않습니다. `maximum`은 전달받은 의도의 상한 표현이고 `approximate`는 대략적인 예산 표현입니다. 어느 값도 결제 승인이나 권한이 되지 않습니다.

### 검증 범위

- 필수값, 엄격한 타입, 빈 문자열, 양의 정수 수량, 음수가 아닌 정수 금액을 검사합니다. 알 수 없는 필드는 거부합니다.
- 의도·주문·컨텍스트의 구매 요청 ID가 일치해야 합니다. 주문 ID는 스냅샷 안에서, 항목 ID는 개별 주문 안에서 유일해야 합니다. 한 구매 요청에 여러 판매처 또는 같은 판매처의 여러 주문이 올 수 있습니다.
- 주문·상품·구성 금액·예산의 통화가 달라 환산이 필요한 입력은 거부합니다.
- 의도 원천은 `user_direct`, `agent_summary`, `unknown` 중 전달받은 값으로 보존합니다. `user_direct`라는 값만으로 실제 사용자 발언인지 인증하지 않습니다.
- 해시는 SHA-256 문자열 모양만 검사합니다. 해시를 생성하거나 원본과 대조하지 않습니다. 버전은 숫자로 변환하지 않는 불투명 문자열입니다.
- 예산 초과, 금액 합계 불일치, 승인 참조와 현재 주문의 버전·해시 차이는 후속 분석에 필요한 입력으로 보존합니다. 이 단계에서 승인이나 위험 분류를 결정하지 않습니다.

검증 후 모델은 필드 재할당을 막고 목록을 튜플로 보존합니다. JSON 배열은 `model_validate`와 `model_validate_json` 모두에서 받을 수 있으며 직렬화하면 배열로 나갑니다. 검증이 필요한 외부 입력에 Pydantic의 검증 우회용 `model_construct` 또는 `model_copy(update=...)`를 사용하지 않습니다.

검증 오류의 문자열에는 입력값을 표시하지 않습니다. 오류 상세를 처리할 때도 `ValidationError.errors(include_input=False)`를 사용하고 원문 구매 입력이나 사용자 식별자를 로그에 기록하지 않습니다.

이 계약은 AI 모듈의 입력 모델입니다. 현재 Core·Pay와 연결된 API 계약이나 제공자·소비자 통합 검증을 의미하지 않습니다. 인증·승인 진위, DB 접근, 결제 실행 및 외부 서비스 호출은 포함하지 않습니다.

## 개별 평가 결과 계약

이 절의 `SiftScore`, `SiftFraudResult`와 아래 `FraudDecision`은 기존 소비자 호환을 위한 계약입니다. 자체 FDS는 별도의 `BehaviorEvaluationRequest`와 `BehaviorEvaluationResult`를 사용합니다. 두 점수 체계를 자동 변환하지 않으며 기존 최종 판정 계약의 교체는 통합 구현에서 수행합니다.

`IntentAnalysisResult`, `SiftFraudResult`, `RuleResult`는 모두 실행 상태, 평가 메타데이터, 사유 목록을 가집니다. JSON 문자열과 Python dict 양쪽에서 같은 문자열 Enum 값을 받습니다.

| 실행 상태 | 결과 필드 | 평가 시각 | 사유 |
| --- | --- | --- | --- |
| `SUCCESS` | 반드시 존재 | 필수 | 의미·위험이 UNKNOWN이면 필수 |
| `FAILED` | `None` | 필수 | 실패 코드 필수 |
| `NOT_RUN` | `None` | `None` | `NOT_EXECUTED` 필수 |

`IntentAnalysisResult.comparison`은 `MATCH`, `MISMATCH`, `UNKNOWN`을 구분합니다. UNKNOWN은 분석 실행에는 성공했지만 의미 비교가 불확실한 상태입니다. 실행 실패·미실행은 `comparison=None`으로 구분하며 bool이나 금융 사기 확률로 변환하지 않습니다. 성공 시 실제 사용한 모델·프롬프트 버전을 반드시 전달합니다.

`SiftFraudResult.score`는 `{"value": 0.2, "unit": "SIFT_PAYMENT_ABUSE_0_1"}` 형태입니다. [Sift Score API 공식 문서](https://developers.sift.com/docs/v204/curl/score-api)의 `payment_abuse` API 점수 범위인 0.0~1.0을 그대로 보존합니다. 콘솔 표시용 0~100 값은 받지 않고 확률로 재해석하지 않습니다. 실패·미실행 시 점수는 `None`이며 0점으로 채우지 않습니다. 제공되지 않은 Sift 모델 버전도 `None`으로 유지합니다.

`RuleResult`는 규칙 ID와 `LOW`, `MEDIUM`, `HIGH`, `UNKNOWN` 중 평가자가 전달한 위험 수준을 보존합니다. 성공 시 적용한 정책 버전이 필요합니다. 이 모델은 점수·규칙·임계값을 계산하지 않습니다.

`EvaluationMetadata`에는 아래 정보가 연결됩니다.

- `target`: 평가·사용자·구매 요청 ID, 기존 `SnapshotReference`, `intent_version`. 입력의 `PurchaseIntent.version`이 미지정이면 의도 버전도 명시적으로 `None`으로 전달하며 새 버전을 만들어내지 않습니다.
- `trace_id`, `sources`: 추적 ID와 전달받은 데이터 출처. `BE`, `OPENAI`, `SIFT`, `RULE_ENGINE`, `FDS`, `MOCK`, `UNKNOWN`을 구분합니다. 출처 표기는 원천의 진위를 증명하지 않습니다.
- `evaluated_at`, `valid_until`: 시간대가 있는 ISO 날짜·시각. `valid_until`은 선택 사항이며 지정할 경우 평가 시각보다 늦어야 합니다. 실패·미실행에는 유효기간을 부여하지 않습니다.
- `model_version`, `prompt_version`, `policy_version`: 실제 적용한 버전. 필요한 버전을 임의 기본값으로 채우지 않습니다.

사유는 `EvaluationReason(code, source, reference_id)`로 표현합니다. 실패는 `EVALUATION_FAILED`, `TIMEOUT`, `INVALID_RESPONSE` 등 실행 실패 코드로 설명하고, 의미 불확실·데이터 부족·규칙 탐지는 별도 코드로 구분합니다. 외부 응답 전문이나 원문 사용자 입력을 사유에 넣지 않습니다.

## 통합 평가 결과 계약

`FraudDecision`은 개별 의도·Sift·규칙 결과, 정책에서 명시한 `required_components`, 위험 수준, FDS 권고, 확인 권고를 함께 받습니다. `required_components`는 생략할 수 없는 비어 있지 않은 목록이며, 모델이 임의로 필수 평가를 제외하지 않습니다.

- `SUCCESS`: 필수 평가가 성공하고 결과가 알려져 있어야 합니다. 위험 수준과 권고가 명시되어야 합니다. 정책에서 필수로 지정하지 않은 평가가 실패한 경우에도 그 실패 기록은 그대로 남습니다.
- `INCOMPLETE`: 필수 평가가 실패·미실행·UNKNOWN 또는 만료된 경우를 표현합니다. 위험은 `UNKNOWN`, 권고는 `None`으로 두며 사유를 전달합니다.
- `FAILED`: 통합 평가 자체의 실패입니다. 개별 결과가 성공했더라도 통합 권고는 만들지 않습니다. 실행 실패 사유가 필요합니다.
- `NOT_RUN`: 통합 평가와 포함된 개별 평가가 모두 미실행이어야 합니다.

통합 결과와 개별 결과의 평가 대상·주문 참조·Intent 버전·추적 ID가 다르면 거부합니다. 필수 결과의 유효기간이 정해져 있다면 통합 평가 시점에 유효해야 하고 통합 결과가 그 기간보다 오래 유효할 수 없습니다. 유효기간은 FDS 결과의 해당 대상에 대한 적용 범위이며 사용자 승인 만료 정책이 아닙니다. 기본 TTL이나 현재 시각을 모델에서 생성하지 않습니다.

`ALLOW`, `REQUIRE_AUTH`, `DENY`는 **FDS 평가 권고**입니다. 모델은 입력된 위험 수준과 권고를 형식 검증할 뿐 위험 분류 정책을 실행하지 않습니다. 사용자 승인 확인과 실제 추가 인증·결제 차단·결제 실행은 BE의 책임입니다. `review_actions`에는 `CHECK_INPUT`, `RETRY_EVALUATION`, `REVIEW_ORDER`, `VERIFY_USER_INTENT`를 전달할 수 있습니다.

개별 결과 예시는 `tests/conftest.py`와 `tests/test_result_contracts.py`, 통합 결과 예시는 `tests/test_decision_contracts.py`에 있습니다. 모두 합성 데이터로 실행하며 외부 API나 서비스 DB를 호출하지 않습니다.

### 입력과 평가 결과 연결

입력과 결과를 각각 역직렬화한 경우, 결과를 사용할 때 `validate_for_request`로 두 계약의 참조가 일치하는지도 검사합니다.

```python
from copenhagen_fds.contracts import FraudDecision, FraudEvaluationRequest

request = FraudEvaluationRequest.model_validate_json(request_json)
decision = FraudDecision.model_validate_json(decision_json)
decision.validate_for_request(request)
```

평가 ID·사용자 ID·구매 요청 ID, 현재 주문의 스냅샷 ID·버전·해시, 의도 버전을 비교합니다. 하나라도 다르면 입력값을 포함하지 않는 `ValueError`를 발생시키며, 일치하면 기존 결과 객체를 그대로 반환합니다. 입력의 의도 버전이 `None`이면 결과도 명시적인 `None`이어야 합니다. 개별 모델의 역직렬화만으로 이 연결 검사까지 자동 실행되지는 않습니다.

이 검사는 전달된 참조의 일치 여부만 확인합니다. 주문 내용의 해시를 재계산하거나 사용자·승인을 인증하지 않습니다. 이전 승인에 연결된 스냅샷은 분석용 입력으로 보존하고, 결과의 대상은 현재 주문과 비교합니다.

## GPT 분석 계약

### 분석 입력과 모델 출력

`IntentAnalysisRequest`는 BE가 제공하는 `user_request`, `order`, `context`를 받습니다. `user_request`에는 구매 요청 ID, 요청 출처, 선택적인 문자열 버전과 요청 텍스트가 들어갑니다. 전체 대화나 인증 정보를 조회하지 않으며 텍스트 미제공은 `None`으로 구분합니다. 원문 텍스트는 `repr`에서 숨기지만 직렬화에는 포함되므로 입력 객체나 JSON 전문을 로그에 기록하지 않습니다.

`IntentExtractionOutput`과 `IntentComparisonOutput`은 모델이 생성할 수 있는 분석 내용만 정의합니다. 사용자·평가·구매 요청 ID, 입력 출처·버전·해시, 실행 상태·모델 버전·토큰 사용량·위험 점수를 출력에 넣으면 거부합니다. 실행 메타데이터는 호출 어댑터가 별도로 연결합니다.

| 모델 | 내용 |
| --- | --- |
| `IntentExtractionOutput` | 상품 이름·수량·브랜드·카테고리, 예산, 구매 목적, 명시 조건·참고 선호·모호한 항목 |
| `IntentComparisonOutput` | MATCH/MISMATCH/UNKNOWN, 구조화 사유, 확인 필요 사항 |
| `ComparisonFinding` | 사유 코드, 상품·브랜드·카테고리·목적·수량·예산·조건·대체 중 비교 항목, 관련 주문 항목 |
| `ConfirmationRequest` | 의도 명확화·대체 확인·정보 보충·모순 해결 요청과 관련 주문 항목 |
| `OrderItemReference` | 주문 ID와 그 주문 안의 항목 ID를 함께 가진 참조 |

분석용 필드는 모두 응답에 명시하며 미제공 값은 `null`로 전달합니다. `null`과 빈 배열, 명시적 0을 구분합니다. 통화·단위가 불명확하여 기존 `IntentBudget`을 구성할 수 없으면 `budget=null`로 두고 `ambiguities`에 확인할 내용을 보존합니다. 통화나 수량을 주문 내용에서 임의로 채우지 않습니다. 예산의 `maximum`/`approximate` 구분도 유지합니다.

기존 공통 계약에는 선택 필드만 추가했습니다. `IntentItem`은 `brand`·`category`, `PurchaseIntent`는 `purpose`, `OrderItem`은 `brand`·`category`·`description`을 받을 수 있습니다. 기존 입력에서는 모두 `None`이며, 상품 설명은 외부 데이터로 취급합니다.

```python
from copenhagen_fds.contracts import (
    IntentAnalysisRequest, IntentExtractionOutput, IntentComparisonOutput,
    analysis_response_format,
)

request = IntentAnalysisRequest.model_validate(analysis_input)
extracted = IntentExtractionOutput.model_validate_json(extraction_json)
evaluation_request = request.with_extracted_intent(extracted)

compared = IntentComparisonOutput.model_validate_json(comparison_json)
compared.validate_for_request(evaluation_request)
response_format = analysis_response_format("comparison")  # 또는 "extraction"
```

추출 결과를 연결할 때 구매 요청 ID·출처·의도 버전은 `user_request`에서 복사하고 현재 주문·컨텍스트는 유지합니다. 이것이 해당 원천의 진위나 사용자 승인을 증명하지는 않습니다. 요청 텍스트가 없으면 추출 내용으로 평가 요청을 만들 수 없습니다.

관련 상품은 `(order_id, item_id)` 조합으로 검사합니다. 다른 주문에 같은 항목 ID가 있어도 구분하며, 존재하지 않는 조합과 한 사유 안의 중복 참조는 거부합니다. 주문 전체에 대한 사유·확인은 빈 참조 배열로 표현합니다. MATCH에는 미해결 사유나 확인 요청을 넣지 않습니다. MISMATCH에는 확정된 의도 불일치 사유, UNKNOWN에는 불확실·정보 부족 사유가 필요합니다.

`analysis_response_format()`은 Chat Completions의 `response_format`에 사용할 버전 1 JSON Schema를 새 객체로 반환합니다. [Structured Outputs 명세](https://developers.openai.com/api/docs/guides/structured-outputs)에 맞춰 모든 객체 필드를 필수로 선언하고 추가 속성을 금지하며, 미제공 값은 nullable 필드로 표현합니다. 로컬 스키마·관계 검증을 통과한 것과 GMS 게이트웨이에서 해당 옵션이 지원되는 것은 별개입니다.

### 실행 결과와 공통 계약 매핑

`IntentExtractionResult`와 `IntentComparisonResult`는 공통 실행 상태·메타데이터·사유에 `payload`, `failure`, `telemetry`를 연결합니다. `payload`에만 검증한 모델 출력을 넣으며, 나머지 필드는 호출 어댑터가 수집한 정보를 사용합니다. 계약과 변환은 `contracts`가, SDK 호출은 별도 `analysis` 패키지가 담당합니다.

| 실행 상태 | payload / failure | 공통 의미 비교 값 |
| --- | --- | --- |
| SUCCESS | payload 필수, failure 없음 | 비교 결과의 MATCH/MISMATCH/UNKNOWN |
| FAILED | payload 없음, failure 필수 | `None` |
| NOT_RUN | 둘 다 없음 | `None` |

`AnalysisFailure.kind`는 API_ERROR, TIMEOUT, REFUSAL, INCOMPLETE_RESPONSE, SCHEMA_ERROR, INSUFFICIENT_INPUT을 구분합니다. API_ERROR와 INSUFFICIENT_INPUT은 공통 사유 EVALUATION_FAILED, TIMEOUT은 TIMEOUT, 나머지는 INVALID_RESPONSE로 매핑합니다. 세부 실패 종류는 원래 분석 결과에 보존합니다. 제공받은 HTTP 상태도 선택적으로 기록하므로 HTTP 200으로 전달된 거부 응답을 분석 성공으로 바꾸지 않습니다. 예외·외부 응답 전문은 실패 계약에 넣지 않습니다.

`AnalysisTelemetry`에는 GMS/MOCK 경로, 요청 모델, 응답 ID와 제공자 요청 ID, 밀리초 단위 처리 시간, 제공받은 토큰 사용량을 기록합니다. GMS 호출의 모델 제공자 출처는 공통 계약의 OPENAI로, Mock은 MOCK으로 연결합니다. 누락 측정값은 `None`이며 0으로 채우거나 합계 토큰을 만들어내지 않습니다. NOT_RUN에는 호출 측정값을 넣지 않습니다. 성공 결과는 공통 메타데이터에 실제 사용한 모델·프롬프트 버전이 있어야 합니다.

```python
from copenhagen_fds.contracts import IntentExtractionResult, IntentComparisonResult

extraction_result = IntentExtractionResult.model_validate(extraction_result_data)
evaluation_request = extraction_result.to_evaluation_request(request)

comparison_result = IntentComparisonResult.model_validate(comparison_result_data)
common_result = comparison_result.to_common_result(evaluation_request)
```

이 변환들은 입력과 결과의 평가·사용자·구매 요청 ID, 주문 스냅샷 ID·버전·해시와 의도 버전을 대조합니다. 비교 결과는 관련 상품 참조도 다시 검사합니다. 역직렬화만 한 경우에는 `validate_for_request()`를 명시적으로 호출합니다.

`to_common_result()`는 기존 `IntentAnalysisResult`로의 요약 변환입니다. 비교 항목별 사유·상품 참조·확인 요청·세부 실패·측정 정보까지 보존하려면 원래 분석 결과 객체도 함께 유지합니다. 공통 결과의 상태·사유·평가 대상·버전은 변환 중 바뀌지 않습니다.

합성 예시와 테스트는 `tests/analysis/`에 있습니다. `python -m pytest tests/analysis -q`로 네트워크 없이 실행합니다. 이는 계약·변환 검증이며 실제 GPT의 정확도나 Prompt Injection 방어 성능 검증을 의미하지 않습니다.

### 구매 의도 추출기

`IntentExtractor`는 요청 텍스트만 담은 버전 관리 프롬프트를 GMS 어댑터에 전달하고 `IntentExtractionResult`로 연결합니다. 출처·버전·추적 정보는 호출자가 준 입력에서 보존합니다. 요청 텍스트 미수집은 NOT_RUN, 추출 가능한 구매 내용이 없는 응답은 INSUFFICIENT_INPUT 실패, 모호하지만 일부 구매 내용이 있는 경우는 확인 사항을 포함한 SUCCESS로 구분합니다.

결과의 `input_source`는 요청의 `user_direct`/`agent_summary`/`unknown` 값입니다. `clarifications`는 확인이 필요한 내용을 담으며, 입력 부족으로 payload를 반환하지 못할 때도 유지합니다. 기존 계약과의 호환을 위해 두 필드에는 기본값이 있으나 추출기는 출처를 항상 채웁니다. `to_purchase_intent(request)`는 주문과 분리된 구매 의도로 변환하며, `to_evaluation_request(request)`는 기존 공통 비교 입력의 통화·참조 검증까지 적용합니다.

실행 예제, 예산·미지정 값 처리와 검증 범위는 [구매 의도 추출기 사용 문서](docs/intent-extractor.md)를 참고하세요.

## 환경설정

`copenhagen_fds.settings.load_settings()`는 기존 Pydantic 모델과 표준 라이브러리로 프로세스 환경변수를 읽고 검증합니다. 반환하는 설정은 필드 재할당을 막습니다. 명시적인 매핑을 넘기면 해당 매핑만 사용하므로 테스트가 개발자 PC의 키나 환경설정에 의존하지 않습니다.

```python
from copenhagen_fds.settings import SettingsError, load_settings

settings = load_settings({})  # 외부 키 없이 독립적인 MOCK 설정
# 실제 실행 진입점에서는 load_settings()로 프로세스 환경변수를 읽습니다.

try:
    load_settings({"FDS_MODE": "LIVE"})
except SettingsError as error:
    print(error)  # 누락되거나 잘못된 설정 항목만 표시하며 입력값은 포함하지 않습니다.
```

| 환경변수 | 미지정 시 | 의미 |
| --- | --- | --- |
| `FDS_MODE` | `MOCK` | `MOCK` 또는 `LIVE`, 대소문자 구분 |
| `FDS_OPENAI_MODEL` | `gpt-5.4-mini` | 명시한 모델명 보존, 오류 시 다른 모델로 대체하지 않음 |
| `GMS_KEY` | `None` | GMS에서 발급한 키, LIVE 필수 |
| `FDS_OPENAI_TIMEOUT_SECONDS` | `None` | GMS 경유 OpenAI 호출 제한 시간, 초 단위 |
| `FDS_EVALUATION_TIMEOUT_SECONDS` | `None` | 전체 평가 제한 시간, 초 단위 |
| `FDS_POLICY_VERSION` | `None` | 선택적으로 전달하는 정책 버전 메타데이터 |

- **MOCK**는 키·제한 시간·정책 버전 없이 설정을 읽을 수 있습니다. 미지정 값은 그대로 `None`이며 운영값으로 채우지 않습니다.
- **LIVE**는 `GMS_KEY`, `FDS_OPENAI_TIMEOUT_SECONDS`, `FDS_EVALUATION_TIMEOUT_SECONDS`를 명시해야 합니다. 설정 로딩은 실제 API 호출이나 접근 권한 확인을 수행하지 않습니다. 설정 검증 통과는 GMS 접근 성공이나 전체 FDS 평가·위험 정책의 준비 완료를 의미하지 않습니다.
- 제한 시간은 유한한 양수이며 GMS 호출 제한 시간은 전체 평가 제한 시간 이하여야 합니다. 실제 실행과 취소 처리는 호출·통합 평가 구현에서 담당합니다.
- 자체 FDS 설정은 Sift 필드·임계값을 사용하지 않습니다. 최종 가중치·위험등급 임계값은 별도 정책 구현에서 정의하며, `FDS_POLICY_VERSION`만 설정해도 해당 정책이 실행되지는 않습니다.
- 기존 `FDS_SIFT_API_KEY`, `FDS_SIFT_TIMEOUT_SECONDS`, `FDS_SIFT_MEDIUM_THRESHOLD`, `FDS_SIFT_HIGH_THRESHOLD`, `FDS_SIFT_SCORE_UNIT`은 환경변수 또는 명시적으로 읽는 `.env`에서 제거해야 합니다. 빈값이어도 `load_settings`와 `load_openai_settings` 모두 제거 안내 오류를 반환합니다. 예전 Sift 값을 새 이상점수로 자동 변환하거나 무시하지 않습니다.
- 선택 항목의 빈 문자열은 미지정으로 처리합니다. 모드·모델의 빈 문자열, 공백뿐인 값, 오타가 있는 `FDS_` 변수는 오류로 처리합니다. 무관한 프로세스 환경변수는 무시합니다.

### GMS 인증과 연결 주소

OpenAI 모델 호출에는 GMS에서 발급한 `GMS_KEY`를 사용합니다. 설정 객체의 `gms_key`에 `SecretStr`로 보관하며 기존 `FDS_OPENAI_MODEL`과 `FDS_OPENAI_TIMEOUT_SECONDS` 이름은 유지합니다. 기본 모델은 `gpt-5.4-mini`입니다.

| 설정 속성 / 요청 항목 | 값 |
| --- | --- |
| `settings.openai_base_url` | `https://gms.ssafy.io/gmsapi/api.openai.com/v1` |
| `settings.openai_chat_completions_url` | `https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions` |
| HTTP 메서드 | `POST` |
| 인증 헤더 | `Authorization: Bearer <GMS_KEY 값>` |
| Content-Type | `application/json` |
| 요청 본문 | `model`, `messages` — `developer`와 `user` 메시지 |

GMS 주소와 인증은 제공받은 연결 규격을 따릅니다. 메시지 형식은 [OpenAI Chat Completions 명세](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)를 참고합니다. `OpenAIAnalysisAdapter`는 위 주소와 `gms_key`를 명시적으로 사용합니다. 호출·실패·시간 예산 처리는 [GMS 어댑터 사용 문서](docs/openai-adapter.md)에 설명합니다.

기존 환경설정의 `FDS_OPENAI_API_KEY` 항목은 제거하고 `GMS_KEY`로 교체합니다. 이전 항목이 남아 있으면 값이 비어 있더라도 변경 안내 오류가 발생합니다. `GMS_KEY`는 대소문자를 구분하며 `gms_key` 등의 표기를 거부합니다. 일반 `OPENAI_API_KEY`·`OPENAI_BASE_URL` 환경변수는 이 설정 로더가 사용하지 않으며 GMS 키·주소를 대체하지 않습니다.

키는 실행 환경에서 주입합니다. `.env` 파일 자동 로딩은 없으며 `.env.example`에도 실제 키를 넣지 않습니다. GMS의 실제 인증·모델 접근·Structured Outputs 지원은 별도 연동 검증 대상입니다. 현재 설정 검증만으로 `/responses`나 `response_format` 옵션의 GMS 지원을 보장하지 않습니다.

키는 `SecretStr`로 보관해 `str`, `repr`, `model_dump_json`과 JSON 모드의 `model_dump`에서 마스킹됩니다. 제공자 클라이언트가 실제 호출할 때만 해당 키의 `get_secret_value()`를 사용하고 그 반환값을 로그나 문서에 넣지 않습니다. `load_settings`의 `SettingsError`는 변수 이름과 고정된 설명만 보존하며 원본 검증 오류나 입력값을 예외 체인에 연결하지 않습니다. 환경변수 입력의 검증 경계는 `load_settings`이고, 설정 모델의 저수준 생성·검증 API를 직접 사용할 필요는 없습니다.

`ai/.env.example`은 실제 키·제한 시간·정책 버전이 비어 있는 참조 예제입니다. 로더는 이 파일이나 루트 `.env`를 자동으로 읽지 않습니다. 필요한 값을 프로세스 환경변수 또는 실행 환경의 Secret 주입 기능으로 전달합니다. 읽기 예제는 다음처럼 MOCK 모드에서 실행할 수 있습니다.

```powershell
# ai 디렉터리에서 실행
$env:FDS_MODE = "MOCK"
.\.venv\Scripts\python.exe -c "from copenhagen_fds.settings import load_settings; print(load_settings())"
```

설정 검증 예시는 `tests/test_settings.py`에 있습니다. 예제와 테스트에 쓰는 문자열은 합성 데이터이며 실제 외부 서비스에 연결하지 않습니다.

### OpenAI 단독 설정과 명시적 환경 파일

`copenhagen_fds.openai_settings.load_openai_settings()`는 GMS 분석만 실행할 때 사용하는 설정 경계입니다. 정책 버전을 요구하지 않으며 MOCK에서도 두 제한 시간이 필요합니다. 공통 `load_settings()`는 MOCK에서 제한 시간을 생략할 수 있습니다. 두 로더 모두 LIVE에서 GMS 인증과 두 제한 시간을 검증하고 이전 Sift 설정은 거부합니다.

| 설정 | OpenAI 단독 실행 기준 |
| --- | --- |
| `FDS_MODE` | 기본 MOCK, 실제 연결 시 LIVE 명시 |
| `GMS_KEY` | LIVE 필수, MOCK은 키 없이 주입한 SDK 사용 |
| `FDS_OPENAI_MODEL` | 기본 `gpt-5.4-mini`, 변경 시 입력값 그대로 전달 |
| `FDS_OPENAI_TIMEOUT_SECONDS` | 두 모드 모두 유한한 양수 필수 |
| `FDS_EVALUATION_TIMEOUT_SECONDS` | 두 모드 모두 필수, OpenAI 제한 시간 이상 |

파일의 키를 사용할 때는 `env_file`에 경로를 명시합니다. 파일을 자동 탐색하지 않으며, 읽는 동작만으로 API를 호출하지 않습니다.

```python
from copenhagen_fds.openai_settings import load_openai_settings

# ai 디렉터리에서 루트 .env를 명시적으로 읽는 예제입니다.
# 아래 제한 시간은 사용 예시이며 운영 권장값이 아닙니다.
settings = load_openai_settings(
    {"FDS_MODE": "LIVE", "FDS_OPENAI_TIMEOUT_SECONDS": "5", "FDS_EVALUATION_TIMEOUT_SECONDS": "10"},
    env_file="../.env",
)
```

명시적 매핑이 파일 값보다 우선합니다. 매핑을 생략하면 프로세스 환경변수가 파일 값보다 우선하고, 빈 매핑 `{}`을 넘기면 파일 값만 읽습니다. `${VAR}` 보간은 수행하지 않고 `os.environ`도 수정하지 않습니다. 지정한 파일을 읽지 못하면 경로·입력 원문을 포함하지 않는 `SettingsError`를 반환합니다. 파일·코드·로그에 키 값을 출력하지 않습니다.

`tests/test_openai_settings.py`는 합성 환경 파일, 우선순위, 키 마스킹, 제한 시간과 기존 전체 FDS 설정의 회귀 동작을 검증합니다.

## 계약 통합 테스트

`tests/contract/`는 입력·개별 결과·통합 결과·MOCK 설정을 함께 사용합니다. 정상, 여러 판매처, 의미 불확실, 외부 평가 실패, 미수집, 미실행 상황의 합성 데이터로 JSON·dict 역직렬화와 JSON 왕복 변환을 검증합니다. 테스트 내부의 요청·결과 묶음은 테스트용 포맷이며 외부 API 명세가 아닙니다.

```powershell
# ai 디렉터리에서 실행
.\.venv\Scripts\python.exe -m pytest tests/contract -q
```

금액·통화·버전·상태·시각·주문 관계를 보존하고, 다른 요청의 결과를 연결하거나 UNKNOWN을 bool로 바꾸거나 실패 점수를 0으로 채운 경우를 검사합니다. 설정은 명시적인 매핑으로 주입하며 실제 키나 `.env`에 의존하지 않습니다. 소켓 연결을 시도하면 테스트가 실패합니다.

큰 정수 보존은 Python의 JSON 처리 경계에서 검증합니다. JavaScript·BE의 숫자 범위와 전송 포맷 호환성, 실제 Core·Pay 제공자·소비자 연동은 이 테스트의 검증 범위에 포함되지 않습니다.

## 디렉터리

```text
ai/
├── .env.example              # 키·운영 정책값이 비어 있는 환경변수 참조 예제
├── pyproject.toml            # 패키지, 의존성, pytest 설정
├── requirements*.txt        # pip 설치 진입점
├── src/copenhagen_fds/
│   ├── contracts/           # FDS 입력·결과·상태 모델과 공통 값 타입
│   ├── analysis/            # GMS 어댑터, 공유 시간 예산, 의도 추출·주문 의미 비교
│   ├── openai_settings.py   # OpenAI 단독 설정과 명시적 dotenv 로딩
│   └── settings.py          # 전체 FDS 환경변수와 정책 검증
├── tests/                   # 합성 데이터 기반 단위 테스트
│   └── contract/           # 입력·결과·설정의 연결과 JSON 왕복 검증
├── config/                  # 설정 작업 공간
├── data/                    # 로컬 데이터, Git 제외
├── docs/                    # 기존 데이터셋 검토 문서
├── models/                  # 모델 관련 산출물 작업 공간
└── results/                 # 평가 결과 작업 공간
```

가상환경과 Python 생성 파일은 `ai/.gitignore`로 제외합니다. 비밀키, 개인정보, 대용량 데이터 및 개인 기획 문서는 저장소에 추가하지 않습니다.
