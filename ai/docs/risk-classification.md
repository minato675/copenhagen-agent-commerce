# FDS 위험 등급과 결정 우선순위

`classify_risk(supplied)`는 기존 행동 평가·그룹 가중합·보완 규칙·GPT 정책 신호를 사용해 최종 위험 수준을 결정한다. `supplied`는 이미 계산된 행동 결과와 GPT 비교 결과를 가진 `RiskPolicyInput`이다. 실제 모델 호출이나 Feature 재계산은 하지 않는다.

```python
from copenhagen_fds.risk_classification import classify_risk

result = classify_risk(supplied)
print(result.status, result.threshold_risk, result.risk_level)
result.validate_for_input(supplied)
```

## 우선순위

| 순위 | 조건 | status | risk_level |
| --- | --- | --- | --- |
| 1 | 행동 또는 GPT 실행 실패 | FAILED | UNKNOWN |
| 2 | 행동 점수가 하나라도 없거나 행동 미실행 | INCOMPLETE | UNKNOWN |
| 2 | 필수 GPT의 UNKNOWN 또는 NOT_RUN | INCOMPLETE | UNKNOWN |
| 3 | 위 조건 없이 평가 가능 | SUCCESS | 점수 기준 등급과 발동한 규칙 하한 중 높은 등급 |

점수 기준 등급은 `score >= high_from`이면 HIGH, 그 외 `score >= medium_from`이면 MEDIUM, 나머지는 LOW다. 임계값과 정확히 같은 값은 높은 쪽 구간에 포함한다. 분류 전에 점수를 반올림하지 않는다. 임계값은 `RiskPolicyConfig`에 명시한다.

`threshold_risk`는 종합 행동 점수를 임계값과 비교한 산술 근거다. 유효 점수가 남아 있는 실패 결과에서는 이 값이 MEDIUM/HIGH여도 최종 `risk_level`은 UNKNOWN이다. 점수가 없으면 `threshold_risk`도 UNKNOWN이다. 원본 점수·규칙 하한·실패·부족 자료는 `evidence`에 보존한다.

선택 Feature 하나가 빠지면 필수 자료 범위가 FULL이어도 전체 점수는 없으므로 INCOMPLETE다. 행동과 GPT가 모두 미실행이어도 이 정책 함수를 실행했다면 INCOMPLETE다. NOT_RUN은 정책 자체를 실행하지 않은 기존 결과 계약의 상태이며 이 함수가 반환하는 상태가 아니다.

GPT의 MISMATCH는 확정된 의미 불일치이며 행동 점수에 수치를 더하지 않는다. 행동 LOW와 GPT 불일치가 함께 존재할 수 있으므로 후속 사유·확인 권고까지 함께 읽어야 한다. 선택 GPT UNKNOWN/NOT_RUN은 행동 분류를 허용하지만 원래 신호와 확인 권고는 남는다. 선택 GPT라도 실행 실패는 FAILED다. MATCH가 행동 위험 또는 보완 규칙의 하한을 낮추는 일은 없다.

## 재현 예시

다음 수치는 비중 0.4/0.35/0.25, 임계값 30/70과 사용자 지정 HIGH 하한을 포함한 테스트 정책의 예시다.
[프로젝트 기본 정책](default-risk-policy.md)은 피처 100점에 MEDIUM 하한만 적용한다.
아래 금액 단독·두 그룹 동시 HIGH 하한은 명시적으로 공급한 구 정책의 호환 검증이며 기본 설정이 아니다.
어느 예시도 실거래 라벨로 검증한 사기 확률을 뜻하지 않는다.

| 사례 | 행동 점수 | 점수 기준 등급 | 규칙 하한 | 최종 상태 / 위험 |
| --- | --- | --- | --- | --- |
| 정상·GPT MATCH | 0 | LOW | 없음 | SUCCESS / LOW |
| 빈도 이상 | 35 | MEDIUM | 없음 | SUCCESS / MEDIUM |
| 금액 100점, 금액 90점 이상 HIGH 규칙 | 40 | MEDIUM | HIGH | SUCCESS / HIGH |
| 금액·빈도 각각 60점, 두 그룹 동시 조건 | 45 | MEDIUM | HIGH | SUCCESS / HIGH |
| 강한 행동 이상·GPT MATCH | 75 | HIGH | HIGH | SUCCESS / HIGH |
| 강한 행동 이상·필수 GPT UNKNOWN | 75 | HIGH | HIGH | INCOMPLETE / UNKNOWN |
| 개인 금액 실패 후 Cold 복귀, 시간 기여도 2.5점 유지 | 42.5 | MEDIUM | HIGH | FAILED / UNKNOWN |

## 직접 검증

저장소의 `ai` 디렉터리에서 실행한다. 예상 결과는 **33 passed**다.

```sh
./.venv/Scripts/python.exe -m pytest tests/learning/personalization/evaluation/risk/test_classification.py -v --tb=short
```

`-k threshold_boundaries`를 추가하면 임계값의 바로 위와 정확한 경계 4개를 확인한다. `-k 'failure_and_missing or never_discounts'`는 실패/부족 우선순위와 MATCH를 포함한 GPT 상태의 위험 상쇄 금지를 확인한다. `-k classification_replay`는 JSON 왕복·동일 입력 재실행과 결과 변조 거절을 확인한다.
