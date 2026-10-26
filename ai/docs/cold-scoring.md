# Cold 점수와 데이터 충분도 반환

`copenhagen_fds.cold_scoring.score_cold_features`는 이미 변환된 Raw Feature와
이미 선택된 집단 기준·초기 정책을 받아 Feature별 점수를 반환한다.
[점수 수식](feature-scoring.md)은 `cold-distance-linear-v1`을 사용한다.
원천 이벤트 변환부터 평가까지 묶는 운영 진입점, DB 조회, 외부 호출, 학습,
개인 기준 혼합, 가중 합산, 위험등급은 이 함수에 포함되지 않는다.

## 호출과 결과

```python
from copenhagen_fds.contracts.scoring import ColdScoringInput, ColdScoringResult
from copenhagen_fds.cold_scoring import score_cold_features

# payload는 request, raw_features, selection, trace_id를 포함한다.
supplied = ColdScoringInput.model_validate(payload)
result = score_cold_features(supplied)
encoded = result.model_dump_json()
restored = ColdScoringResult.model_validate_json(encoded).validate_for_input(supplied)
```

- `request`: `BaselineSelectionRequest`. 평가 요청과 고정한 기준 ID·버전을 포함한다.
- `raw_features`: `RawFeatureVector`. 요청한 모든 Feature의 원시 값 또는 결측 상태다.
- `selection`: `BaselineSelection`. 선택한 파라미터와 출처·시각·관측·진단이다.
- `trace_id`: 호출자가 제공한다. 함수는 현재 시각이나 임의 식별자를 만들지 않는다.

서로 다른 사용자·구매·주문 스냅샷·평가 시각·통화·스키마·요청 Feature·기준 버전의
자료가 섞이면 계산 전에 `ValueError`/Pydantic `ValidationError`로 거부한다.
잘못된 입력을 정상 결과나 계산 실패 결과로 변환하지 않는다.

반환 구조는 다음과 같다.

| 필드 | 확인할 내용 |
|---|---|
| `behavior` | 공통 `BehaviorEvaluationResult`: Feature별 상태·0~100점·사유·적용 기준, 실행 상태, coverage, 평가 시각, 계산 버전 |
| `raw_features` | 계산에 전달한 원시 값·단위·시간 창·가용 상태 |
| `selection` | 선택 파라미터, 집단/정책 ID·버전, 출처 참조, 생성·가용·유효 시각, 선택 과정 진단, `UNVALIDATED` |
| `personal_profile_status` | `NOT_SUPPLIED` 또는 `SUPPLIED_NOT_USED`. Cold 계산에는 개인 프로필을 적용하지 않음 |
| `sufficiency` | Feature별 `reference_support`, 집단 기준의 `population_sample_count` |

충분도는 세 축으로 확인한다. **원시 입력 가용성**은 `raw_features.features[].status`,
**개인 프로필 제공 여부**는 `personal_profile_status`, **기준의 관측 근거**는
`sufficiency`다. `reference_support`는 `UNAVAILABLE` / `POLICY_ONLY` /
`POPULATION_SAMPLES` 중 하나다. 정책의 표본 수는 `null`이며 0개를 관측했다고 꾸미지 않는다.
집단 표본 수는 해당 Feature의 제공된 관측 수를 그대로 보존한다. 실제 표본의 진위를
인증하거나 통계적으로 충분하다고 판정하는 값은 아니다. 합성 자료의 표본 수 역시
`selection`의 `SYNTHETIC` 출처와 함께 해석한다.

개인 프로필이 없다는 사실만으로 신규 가입자이거나 개인 거래가 0건이라고 단정하지 않는다.
신규 이용자도 가용 입력과 집단 기준/정책이 있으면 계산할 수 있다.
개인 프로필이 제공돼도 이 단계에서는 적용하지 않으며 개인 관측 수를 집단 표본에 더하지 않는다.

## 상태와 판단 근거

| 조건 | Feature 상태 | score | 사유 |
|---|---|---|---|
| 입력·기준 모두 가용, 거리 0 | `SCORED` | 0 | `WITHIN_REFERENCE` |
| 입력·기준 모두 가용, 거리 양수 | `SCORED` | 양수, 최대 100 | `PATTERN_DEVIATION` |
| 원시 입력 미수집 | `NOT_COLLECTED` | null | `MISSING_INPUT` |
| 관측 기간 불완전 | `INSUFFICIENT_DATA` | null | `INSUFFICIENT_HISTORY` |
| 수집 대상이 아님 | `NOT_APPLICABLE` | null | `NOT_APPLICABLE` |
| 입력은 가용하나 기준 없음 | `INSUFFICIENT_DATA` | null | `REFERENCE_UNAVAILABLE` |
| 계산 중 산술 오류·유효하지 않은 점수 | `FAILED` | null | `CALCULATION_FAILED` |

여러 문제가 겹치면 원시 입력의 상태를 먼저 반환하고 기준 문제도 `selection.issues`에
보존한다. 선택했지만 입력이 없어 사용하지 못한 기준은 `behavior.features[].bases`에
포함하지 않는다. 계산을 시도하다 실패한 경우에는 시도한 기준을 보존한다.
`behavior.profiles`에는 실제 적용/계산 시도한 프로필만 넣는다.

일부 Feature 계산이 실패하면 전체 실행 상태는 `FAILED`이며 다른 Feature의 점수는 보존한다.
모두 계산할 수 없어도 단순 데이터 부족이라면 실행 상태는 `SUCCESS`, coverage는
`UNAVAILABLE`이다. 이것은 정상 거래 판정이 아니다. 실패와 데이터 부족이 함께 있으면
공통 사유에 두 원인을 모두 남긴다. 내부 오류 문자열은 결과에 노출하지 않는다.

`coverage=COMPLETE`는 요청한 모든 점수를 계산했다는 뜻이다. 초기 정책만으로도 COMPLETE가
될 수 있으며 탐지 성능·신뢰도·개인 이력의 충분도를 보장하지 않는다.
결과는 해당 평가 시점에 한정하므로 `behavior.metadata.valid_until`은 부여하지 않는다.
기준 자체의 만료 시각은 `selection`에서 별도로 확인한다.

## 직접 검증

가상환경이 활성화된 Git Bash에서 **ai 디렉터리** 기준으로 실행한다.
현재 `ai/tests`라면 먼저 `cd ..` 한다.

```bash
# 수식·대표값·경계값
python -m pytest tests/test_feature_scoring.py -v --tb=short

# 신규 이용자 상황, 입력/이력/기준 부족과 계산 실패
python -m pytest tests/test_cold_scoring.py -v --tb=short -k "new_user or missing or empty_history or failure or calculations_failed or personal_profiles"

# 동일 입력 재실행, 기준 버전 연결, 근거 변조 거부
python -m pytest tests/test_cold_scoring.py -v --tb=short -k "replay or pinned_reference or output_cannot"

# 전체 점수·결과 매핑과 공통 계약 회귀
python -m pytest tests/test_feature_scoring.py tests/test_cold_scoring.py tests/test_behavior_contracts.py -v --tb=short
```

각 명령에서 선택된 테스트가 모두 `PASSED`여야 한다. `deselected`는 `-k` 조건에서
제외된 테스트이며 실패가 아니다. 파일을 `python test_...py`로 실행하면 pytest가 수집되지 않는다.

점수 JSON을 직접 확인할 때는 합성 예제를 실행한다. API 키나 DB 연결은 필요 없다.

```bash
python examples/cold_scoring_demo.py
python examples/cold_scoring_demo.py --scenario empty_history
python examples/cold_scoring_demo.py --scenario missing_history
python examples/cold_scoring_demo.py --scenario missing_input
python examples/cold_scoring_demo.py --scenario no_reference
python examples/cold_scoring_demo.py --scenario not_applicable
```

| 시나리오 | 확인할 결과 |
|---|---|
| 기본 | 금액 0점(집단 기준), 10분 구매 빈도 50점(초기 정책), 시간대 100점(초기 정책), COMPLETE |
| `empty_history` | 완전 수집된 빈 창을 가정한 빈도 0건 → 점수 0, SCORED |
| `missing_history` | 빈도 score=null, INSUFFICIENT_HISTORY, PARTIAL |
| `missing_input` | 시간대 score=null, MISSING_INPUT, PARTIAL |
| `no_reference` | 모든 score=null, REFERENCE_UNAVAILABLE, UNAVAILABLE |
| `not_applicable` | 빈도 score=null, NOT_APPLICABLE, PARTIAL |

이 예제는 원시 벡터를 직접 제공한다. 빈도 6건과 완전 수집된 빈 창은 합성 입력의 선언이며
실제 이벤트를 수집·변환한 증거가 아니다. 원천 이벤트부터의 전체 Cold 연결 검증은 별도다.
기본 예제의 시간대 기준은 정오 12시, scale=12시간이므로 자정 0시는 100점이다.
이는 설정된 기준과의 거리일 뿐 새벽 거래가 사기라는 근거가 아니다.

동일 입력의 JSON 재현 여부는 Git Bash에서 파일을 만들지 않고 비교할 수 있다.

```bash
diff <(python examples/cold_scoring_demo.py) <(python examples/cold_scoring_demo.py)
```

출력이 없고 종료 코드가 0이면 두 결과가 동일하다. 재현에는 코드·의존성 버전,
요청 전체·평가 시각·원시 벡터·기준의 실제 파라미터/출처/버전과 trace ID를 고정해야 한다.
버전 문자열이 같아도 기준 내용이 바뀌면 다른 실험이다. 이 함수는 외부 저장소의
버전 불변성을 보장하지 않으므로 입력 스냅샷 전체를 보존한다.
