# 개인화 Feature 결과 계약

`PersonalizedScoringResult`는 기존 공통 `BehaviorEvaluationResult`와 Feature별 계산
상세를 연결한다. 개인 금액 점수 후보는 [개인 금액 이상점수](personal-amount-scoring.md)에
구현되어 있다. [개인 구매·시도 빈도 점수](personal-frequency-scoring.md)도 최근·장기
후보를 반환한다. [개인 시간대·요일 점수](personal-time-scoring.md)는 원형 분포의
희소성 후보를 반환한다. [개인화 단계 전환](personal-transition.md)은 기준 사용 정책을
결정하며, [개인화 평가 실행](personal-evaluation.md)이 실제 점수 혼합·평가 조립을 수행한다.
이 문서의 숫자는 계약 검증용으로 직접 지정한 값이며 계산기가 산출한 점수가 아니다.

## 반환 구조

| 필드 | 내용 |
| --- | --- |
| `input_sha256` | 구매·Raw Feature·Cold 기준·프로필/정책 상태·설정을 포함한 입력 내용 해시 |
| `config`, `config_sha256` | 실제 개인화 설정과 내용 해시 |
| `cold` | 기존 Cold 계산 결과, 원시 값, 기준과 충분도 |
| `behavior` | 공통 계약의 Feature별 최종 점수·실행 상태·coverage·실제 사용 프로필 |
| `profiles` | 개인/집단의 미제공·가용·만료 상태와 정책 적용 후 프로필·정책 상태 버전·해시·가용 시각 |
| `features` | 개인/Cold/혼합 점수, 실제 혼합 비중, 단계, 선택한 개인 통계 창·수식 버전, 최근/장기 분포, 단계 사유 |

Feature 상세의 `evidence`는 Story 5 `published`의 최근/장기 `FeatureDistribution`을
그대로 보존한다. 유효 표본 수, 관측 시각, 각각의 미수집/이력 부족/적용 불가 수와
분포를 함께 확인할 수 있다. 개인 입력이 없으면 근거 배열도 비어 있다.
만료 프로필의 통계는 진단용으로 남길 수 있지만 개인 계산의 적용 근거가 될 수 없다.

## 단계와 점수 불변 조건

각 Feature의 개인 비중을 `w`라고 하면 Cold 비중은 `1.0 - w`로 기록한다.

| 단계 | 실제 사용 비중 |
| --- | --- |
| `COLD` | Cold 1, 개인 0 |
| `TRANSITION` | 개인 비중이 0보다 크고 1보다 작음 |
| `PERSONALIZED` | Cold 0, 개인 1 |
| `UNAVAILABLE` | 최종 점수 없음, 두 비중 모두 null |

최종 점수는 양수 비중인 항목만 `math.fsum([Cold 비중 × Cold 점수, 개인 비중 × 개인 점수])`로
합산한 값과 같아야 한다. 사용하지 않는 점수가 없어도 계산할 수 있지만 점수가 없는
항목에 양수 비중을 주거나 임의로 0을 넣을 수 없다. 실제 적용 기준도 양수 비중인
항목의 기준과 같아야 하며, 0점과 양수 점수의 사유도 일치해야 한다.
부동소수점 반올림으로 100을 넘으면 100으로 제한한다. 양수 항의 곱이 표현 한계로
모두 0이 되면 최소 양의 실수로 보존하여 실제 기준 일치 0점과 구분한다.

예를 들어 **Cold 0점, 개인 80점, 개인 비중 0.25**를 명시하면 결과는 전환 단계,
최종 **20점**이다. 개인 비중을 0으로 주면 Cold 0점, 1이면 개인화 80점이다.
이 검증은 혼합 산식의 일관성만 보장한다. 두 점수의 척도가 통계적으로 동등한지,
0.25라는 비중이 적절한지는 점수 수식·단계·혼합 정책에서 검증해야 한다.
`score_scale_version`은 그 정의를 추적하는 버전이며 사기 확률 보정의 증명이 아니다.

미수집·이력 부족·적용 불가 입력은 개인·최종 점수에도 같은 상태와 사유를 유지한다.
미제공 또는 만료된 개인 기준 대신 유효한 Cold를 썼다면 그 Cold 점수는 0일 수도 있다.
이는 누락된 개인 점수를 0으로 채운 결과와 구분되며 개인 점수는 null로 남는다.
두 기준을 모두 사용할 수 없으면 최종 점수도 null이다.

계산 실패가 있으면 다른 유효 점수를 보존하더라도 실행 상태를 `FAILED`로 기록한다.
`coverage=COMPLETE`는 모든 최종 Feature 점수가 존재한다는 뜻이므로 일부 컴포넌트가
실패하고 Cold로 대체한 경우에도 COMPLETE일 수 있다. 실행 성공, 점수 coverage,
기준 충분도와 탐지 정확도는 서로 다른 정보다.

## 입력과 함께 결과 검증

```python
from copenhagen_fds.contracts.personalization_results import PersonalizedScoringResult

result = PersonalizedScoringResult.model_validate_json(result_json)
result.validate_for_input(supplied)
```

별도 역직렬화 뒤에는 반드시 `validate_for_input`을 호출한다. 구조 검증만으로는 결과가
해당 구매·실제 설정·프로필 내용에 속한다고 보장할 수 없다. 입력 해시, Cold 결과,
정책 출처, 실제 사용 프로필, 최근/장기 통계, 원시 입력의 결측 상태를 대조한다.
일관된 위조 입력의 진위나 수식 실행의 정당성까지 인증하는 서명은 아니다.

동일 입력·버전·설정의 계약과 내용 해시는 재현 가능하며 동등한 시각 표현은 해시 계산에서
UTC로 정규화한다. 집단 변환 산출물의 원본 해시는 기존 변환 계약을 그대로 사용하므로
원본 통계의 직렬화 내용을 바꿨다면 그 산출물도 다시 생성해야 한다.

## 직접 검증

가상환경을 활성화하고 `ai/`에서 실행한다.

```bash
python -m pytest tests/learning/personalization/test_result_contracts.py -v --tb=short
python -m pytest tests/learning/personalization -v --tb=short
```

대표 검증은 손계산 0/20/80점, 단계·비중 합·점수·근거 불일치 거부, 없는 입력의
점수 조작 거부, 만료 시 유효 Cold 보존, 모든 기준 부재, 부분 실패, JSON 왕복이다.
테스트의 개인 점수 80점은 Fixture가 지정하며 실제 개인 점수 계산기가 아니다.
기존 Cold 함수와 실제 개인·집단 학습/정책 생성기는 Fixture 구성에서 재사용한다.

전체 회귀는 `python -m pytest -q --tb=short`로 실행한다. 기본 테스트는 외부 네트워크를
차단하며 `.env`의 GMS 키를 호출하지 않는다. 최종 위험등급, GPT 결합, 서비스 DB 저장,
실거래 탐지 성능은 이 계약의 산출물이 아니다.
