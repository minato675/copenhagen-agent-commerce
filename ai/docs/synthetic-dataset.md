# 합성 행동 데이터 저장과 검증

생성 근거·사용자 유형·20개 시나리오의 의미는 [합성 패턴 명세](synthetic-patterns.md)에 설명한다. 이 자료는 향후 프로필 학습·개인화 점수·정책 구현에 계속 사용할 수 있는 고정 회귀 데이터다. 실제 모델의 탐지율을 측정한 결과는 포함하지 않는다.

## 생성과 보관

```python
from pathlib import Path
from copenhagen_fds.synthetic.generator import GeneratorConfig
from copenhagen_fds.synthetic.storage import create_benchmark, verify_dataset

# ai 폴더에서 실행. 이미 존재하는 경로는 덮어쓰지 않는다.
report = create_benchmark(Path("data/synthetic/fds-behavior-v1"), GeneratorConfig())
checked = verify_dataset(Path("data/synthetic/fds-behavior-v1/stream"))
```

기본 장기 스트림은 seed `20260910`, 사용자 120명이다. 도전 세트는 다음 seed와 기본 사용자 40명 및 격리된 시나리오 사용자 20명을 사용한다. 생성 구매 수는 분포에 따라 결정되며 `benchmark.json`과 각 `manifest.json`에 실제 건수를 기록한다. `config.users`는 도전 세트에서 격리 사용자를 제외한 기본 집단 수다.

```text
data/synthetic/fds-behavior-v1/
  benchmark.json
  stream/
    manifest.json
    users.jsonl / purchases.jsonl / events.jsonl
    outcomes.jsonl / feedback.jsonl / coverage_gaps.jsonl
    truth.jsonl / personas.jsonl
    purchases.csv / truth.csv
  challenge/
    (동일한 파일 구성)
```

JSONL은 한 줄에 하나의 JSON 객체를 담는 원본이다. CSV는 금액·시각과 정답을 쉽게 확인하는 보조 보기이며, 판매처 배열 등 일부 원본 필드는 생략한다. `truth`와 `personas`는 평가 담당자용이다. 학습기나 점수 계산기에 정답·시나리오 이름·숨겨진 사용자 분포를 입력하면 평가 근거가 훼손된다.

`manifest.json`에는 데이터 버전, 전체 생성 설정, Python 버전, 생성기 파일 SHA-256, 각 파일 해시·행 수, 분할 경계, 출처와 검증 집계를 기록한다. 생성할 때 사건 참조, 금액 합계·주문 해시, 시각 순서, 정답·확인 결과 연결을 검사한다. `verify_dataset`은 저장 후 파일 해시와 같은 의미 검증을 다시 수행한다. 해시는 변경 탐지용이며 외부 서명·인증을 제공하지 않는다.

생성기는 기존 출력 폴더를 덮어쓰지 않는다. 불완전한 생성에도 기존 경로를 자동 삭제하지 않으며 새 경로를 사용해야 한다. 정상 완료한 개별 세트는 `manifest.json`, 전체 생성은 `benchmark.json`이 존재한다. 데이터·설정 또는 생성기를 변경하면 별도 버전/경로로 생성하여 기존 기준 파일을 보존한다.

`ai/data/`는 기존 Git 제외 규칙을 따른다. 생성기·검증 코드·기술 사용 문서를 Git에 기록하고, 실제 데이터 폴더는 별도로 보관한다. 공유할 때는 선택한 세트의 모든 JSONL과 manifest를 함께 전달한다.

## 시간순 사용 원칙

정상 패턴 학습용으로 과거 Feature와 시점별 적격 여부를 내보내려면 [합성 학습 표본 사용법](synthetic-learning.md)의 `export-learning` 명령을 사용합니다. 이 경로는 관측 자료와 후속 확인 파일만 읽고, 정답·persona 파일은 열지 않습니다.

기본 구간은 구매 발생 시각 기준의 반열린 구간이다.

| 용도 | 시작 포함 | 종료 제외 |
|---|---|---|
| 과거 프로필 축적 `train` | 2026-01-01 00:00 KST | 2026-03-02 00:00 KST |
| 개발·조정 `validation` | 2026-03-02 00:00 KST | 2026-04-01 00:00 KST |
| 미래 회귀 평가 `test` | 2026-04-01 00:00 KST | 2026-05-01 00:00 KST |

구매의 분할과 정보 가용성은 별개다. 과거 구간에 발생했더라도 그 종료 이후에 도착한 이벤트·확인 결과는 과거 시점의 학습에 사용할 수 없다. 후속 확인 레코드는 120일 종료 이후에도 남을 수 있다. 학습 호출 시점마다 `available_at`을 확인하고, 현재 거래를 평가하기 전에 해당 거래를 프로필에 반영하지 않는다.

개발 중 반복해서 확인하는 고정 `test`는 장기 회귀 기준이다. 최종 모델 선택이 끝난 뒤에는 별도 seed와 분포 설정 변형으로 생성한 자료에서도 확인해야 한다. 동일한 생성 규칙의 새 seed만으로 실서비스 일반화가 증명되지는 않는다. 도전 세트는 시나리오별로 보고하며 낮은 사기 비율의 장기 스트림과 평가 지표를 섞지 않는다.

## 저장·무결성 검증

```bash
# ai 폴더, 기존 가상환경
python -m pytest tests/test_synthetic_generator.py tests/test_synthetic_artifacts.py -v --tb=short
```

검증 대상은 재현 가능한 생성, 파일 변경 탐지, 잘못된 참조·시각·금액·정답 연결 거부다. 성공적으로 끝나면 모든 선택 테스트가 `PASSED`다. 프로필 생성·갱신, 최종 FDS·GPT 연결, 실제 탐지 성능은 실행하지 않는다.

## CLI와 기존 Feature 입력 확인

명령은 모두 `ai` 폴더의 기존 가상환경에서 실행한다. `ai/tests`에 있다면 먼저 `cd ..`로 이동한다. 테스트 파일을 `python test_....py`로 직접 실행하지 않고 `python -m pytest`를 사용한다.

```bash
# 1. 저장된 파일의 해시·참조·시각·금액 검증: 각각 status=VERIFIED
python -m copenhagen_fds.synthetic verify data/synthetic/fds-behavior-v1/stream
python -m copenhagen_fds.synthetic verify data/synthetic/fds-behavior-v1/challenge

# 2. 새 폴더로 재생성: 기존 v1을 덮어쓰지 않음
python -m copenhagen_fds.synthetic generate --output data/synthetic/fds-behavior-v1-reproduced

# 3. 도전 세트 미래 구간에서 100개 구매를 기존 Feature 계약으로 내보내기
python -m copenhagen_fds.synthetic export data/synthetic/fds-behavior-v1/challenge --output results/evaluation/synthetic-v1-check --limit 100

# 4. 생성·저장·시점/입력 호환성의 기본 테스트
python -m pytest tests/test_synthetic_generator.py tests/test_synthetic_artifacts.py tests/test_synthetic_replay.py -v --tb=short
```

`generate`는 `--seed`, `--users`, `--days`, `--train-days`, `--validation-days`, `--start`를 받는다. 기본값과 다른 조건은 새 폴더에 생성하고 manifest로 구분한다. 최소 사용자 12명·최소 기간 12일과 비어 있지 않은 세 분할이 필요하다. `export`는 `--split train|validation|test`를 받을 수 있다. 정상 종료는 0, 입력·파일 오류 또는 이미 존재하는 출력 경로는 2다. 기존 출력 경로를 삭제하거나 강제로 덮어쓰는 옵션은 제공하지 않는다.

내보내기는 도전 대상 구매를 우선 선택하고 나머지를 시각순 배경 구매로 채운다. `--limit`는 구매 수이며, 재시도가 있으면 마지막 시도의 평가점을 추가하므로 출력 줄 수가 더 많을 수 있다.

| 출력 | 내용 |
|---|---|
| `feature_inputs.jsonl` | 기존 `FeatureTransformInput` 형식의 모델 입력. 정답·시나리오·사용자 유형·피드백을 포함하지 않음 |
| `raw_features.jsonl` | 기존 변환기로 계산한 9개 원시 Feature와 미수집/부족 상태 |
| `targets.jsonl` | 평가 ID에 연결된 정답, 구매 분할·평가 분할·평가 시각. 평가 전용 |
| `report.json` | 선택 구매 수·평가점 수·분할 밖 생략 수, `model_scored=false` |

구매가 도착한 시각 또는 선택한 마지막 재시도가 도착한 시각의 1마이크로초 뒤를 고정 평가 시각으로 사용한다. 시간대/요일은 구매 발생 시각, 횟수는 이 평가 시각을 기준으로 한다. 평가는 주문·시도와 함께 시간이 흘렀을 때 일어나는 호출을 표현하며, 학습 프로필은 생성하지 않는다. 구매 분할이 과거라도 재시도 평가가 다음 구간에 속하면 해당 구간 export에서 제외하고 생략 수를 기록한다.

과거 24시간 중 평가 때 알려진 해당 사용자의 사건만 포함한다. 수집 범위는 가입 시각과 실제 합성 수집 시작 시각, 당시 공지된 수집 공백을 함께 반영한다. 지연 구간이 해소된 뒤에도 그 구간이 시간 창 안에 남아 있으면 보수적으로 부족 상태를 유지한다. 알 수 없는 기록을 0건으로 채우지 않는다. 현재 구매·현재 시도 제외와 재전송 중복 제거는 기존 Feature 변환기를 그대로 사용한다.

이 어댑터는 원천 자료가 기존 AI 입력과 연결되는지 확인하는 로컬 검증 도구다. GPT 분석 입력의 실제 사용자 발화를 합성하지 않으며 `intent.source=unknown`으로 둔다. 최종 통합과 학습 정책을 대신하지 않는다.

완료 기준별 확인은 다음과 같다.

1. **원본 보존·재현**: 두 `verify` 명령이 `VERIFIED`이며, 같은 설정으로 재생성한 두 세트의 manifest 안 데이터 파일 해시가 각각 같다.
2. **패턴과 정답 분리**: `truth.csv`에서 정상 고액·정상 야간·소액 반복·정상 모방 사기를 확인하고, `feature_inputs.jsonl`에는 `is_fraud`, `scenario`, `archetype`가 없는지 확인한다.
3. **데이터 부족과 중복 처리**: `targets.jsonl`의 `collection_gap`, `delayed_event`, `cold_start`, `duplicate_delivery`, `legitimate_retry`에 연결된 `raw_features.jsonl`을 확인한다. 부족 값은 `null`, 같은 구매의 이전 시도는 횟수에 반영된다.
4. **시간 경계·누출 방지**: `test_synthetic_replay.py`의 경계·미래 이벤트·첫 수집일·다음 분할 재시도 테스트가 통과해야 한다. 미래 피드백을 사용하지 않는 것은 입력 생성 경계 검증이며, 후속 학습 구현에서도 가용 시각을 별도로 검증해야 한다.

합성 패턴이 생성되고 계약 검증이 통과했다는 사실과 실제 FDS의 탐지 품질은 구분한다. 향후 모델에서 이 자료를 재생하면서 정상 고액·야간의 오탐, 삽입한 공격의 탐지, 평범해 보이는 사기의 한계, 표본 수별 개인화 효과를 별도로 측정한다.
