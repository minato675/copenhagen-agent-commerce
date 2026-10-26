# 개인화 기준 선택과 단계 전환

`S15P21A207-268`의 `decide_personal_transition(PersonalizedScoringInput)`은
[Feature별 충분도](personal-readiness.md)를 구한 뒤 각 Feature에 사용할 개인 통계 창과
Cold·개인 기준의 반영 비중을 결정한다. 반환형은 `PersonalTransitionResult`다.

이 출력은 **점수 계산 성공을 전제로 한 기준 사용 정책**이다. 점수를 계산·혼합하거나
최종 `BehaviorEvaluationResult`를 생성하지 않는다. 실제 계산 실패 시 대체 처리와
최종 적용 비중·점수 조립은 [Task 269 개인화 평가 실행](personal-evaluation.md)에서 수행한다.

## 최근·장기 선택

개인 비중이 높은 통계 창을 선택한다. 같으면 최근 창을 선택하고 `RECENT_TIE`를 기록한다.
양쪽 모두 0이면 선택 창은 null이다. 점수가 낮게 나오는 창이나 정답 라벨을 기준으로
선택하지 않는다. 최근 창에 표본이 부족하면 장기 창을 사용할 수 있고, 장기 창의
가용 비율이 더 낮으면 최근 창을 사용할 수 있다. 이 선택은 평가마다 입력에서 결정되며
이전 평가의 단계 상태를 DB에 저장하거나 별도 히스테리시스를 적용하지 않는다.

## 기준 조합 결정표

| 현재 입력 | 개인 충분도 w | 유효 Cold 기준 | 단계 | 개인/Cold 비중 |
| --- | --- | --- | --- | --- |
| 미산출 | 무관 | 무관 | UNAVAILABLE | null / null |
| 가용 | 0 | 있음 | COLD | 0 / 1 |
| 가용 | 0 | 없음 | UNAVAILABLE | null / null |
| 가용 | 0보다 크고 1보다 작음 | 있음 | TRANSITION | w / (1-w) |
| 가용 | 0보다 크고 1보다 작음 | 없음 | UNAVAILABLE | null / null |
| 가용 | 1 | 무관 | PERSONALIZED | 1 / 0 |

부분 개인 이력에 필요한 Cold 기준이 없다고 개인 비중을 1로 재정규화하지 않는다.
미제공·만료·관측 부족으로 개인 비중이 0으로 줄어들면 기존 선택기의 유효한 집단 기준이나
초기 정책을 사용한다. 두 기준이 모두 없거나 현재 Feature가 미산출이면 비중도 null이다.
사용하지 않는 개인 값을 정상 점수 0으로 채우는 정책이 아니다.

`preferred_personal_weight`와 `selected_window`는 개인 이력만으로 선택한 값이다.
현재 입력이 미산출이거나 전환에 필요한 Cold가 없으면 이 값은 남길 수 있지만,
`personal_weight`와 `cold_weight`는 null이다. 실제로 사용 가능한 조합과 개인 이력의
충분도를 구분하기 위한 필드다. 결과는 Cold의 선택 근거·유효 시각·진단과 두 창의
충분도 근거도 보존한다.

## 전환 예시

검증용으로 유효 표본 최소 2개·충분 4개를 설정하고 나머지 축이 모두 1인 경우,
유효한 Cold 기준이 있으면 2개에서 COLD(0), 3개에서 TRANSITION(0.5),
4개에서 PERSONALIZED(1)가 된다. 이 수치는 테스트용이며 운영 추천값이 아니다.

Feature별 관측이 다르면 같은 거래에서 금액은 PERSONALIZED, 구매 횟수는 TRANSITION,
시간대는 COLD가 될 수 있다. 신선/오래됨 경계 사이로 시간이 지나면 개인 비중이
줄어들고 오래됨 경계나 프로필 만료에서 0이 된다. 관측 기간이 부족하거나
가용 비율이 낮아지는 경우에도 해당 축이 개인 비중을 제한한다.

```python
from copenhagen_fds.personal_transition import decide_personal_transition
from copenhagen_fds.contracts.personal_transition import PersonalTransitionResult

decision = decide_personal_transition(supplied)
restored = PersonalTransitionResult.model_validate_json(decision.model_dump_json())
restored.validate_for_input(supplied)
```

같은 평가 시각·프로필·설정은 동일한 결정과 해시를 만든다. 시스템 시각, DB, 외부 API는
읽지 않으며 평가 도중 프로필을 갱신하지 않는다. 비중과 임계값은 버전 관리하는 설계
정책으로, 학습된 사기 확률이나 실거래 탐지 성능을 뜻하지 않는다.

## 완료 기준별 직접 검증

가상환경을 활성화한 `ai/` 위치에서 실행한다. Git Bash는 `source .venv/Scripts/activate`로
활성화한다. `python test_*.py`로 파일을 직접 실행하지 말고 `python -m pytest`를 사용한다.

| Task 268 완료 기준 | 실행 명령 | 예상 결과 |
| --- | --- | --- |
| 1. 단계 진행·비중 축소·기준 복귀·창 선택 | `python -m pytest tests/learning/personalization/readiness/test_transition.py -k "reference_combination or aging or expiry or window_selection" -v --tb=short` | 11 passed |
| 2. Feature별 관측 차이와 수집 범위 | `python -m pytest tests/learning/personalization/readiness -k "feature_observation or source_coverage or uncollected_or_partial or observed_zero" -v --tb=short` | 9 passed |
| 3. 축의 누락/0·경계·비중 범위 | `python -m pytest tests/learning/personalization/readiness/test_readiness.py -v --tb=short` | 45 passed |
| 3. 기준 조합별 비중 합·사용 조건 | `python -m pytest tests/learning/personalization/readiness/test_transition.py -k "reference_combination" -v --tb=short` | 6 passed |
| 4. 동일 입력 재현·JSON 복원·근거 변조 거절 | `python -m pytest tests/learning/personalization/readiness -k "roundtrip or detached" -v --tb=short` | 16 passed |

Task 전체: `python -m pytest tests/learning/personalization/readiness -v --tb=short` → **73 passed**.
전체 회귀: `python -m pytest -q --tb=short` → Task 268 완료 시점 **2092 passed**.
선택 명령은 서로 일부 테스트가 겹치므로 합산하지 않는다. `deselected`는 실패가 아니며,
예외 사례가 기대대로 거절되어도 PASSED다. 외부 API 키·서비스 DB 없이 실행한다.
