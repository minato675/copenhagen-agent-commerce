# 강한 단일 신호와 동시 이상 규칙

`evaluate_risk_rule(rule, groups)`는 기존 그룹 점수에 `RiskFloorRule`의 조건을 적용한다.
가중합에 점수를 더하거나 Feature를 다시 계산하지 않는다. 개별 규칙의 관측 상태와
임계값 충족 여부를 반환하며 여러 규칙의 우선순위와 최종 위험 분류는 별도 책임이다.

```python
from copenhagen_fds.contracts.risk_policy import RiskFloorRule
from copenhagen_fds.risk_rule_checks import evaluate_risk_rule

rule = RiskFloorRule(rule_id="amount-and-frequency", minimum_risk="HIGH",
    conditions=[dict(group_id="AMOUNT", minimum_score=60.0),
                dict(group_id="FREQUENCY", minimum_score=60.0)])
check = evaluate_risk_rule(rule, aggregation.groups)
```

## 비교와 관측 조건

- 단일 조건은 강한 그룹 신호, 둘 이상의 조건은 서로 다른 그룹의 동시 이상이다.
- 모든 조건은 AND로 연결하고 `그룹 점수 >= minimum_score`로 비교한다. 임계값은
  0보다 크고 100 이하이며, 임계값과 같은 점수도 조건을 만족한다. 비교 전에 반올림하지 않는다.
- 조건 그룹은 금액·빈도·시간의 고정 순서를 따르고 중복 그룹은 거절한다. 원본 그룹 결과는
  세 그룹을 각각 한 번 포함해야 하며 입력 배열 순서는 결과를 바꾸지 않는다.
- 규칙에 사용되는 **각 그룹의 모든 Feature 점수**가 있어야 판단한다. 단일 금액 규칙은
  금액 점수 1개, 빈도 규칙은 빈도 점수 6개가 필요하다. 그룹 일부의 큰 점수만으로
  불완전한 그룹을 확정하지 않는다. 규칙 밖 그룹의 결측은 이 규칙의 관측 조건에 포함하지 않는다.

| 관측 상태 | 결과 |
| --- | --- |
| 모든 조건 그룹이 관측되고 모두 임계값 이상 | MATCHED |
| 모든 조건 그룹이 관측되고 하나 이상 임계값 미만 | NOT_MET |
| 조건 그룹 중 하나 이상 점수 없음 | UNAVAILABLE |

결측과 임계값 미만이 함께 있으면 UNAVAILABLE로 남긴다. 이는 보수적인 관측 상태 표현이며
규칙이 충족되었다는 뜻이 아니다. 정상 0점은 관측된 값이므로 NOT_MET로 비교한다.

## 적용 단계와 한계

기존 `USE_FINAL_FEATURE_SCORE` 정책에 따라 COLD·TRANSITION·PERSONALIZED에서 같은
임계값으로 최종 점수를 비교한다. 단계는 Feature마다 다를 수 있다. 원래 기준 선택·표본 수·
관측 기간·최근성·충분도·혼합 비중의 조건을 이미 통과해 생성된 점수를 사용하며, 규칙 단계에서
별도 최소 개인 이력을 요구하거나 충분도를 다시 곱하지 않는다. UNAVAILABLE 단계에는
유효한 점수가 없으므로 그 그룹이 필요한 규칙은 UNAVAILABLE다.

예시: 금액 100, 나머지 0이고 금액 비중이 0.1이면 종합 점수는 10이다. 금액 90 이상에
HIGH 하한을 주는 규칙을 설정하면 점수 10을 보존한 채 별도 HIGH 하한 근거를 얻는다.
금액·빈도가 각각 60 이상일 때만 적용하는 동시 이상 규칙도 명시적으로 설정할 수 있다.

수치와 규칙은 버전이 지정된 `RiskPolicyConfig.floor_rules`로 공급한다. 규칙을 자동 생성하거나
예시 임계값을 숨겨진 기본값으로 활성화하지 않는다. 이는 수작업 초기 정책이고 실거래 라벨로
학습·검증한 최적 임계값이나 확정 사기 판정이 아니다.
