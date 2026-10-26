# 집단 행동 통계 프로필

`build_population_profile(request, samples)`는 명시적으로 제공한 집단과 통화의
`LearningSample`에서 정상 확인된 `INCLUDE` 구매를 학습한다. 개인 프로필과 같은
금액·구매 빈도 3개·시도 빈도 3개·KST 시간대·요일의 9개 Feature를 요약한다.
집단 구성원을 추론하거나 사기 정답·숨겨진 사용자 분포로 집단을 만들지 않는다.

## 입력과 집계

`PopulationProfileRequest`는 집단 정의 `cohort`, POPULATION 참조, 상태 버전,
학습 자료의 출처·기준 시각·생성/만료 시각과 설정을 받는다.

| 집단 정의 | 의미 |
| --- | --- |
| `cohort_id`, `membership_version` | 제공자가 고정한 집단 ID와 구성 버전 |
| `definition` | 포함 조건의 설명. 실행하는 쿼리나 분류 모델이 아니다 |
| `member_user_ids` | 명시적으로 포함할 사용자 목록. 중복 ID는 거부 |
| `source_reference`, `available_at` | 구성 목록의 출처와 이용 가능 시각 |
| `currency` | 통화. 환산하지 않고 정확히 일치하는 구매만 선택 |

구성 목록의 `available_at <= as_of <= generated_at < valid_until`이어야 한다.
BE가 집단 구성의 적합성과 실제 수집 시각을 제공한다. 목록에 포함돼도 표본이 없는
이용자는 0건으로 표시하고 정상 거래나 가중치를 만들어 넣지 않는다.

기본값은 최근 7일/장기 90일, `min_samples=20`, `min_users=3`, `USER_EQUAL`이다.
임시 데이터 충분도 설정이며 검증된 운영 기준이 아니다. 각 창과 각 Feature는 자신의
유효 구매 수와 기여 사용자 수를 모두 충족해야 READY다. 개인 프로필의 READY와도
조건이 다르다. EMPTY/INSUFFICIENT_SAMPLES에서도 계산 가능한 분포는 그대로 남는다.

[가중 방식과 수식](population-weighting.md)을 참고한다. `TRANSACTION`은 구매별 동일
비중, `USER_EQUAL`은 유효 관측 사용자별 동일 비중이다. 각 Feature의 `contributors`에
사용자별 실제 표본 수와 총 가중치를, 히스토그램에 건수와 확률 질량을 함께 보존한다.
`weight_effective_sample_size`는 가중치의 쏠림만 설명한다.

## 결과와 재현 범위

`PopulationProfileSnapshot`은 기존 `BehaviorProfile` 메타데이터, 집단 구성, 설정,
각 입력 해시, 최근/장기 창, 장기 보관 표본을 가진다. `ProfileReference`의 집단 ID,
프로필/상태 버전과 Feature 단위·시간 창을 그대로 보존한다.

동일 구매 ID의 같은 표본은 한 번만 세고 내용이 다른 중복은 정정 재생을 요구한다.
선택된 사용자·통화의 출처와 학습 기준 시각이 다르면 거부한다. 최근/장기 창은
`[as_of - 기간, as_of)`이며 오래된 표본은 상태에서 제외한다. 보류·제외 표본과
수집 범위는 장기 상태에 남겨 후속 정정에 쓸 수 있게 한다. 갱신 실행은 별도 작업이다.

사용자·입력 순서, 동일 중복, 동등한 시각의 시간대 표기를 정규화한다. 같은 요청과
표본으로 JSON 왕복 및 보관 상태 재계산이 가능하다. 해시는 내용 추적용으로 사용하며
제공자의 진위나 입력 자료의 누락을 인증하지 않는다.

현재 결과는 집단의 경험적 분포다. 위험 점수·GPT 결합·DB 저장·Cold 평가 실행은
수행하지 않는다. 집단의 모든 습관을 평균 하나로 정상/이상 구분할 수 있다고 가정하지 않는다.

## 완료 기준별 직접 검증

가상환경을 활성화하고 `ai` 폴더에서 실행한다.

```bash
python -m pytest tests/learning/test_population_profiles.py -v --tb=short
```

이 파일의 14개 검증은 다음 명령으로 나누어 볼 수 있다. `deselected`는 실패가 아니라
이번 명령이 선택하지 않은 테스트다.

| 확인할 내용 | `python -m pytest tests/learning/test_population_profiles.py` 뒤에 추가할 옵션 | 예상 |
| --- | --- | --- |
| 집단·통화 격리와 거래량 영향 | `-k 'isolated or frequent_user' -v --tb=short` | 2 passed |
| 표본·관측 범위·출처와 미수집 | `-k 'missingness or windows or empty_cohort' -v --tb=short` | 3 passed |
| 동일 입력·JSON 재현성 | `-k reproducible -v --tb=short` | 1 passed |
| 잘못된 입력·참조·수치 상태 | `-k 'incompatible or invalid_cohort or tampered' -v --tb=short` | 8 passed |

공통 수식의 직접 검증은 `python -m pytest tests/learning/test_population_weighting.py -v --tb=short`이며
7개가 통과해야 한다. Story 4 계약 검증은 [기준 변환 문서](population-baselines.md),
기존 합성 표본의 실제 실행은 [집단 프로필 내보내기](population-profile-export.md)를 참고한다.
