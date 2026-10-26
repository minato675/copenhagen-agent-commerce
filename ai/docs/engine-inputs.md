# 통합 평가 요청과 시각

`EngineEvaluationRequest`는 FDS 내부에서 사용할 입력 스냅샷이다. 생성과 검증은
GMS 호출·점수 계산·프로필 갱신을 실행하지 않는다. 외부 BE API 요청 규격과 구분한다.

| 필드 | 의미 |
| --- | --- |
| `trace_id` | 한 실행에서 행동·GPT·정책을 연결하는 식별자 |
| `feature_input` | 기존 `FeatureTransformInput`: 구조화된 구매 요청, 거래 발생 시각, 이력의 관측 범위와 행동 기준 시각 |
| `analysis` | 기존 `IntentAnalysisRequest`: 제공된 의도 원문과 동일한 주문·사용자·구매·버전 |
| `personal`, `population` | 제공자가 게시한 `PublishedProfileInput` 또는 `null`; 정책 상태와 게시 시각도 보존 |
| `population_baseline_request` | 집단 게시본에서 Cold 기준을 유도할 때의 원본 참조와 가용 시각; 집단 게시본과 함께 제공 |
| `initial_policy` | 명시적인 초기 Cold 정책 또는 `null`; 필드 자체는 필수 |
| `personalization`, `scoring` | 기존 단계 전환·Feature 점수 설정 |
| `risk_policy` | 최종 위험 정책. 생략하면 `fds-risk-policy-v2`; 명시하면 해당 정책을 보존 |
| `execution` | 게이트웨이, 요청 모델, 두 프롬프트 버전, 호출 제한 시간과 전체 시간 예산 |

[기본 위험 정책](default-risk-policy.md)은 최종 피처 100점에 MEDIUM 하한을 적용하고,
종합 점수 70점 이상일 때 HIGH를 반환한다. 요청 생성 시 정책 전체가 채워지므로 JSON과
해시에 실제 설정이 포함된다. `risk_policy=null`은 허용하지 않으며 기존 명시 정책은 유지한다.

게이트웨이는 실제 연결 `GMS` 또는 주입한 응답 `MOCK`이다. 기존 환경 설정의
`FDS_MODE=LIVE`와는 다른 필드다. 키는 실행 의존성에서 별도로 관리하며 이 스냅샷에
넣지 않는다. 제한 시간은 유한한 양수이고 개별 호출 한도는 전체 예산 이하여야 한다.
계약은 설정을 검증하고 기록하며 시간 제한의 실제 집행은 실행기의 책임이다.
지원하는 추출·비교 프롬프트, Feature·점수 척도·수식·충분도 버전을 검증한다.

행동과 GPT 입력은 참조 식별자뿐 아니라 주문·문맥 내용 전체가 같아야 한다.
의도 원문은 구조화된 의도와 동일한 출처·버전을 가져야 한다. 원문이 없으면 `text=null`,
미수집 이력은 기존 `NOT_COLLECTED`를 유지한다. 없는 원문이나 빈도를 추측해서 채우지 않는다.

## 행동 기준과 실제 실행 시각

행동 기준은 `feature_input.evaluation.evaluated_at`으로 고정한다. 거래의
`occurred_at`, 데이터의 `available_at`과 별개의 개념이다. 행동 빈도·시간대와 프로필
선택은 이 기준에서 계산한다. 개인 프로필은 사용자, 모든 게시본은 통화·스키마가 맞아야 하며,
프로필 `as_of < 행동 기준`, 게시 가용 시각은 `<= 행동 기준`이어야 한다.
현재 구매가 게시 상태의 관측 이력에 들어 있으면 거절한다.

만료된 게시본은 진단과 Cold 대체 판단을 위해 제공할 수 있다. 입력에서 이를 새 프로필로
바꾸거나 유효 기간을 연장하지 않는다. 기존 개인화 평가 계약은 만료된 프로필을 실제 개인
점수의 근거로 사용하는 것을 거절한다. `feature_input`에 별도 프로필 메타데이터를 적었다면
그 계약의 유효성 검사도 적용되며 제공한 게시본과 내용이 일치해야 한다.

실행 시각은 다음과 같이 구분한다.

| 예시 시각 | 사용처 |
| --- | --- |
| 14:00:00 | 고정된 행동 기준과 실행 시작 |
| 14:00:02 | GPT 응답의 실제 완료 시각 |
| 14:00:03 | `RiskPolicyInput.policy_evaluated_at`: 최종 정책 계산을 시작한 시각 |
| 14:00:03 이후 | 전체 결과의 실행 완료 시각 |

`RiskPolicyInput.evaluated_at`은 계속 행동 기준이다. 별도 정책 시각을 제공하면
GPT의 미래 여부·최대 나이·만료를 그 정책 시각에서 확인한다. 위 예시 응답은 허용되지만,
정책 시각보다 미래인 응답, `age >= intent_max_age_seconds`, `valid_until <= 정책 시각`은
거절한다. 정책 시각이 행동 기준보다 빠른 경우도 거절한다.

정책 시각을 생략하거나 `null`로 주면 기존처럼 행동 기준에서 검증하며 JSON과 해시에도
새 필드를 추가하지 않는다. 명시하면 입력과 `RiskInputReference`에 함께 기록한다.
이 필드는 과거 점수의 재사용이나 미래 프로필 사용을 허용하지 않는다.
다른 행동 기준의 평가는 새 입력이 필요하다.

## 원본 참조와 진단

```python
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference

# payload는 위 기존 계약들로 구성한 제공자 소유의 스냅샷이다.
request = EngineEvaluationRequest.model_validate(payload)
reference = EngineRequestReference.from_request(request)
assert EngineEvaluationRequest.model_validate_json(request.model_dump_json()) == request
```

참조에는 전체 요청과 의도·이력·게시본·설정별 SHA-256을 남긴다. 버전 이름이 그대로여도
실제 내용이 바뀌면 구분하며 동일 시각의 시간대 표기는 UTC로 정규화한다. 해시는 입력의
연결 근거이고 원천 데이터의 진위나 사용자 승인을 인증하지 않는다.

오류 문자열은 입력값을 숨긴다. 구조화된 오류를 기록할 때도
`ValidationError.errors(include_input=False, include_context=False, include_url=False)`를 사용한다.
전체 `model_dump()`에는 제공된 원문·주문·이력이 있으므로 일반 로그에 그대로 출력하지 않는다.

## 직접 검증 — S15P21A207-287, 완료 기준 1·2·4

가상환경을 활성화하고 `ai/`에서 실행한다.

```bash
python -m pytest tests/learning/personalization/replay/engine/test_inputs.py -v --tb=short
python -m pytest tests/learning/personalization/evaluation/risk/test_engine_time.py -v --tb=short
```

첫 명령은 대상·내용·프로필·집단 기준·버전·타입·설정·원문 비노출을,
둘째 명령은 기존 JSON/해시 호환성과 별도 정책 시각의 미래·만료 경계를 검증한다.
예상 결과는 모두 `PASSED`다. 오류를 의도적으로 주는 테스트도 정확하게 거절되면 통과한다.
`python test_inputs.py` 대신 반드시 `python -m pytest`를 사용한다.
