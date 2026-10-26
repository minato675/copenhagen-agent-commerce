# 개인·Cold 점수 혼합 평가

`evaluate_personalized_features(PersonalizedScoringInput, PersonalScoringPolicies)`는 기존 Cold
평가와 [선택된 개인 점수](personal-components.md)를 연결한다. 반환형은
`PersonalizedEvaluationResult`다. Feature 간 가중합·GPT 결합·위험등급·결제 차단을 수행하지 않는다.

## 출력에서 근거를 찾는 경로

| 확인할 내용 | 결과 경로 |
| --- | --- |
| 원시 값·단위·평가 대상·시각 | `result.cold.raw_features` |
| Cold 기준·출처·파라미터·충분도 | `result.cold.selection`, `result.cold.sufficiency` |
| 개인 프로필·공개/정책 버전·가용 시각 | `result.profiles` |
| 최근/장기 충분도 축과 선택 이유·권장 비중 | `components.transition` |
| 실제 개인 수식 설정·해시 | `components.policies`, `components.policies_sha256` |
| 선택 창의 점수·계산 근거·진단 | `components.features[*].candidate` |
| 두 점수의 수식 버전·방향·혼합 허용 규칙 | `scale_agreements` |
| 개인/Cold 점수·실제 적용 비중·단계·최종 Feature 점수 | `result.features` |
| 공통 행동 평가 결과·실행 상태·점수 제공 범위 | `result.behavior` |

모든 배열은 요청된 Feature만 공통 스키마 순서로 반환한다. 입력의 순서로 Feature가 서로
어긋나지 않는다. 현재 구매나 미래 정보가 포함된 프로필, 다른 이용자·버전·통화는 입력에서
거절한다. 평가는 `published`를 읽으며 프로필/정책 상태를 갱신하지 않는다.

## 혼합 조건

금액·빈도의 개인 점수는 상방 이탈이다. Cold도 `UPPER_DISTANCE`일 때만 부분 혼합한다.
Cold가 `ABSOLUTE_DISTANCE`이면 하방 이탈 의미가 달라 부분 혼합을 거절하고 유효한 Cold를
그대로 적용한다. `INCOMPATIBLE_SCALE_COLD_FALLBACK`을 남긴다. 개인 충분도가 1이면 Cold를
혼합하지 않으므로 개인 점수만 적용할 수 있다.

시간대·요일은 Cold `CIRCULAR_DISTANCE`와 개인의 원형 분포 희소성을 결합한다. 이는
`CIRCULAR_DISTANCE_RARITY_HEURISTIC`이라는 명시적 설계 선택이다. 같은 0~100 범위만으로
통계적 척도 동등성을 주장하지 않으며, 오탐률·탐지율·가중치 적합성은 별도 평가가 필요하다.

실제 개인 비중을 w라 하면 `final = (1-w) * Cold + w * personal`이다. 사용 비중이 0인
컴포넌트는 계산에서 제외하며 없는 점수를 0으로 대체하지 않는다. 사용 기준은 양수 비중인
컴포넌트의 기준만 Cold→개인 순서로 보존한다. 미세한 양수의 언더플로와 100 초과 반올림
처리는 기존 [결과 계약](personalization-results.md)을 따른다.

테스트용 설정에서 Cold 금액 0점·개인 금액 100점이면, 표본 2/3/4개와 나머지 충분도 축 1에서
w=0/0.5/1, 최종 점수=0/50/100이다. 이 표본 임계값은 운영 권장치가 아니다.

## 계산 실패와 부족한 기준

| 조건 | 실제 적용 |
| --- | --- |
| 현재 Feature 입력 미산출 | 동일 상태·사유 유지, 최종 점수/비중 null |
| 개인 미제공·만료·충분도 0, Cold 성공 | Cold 그대로, 개인 비중 0 |
| 개인 성공·충분도 일부, Cold 성공·척도 허용 | 결정된 비중으로 혼합 |
| 개인 성공·충분도 일부, Cold 없음/실패 | 최종 점수/비중 null; 개인 100% 승격 금지 |
| 개인 성공·충분도 1 | 개인만 적용; Cold 실패가 있었다면 전체 실행 FAILED 유지 |
| 개인 실패, Cold 성공 | Cold로 복귀, 개인 실패·시도한 프로필 보존, 전체 실행 FAILED |
| 계산 실패가 있고 어느 점수도 적용 불가 | 최종 FAILED, 점수/비중 null |
| 필요한 기준만 부족하고 계산 실패 없음 | INSUFFICIENT_DATA, 점수/비중 null |

Cold는 기존 결과와 비교·진단할 수 있도록 모든 요청 Feature에 대해 기존 함수를 실행한다.
개인은 선택된 창만 실행한다. 한 Feature의 ArithmeticError/ValueError는 다른 Feature의
유효한 결과를 지우지 않는다. 지원하지 않는 설정·잘못 연결된 입력은 계산 전에 예외를 낸다.

`status=FAILED`와 `coverage=COMPLETE`는 동시에 가능하다. 예를 들어 개인 금액 계산이
실패했어도 유효한 Cold로 복귀하면 모든 점수는 존재하지만 실행 실패 사실은 남는다.
부분 실패 결과를 성공으로 바꾸어 직렬화하면 결과 계약이 거절한다. `COMPLETE`는 데이터
충분도나 탐지 정확도의 보장이 아니다. 실패 원문의 예외 문자열이나 민감정보는 반환하지 않는다.

## 사용과 재현

`supplied`는 유효한 개인화 입력이며 지원 버전·충분도 임계값을 명시해야 한다.

```python
from copenhagen_fds.personal_evaluation import evaluate_personalized_features
from copenhagen_fds.contracts.personal_evaluation import PersonalizedEvaluationResult

report = evaluate_personalized_features(supplied)
result_json = report.model_dump_json(indent=2)
restored = PersonalizedEvaluationResult.model_validate_json(result_json)
restored.validate_for_input(supplied)

for feature in report.result.features:
    print(feature.combined.feature_id, feature.stage, feature.personal_weight,
          feature.combined.score.value if feature.combined.score else None)
```

JSON 복원은 구조·컴포넌트·실제 혼합 정책을 검사한다. 별도 입력 연결과 수식 재현은
`validate_for_input`으로 확인한다. 동일 입력·정책에서 성공 계산은 재현되며 다른 시각 표현은
해시에서 UTC로 정규화한다. 일시적 계산 실패는 재시도에서 성공할 수 있다. 실패 재현 테스트는
동일 실패 조건을 주입하여 검사한다. 입력의 진위를 인증하는 서명이나 운영 성능 검증은 아니다.

## 완료 기준별 직접 검증

가상환경을 활성화한 `ai/`에서 실행한다. Git Bash에서는 `source .venv/Scripts/activate`를
사용한다. 파일을 Python으로 직접 실행하지 말고 `python -m pytest`로 실행한다.

| Task 269 완료 기준 | 실행 명령 | 예상 결과 |
| --- | --- | --- |
| 1. 입력·계산·비중·점수 근거와 척도 | `python -m pytest tests/learning/personalization/evaluation/test_evaluation.py -k "hand_calculated or incompatible_numeric" -v --tb=short` | 5 passed |
| 2. Cold 복귀·미산출·Feature별 관측·노후화 | `python -m pytest tests/learning/personalization/evaluation/test_evaluation.py -k "unused_personal or missing_current or no_cold or feature_specific or aging" -v --tb=short` | 12 passed |
| 3. 부분 실패·비정상 수치·다른 Feature 보존 | `python -m pytest tests/learning/personalization/evaluation/test_failures.py -v --tb=short` | 24 passed |
| 4. 출처·연결·JSON 재현·요청 Feature 보존 | `python -m pytest tests/learning/personalization/evaluation/test_evaluation.py -k "population_and_initial or requested_subsets or json_replay or detached" -v --tb=short` | 17 passed |

Task 전체: `python -m pytest tests/learning/personalization/evaluation -v --tb=short` → **79 passed**.
설정·선택 계산 자체의 테스트 20개도 포함한다. 선택 실행은 서로 겹칠 수 있으므로 합산하지
않는다. `deselected`는 실패가 아니며, 예외를 예상대로 거절하는 테스트도 PASSED다.

전체 회귀는 `python -m pytest -q --tb=short`로 실행한다. Task 269 완료 시점 **2171 passed**다.
실제 GMS API·서비스 DB·실거래
탐지 성능은 이번 검증 대상이 아니다. 고정 합성 이력은 [Task 270 비교·재현 CLI](personal-replay-cli.md)로 평가할 수 있다.
