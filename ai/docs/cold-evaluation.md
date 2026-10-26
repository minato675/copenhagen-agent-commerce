# Cold 단독 평가 흐름

`copenhagen_fds.cold.evaluate_cold`는 원천 이벤트 입력에서 Feature별 점수까지 연결한다.
함수는 동기식 순수 계산이며 파일·환경 변수·현재 시각·API·DB를 읽지 않는다.
기존 `FdsSettings`의 LIVE 모드는 GPT/GMS 연결 설정이다. Cold 단독 호출은 그 설정을
로드하지 않으며 외부 FDS 키나 GMS 키 없이 실행할 수 있다.

```text
ColdEvaluationInput + 제공된 population / policy 후보
  → 입력·평가 대상·기준 ID 검증
  → transform_features: 원천 이벤트 → RawFeatureVector
  → select_cold_baselines: 집단 기준 / 초기 정책 선택·진단
  → score_cold_features: Feature별 점수·근거·충분도
  → ColdScoringResult
```

## Python 사용법

```python
import json
from pathlib import Path
from copenhagen_fds.cold import evaluate_cold
from copenhagen_fds.contracts.cold import ColdEvaluationInput

# 파일 조회는 호출자가 수행한다. 이 예제는 ai 디렉터리 기준이다.
bundle = json.loads(Path("examples/cold_evaluation_input.json").read_text(encoding="utf-8"))
supplied = ColdEvaluationInput.model_validate(bundle["input"])
result = evaluate_cold(supplied, population=bundle["population"], policy=bundle["policy"])
print(result.model_dump_json(indent=2))
```

입력 형식 버전은 `cold-evaluation-v1`이다. `feature_input`에는 기존
`FeatureTransformInput`을 넣는다. 평가 요청은 이곳에 한 번만 전달하고 모든 단계가
동일한 대상·통화·Feature 집합·평가 시각을 사용한다. `population_reference`,
`policy_id`, `policy_version`으로 기준을 고정하고 `trace_id`를 명시한다.
사용하지 않는 기준 식별자는 null로 제공한다. 개인 프로필 학습이나 최신 기준 조회를 하지 않는다.

`population`/`policy` 후보는 기존 기준 선택기와 동일하게 dict, JSON 문자열,
`PopulationBaseline`/`InitialPolicy` 또는 None으로 전달한다. 후보가 없다면
호출자가 명시한 초기 정책으로 대체하거나 기준 부재를 반환한다. 손상·만료·미래 시각 등
유효하지 않은 집단 후보는 초기 정책으로 무음 대체하지 않는다.

반환 형식은 [Cold 점수·충분도 계약](cold-scoring.md)을 그대로 사용한다. 원시 값,
단위·시간 창, 기준 파라미터·출처·버전·시각·진단과 계산 버전이 결과에 남는다.
원천 이벤트 전체를 결과에 복제하지 않으므로 재현에는 원래 입력 스냅샷도 함께 보존해야 한다.

## 오류와 부족 상태

- 잘못된 평가 입력, 다른 사용자의 이력, 서로 충돌하는 중복 이벤트 등은
  `ValueError`/Pydantic `ValidationError`로 거부한다. 유효하지 않은 요청의 정상 평가 결과를 만들지 않는다.
- 수집되지 않은 이력·현재 시각, 관측 기간 부족, 수집 대상 아님은 기존 Feature 상태를 보존한다.
  알려진 빈 창의 0건과 수집 여부를 모르는 이력을 구분한다.
- 기준 부재·손상·만료는 선택 진단과 null 점수로 남긴다. 데이터 부족으로 점수가 없으면
  실행은 SUCCESS여도 coverage는 PARTIAL 또는 UNAVAILABLE이다.
- Feature 점수 계산 오류는 FAILED와 null 점수로 남기고 다른 Feature의 점수를 보존한다.
  데이터 부족도 함께 있으면 두 원인을 모두 보존한다.

SYNTHETIC / OBSERVED / INITIAL_POLICY와 UNVALIDATED를 유지한다. 표본 수와 COMPLETE는
통계적 충분도나 실사기 탐지 성능을 보장하지 않는다. 최종 위험등급, GPT와의 정책 결합,
개인화 학습, BE 서빙 API, 실제 결제 승인·실행은 포함하지 않는다.

## 통합 예제와 검증

`examples/cold_evaluation_input.json`은 원천 이벤트 5개를 포함한 고정 합성 자료다.
중복 구매 이벤트와 다른 판매처의 복제 이벤트는 같은 구매로 집계하고, 별개의 두 결제
시도는 각각 집계한다. 다음 결과가 예상된다.

| Feature | 원시 값 | 점수 |
|---|---:|---:|
| 금액 | 10,000 KRW 최소 단위 | 0 |
| 구매 건수 10분·1시간·24시간 | 각각 1 | 각각 0 |
| 시도 건수 10분·1시간·24시간 | 각각 2 | 각각 25 |
| KST 시간 | 0시 | 100 |
| KST 요일 | 0 (월요일) | 0 |

실행 상태는 SUCCESS, coverage는 COMPLETE다. 시간대 기준이 정오이므로 자정이 100점인
시연용 설정이며, 새벽 거래가 사기라는 뜻이 아니다. 파라미터는 운영 기준으로 검증하지 않았다.

가상환경이 활성화된 Git Bash의 **ai 폴더**에서 실행한다. `ai/tests`라면 먼저 `cd ..` 한다.

```bash
python -m pytest tests/test_cold_evaluation.py -v --tb=short
```

정상·신규 이용자·결측·기준 부재·만료·계산 오류, 다중 판매처·재시도·중복 충돌,
시간 창 경계·미래 정보 제외, 출처·버전 보존과 재현성을 실제 Cold 전체 경로에서 검사한다.
네트워크 차단 상태의 합성 테스트이며 실제 DB/API·결제 E2E나 실사기 탐지 성능 검증이 아니다.

## JSON 파일 실행

동일한 `ai` 디렉터리에서 다음 명령으로 전체 Cold 평가 결과를 확인한다.

```bash
python -m copenhagen_fds.cold_cli examples/cold_evaluation_input.json
```

최상위 키는 `input`, `population`, `policy` 세 개를 정확히 포함해야 한다.
후보가 없으면 해당 값을 null로 지정한다. UTF-8 및 UTF-8 BOM 파일을 지원한다.
중복 JSON 키, NaN/Infinity, 잘못된 최상위 구조는 거부한다.
후보 내부의 잘못된 설정은 기존 기준 선택기의 진단으로 반환한다.

| 종료 코드 | stdout | 의미 |
|---:|---|---|
| 0 | 결과 JSON | 실행 SUCCESS, 점수 coverage COMPLETE |
| 1 | 결과 JSON | 실행 SUCCESS, 점수 coverage PARTIAL/UNAVAILABLE |
| 2 | 없음 | 파일·JSON·입력 계약 또는 중복 이벤트 충돌 오류 |
| 3 | 결과 JSON 또는 없음 | 점수 계산 실패. 결과 생성 전 산술 오류라면 JSON 없이 고정 오류 문구 |

종료 코드 0은 정상 거래 승인이라는 뜻이 아니다. 오류 문구는 stderr로 출력하며
원문 데이터·식별자·파일 경로·내부 예외 문자열을 포함하지 않는다.
CLI의 stdout은 결과 확인용이므로 실제 이용자 자료를 사용한다면 원시 값과 평가 대상이 포함된다.

합성 예제를 복사해 다음 필드를 수정하면 다른 경로를 직접 확인할 수 있다.
원본 예제는 유지하고 Git에서 제외된 `ai/results/evaluation` 경로에 복사본을 둔다.

| 수정 | 예상 결과 |
|---|---|
| `population=null`, `input.feature_input.history.events=[]` | 개인 프로필 없는 이용자·알려진 빈 창: 정책으로 COMPLETE, 건수 점수 0 |
| `input.feature_input.history=null` | 빈도 6개 score=null, NOT_COLLECTED, PARTIAL, 종료 1 |
| `input.feature_input.current_occurred_at=null`, `current_available_at=null` | 시간·요일 score=null, MISSING_INPUT, PARTIAL, 종료 1 |
| `population=null`, `policy=null` | 모든 score=null, REFERENCE_UNAVAILABLE, UNAVAILABLE, 종료 1 |
| `population.profile.valid_until`을 평가 시각과 같게 변경 | EXPIRED 진단, UNAVAILABLE, 종료 1 |
| `input.feature_input.current_attempt_id` 키 삭제 | 입력 거부, 결과 JSON 없음, 종료 2 |

```bash
mkdir -p results/evaluation
cp examples/cold_evaluation_input.json results/evaluation/cold-check.json
# 편집기로 복사본의 위 필드 중 하나를 수정한 뒤 실행
python -m copenhagen_fds.cold_cli results/evaluation/cold-check.json
echo $?
```

계산 오류는 테스트에서 산술 오류를 주입해 확인한다. 실행 도구에 임의 오류를 발생시키는
운영 옵션은 추가하지 않는다. 동일 입력 재현 확인은 Git Bash에서 다음 명령을 사용한다.

```bash
diff <(python -m copenhagen_fds.cold_cli examples/cold_evaluation_input.json) <(python -m copenhagen_fds.cold_cli examples/cold_evaluation_input.json)
```

출력 없음과 종료 코드 0이 예상된다. 코드·의존성·원천 입력·기준 후보 스냅샷 전체를 고정한다.
버전 이름만 같고 파라미터가 바뀐 후보는 동일 실험이 아니다.

## 완료 기준별 검사 범위

```bash
# Cold 정상·결측·오류와 외부 설정 없이 실행, JSON 명령
python -m pytest tests/test_cold_evaluation.py tests/test_cold_cli.py -v --tb=short

# 연결 과정의 실패·부족·출처·버전 보존
python -m pytest tests/test_cold_evaluation.py -v --tb=short -k "missing or short_history or inapplicable or no_references or invalid_population or expired or provenance or calculation_failure"

# 기존 공통 계약·설정 및 GPT/GMS 오프라인 회귀를 포함한 전체 검사
python -m pytest -q
python -m pip check
```

모든 선택 테스트가 PASSED여야 한다. `deselected`는 `-k`에서 제외된 검사다.
첫 번째 명령은 전체 Cold 연결/CLI 통합 검증이며 각 구성 요소의 기본 단위 테스트는
전체 검사에서 함께 실행한다. GPT/GMS 검사는 합성·Mock 응답과 네트워크 차단을 사용하므로
실제 GMS 인증·모델 응답·실서비스 연결 성공을 검증한 것으로 해석하지 않는다.
