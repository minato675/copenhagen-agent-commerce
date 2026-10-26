# 시간순 FDS 평가

이 도구는 기존 행동·프로필·GPT 모듈의 평가 입력을 고정한다. 실제 결제 서비스,
PG 호출, 승인 집행은 포함하지 않는다. 합성 결과는 실거래 탐지 성능의 증명이 아니다.

## 입력 고정과 직접 검증

가상환경을 활성화한 뒤 `ai/`에서 실행한다. 출력은 존재하지 않는 새 디렉터리를 사용한다.

```bash
python -m copenhagen_fds.temporal_benchmark prepare \
  --settings examples/personal-replay-settings.json \
  --suite stream --seed 20260916 --users 12 --days 12 \
  --train-days 4 --validation-days 4 --limit 2 \
  --output results/evaluation/temporal-inputs-stream

python -m copenhagen_fds.temporal_benchmark verify-inputs \
  results/evaluation/temporal-inputs-stream
```

첫 명령은 `PREPARED`, 두 번째는 `VERIFIED`를 반환한다. 이 단계의 `model_scored=false`,
`external_api=NOT_RUN`은 정상이다. 아직 모델 평가나 GMS 호출을 수행한 것이 아니다.

`--suite challenge`는 시나리오가 강화된 진단용 데이터다. 기본 stream과 합쳐 Precision을
계산하지 않는다. `--role CANDIDATE_HOLDOUT --variant DOUBLE_AMOUNTS --seed 20260917`은
기존 seed와 금액 분포가 다른 최종 시험 후보를 만든다. 새 seed만으로 일반화가 입증되지
않으며, 이미 결과를 보고 조정한 데이터는 회귀용으로 다시 분류한다. 이 도구가 사람의
미열람 여부나 정답 검토 완료를 인증하지 않는다.

기본 120일은 앞 60일 학습, 다음 30일 조정, 마지막 30일 시험이다. `--split validation`으로
조정 구간을 선택한다. `plan.json`에는 기간, 설정 전체, 위험 정책, 표본 상한과 집계 기준을
저장한다. 가중치·임계값은 조정 구간에서만 검토하고 최종 시험에 맞춰 반복 수정하지 않는다.
위 빠른 확인 명령은 실행 시간을 줄이기 위해 명시적으로 12명·12일·2건을 사용한다.
작은 실행의 성공은 기본 120명·120일 전체 평가의 성공을 의미하지 않는다.

## 입력과 정답의 분리

| 파일 | 용도 |
| --- | --- |
| `plan.json` | 고정된 데이터·프로필·개인화·위험 정책 및 평가 기준 |
| `observations.json` | 사용자·구매·이벤트·결과·도착한 검증 피드백·수집 공백 원천 자료 |
| `selection.json` | 발생/도착 시각으로 고른 거래 ID, 정답 기반 선택 없음 |
| `semantic-observations.json` | 명시적으로 만든 합성 발화와 파생 주문서 |
| `evaluator-labels.json` | 사기/이탈 축·사용자 유형·의도 비교 정답, 평가자만 사용 |
| `manifest.json` | 파일/계획/생성 코드 해시와 출처·실행 여부 |

`read_inputs()`는 정답 파일을 열지 않는다. 평가 도구는 예측을 기록한 다음 `read_targets()`로
정답을 읽는다. profile 학습에 쓰는 feedback은 합성 truth와 별도이며 `available_at` 전에는
사용할 수 없다. 결제 성공이나 미탐을 정상 학습 정답으로 취급하지 않는다.

기존 행동 합성 데이터에는 사용자 원문이 없다. 새 발화는 복구한 원문이 아니라 독립적인
합성 시나리오다. recipe는 seed와 거래 ID로 선택하며 사기 정답·점수에 의존하지 않는다.
원래 거래의 금액·통화·시각·식별자는 보존하고 상품 의미를 구성한다. 새 상품 내용에는 새
snapshot 참조/해시를 사용하고 원본 snapshot 참조를 별도 보관한다.

원문 일치·상품 불일치·필수 속성 부재·Prompt Injection·원문 없음과 행동 사기 라벨을
각각 기록한다. Mock에 주입하는 GPT 응답은 연결 동작 검증용이며 실제 의미 판단 품질로
보고하지 않는다. 사람의 정답 검토 상태는 `NOT_PERFORMED`로 명시한다.

## 집계 기준과 한계

- 구매 요청의 최초 평가를 본 성능집계 단위로 사용한다. 재시도는 별도 보조 검증이다.
- 사건과 정보 도착이 선택 기간 안인 거래만 본 집계에 사용하고 기간 밖 지연 도착 수를 기록한다.
- 행동 사기 판정은 기록된 정책의 `medium_from` 이상 점수로 고정한다. 위험 floor 규칙과
  GPT 확인 권고를 이 점수 임계값 지표와 혼합하지 않는다.
- 순위 지표는 동일 점수를 한 그룹으로 묶는 Average Precision으로 정의한다.
  사다리꼴 적분 PR-AUC와 구분하여 이름을 표시한다.
- 점수가 없는 사례를 0점으로 채우지 않는다. 점수 지표의 분모와 평가 가능 비율,
  실패·판단 부족·검토 요청 비율을 함께 보고해야 한다.
- 초기 정책과 기본 readiness는 경험적으로 검증되지 않았다. 이 평가가 자동으로
  `UNVALIDATED` 상태를 운영 검증 완료로 바꾸지 않는다.

## 데이터 계약 검증

```bash
python -m pytest tests/test_temporal_benchmark_protocol.py \
  tests/test_temporal_benchmark_dataset.py tests/test_temporal_semantic_cases.py \
  tests/test_temporal_benchmark_preparation.py -v --tb=short
```

테스트는 시간 분할·고정 설정, 정답 비노출, 원문/주문서 연결, 산출물 무결성과 반복 생성의
동일성을 확인한다. 사기 탐지 성능이 좋다는 것을 확인하는 테스트는 아니다.

## 시간순 모델 실행과 재현 확인

관련 Task: S15P21A207-357. 같은 `ai/` 위치에서 실행한다.

```bash
python -m copenhagen_fds.temporal_benchmark run \
  results/evaluation/temporal-inputs-stream --output results/evaluation/temporal-run-stream
python -m copenhagen_fds.temporal_benchmark verify-run \
  results/evaluation/temporal-inputs-stream --output results/evaluation/temporal-run-stream
```

각각 `EXECUTED`, `REPRODUCED`를 반환한다. 구매마다 실제 Native FDS를 Cold 전용과
개인/집단 프로필 적용 조건으로 두 번 실행한다. `predictions.jsonl`에 예측을 먼저 기록하며
이 명령들은 `evaluator-labels.json`을 읽지 않는다. 비교 점수와 프로필 참조가 동일한지 확인하는
재현 검증은 API를 호출하지 않는다. 실행 시각의 소스와 런타임이 달라지면 재현 검증은 거절되므로,
기록한 코드 버전으로 돌아가거나 새 출력 폴더에 새 실험으로 실행해야 한다.

프로필은 평가 직전까지 도착한 정보로 메모리에서 생성·갱신한다. 현재 구매는 학습에서 제외하며,
현재 구매 제외나 집단 구성 변경으로 재구축한 경우에는 이유와 이전/새 상태 버전을 남긴다.
프로필 계산 실패는 실패 이력과 프로필 부재로 기록한다. 이 상태를 실제 운영 DB에 당시 존재했던
프로필이라고 해석하면 안 된다. 재시도·결과 재사용은 별도 계약 회귀에서 확인하며 본 표본에 더하지 않는다.

이 단계의 GPT 제공자는 원문이 있으면 항상 MATCH를 반환하는 명시적인 Mock이다. 행동 점수는
실제 계산하지만 의미 판단 품질은 측정하지 않는다. 합성 고정 시계의 응답 시간은 `null`이며,
전체 `wall_duration_seconds`는 로컬 재생·검증 비용이지 서비스 응답 시간이나 SLA가 아니다.
사용자 수와 이력이 커지면 프로필 재계산 비용도 커진다. 작은 계획으로 실행 경로를 먼저 확인하고
측정 규모를 늘린다. 표본 상한을 낮춰도 해당 시점까지의 학습 이력을 임의로 잘라내지 않는다.

```bash
python -m pytest tests/test_temporal_profile_timeline.py \
  tests/test_temporal_benchmark_execution.py -v --tb=short
```

도착 시각·중복/역순 정보·프로필 전환과 정답 비노출을 확인한다. 중간에 실패한 실행은
부분 journal을 남기지만 완료 manifest를 만들지 않으므로 정상 완료 결과로 집계할 수 없다.

## Cold와 개인화 비교

관련 Task: S15P21A207-358. 예측 완료 뒤에만 평가자 정답을 결합한다.

```bash
python -m copenhagen_fds.temporal_benchmark score \
  results/evaluation/temporal-inputs-stream --run results/evaluation/temporal-run-stream \
  --output results/evaluation/temporal-score-stream
```

`report.json`의 `behavior`는 두 조건의 혼동행렬·Precision·Recall·오탐률·Average Precision과
점수 산출률을 제공한다. `paired_scored`는 양쪽 모두 정상 산출된 같은 거래끼리만 비교한다.
`strata`에서 개인화 금액 단계·학습 표본 수·사용자 유형·시나리오별 거래 수와 지표를 확인한다.
분모가 0인 지표는 `null`이다. 실패했지만 보존된 점수는 정상 산출 점수의 성능 분모에서 제외한다.
단계·시나리오가 결과에 없으면 이번 표본으로 확인하지 못한 것이며, 통과로 간주하지 않는다.

비교 arm의 `personalized`는 개인·집단 프로필을 사용할 수 있게 한 실행 조건 이름이다.
`cold`는 프로필을 주지 않은 고정 초기 기준이며, 프로필 허용 조건에서는 피처가 `COLD`여도
집단 기준이 적용될 수 있다. 따라서 두 arm의 점수 차이만으로 개인 학습의 개선이라고
해석하지 않는다. `strata.amount_stage`는 금액 피처의 단계이며 다른 피처 전체의 단계가 아니다.
실제 피처별 단계와 사용한 기준을 함께 확인해야 하고, 개인 기준만의 효과를 분리하려면
같은 집단 기준을 유지하는 별도 사전 고정 비교가 필요하다.

기간 끝의 명시적 challenge 사례를 모두 살펴보는 작은 진단 계획은 다음과 같다.

```bash
python -m copenhagen_fds.temporal_benchmark prepare \
  --settings examples/personal-replay-settings.json --suite challenge \
  --users 12 --days 12 --train-days 10 --validation-days 1 --limit 1000 \
  --output results/evaluation/temporal-inputs-challenge
```

같은 `run`·`score` 명령에서 입력/출력 경로를 바꿔 실행한다. 명시적 probe 사용자가 추가되므로
결과의 전체 사용자 수는 기본 사용자 12명보다 많다. 기본 stream도 같은 기간으로 별도 실행한다.
짧은 기간에는 학습 피드백과 개인화 단계가 부족할 수 있으므로, 기본 120일 계획의 소규모 표본을
추가하더라도 각각의 조건과 결과를 분리해서 해석한다. 시험 결과에 맞춰 설정을 자동 수정하지 않는다.

## 결합 및 예외 계약 회귀

관련 Task: S15P21A207-359.

```bash
python -m copenhagen_fds.temporal_benchmark regressions \
  --settings examples/personal-replay-settings.json --output results/evaluation/temporal-regressions
python -m copenhagen_fds.temporal_benchmark verify-regressions results/evaluation/temporal-regressions
```

Native 평가 17개·재사용 5개, Provider 계약 27개, GPT 계약 35개, 낮은/높은 행동 위험과
MATCH/MISMATCH/UNKNOWN/실패 조합 8개를 실행한다. 정상 회귀 결과는 총 92개, 실패·미실행 0개다.
통제된 응답을 넣는 Mock 회귀이며 실제 Prompt Injection 방어 품질이나 API 응답 품질을
측정한 것이 아니다. `verify-regressions`는 저장 파일 확인만 수행하고 평가를 다시 실행하지 않는다.

## 실제 GMS와 저장 응답 재집계

관련 Task: S15P21A207-360. 다음 준비는 무료이며 키를 읽지 않는다. 원문이 있는 구매가
20건 이상인 기준 입력에서 앞 12건을 선택하고, 같은 표본에 대해 두 번의 전체 추출/비교를 준비한다.
사기 정답이나 모델 결과로 표본을 고르지 않는다. 최대 API 호출은 48회다.

```bash
python -m copenhagen_fds.temporal_benchmark.gms_cli prepare \
  results/evaluation/temporal-inputs-challenge --output results/evaluation/temporal-gms-plans
```

실제 호출은 별도 `collect` 명령에서만 한다. 출력된 해당 round의 `plan_sha256`을 정확히 사용한다.
키는 `.env`의 `GMS_KEY`로 제공하며 키 값을 명령에 넣지 않는다. 저장된 결과에 다시 호출하지 않는다.

```bash
python -m copenhagen_fds.temporal_benchmark.gms_cli collect \
  results/evaluation/temporal-gms-plans --round 1 --approve-plan-sha256 <round-1의-plan_sha256> \
  --env-file .env --output results/evaluation/temporal-gms-round-1
```

두 번째 호출은 `--round 2`, round 2 해시, 새 `temporal-gms-round-2` 경로를 사용한다.
키·사용량 오류나 연속 실패 시 기존 수집기 기준으로 중단하고 실패/누락을 보존한다.
완료 여부, 실제 HTTP 수, 응답 모델, 세부 추출 검사, 의미 혼동행렬, 관측 토큰과 실행 시간을
`report.json`에서 확인한다. 인간이 검토하지 않은 작은 합성 표본이며 운영 품질 합격 판정을 하지 않는다.

아래 명령은 저장된 응답만 재집계하므로 추가 API 호출이 없다.

```bash
python -m copenhagen_fds.temporal_benchmark.gms_cli rescore \
  results/evaluation/temporal-gms-plans --round 1 --run results/evaluation/temporal-gms-round-1
python -m copenhagen_fds.temporal_benchmark combine-gms \
  results/evaluation/temporal-inputs-challenge --run results/evaluation/temporal-run-challenge \
  --gms-plans results/evaluation/temporal-gms-plans \
  --gms-run results/evaluation/temporal-gms-round-1 --gms-run results/evaluation/temporal-gms-round-2 \
  --output results/evaluation/temporal-combination
python -m copenhagen_fds.temporal_benchmark verify-combination results/evaluation/temporal-combination
```

결합 결과는 저장된 과거 행동 근거에 보관한 GMS 판단을 적용한 정책 재평가다. 실제 호출 시각과
행동 평가 시각을 별도로 보존하고 `live_end_to_end=false`로 표시한다. 의미 판단·추출 payload·
최종 권고의 반복 변동도 나눠 기록한다. 실제 결제 승인·차단을 실행하지 않는다.

## 최종 보고서와 직접 테스트

관련 Task: S15P21A207-361. 기본 명령은 입력만으로도 보고서를 만들지만, 빠진 실험은 `NOT_RUN`으로 남긴다.

```bash
python -m copenhagen_fds.temporal_benchmark report \
  results/evaluation/temporal-inputs-challenge --run results/evaluation/temporal-run-challenge \
  --score results/evaluation/temporal-score-challenge --regressions results/evaluation/temporal-regressions \
  --gms-plans results/evaluation/temporal-gms-plans \
  --gms-run results/evaluation/temporal-gms-round-1 --gms-run results/evaluation/temporal-gms-round-2 \
  --combination results/evaluation/temporal-combination --output results/evaluation/temporal-final-report
```

`report.md`는 읽기용 요약, `summary.json`은 집계와 출처 참조, `manifest.json`은 두 파일의 해시다.
서로 다른 데이터의 stream/challenge 보고서는 따로 만든다. 파일 무결성 검증, 예측 재실행,
GMS 저장 응답 재집계, 실제 서비스 전체 통합을 구분한다. 최종 보고서는 예측을 다시 실행하지
않으므로 재생 실행 자체를 자동으로 통과 처리하지 않는다. 위 `verify-run`으로 별도 확인한다.

모든 신규 평가 도구와 검증 캐시의 테스트를 실행하려면 다음을 사용한다. 실제 GMS 호출은 없다.

```bash
python -m pytest tests -k "temporal or validation_memo" -v --tb=short
```

전체 명령에는 실제 Native/Provider 회귀 실행도 포함되어 수 분 이상 걸릴 수 있다.
빠르게 확인하려면 `tests/test_temporal_benchmark_metrics.py` 또는 `tests/test_temporal_gms_cli.py`처럼
확인할 파일을 지정한다. `python test_...py`로 실행하면 pytest가 테스트를 수집하지 않는다.
실패한 명령의 종료 코드는 1 또는 2이며, 새 출력 폴더를 사용하고 `manifest.json` 및
부분 `predictions.jsonl`/`observations.json`을 함께 확인한다. 비밀정보나 인증 헤더를 로그에 넣지 않는다.
