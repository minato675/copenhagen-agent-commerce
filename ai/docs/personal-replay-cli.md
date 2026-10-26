# 개인화 재생 CLI 직접 검증

`ai/`에서 기존 가상환경을 활성화한 뒤 실행한다. Git Bash에서는
`source .venv/Scripts/activate`를 사용한다. 외부 API 키·서비스 DB는 필요하지 않다.

원본은 `data/synthetic/fds-behavior-v1/{stream,challenge}`다. 이 자료가 없는 환경에서는
`python -m copenhagen_fds.synthetic generate --output data/synthetic/fds-behavior-v1`로 생성한다.
이미 있는 고정 자료는 보존한다. `examples/personal-replay-settings.json`은 프로필·반영 정책·
개인화 충분도·수식·초기 Cold 기준을 명시한 검증용 설정이며 실거래 성능으로 보정한 값이 아니다.

## 실제 구매 재생

```bash
python -m copenhagen_fds.personal_replay_cli replay data/synthetic/fds-behavior-v1/stream --settings examples/personal-replay-settings.json --as-of "2026-04-01T00:00:00+09:00" --start "2026-04-01T00:00:00+09:00" --end "2026-04-02T00:00:00+09:00" --limit 6 --output results/evaluation/personal-stream-manual-270-01
```

기본 고정 스트림에서는 평가점 6개가 산출된다. 같은 명령에서 `stream`을 `challenge`로 바꾸고
출력 폴더 이름도 바꾸면 도전 세트에서 평가점 7개를 확인할 수 있다. 두 세트는 독립적인 자료로
실행·보고한다. 전체 120일 구매를 전수 평가한 수치가 아니다.

`--as-of`는 프로필 학습 cutoff이며 시작 시각 이하, 종료 시각 미만이어야 한다. 대상 구매는
도착 시각이 `[start,end)`인 구간에서 선택한다. 현재 구매는 프로필에서 제외된다.

## 이용자별 동일 조건과 이력 축적 비교

```bash
python -m copenhagen_fds.personal_replay_cli compare data/synthetic/fds-behavior-v1/stream --settings examples/personal-replay-settings.json --cutoffs "2026-01-10T14:00:00+09:00" "2026-01-26T14:00:00+09:00" "2026-03-30T14:00:00+09:00" --expired-at "2026-04-07T14:00:00+09:00" --output results/evaluation/personal-compare-manual-270-01
python -m copenhagen_fds.personal_replay_cli show results/evaluation/personal-compare-manual-270-01
```

기본 100,000원 probe, 2명 × 5개 시점으로 **10개 평가**가 나온다. 각 시점의 이용자 간
금액·시간대·직전 24시간 구매/시도 0건 조건이 같다. 실제 최근 활동을 재생하는 입력은 아니며
프로필 차이만 비교하기 위한 통제 조건이다. 금액은 `--amount-minor`, 이용자는 `--users ID1 ID2`,
기본 선택 인원은 `--user-limit`으로 변경할 수 있다. `--expired-at`은 마지막 cutoff에 설정된
프로필 유효 기간을 더한 시각 이상이어야 한다.

`show`는 파일 검증·점수 재연산 후 아래 열을 출력한다.

```text
case_id | stage | Cold | personal | weight | combined
```

다른 Feature는 예를 들어 `show 폴더 --feature purchase_count_10m`로 확인한다. `null`은 0점이
아니라 미산출/미실행이다. 자세한 충분도·원인·원시 값은 `comparisons.json`과 `evaluations.jsonl`에 있다.

현재 고정 스트림의 기본 선택 이용자 두 명은 마지막 cutoff에서 금액 표본 11개/8개로 충분
30개에 못 미쳤다. 개인 비중 0.24/0.12, 개인 점수 약 47.8982/100, 최종 약 11.4956/12점이다.
두 명 모두 전환 단계이며 만료 후 Cold 0점으로 돌아간다. 개인화 100% 단계까지 갔다고
해석하지 않는다. 전체 단계 진행의 손계산 경계는 아래 완료 기준 2의 테스트로 별도 확인한다.

## 출력과 재현성

| 파일 | 내용 |
| --- | --- |
| `request.json` | 실제 설정과 평가별 관측 입력·cutoff |
| `evaluations.jsonl` | 한 줄에 평가 1개: 입력, 공개 프로필/정책 상태, 상세 개인화 결과 |
| `comparisons.json` | Cold·개인·최종 점수, 차이, 단계·비중·표본 수·사유 |
| `report.json` | 파일 해시, 소스 코드·Python/Pydantic 버전, 관측 파일 출처, 실행/coverage/단계 집계 |

`status=SCORED`는 평가 내보내기 성공을 뜻한다. 실제 점수 제공 수는 `scored_features`,
범위는 `coverage`, 실행 실패는 `execution`에서 확인한다. 부족한 기준을 성공적인 탐지로
해석하지 않는다. 이 결과는 위험등급·사기 확률·실거래 탐지율·오탐률을 제공하지 않는다.

출력은 반드시 새 폴더여야 한다. 기존 폴더, 데이터셋 내부·동일 경로·상위 경로에는 쓰지 않는다.
계산/계약 검증을 마친 후 출력 폴더를 만들고 완료 표시인 `report.json`을 마지막에 쓴다.
디스크 오류로 중단된 출력은 자동 삭제하지 않으며 다음 실행에는 새 폴더를 사용한다.
생성 결과는 기존 `results/evaluation/` Git 제외 규칙을 따른다.

```bash
python -m copenhagen_fds.personal_replay_cli verify results/evaluation/personal-compare-manual-270-01
```

성공은 **status=VERIFIED**다. 각 파일 해시, 설정·대상 연결, Raw Feature 재생, 실제 점수·비중·
단계 재연산, 요약 일치를 확인한다. 원본 관측 자료에서 공개 프로필을 다시 구축하는 확인은
같은 `compare`/`replay` 명령을 다른 출력 폴더로 재실행하여 수행한다.

동일 관측 파일·명시 시각·설정·코드·런타임이면 네 파일이 바이트 단위로 일치한다. 파일 경로와
실행한 현재 시각은 해시에 넣지 않는다. 소스 LF/CRLF 차이는 정규화한다. 이후 코드·런타임을
바꾸었다면 기존 결과의 `verify`는 거절할 수 있으므로 기록된 버전 환경에서 재현한다.
해시는 변경 탐지용이며 외부 서명이 아니다. 당시 일시적 계산 실패는 정상 재시도와 다를 수 있다.

종료 코드는 정상 평가/검증 **0**, 설정·파일·시각·출력 경로 오류 **2**, 결과에 계산 실패가
있는 경우 **3**이다. 잘못된 입력의 원문·키·경로를 오류 로그에 노출하지 않는다.

## Task 270 완료 기준별 테스트

```bash
python -m pytest tests/learning/personalization/replay -v --tb=short
```

예상 결과는 **48 passed**다. 테스트 파일을 `python test_*.py`로 직접 실행하지 않는다.

| 완료 기준 | 실행 명령 | 예상 결과 |
| --- | --- | --- |
| 1. 반복 실행·JSON·코드 버전 재현 | `python -m pytest tests/learning/personalization/replay -k "roundtrip or byte_identical or code_version" -v --tb=short` | 3 passed |
| 2. 이용자별 동일 거래·Cold→전환→개인화→만료 | `python -m pytest tests/learning/personalization/replay/test_replay.py -k "same_probe" -v --tb=short` | 1 passed. 실제 계산 0/0/50/100/0점과 다른 이용자의 0점을 대조 |
| 3. 미래·현재 구매·정답/페르소나 분리 | `python -m pytest tests/learning/personalization/replay -k "hidden_or_future or future_review or current_purchase or never_opened" -v --tb=short` | 10 passed |
| 4. CLI·출력 격리·무결성·고정 생성기 회귀 | `python -m pytest tests/learning/personalization/replay/test_export_cli.py -v --tb=short` | 24 passed |
| 4. 잘못된 대상·cutoff·선택 설정 | `python -m pytest tests/learning/personalization/replay/test_replay.py -k "invalid_replay or selection_errors" -v --tb=short` | 13 passed |

전체 회귀: `python -m pytest -q --tb=short` → Task 270 완료 시점 **2219 passed**.
선택 테스트는 일부 겹치므로 합산하지 않는다. `deselected`는 실패가 아니며, 잘못된 입력을
예상대로 거절하는 테스트도 PASSED다. 기존 FDS/Cold/GMS Mock 회귀를 포함하며 실제 GMS를 호출하지 않는다.
