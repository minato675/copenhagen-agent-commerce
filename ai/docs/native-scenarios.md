# 통합 FDS 재현 시나리오

관련 Task: **S15P21A207-293**. 가상환경을 활성화하고 `ai/`에서 실행한다.
한 번 사용한 출력 폴더는 덮어쓰지 않는다. 다시 실행할 때는 폴더 이름에 `-2` 등을 붙인다.

## 1. 합성 입력을 만들고 평가 결과 비교하기

```bash
# 입력·Mock 응답·별도 기대값·별도 갱신 입력 생성. 아직 평가하거나 갱신하지 않음
python -m copenhagen_fds.native_cli prepare-demo --output results/evaluation/story8-input

# 17개 사례의 실제 통합 단계를 Mock 제공자 응답으로 실행한 뒤 기대값 비교
python -m copenhagen_fds.native_cli suite results/evaluation/story8-input --output results/evaluation/story8-run

# 저장된 비교 표 보기. 모델 재실행이나 GMS 호출 없음
python -m copenhagen_fds.native_cli show results/evaluation/story8-run

# 기록된 코드·런타임 확인, 고정 시계/Mock 재실행, 전체 결과·보고서 대조
python -m copenhagen_fds.native_cli verify-suite results/evaluation/story8-run
```

준비는 `PREPARED`, 실행은 `PASSED`와 `cases=17`, `reuse_checks=5`, 재현 검증은
`VERIFIED`와 `scenario_status=PASSED`가 예상 결과다. `show`는 저장된 결과를 보여주는 명령으로,
현재 코드에서의 재실행 검증은 `verify-suite`가 담당한다.

| 사례 | 확인할 내용 |
| --- | --- |
| `cold-normal` | 프로필 없는 Cold, 정상 완료/LOW |
| `cold-amount` | 금액 한 피처가 극단값이면 기본 정책에 따라 MEDIUM |
| `cold-combined` | 고액과 짧은 시간의 구매/시도 50건이 겹쳐 합산 HIGH |
| `transition` | 유효한 소량 이력의 금액 단계 TRANSITION |
| `personalized` | 충분한 예제 이력의 금액 단계 PERSONALIZED |
| `expired-profile` | 게시 프로필이 만료되면 Cold로 복귀 |
| `gpt-mismatch`, `gpt-unknown` | 불일치 확인 권고와 판단 부족 구분 |
| `missing-text`, `missing-history` | 미실행/자료 부족을 정상 0점으로 바꾸지 않음 |
| `missing-baseline`, `expired-baseline` | 기준이 없거나 만료된 경우 INCOMPLETE |
| `extraction-error`, `comparison-error`, `comparison-timeout` | 최초 실패·후속 미실행·실패 종류 보존 |
| `behavior-error`, `compound-error` | 행동 단독 실패 및 행동/추출 복합 실패 보존 |

표의 SUCCESS는 평가가 완료됐다는 뜻이다. 정상 거래 확정이나 결제 승인 의미가 아니다.
GPT MISMATCH는 행동 점수를 높이지 않아 LOW와 REVIEW_ORDER가 함께 나올 수 있다.
상세 점수·피처 단계·충분도·이유·권고·버전은 각 사례의 `result.json`, `summary.json`에 남는다.
전체 비교는 `comparisons.json`, 재사용 검사는 `reuse.json`에 있다.

재사용의 다섯 사례는 동일 입력 VALID, 주문 버전 변경 REEVALUATION_REQUIRED,
다른 사용자 INVALID_LINK, 행동 기준 시각 변경 REEVALUATION_REQUIRED, 명시한 만료 시각에서
REEVALUATION_REQUIRED다. 결과 기한은 실행 전 예제 시계에서 정한 30초이며 검사 때 연장하지 않는다.
실제 서비스의 결과 기한을 발급하거나 결제 권한을 주는 예제가 아니다.

## 2. 재현 조건과 합성 데이터 범위

기존 합성 관측 변환·학습 재생·프로필 게시·개인화 계산기를 재사용한다. 한 이용자의 과거 정상 확인
5건과 현재 가상 구매를 사용한다. 현재 가상 구매는 과거 프로필에서 제외하고, 일반 사례에서는 최근
24시간의 구매/시도가 없다고 명시한다. `cold-combined`에만 별도의 최근 구매/시도 이벤트를 추가한다.

세 단계를 짧게 시연하려고 **예제 전용** 프로필 최소 표본 2, 준비도 최소/전체 표본 2/4,
전체 관측 기간 1일, 프로필 유효기간 7일을 사용한다. 만료 사례는 1일 기한이다.
원래 설정은 `source-settings.json`, 예제 변경은 `demo-settings.json`에 보존한다.
점수 함수·위험 가중치·임계값은 결과에 맞춰 조정하지 않는다. 위험 정책은 현재 프로젝트 기본
`fds-risk-policy-v2`를 사용한다. 이 사례로 실제 개인화의 표본 충분성이나 탐지 성능을 입증하지 않는다.

`expected.json`은 모든 평가가 끝난 뒤에만 읽고 비교한다. 모델·프롬프트에는 기대 상태/위험/정답을
전달하지 않는다. Mock의 제공자 응답은 분석 단계를 확인하기 위한 입력이며 최종 FDS 결과가 아니다.
기존 완료 결과를 덧붙이는 평가 경로와 달리 행동 → 추출 → 비교 → 위험 정책을 실제 실행한다.
추출 오류 뒤 비교 미실행 등 실제 제어 흐름도 그대로 적용한다.

Mock 시각은 고정 시작점에서 1마이크로초씩 증가한다. 동기 처리 시간은 합성 예산에서 0으로 두므로
지연시간/SLA 검증으로 해석하면 안 된다. `comparison-timeout`은 명시적 실패 응답이고,
`behavior_fault=INTERNAL_ERROR`는 전용 순차 Mock 프로세스에서만 적용하는 장애 주입이다.
실제 시간 예산·취소 경계는 기존 통합 실행 테스트가 별도로 검증한다.

같은 입력·프로필·정책·Mock·코드·Python/기록된 의존성 버전에서는 결과와 보고서가 동일해야 한다.
코드나 런타임이 달라졌다면 재현 검증은 거절한다. 새 코드용 입력/출력을 새 폴더에 생성한다.
해시는 내용 대조용이며 원본 발급자의 인증 서명이 아니다.

## 3. 평가 후 독립적인 프로필 갱신 실행하기

```bash
python -m copenhagen_fds.native_cli update results/evaluation/story8-input/update-input.json --output results/evaluation/story8-update
```

이 명령은 FDS 평가나 GMS를 호출하지 않는다. 평가 이후 2분에 도착한 명시적 합성 정상 확인과
완전한 관측 재생으로 기존 `update_profile_policy`를 별도로 호출한다. 이전 게시 상태를 변경하지 않고
새 후보/게시 상태를 출력 폴더에 저장한다. 실제 서비스 DB에는 반영하지 않는다.

예상 결과는 `UPDATED`, `previous_state_unchanged=true`, `evaluation_executed=false`다.
새 거래의 원천 학습 적격성은 INCLUDE지만 기존과 다른 시간/요일 패턴 때문에 입장 정책은
`DEFER / ABRUPT_CHANGE`로 보류한다. **UPDATED는 모든 관측이 학습에 채택됐다는 뜻이 아니다.**
낮은 FDS 위험이나 결제 성공을 정상 라벨로 만드는 코드가 없다. `update-result.json`의 후보와
게시 상태·전이 이유를 함께 확인한다. 이미 평가한 결과는 이전 프로필을 유지한다.

## 4. 하나의 입력만 실행하거나 실제 GMS 확인하기

```bash
python -m copenhagen_fds.native_cli evaluate results/evaluation/story8-input/cold-normal.request.json --mock-script results/evaluation/story8-input/cold-normal.script.json --output results/evaluation/story8-single
python -m copenhagen_fds.native_cli verify results/evaluation/story8-single

# 실제 호출을 원할 때만 실행. 비용 및 현재 키/모델/네트워크 상태의 영향을 받음
python -m copenhagen_fds.native_cli evaluate results/evaluation/story8-input/cold-normal.request.json --mode gms --env-file .env --output results/evaluation/story8-gms
python -m copenhagen_fds.native_cli verify results/evaluation/story8-gms
```

GMS 검증 명령은 기록의 계약·해시·연결만 확인하고 `reproduction=NOT_RUN_GMS`로 반환한다.
실제 응답을 다시 받거나 Mock 기대값에 맞추지 않는다. 정상이라면 제공자 단계는 추출/비교 각 1회,
추출 실패면 비교는 미실행이다. 성공·실패·미실행은 실제 결과로 확인한다.
이 문서의 실제 GMS 명령은 실행 방법이며 이번 자동 테스트에서 실제 호출을 완료했다는 뜻이 아니다.
현재 GMS 가용성/의미 판단 품질, 실거래 탐지 품질·오탐률·응답시간은 별도 검증 범위다.

## 완료 기준별 직접 테스트

관련 Task: **S15P21A207-293**. 위 명령도 직접 검증에 포함되며 아래는 자동 테스트 선택 방법이다.

```bash
CLI_TESTS=tests/learning/personalization/replay/engine/analysis/finalization/cli

# 기준 1: 3단계·GPT·누락/만료·단일/복합 장애 비교
python -m pytest "$CLI_TESTS" -v --tb=short -k "all_scenarios or real_pipeline or scripted_behavior"

# 기준 2: 기본 오프라인 / 명시적 GMS / 비노출 / 실제 모드 재호출 방지
python -m pytest "$CLI_TESTS/test_cli.py" -v --tb=short

# 기준 3: 고정 재현·출력 보호·기대값 분리·평가와 갱신 독립성
python -m pytest "$CLI_TESTS" -v --tb=short -k "preparation or reproduce or fixed_native or output or expectation or verification or update"

# 기준 4: 기존 FDS·GMS Mock 전체 회귀
python -m pytest -q --tb=short
```

선택한 테스트는 모두 PASSED여야 한다. 사례 자체에 FAILED/INCOMPLETE가 있어도 정해진 실패/부족
처리를 보존하면 테스트는 통과한다. 사용자 확인 전에는 Jira/MR의 체크박스를 체크하지 않는다.
