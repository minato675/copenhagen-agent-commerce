# 자체 FDS 통합 진입점과 호환 경계

`copenhagen_fds.native_evaluation.evaluate_fraud()`는 한 구매 요청의 행동 평가 → GPT 추출 →
주문 비교 → 기존 위험 정책을 연결한다. Sift 키나 응답을 요구하지 않는다. Python 내부 호출용이며
BE 서비스 인터페이스와 통합 CLI는 별도 범위다.

```python
from copenhagen_fds.analysis import OpenAIAnalysisAdapter
from copenhagen_fds.native_evaluation import NativeEvaluationCancelled, evaluate_fraud

# request: EngineEvaluationRequest, settings: 기존 OpenAISettings
# offline_sdk: MOCK에서만 사용하는 오프라인 SDK. LIVE에서는 None.
async with OpenAIAnalysisAdapter(settings, client=offline_sdk) as adapter:
    try:
        result = await evaluate_fraud(request, adapter)
        outcome = result.outcome
    except NativeEvaluationCancelled as error:
        partial = error.result  # 필요한 호출자가 명시적으로 보관, 일반 로그 출력 금지
        raise
```

원래 입력·프로필을 호출자가 제공한다. 요청의 `risk_policy` 생략 시
[기본 위험 정책](default-risk-policy.md)을 채우며, 명시한 정책은 그대로 유지한다.
GMS 연결 설정은 기존 로더로 명시적으로 읽어 전달하며,
진입점은 `.env` 탐색, 서비스 DB 조회나 프로필 갱신을 하지 않는다. 어댑터의 종료도 호출자 책임이다.
실제 GMS는 기존 LIVE 설정을 사용하고, 기본 테스트는 키 없는 Mock 어댑터/전송을 사용한다.

입력을 검증한 뒤 예산을 한 번 만들고 기존 실행기와 최종 조립에 같은 객체를 전달한다.
`budget=`을 제공하면 이미 소비한 시간까지 유지한다. 새 예산에는 최초 입력 검증은 포함되지
않으며, 이후 실행기 사전 검증·행동·GPT·정책·최종 검증을 포함한다. 함수 반환 후의 호출자
JSON 직렬화·전송은 측정 밖이다. 제한과 결과 의미는 [최종 조립](engine-finalization.md)을 따른다.

`NativeEvaluationCancelled`는 `asyncio.CancelledError`의 하위 클래스다. GPT 실행 중 취소는
290번의 부분 결과를 보존해 변환하며, 정책 시작 전 또는 정책 계산 후 경계에서 받은 취소도
최종 결과에 남겨 전파한다. 이미 얻은 행동·추출·비교·정책 근거는 유지하되 최종 위험은 UNKNOWN이다.
동기식 정책 계산을 즉시 중단하거나 이미 보낸 외부 요청의 서버 처리·과금을 취소한다고 보장하지 않는다.

## 지원하는 호환 범위

| 기존 경로/계약 | 처리 |
| --- | --- |
| `IntentAnalyzer.analyze`, GPT 추출/비교 계약 | 기존 입력·출처·프롬프트·실패·공유 예산 규칙 유지 |
| 기존 Cold/개인화 및 위험 정책 함수 | 실제 함수를 재사용하고 기존 결과/해시 의미 유지 |
| `execute_engine` / `EngineExecutionResult` | 행동·GPT 단계까지 반환하는 기존 호출 유지 |
| `EngineEvaluationResult` | 기존 완전한 분석/정책 기록 계약 유지. 부분 취소를 담기 위해 기존 필드를 제거하지 않음 |
| `FraudDecision`, `SiftFraudResult`, `SiftScore` | 기존 공개 JSON과 검증 동작 유지. 새 진입점에서 생성하거나 변환하지 않음 |
| 기존 `copenhagen_fds.evaluation` 패키지 | 기존 평가셋/보고서/CLI 경로 유지. 새 진입점은 별도 `native_evaluation` 모듈 |
| 새 통합 결과 | `native-evaluation-result-v1`과 `NativeEvaluationResult`를 명시적으로 사용 |

기존 Sift 0~1 점수와 자체 행동 0~100 점수는 단위와 의미가 다르다. 단순 나눗셈으로 호환
결과를 만들거나 자체 점수를 Sift 점수/사기 확률로 표시하지 않는다. 기존 소비자가
`FraudDecision`을 받는다면 새 계약을 명시적으로 지원해야 하며 이름만 바꿔 대체할 수 없다.

새 결과의 `NO_ADDITIONAL_REVIEW`는 FDS 관점의 추가 확인 권고가 없다는 의미다. 사용자 승인,
예산·주문 유효성, 추가 인증, 결제 실행 권한을 부여하지 않는다. 기존 계약의 ALLOW/BLOCK
표기를 새 경로로 자동 변환하지 않으며 실제 집행은 BE가 담당한다.

## 직접 검증 — S15P21A207-291

가상환경 활성화 후 `ai/`에서 `python -m pytest`로 실행한다. 파일을 `python 파일명.py`로
직접 실행하면 pytest가 테스트를 수집하지 않는다.

| 완료 기준 | 검증 경로 | 예상 |
| --- | --- | --- |
| 1. 기존 정책 결정표·실패 우선순위 | `test_policy.py` | 25 PASSED |
| 2. 점수·기여도·단계·원본/버전 추적 | `test_policy.py` | 동일 25 PASSED에 포함 |
| 3. Sift 없는 호출·기존 공개 계약 | `test_entrypoint.py` 및 아래 회귀 명령 | 신규 20 PASSED, 회귀 전부 통과 |
| 4. 미완성/실패·예산·취소·권한 분리·JSON | 두 파일 전체 | 45 PASSED |

```bash
python -m pytest tests/learning/personalization/replay/engine/analysis/finalization/test_policy.py -v --tb=short
python -m pytest tests/learning/personalization/replay/engine/analysis/finalization/test_entrypoint.py -v --tb=short
```

두 파일을 한 번에 확인하려면 다음 명령만 실행해도 된다.

```bash
python -m pytest tests/learning/personalization/replay/engine/analysis/finalization -v --tb=short
```

정상 요청은 행동·추출·비교·정책 각 1회, 추출 실패는 비교 API 0회, 텍스트 미수집은 GPT 0회다.
원래 요청과 프로필은 변경되지 않아야 한다. 정책 계산/검증 중 시간이 소진되면 원래 정책
결과가 있어도 `outcome.status=FAILED`, `risk_level=UNKNOWN`, `recommendation=None`이어야 한다.
취소는 예외로 전파되고 앞 단계의 결과는 유지되어야 한다.

기존 GPT·통합 계약·위험 정책·공개 계약·평가 CLI 회귀:

```bash
python -m pytest tests/analysis tests/learning/personalization/replay/engine tests/learning/personalization/evaluation/risk tests/contract tests/test_input_contracts.py tests/test_result_contracts.py tests/test_decision_contracts.py tests/test_behavior_contracts.py tests/evaluation -q --tb=short
```

테스트는 합성 데이터로 실행하며 실제 GMS를 호출하지 않는다. 실제 GMS 검증이 필요하면 먼저
사용자에게 요청한다. 결과 재사용 조건 검증과 통합 CLI는 후속 Task에서 제공한다.
