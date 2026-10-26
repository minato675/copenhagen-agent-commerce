# 기본 FDS 위험 정책

`EngineEvaluationRequest`에서 `risk_policy`를 생략하면 `fds-risk-policy-v2`를 적용한다.
요청을 만드는 시점에 완전한 정책 객체가 채워지며 요청 JSON, 정책 내용 해시, 최종 결과에
실제로 사용한 설정을 기록한다. 실행 도중 환경 변수나 전역 상태에서 정책을 다시 선택하지 않는다.

| 설정 | 기본값 |
| --- | --- |
| 금액 / 빈도 / 시간 가중치 | 0.40 / 0.35 / 0.25 |
| 그룹 대표값 | MAX |
| 종합 점수 기준 | 30 이상 MEDIUM, 70 이상 HIGH |
| 단일 피처 보완 규칙 | 최종 점수 100이면 최소 MEDIUM |
| 금액만으로 HIGH를 만드는 규칙 | 없음 |
| GPT 결과 | 필수, 유효 나이 300초 미만 |
| 실패·자료 부족 | 기존 UNKNOWN 우선순위 유지 |

보완 규칙은 Cold와 개인 점수를 혼합한 **최종 피처 점수**에 적용한다. 금액, 구매·시도 빈도
각각 10분/1시간/24시간, 한국 시간대·요일의 총 9개가 대상이다. 그룹별 MAX가 100이면
그 그룹의 피처 하나 이상이 100이므로 세 그룹에 각각 MEDIUM 하한 규칙을 둔다.
다른 피처가 누락되어 평가가 불완전하거나 실행 실패가 있으면 확정 MEDIUM으로 바꾸지 않는다.

원래 점수는 보존한다. 예를 들어 시간 피처가 100이고 나머지가 0이면 종합 점수는 25이고,
결과는 `25점 / MEDIUM / REVIEW_ORDER`이며 `maximum-time` 규칙 근거가 남는다.
금액만 100이면 `40점 / MEDIUM`이다. 한 그룹만으로는 최대 40점이므로 HIGH에 도달하지 못한다.
여러 그룹의 가중합이 70점 이상일 때는 `HIGH / REQUIRE_AUTH`를 유지한다.
HIGH는 확정 사기나 결제 차단 명령을 뜻하지 않는다.

## 기존 요청과 명시적인 정책

기존 요청에 `risk_policy`가 있으면 해당 설정을 그대로 검증·사용한다. 과거 금액 HIGH 규칙도
자동 삭제하지 않으며 저장된 요청·결과는 원래 정책으로 재현할 수 있다. `null`이나 잘못된
설정을 새 기본값으로 대체하지 않는다. 새 정책을 쓰려면 요청 생성 시 필드를 생략하거나
다음과 같이 명시한다.

```python
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.risk_settings import default_risk_policy

# payload: 호출자가 구성한 통합 평가 입력 dict
payload["risk_policy"] = default_risk_policy()
request = EngineEvaluationRequest.model_validate(payload)
assert request.risk_policy.version == "fds-risk-policy-v2"
```

`RiskPolicyInput`을 직접 만드는 호출자는 `policy=default_risk_policy()`를 제공한다.
`RiskPolicyConfig` 자체는 사용자 지정 정책의 가중치·임계값을 계속 명시적으로 요구한다.
`FDS_POLICY_VERSION` 문자열만 바꿔서는 위험 정책 객체가 교체되지 않는다.
구 정책으로 얻은 결과를 새 정책 결과처럼 재사용하지 말고 새 요청으로 평가한다.
기존 결과 연결 검사는 정책 변경을 `RISK_POLICY_CHANGED`로 구분한다.

기본값 채택은 실거래 성능 검증을 뜻하지 않으므로 `validation_status=UNVALIDATED`를 유지한다.
정상 거래에서도 여러 행동 축이 동시에 크게 달라지면 HIGH가 나올 수 있다.
또한 금액 단독 사기의 권고도 HIGH에서 MEDIUM으로 바뀔 수 있어 검토·인증의 실제 효과는
BE 집행 단계에서 별도로 검증해야 한다.

## 직접 검증

`ai` 폴더에서 가상환경 Python으로 실행한다. GMS 키나 외부 API 호출은 필요 없다.

```sh
./.venv/Scripts/python.exe -m pytest tests/learning/personalization/evaluation/risk/test_default_policy.py tests/learning/personalization/replay/engine/analysis/finalization/test_default_policy.py -v --tb=short
```

첫 파일은 9개 피처의 100점 경계, 금액 단독 MEDIUM, 복합 이상 HIGH, 실패·자료 부족 우선순위를
검증한다. 두 번째 파일은 기본 설정 자동 적용, 기존 정책 보존, JSON·해시 고정과 실제 통합
진입점의 권고를 검증한다. 두 파일을 함께 실행하면 `45 passed`가 예상 결과다.
