# 선택된 개인 점수 계산

`evaluate_personal_components(supplied, policies)`는 Feature별 충분도·단계 정책이 선택한
최근 또는 장기 창만 기존 금액·빈도·시간대 계산기에 전달한다. 두 창을 계산한 뒤 낮은 점수를
고르거나 실패 후 다른 창으로 바꾸지 않는다. 기존 두 창 후보 API도 그대로 사용할 수 있다.

## 설정과 척도

`PersonalScoringPolicies`는 금액·빈도·시간대의 실제 계산 설정을 담는다. 결과에 설정 전체와
해시를 보존하므로 기본값 변경이나 사용자 지정 설정도 추적할 수 있다.

| 설정 | 지원 버전 |
| --- | --- |
| `config.score_scale_version` | `behavior-deviation-blend-v1` |
| `config.transition_policy_version` | `feature-readiness-min-v1` |
| 금액 `score_version` | `personal-amount-upper-linear-v1` |
| 구매/시도 빈도 `score_version` | `personal-frequency-upper-linear-v1` |
| 시간대/요일 `score_version` | `personal-time-circular-rarity-v1` |

지원하지 않는 설정은 Cold 전용으로 끝나는 입력에서도 계산 전에 거절한다. 잘못된 설정은
데이터 부족이나 Feature 계산 실패로 바꾸지 않는다. 충분도 임계값에는 운영 기본값이 없으며
호출자가 명시해야 한다. 계산 설정의 기본값은 이전 개별 계산기와 같다.

`behavior-deviation-blend-v1`은 같은 Feature의 기준 이탈 강도를 결합하는 **검증 전 설계 정책**이다.
0은 해당 기준에서 이탈이 없음을 뜻한다. 금액·빈도 100은 설정된 상방 포화 거리에 도달했다는
뜻이고, 시간 희소성은 가장 흔한 구간 대비 질량 감소를 나타낸다. 같은 50점이어도 동일한
빈도·분위수·사기 확률이라는 뜻은 아니다. 특히 시간의 거리와 희소성은 통계적으로 동등하지
않다. 이 조합을 명시적으로 허용한 정책 버전과 `UNVALIDATED` 상태를 기록한다.

## 선택 계산과 미실행

| 조건 | 개인 컴포넌트 |
| --- | --- |
| 현재 입력 미수집/이력 부족/적용 불가 | 입력의 상태와 사유 유지, 점수 null |
| 개인 프로필 미제공/만료 | INSUFFICIENT_DATA, PROFILE_NOT_SUPPLIED/PROFILE_EXPIRED, 점수 null |
| 충분도 0으로 선택 창 없음 | NOT_RUN, PERSONAL_SUPPORT_ZERO, 점수 null |
| 선택 창 있음 | 해당 창의 기존 점수·계산 근거·진단을 보존 |
| 선택 창 계산 중 ArithmeticError/ValueError 또는 비정상 점수 | FAILED, CALCULATION_FAILED, 점수 null |

충분도가 0인 이유는 `transition.readiness.features[*].windows`에서 표본·관측 기간·최근성·
가용 비율과 수집 범위로 확인한다. 계산을 실행하지 않은 상태를 정상 0점으로 채우지 않는다.
개인 비중이 일부이고 Cold가 없는 경우에도 개인 후보는 진단용으로 계산할 수 있지만,
후속 평가기는 그 점수를 최종 결과로 승격하지 않는다.

출력의 `candidate`에는 선택 창, 원본 분포, 실제 계산 근거가 있다. 실패 시 적용을 시도한
개인 프로필 참조를 남기고 계산 수치는 null로 둔다. 금액·빈도·시간대 계산기는 반환 직전에
점수 범위를 다시 검사하여 NaN·무한대·범위 밖 값을 다른 Feature에 전파하지 않는다.

```python
from copenhagen_fds.personal_components import evaluate_personal_components
from copenhagen_fds.contracts.personal_components import PersonalComponentsResult, PersonalScoringPolicies

policies = PersonalScoringPolicies(frequency={"spread_multiplier": 4.0})
components = evaluate_personal_components(supplied, policies)
restored = PersonalComponentsResult.model_validate_json(components.model_dump_json())
restored.validate_for_input(supplied)
```

동일 입력·시각·설정·계산 동작이면 재현된다. 실패 기록을 정상 실행 환경에서 재계산하면
성공으로 달라질 수 있으므로 재계산 일치 검증과 당시 실패 기록의 보관을 구분한다.
학습 상태·DB·외부 API·시스템 시각을 변경하거나 조회하지 않는다.

## 직접 검증

가상환경을 활성화한 `ai/`에서 실행한다.

```bash
python -m pytest tests/learning/personalization/evaluation/test_components.py -v --tb=short
```

예상 결과는 **20 passed**다. 기존 계산기와 동일한 후보, 선택 창만 실행, 미실행과 누락,
설정 검증·정책 해시·재현성을 확인한다. 점수 혼합은 [개인화 평가 실행](personal-evaluation.md)에서 다룬다.
