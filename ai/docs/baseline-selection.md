# Cold 기준 로딩과 선택

`select_cold_baselines`는 호출자가 제공한 집단 기준과 초기 정책을 읽고 Feature별 사용 기준을 선택한다. 입력은 Python dict, JSON 문자열 또는 해당 후보 모델이다. 파일·환경변수·DB·외부 API를 조회하지 않으며, 최신 기준을 검색하거나 새 분포를 학습하지 않는다. 명시한 JSON 파일을 읽는 CLI는 별도로 제공한다.

기준값·시간·출처 계약은 [기준 계약 문서](baseline-contracts.md), 원시 값의 정의는 [Feature 변환 문서](feature-transform.md)를 참고한다. 이 결과에는 원시 Feature 값·이상점수·그룹 가중치·위험등급이 없다.

## 선택 순서

| 집단 후보 | 정책 후보 | Feature별 결과 |
|---|---|---|
| 유효하며 해당 Feature 기준 있음 | 무관 | 집단 기준 SELECTED |
| 없음 | 유효하며 해당 Feature 기준 있음 | 초기 정책 SELECTED, 집단 MISSING 기록 |
| 유효하지만 해당 Feature 기준 없음 | 유효하며 해당 Feature 기준 있음 | 초기 정책 SELECTED, 집단 FEATURE_MISSING 기록 |
| 없음 또는 해당 Feature 기준 없음 | 없음·잘못됨·해당 Feature 기준 없음 | UNAVAILABLE, 양쪽 후보 진단 보존 |
| 만료·미래·단위/버전 오류·손상 등 잘못된 집단 후보 | 무관 | UNAVAILABLE, 집단 오류 보존 |

잘못 제공된 집단 설정을 자동으로 정책으로 바꾸면 설정 오류가 가려질 수 있으므로 사용 불가로 반환한다. 호출자가 검토 후 집단 기준을 명시적으로 제거하거나 올바른 버전으로 고쳐야 한다. 집단에서 특정 Feature 기준을 생략한 경우에는 명시한 정책으로 대체할 수 있지만, 기준값을 주면서 관측 근거가 없거나 0표본인 경우는 잘못된 후보다.

한 후보 안에 잘못된 Feature가 있으면 그 후보 전체를 사용하지 않는다. 선택에 사용하지 않는 정책의 오류는 유효한 집단 기준 선택을 막지 않는다. 복수 집단 후보를 받아 임의로 하나를 선택하는 동작은 제공하지 않으며, 하나의 명시적인 집단 ID·프로필 ID·버전을 고정한다. 후보 내 중복 Feature와 JSON 중복 키도 거부한다.

## 검증 결과 코드

| 코드 | 의미 |
|---|---|
| `MISSING` | 해당 후보를 제공하지 않음 |
| `INVALID_FORMAT` | 후보 JSON 구문 오류 또는 중복 키 |
| `INVALID_CONFIG` | 필수값·타입·유한수·양수 scale·시간 관계·원형 방법 등 설정 오류 |
| `UNSUPPORTED_FEATURE` | 등록되지 않은 Feature ID |
| `UNIT_MISMATCH` | Feature 단위 또는 시간 창 불일치 |
| `VERSION_MISMATCH` | 문서 형식·Feature 명세·고정한 기준 버전 불일치 |
| `CURRENCY_MISMATCH` | 평가 대상과 기준의 통화 불일치 |
| `REFERENCE_MISMATCH` | 집단/프로필/정책 ID·scope·이미 제공된 프로필 내용 불일치 |
| `FUTURE` | 기준 생성·가용·적용 시작 등이 평가 시점 이후 |
| `EXPIRED` | 평가 시각이 기준 만료 시각 이상 |
| `FEATURE_MISSING` | 유효한 후보에 요청한 Feature의 기준이 없음 |
| `INSUFFICIENT_SAMPLES` | 집단 기준에 대응하는 관측 메타데이터가 없거나 유효 표본 수가 0 |

각 후보에 대해 먼저 발견한 검증 단계의 오류를 반환하며 모든 오류 목록을 나열하는 도구는 아니다. 요청 자체가 잘못된 Feature 명세/대상/필수 참조를 가진 경우에는 `BaselineSelectionRequest`가 입력 오류를 발생시킨다. 후보 오류는 원시 입력이나 인증정보를 포함하지 않는 코드로 반환한다.

단순 문자열 파싱이 성공했다고 기준이 유효한 것은 아니다. 대상 통화, 고정한 버전과 참조, 평가 시각, Feature 단위와 관측 근거를 모두 검사한다. dict로 바꾸기 전에 이미 사라진 JSON 중복 키는 복원할 수 없으므로 원본 JSON을 받는 경계에서 검사한다.

## 호출과 기존 계약 연결

```python
from copenhagen_fds.baselines import select_cold_baselines
from copenhagen_fds.contracts import BaselineSelectionRequest

request = BaselineSelectionRequest.model_validate(payload["request"])
selection = select_cold_baselines(
    request,
    population=payload["population"],
    policy=payload["policy"],
)
selection.validate_for_request(request)
```

만료·미래 프로필 후보를 먼저 기존 `BehaviorEvaluationRequest.profiles`에 넣으면 기존 계약이 즉시 거절한다. 따라서 후보는 위처럼 별도 인자로 전달한다. 유효하게 선택된 집단 프로필만 `selection.profiles`로 반환한다. 후속 평가 연결 시 이를 기존 평가 요청에 포함하고, 같은 ID의 기존 프로필이 있다면 내용이 같은지 확인해야 한다. 이 단계에서는 요청을 자동으로 바꾸거나 이상점수 결과를 만들지 않는다.

선택 결과를 따로 저장/전달했다면 `BaselineSelection.model_validate_json(...)` 이후 `validate_for_request(request)`를 호출한다. 이 검사는 대상·버전과 결과의 내부 일관성을 확인한다. 외부에서 제공된 원본 기준의 진위나 같은 버전으로 조작된 통계값을 인증하는 서명 기능은 아니며, 변경 시 새 버전 부여와 보관·인증은 제공자 책임이다.

## 직접 검증

가상환경이 활성화된 Git Bash에서 **ai 폴더 기준**으로 실행한다. 현재 위치가 `ai/tests`라면 먼저 `cd ..` 한다.

```bash
python -m pytest tests/test_baseline_contracts.py tests/test_baseline_selection.py -v --tb=short
python -m copenhagen_fds.baseline_cli examples/cold_baseline_input.json
```

고정 합성 예제의 예상 선택은 다음과 같다. 이 예제의 center/scale는 실제 구매 데이터에서 학습한 값이나 운영 권장값이 아니다.

| Feature | 선택 기준 | 출처 | 확인 값 |
|---|---|---|---|
| `amount_minor` | POPULATION_PROFILE | SYNTHETIC | 프로필 synthetic-population / demo-v1 |
| `purchase_count_10m` | INITIAL_POLICY | INITIAL_POLICY | 정책 cold-demo / demo-v1, 집단 FEATURE_MISSING |
| `kst_hour` | INITIAL_POLICY | INITIAL_POLICY | 정책 cold-demo / demo-v1, period 24 |

모두 `status=SELECTED`, `validation_status=UNVALIDATED`여야 한다. 예제는 고정 평가 시각을 사용하므로 실행 날짜가 달라도 결과가 같다. 출처가 SYNTHETIC인 항목이 OBSERVED로 바뀌면 안 된다.

수정 실험은 예제 파일을 복사한 뒤 수행한다. 집단 프로필의 `valid_until`을 `2026-09-14T00:05:00+09:00`로 바꾸면 EXPIRED와 UNAVAILABLE, `available_at`을 `2026-09-14T00:06:00+09:00`로 바꾸면 FUTURE와 UNAVAILABLE이 나온다. 유효한 정책이 있어도 잘못된 집단 후보를 조용히 대체하지 않는다. JSON 최상위 `population`을 null로 바꾸면 정책의 두 Feature는 선택되고 정책에 없는 금액은 UNAVAILABLE로 남는다.

CLI 종료 코드는 모든 Feature 기준 선택 시 0, 일부 기준 사용 불가 시 1이며 두 경우 모두 JSON 결과를 출력한다. 파일/최상위 구조/요청 파싱 오류는 고정 오류 문구와 종료 코드 2다. API 키·실제 `.env`·외부 호출은 사용하지 않는다. pytest의 PASSED는 잘못된 입력을 예상대로 거절한 사례도 포함한다.

완료 기준별로 확인하려면 같은 위치에서 다음 명령을 사용한다. 전체 계약·로더 검증은 위의 두 테스트 파일 실행으로 확인한다. 아래 선택에는 서로 겹치는 사례가 있으므로 테스트 수를 합산하지 않는다.

1. 유효한 집단 기준·초기 정책의 출처와 버전 보존: 집단 우선, 집단 없음, 특정 Feature의 정책 대체를 확인한다.

   ```bash
   python -m pytest tests/test_baseline_selection.py -v --tb=short -k "population_first or missing_population_uses or valid_population_missing"
   ```

2. 기준 오류 구분: 기준 없음·만료·미래 시각·단위·버전·설정·표본 오류와 변조된 선택 결과의 거부를 확인한다.

   ```bash
   python -m pytest tests/test_baseline_selection.py -v --tb=short -k "invalid or malformed or decoded or observation_support or circular_time or missing_both"
   ```

3. 동일 입력 재현과 합성/정책 출처 유지: 입력과 파라미터 순서 변경, CLI JSON, 출처 보존 및 변조 거부를 확인한다.

   ```bash
   python -m pytest tests/test_baseline_selection.py -v --tb=short -k "same_input or cli or provenance"
   ```

모든 선택 테스트가 PASSED이고 FAILED/ERROR가 없으면 통과다. `deselected`는 해당 명령으로 선택하지 않은 테스트이며 실패가 아니다. 합성 기준으로 통과한 테스트는 실거래 탐지 성능 검증을 뜻하지 않는다.
