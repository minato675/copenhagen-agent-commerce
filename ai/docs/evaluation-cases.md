# GPT 분석 고정 평가셋과 오프라인 회귀

`copenhagen_fds.evaluation.cases.load_dataset()`은 `fds-intent-eval-v1` 합성 사례 35개를 반환합니다. 무작위 생성·외부 데이터 조회·실제 개인정보는 사용하지 않습니다. 사례 생성 함수는 고정 입력을 기존 계약으로 확장할 뿐, 기대값을 모델로 추론하지 않습니다.

각 사례에는 고유 ID, 범주, 검증된 요청·주문·컨텍스트, 기대 단계 상태·추출 필드·의미 결과·사유·확인 사항, 한국어 판정 기준과 Mock 응답이 있습니다. 평가셋을 JSON으로 직렬화하면 확장된 전체 입력을 확인할 수 있습니다. 원래 설계 기획이나 작업 진행 문서가 아니라 재현 가능한 기술 테스트 데이터입니다.

## 사례 구성

| 사례 ID | 확인하는 내용 |
| --- | --- |
| fds-01-clear | 명시 상품·수량, 미지정 예산 |
| fds-02-maximum / fds-03-approximate | 최대 예산과 근사 예산 구분 |
| fds-04-no-quantity / fds-05-no-currency / fds-06-budget-kind | 수량·통화·예산 종류를 임의 보완하지 않음 |
| fds-07-required-condition / fds-08-condition-mismatch | 필수 조건 일치·이탈 |
| fds-09-preference / fds-10-brand | 참고 선호와 필수 브랜드 구분 |
| fds-11-unrequested / fds-12-substitution | 미요청 상품과 대체 확인 |
| fds-13-missing-attribute / fds-14-conflict | 상품 정보 부족과 모순 조건 |
| fds-15-multiple-merchants / fds-16-second-merchant | 다중 판매처, 같은 항목 ID의 주문별 구분 |
| fds-17-purpose-only | 상품 미지정과 FDS가 보완한 확인 사항 전달 |
| fds-18-source-summary / fds-19-source-unknown | 출처 보존 |
| fds-20-user-injection / fds-21-mcp-injection | 원문·외부 상품/MCP 설명의 지시 격리 |
| fds-22-name-injection / fds-23-injection-mismatch | 상품명 지시·MATCH 강제 문구와 불일치 근거 |
| fds-24-currency-conflict | USD 의도 보존, KRW 비교 입력 변환 실패 |
| fds-25-no-request / fds-26-greeting | 요청 미수집과 구매 내용 없는 입력 |
| fds-27-authentication ~ fds-35-extra_authority | 인증·호출 제한·서버·시간·거부·불완전·스키마·참조·권한 필드 오류 |

금액·수량을 생성하는 보조 함수의 고정값은 합성 주문 입력일 뿐입니다. BE의 금액 합계·예산 초과·통화 정책을 구현하거나 검증하지 않습니다. 상품/MCP 설명 사례는 해당 데이터 필드의 분석 경계를 검사하며 실제 MCP 서비스 연동을 뜻하지 않습니다.

## 기대값과 재현성

추출 판정은 `FieldCheck`의 경로와 연산을 사용합니다. `equals`는 타입·null을 포함한 정확한 JSON 값 일치, `contains`는 문자열 또는 문자열 목록의 부분 문자열 포함, `nonempty`는 비어 있지 않은 값을 뜻합니다. 필드가 없는 경우와 명시적 null은 다르게 처리합니다. 불필요한 자유 서술 차이는 해당 사례의 `contains` 기준으로 완화할 수 있습니다.

기본 사례에는 참조 추출의 여섯 필드에 대한 정확 비교를 사용하고, 일부 사례에는 핵심 필드의 별도 기준을 명시합니다. 이 규칙은 일반적인 자연어 동의어를 판별하지 않습니다. 실제 응답의 오판정이 의심되면 사람이 기대값·판정 기준을 검토해야 합니다.

비교는 기대 MATCH/MISMATCH/UNKNOWN과 실행 상태를 분리합니다. 사유 코드뿐 아니라 기대 차원과 관련 `(order_id, item_id)` 쌍, 확인 코드·관련 항목도 검사합니다. 유효한 부가 사유는 허용하지만 필수 근거의 누락을 허용하지 않습니다. 각 단계의 실패 종류와 추출·비교 확인 사항 보존 여부를 검사합니다.

평가셋 전체를 정렬된 JSON으로 직렬화한 SHA-256을 버전과 함께 기록합니다. 입력·기대값·판정 기준·Mock 응답 중 하나라도 바뀌면 해시가 바뀝니다. 사례 ID와 평가 대상은 중복될 수 없습니다. 이미 공유한 평가셋을 수정할 때는 버전을 올리고 변경 기준을 검토합니다.

## 오프라인 실행

```python
from copenhagen_fds.evaluation.cases import load_dataset
from copenhagen_fds.evaluation.offline import run_mock_dataset

# 이미 실행 중인 asyncio 컨텍스트에서 호출합니다.
async def regression():
    return await run_mock_dataset(load_dataset())
```

이 경로는 실제 OpenAI SDK + HTTPX2 MockTransport와 `IntentAnalyzer`를 사용합니다. 요청마다 추출·비교 스키마와 GMS 경로를 확인하고, 고정 응답을 JSON으로 전달하므로 어댑터의 파싱·실패 처리와 공통 결과 변환까지 통과합니다. 실제 키·환경 파일·DB는 읽지 않습니다. Model 출력용 토큰 수를 만들어 넣지 않습니다.

네트워크 차단 검증을 포함한 기본 테스트:

```powershell
# ai 디렉터리에서 실행
.\.venv\Scripts\python.exe -m pytest tests/evaluation/test_regression.py -q
.\.venv\Scripts\python.exe -m pytest -q
```

기본 AI 테스트는 DNS와 소켓 연결을 차단합니다. Windows asyncio가 필요로 하는 내부 소켓쌍만 테스트 루프 생성 시 먼저 준비합니다. 회귀 검증은 단계 연결·JSON 왕복·출처·오류·원문 로그 보호·프롬프트 메시지 경계에 집중합니다.

Mock 응답은 사례의 참조 답을 사용합니다. 따라서 통과는 계약과 연결의 회귀 확인이며, 실제 GPT 정확도나 공격 방어 성공률이 아닙니다. 실제 모델 평가에는 고정 사례·판정 기준 외에 사람의 검토와 대표성 검증이 필요합니다. [OpenAI 평가 지침](https://developers.openai.com/api/docs/guides/evaluation-best-practices)은 목적에 맞는 사례와 명확한 기준, 경계·적대적 사례, 사람의 판단을 통한 평가 기준 보완을 권장합니다.
