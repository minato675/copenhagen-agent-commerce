# GPT 결과의 통합 정책 연결

`project_engine_intent(record)`는 [두 단계 분석 기록](engine-analysis.md)에서 정책에 전달할
결과를 선택한다. `EngineIntentEvidence`는 원본 전체 분석과 선택된 결과를 함께 보존한다.

| 분석 결과 | source_stage | signal | failure_stage |
| --- | --- | --- | --- |
| 추출·비교 성공 | COMPARISON | MATCH / MISMATCH / UNKNOWN | null |
| 텍스트 미수집 | EXTRACTION | NOT_RUN | null |
| 추출 실패, 비교 미실행 | EXTRACTION | FAILED | EXTRACTION |
| 추출 성공, 비교 실패 | COMPARISON | FAILED | COMPARISON |

`intent`는 기존 `IntentComparisonResult` 계약을 사용한다. 추출 실패·미수집이면 추출의
상태·메타데이터·사유·실패 상세·측정값으로 내용 없는 정책용 결과를 만든다. 비교가
`NOT_RUN`이라는 이유로 최초 실패를 덮어쓰지 않는다. 비교 성공·실패이면 비교 결과 전체를
그대로 전달한다. 모델/프롬프트, 실패 종류·HTTP 분류·재시도 가능 여부·스키마 진단도 유지한다.

추출의 확인 사항은 `analysis.analysis.extraction.clarifications`, 비교의 항목별 근거와
확인 요청은 `intent.payload.findings`와 `intent.payload.confirmation_requests`에 있다.
추출의 확인 사항이 중요한 경우 정책용 `intent`만 저장하지 말고 전체 evidence를 보관한다.
GPT 신호를 숫자 점수나 확률로 변환하지 않는다.

## 기존 위험 정책 입력 만들기

```python
from datetime import datetime, timezone

from copenhagen_fds.engine_intent import project_engine_intent, build_engine_intent_policy_input
from copenhagen_fds.risk_evaluation import evaluate_risk_policy

# analysis_record: evaluate_engine_analysis의 결과
# behavior_record: 같은 통합 요청으로 evaluate_engine_behavior를 실행한 결과
evidence = project_engine_intent(analysis_record)
supplied = build_engine_intent_policy_input(
    evidence,
    scoring_input=behavior_record.scoring_input,
    behavior=behavior_record.evaluation,
    policy_evaluated_at=datetime.now(timezone.utc),
)
decision = evaluate_risk_policy(supplied)
```

연결 함수는 입력만 구성한다. 모델·행동 점수·위험 정책을 실행하지 않는다. 정책 판단은
예제 마지막 줄처럼 기존 함수로 별도 실행한다. 행동 평가 자체를 실행하지 못한 경우에는
정확한 `scoring_input`이 있을 때만 `behavior=None`을 전달할 수 있다. 정상 점수를 만들지 않는다.

규칙에 사용하는 `supplied.request.intent`는 BE가 제공한 원래 의도다. 추출 결과로 이 필드나
주문 내용을 덮어쓰지 않는다. 점수 입력·설정·제공된 프로필과 정책 버전은 동일한 통합 요청에서
구성한 것인지 검증하며, 이미 계산한 행동 결과를 다시 계산하지 않는다.

정책 판단 시각은 분석 전체 완료 이후여야 한다. 추출과 비교 각각의 완료 시각에 대해
`intent_max_age_seconds`의 상한을 포함하지 않는 유효 기간과 `valid_until`을 확인한다.
새로운 비교 결과로 오래되거나 만료된 추출 결과를 되살릴 수 없다. 행동 기준 시각은 그대로다.
JSON 복원이나 입력 연결은 원본 근거를 검증할 뿐, 캐시 재사용이나 결제 승인을 허가하지 않는다.

`evidence.validate_for_input(supplied)`로 연결 관계를 다시 검사할 수 있다. 정책의 기존
`interpret_risk_intent` 및 `evaluate_risk_intent_policy`는 다섯 신호와 상세 실패를 그대로
해석한다. 전체 엔진 결과에는 원본 `analysis_record.analysis`와 실제 단계 실행 기록도 남겨야
최초 실패 단계와 추출 확인 근거를 보존할 수 있다.

## 직접 검증 — S15P21A207-289

가상환경 활성화 후 `ai/`에서 실행한다. 아래 테스트는 실제 GMS 호출이 없다.

```bash
# 완료 기준 1·2·3: 다섯 신호, 최초 실패·확인 요청 보존, 대상·정책 시각 연결
python -m pytest tests/learning/personalization/replay/engine/analysis/test_policy.py -v --tb=short

# 완료 기준 2·3·4: 기존 실행기 연결과 키 없는 Mock/전송 Mock
python -m pytest tests/learning/personalization/replay/engine/analysis/test_execution.py -v --tb=short

# 완료 기준 4: 기존 GPT·행동 연결·통합 계약·위험 정책 회귀
python -m pytest tests/analysis tests/learning/personalization/replay/engine tests/learning/personalization/evaluation/risk -q --tb=short
```

예상 결과는 전부 `PASSED`다. 정책의 의도 단계는 MATCH/MISMATCH에서 `SUCCESS`, 필수 신호의
UNKNOWN/NOT_RUN에서 `INCOMPLETE`, 추출·비교 실패에서 `FAILED`다. 별도 행동 실패나 자료 부족도
최종 결정에 보존되므로 의도 단계의 `SUCCESS`를 전체 정상 판단으로 해석하면 안 된다.

합성 응답과 전송 Mock은 호출·계약·정책 연결을 재현하는 검증이다. 실제 모델의 의미 정확도와
실거래 사기 탐지 성능은 별도 평가가 필요하다. 사용자 직접 검증은 자동 테스트와 구분하며,
Jira와 MR 테스트 체크박스는 사용자가 체크한다.
