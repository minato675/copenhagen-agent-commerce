# 고정 합성 이력의 학습 표본 내보내기

기존 `fds-behavior-v1`의 장기 스트림과 도전 세트를 그대로 읽습니다. 데이터 생성기를 다시 실행하지 않습니다. [시간순 학습 표본](learning-replay.md)의 입력 어댑터와 `export-learning` 명령을 제공합니다.

## 읽는 자료와 처리 규칙

읽는 JSONL은 `users`, `purchases`, `events`, `outcomes`, `feedback`, `coverage_gaps` 여섯 개입니다. `manifest.json`에서 데이터 버전·합성 출처와 이 파일들의 SHA-256·행 수를 확인합니다. `truth.jsonl`, `personas.jsonl`, `truth.csv`는 열지 않습니다. 기존 전체 데이터 검증 도구가 정답 자료까지 검사하는 것과 달리, 이 학습 경로는 관측 자료의 무결성만 확인합니다.

출처 참조는 관측 파일 6개의 해시로 계산합니다. 정답 파일의 내용이나 시나리오명으로 표본을 선택하지 않습니다. 필요하면 원천 전체 자료의 의미 검증을 기존 `verify` 명령으로 별도 수행할 수 있습니다.

1. 학습 기준 시각까지 도착한 구매·이벤트·결제 결과·후속 확인을 선택합니다. 가용 시각을 적용한 뒤 동일 ID의 충돌을 검사합니다.
2. 동일 ID·내용은 한 번만 사용합니다. 다른 ID가 같은 결제 시도를 가리키면 발생 시각·통화·주문이 일치해야 하며, 최초 가용 시각과 ID 순으로 대표 사건을 선택합니다. 충돌은 오류로 반환합니다.
3. 당시 구매 평가의 Feature는 기존 `ReplayIndex.feature_input`으로 구성합니다. 합성 데이터에는 운영 평가 시각이 없으므로 기존 규칙인 구매 가용 시각 + 1마이크로초를 재생 평가점으로 사용합니다. 시각을 매 실행의 현재 시간으로 채우지 않습니다.
4. 알려진 후속 확인 중 발생 시각이 가장 최근인 기록을 사용합니다. 늦게 도착한 과거 검토가 새 검토를 덮어쓰지 않습니다. 같은 발생 시각의 확인 내용이 다르면 거부합니다. 결제 결과도 시도별 같은 원칙으로 선택합니다.
5. `SYNTHETIC_REVIEW`는 합성 실험에서 검증된 후속 검토를 표현하므로 어댑터가 명시적으로 `VERIFIED`를 연결합니다. 실제 검토자 인증이나 실제 라벨의 신뢰도를 보장하는 설정은 아닙니다.
6. 구매 시작 사건이 아직 관측되지 않았거나 평가점이 학습 상한 이상이면 표본을 만들지 않습니다. 필요한 사건·시도 참조를 발명하지 않습니다. 다른 주문 버전의 정정·재생과 프로필 반영 철회는 후속 갱신 작업에서 처리합니다.

Feature에는 거래 당시의 수집 시작·공백 정보를 적용합니다. 아직 알려지지 않았던 수집 공백을 과거 평가에 미리 반영하지 않습니다. 적격 정책에는 보유한 수집 메타데이터와 학습 시각에 알려진 구매 사건을 전달하며, 메타데이터의 완전 수집 구간을 학습 시각까지 임의로 연장하지 않습니다.

## 전체 입력 연결 검증

가상환경을 활성화한 Git Bash에서 저장소의 `ai` 폴더로 이동한 뒤 실행합니다. 출력은 새 폴더여야 하며 기존 결과를 덮어쓰지 않습니다.

```bash
python -m copenhagen_fds.synthetic export-learning data/synthetic/fds-behavior-v1/stream --as-of '2026-05-01T00:00:00+09:00' --output results/evaluation/learning-stream-check
python -m copenhagen_fds.synthetic export-learning data/synthetic/fds-behavior-v1/challenge --as-of '2026-05-01T00:00:00+09:00' --output results/evaluation/learning-challenge-check
```

현재 고정 데이터와 이 기준 시각의 예상 결과입니다.

| 세트 | 표본 | 학습 반영 INCLUDE | 보류 DEFER | 제외 EXCLUDE |
| --- | ---: | ---: | ---: | ---: |
| 장기 스트림 | 11,210 | 2,465 | 8,671 | 74 |
| 도전 세트 | 6,510 | 1,409 | 5,076 | 25 |

도전 세트의 원천 구매는 6,512개지만 2건의 구매 시작 사건은 이 기준 시각까지 도착하지 않아 빠집니다. 후속 확인이 아직 도착하지 않은 사기 구매도 미확인 보류일 수 있습니다. 따라서 이 숫자는 실제 사기 탐지 성능이나 전체 사기 정답 건수와 다릅니다.

이 명령은 전체 기간의 입력 연결·회귀 확인용입니다. 기존 학습/검증/테스트 시간 분할을 지키며 학습 자료를 만들 때에는 학습 기간 종료인 `--as-of '2026-03-02T00:00:00+09:00'` 등을 사용합니다. 기준 시각 뒤의 확인 결과는 앞선 학습 기간에 들어가지 않습니다.

빠른 확인에는 `--limit 100`을 추가할 수 있습니다. 표본을 시각순으로 제한해도 선택한 구매의 과거 Feature 이력은 잘라내지 않습니다. 전체 학습 자료를 만들 때에는 limit를 생략합니다.

## 결과와 재현 확인

출력 폴더에는 다음 파일이 생성됩니다.

- `learning_samples.jsonl`: 구매당 하나의 표본. 과거 원시 Feature 9개, 학습 적격 상태·사유, 두 기준 시각, 구매 참조·출처·수집 범위가 들어갑니다.
- `report.json`: 기준 시각·스키마·정책·원천 참조, 표본과 적격 상태/Feature 가용성 집계, 표본 파일 SHA-256이 들어갑니다. `profiles_fitted=false`, `model_scored=false`입니다.

명령이 성공하면 `status: EXPORTED`를 출력합니다. 같은 입력과 옵션으로 다른 새 출력 폴더에 다시 실행하면 `samples_sha256`이 같아야 합니다. JSONL 파일을 다음처럼 열어 첫 표본을 확인할 수 있습니다.

```bash
python -c 'import json; from pathlib import Path; p=Path("results/evaluation/learning-stream-check/learning_samples.jsonl"); print(json.dumps(json.loads(p.open(encoding="utf-8").readline()), ensure_ascii=False, indent=2))'
```

원천 이력과 출력 파일에는 사용자·구매 참조가 있으므로 운영 자료를 사용할 때 원문을 로그로 출력하지 않습니다. 위 명령은 이 합성 데이터의 직접 확인용입니다.

자동 검증과 완료 기준별 명령은 [재생 검증 안내](learning-replay.md#직접-검증)에 있습니다. 실제 데이터 및 실행 결과는 기존 Git 제외 경로인 `ai/data/`, `ai/results/`에 보관합니다.
