# FDS 제공자 Mock 실행과 BE 인수인계

관련 Task: S15P21A207-304. 실제 [제공자 경계](provider-runtime.md)를 통과하는 로컬 실행 도구다. 합성 요청으로 인증·권한·설정 검증, 평가, 프로필 생성/갱신, 응답 변환을 재현한다. GPT 응답만 정해 둔 구조화 응답으로 대체하며 행동 계산과 위험 정책은 기존 엔진을 실행한다.

현재 전송 독립 Python 계약이며 HTTP 서버·URL·상태 코드·실제 토큰 발급은 제공하지 않는다. 호출 주체/시점·Core 사실 조립·운영 제한 시간은 300번의 미확정 항목이다. 이 도구의 합격은 실제 서비스 연결이나 사기 탐지 정확도를 뜻하지 않는다. 원문·의도·프로필·수집 상태의 저장소 소유자는 AI다.

## 처음 실행

저장소의 `ai/`에서 가상환경을 활성화한다. 설치가 필요한 경우 `python -m pip install -e ".[test]"`를 사용한다. PowerShell에서는 `python` 대신 `.\.venv\Scripts\python.exe`를 사용할 수 있다.

```bash
python -m copenhagen_fds.provider_cli prepare-demo --output results/evaluation/provider-input-304
python -m copenhagen_fds.provider_cli suite results/evaluation/provider-input-304 --output results/evaluation/provider-run-304
python -m copenhagen_fds.provider_cli show results/evaluation/provider-run-304
python -m copenhagen_fds.provider_cli verify-suite results/evaluation/provider-run-304
```

1. `prepare-demo`: `PREPARED`, 27개 사례. 평가/갱신을 실행하지 않고 요청·설정·예상값·JSON Schema를 작성한다. 이전 프로필 상태는 독립적인 합성 정상 확인 이력으로 구성한다.
2. `suite`: 모든 요청을 실행한 다음 `expected.json`을 읽어 비교한다. `PASSED`, `cases: 27`, `contract_failures: 0`이 예상된다.
3. `show`: 사례별 실제 상태·위험·금액 단계·완료 호출 수·handler 실행 수와 기대값 일치 여부를 표시한다. `UNAUTHENTICATED` 옆의 `true`는 인증 실패를 올바르게 재현했다는 뜻이며 모델 성공이 아니다.
4. `verify-suite`: 기록한 코드/런타임과 파일 해시를 확인하고 제공자를 다시 실행한다. 전체 응답·실행 기록·비교 결과를 대조해 `REPRODUCED` / `scenario_status: PASSED`가 예상된다. 오류마다 새로 발급되는 `error_id`만 재현 비교에서 제외한다.

반복 실행 시 출력 폴더에 `-2` 같은 새 이름을 사용한다. 기존 결과, 원본 자체/하위/상위 경로에는 출력할 수 없다. 실행 도중 실패한 미완성 폴더도 다시 덮어쓰지 않는다. 결과는 이미 Git에서 제외한 `results/evaluation/` 아래에 둔다.

## 요청 한 건 직접 호출

```bash
python -m copenhagen_fds.provider_cli execute results/evaluation/provider-input-304/cold-normal.request.json --call results/evaluation/provider-input-304/cold-normal.call.json --server results/evaluation/provider-input-304/cold-normal.server.json --mock-script results/evaluation/provider-input-304/cold-normal.script.json --output results/evaluation/provider-one-304
```

`execute`는 기대값 파일을 받거나 읽지 않는다. 평가에만 `--mock-script`가 필요하다. 개인/집단 생성과 갱신에는 해당 옵션을 생략하고 사례의 `.request.json`, `.call.json`, `.server.json`을 지정한다. `auth-missing`은 `UNAUTHENTICATED`, `update-stale-version`은 `INVALID_CONTRACT`가 예상되며 둘 다 handler/GPT 호출 수는 0이다.

종료 코드는 준비/성공/기대값 일치/정상 재현 `0`, 잘못된 도구 입력·출력 충돌·파일 변조 `2`, 단건의 비성공 업무 결과 또는 비교 실패 `3`이다. 높은 위험의 `SUCCESS`도 결제 허용을 뜻하지 않는다.

## 파일과 실행 경계

| 파일 | 책임 |
| --- | --- |
| `<사례>.request.json` | 기존 `fds-provider-v1` 메시지. 원문·관측·이전 프로필을 포함할 수 있으므로 AI 내부 계산 계약으로 취급 |
| `<사례>.server.json` | 요청과 별도로 준비한 신뢰 설정·작업별 자료 권한·파싱/시간/응답 한도. 도구가 실행 중 요청을 보고 권한이나 신뢰 해시를 추가하지 않음 |
| `<사례>.call.json` | Mock 작업, 가상 시계 시작, 예제 인증 정상/누락/오류 선택. 네트워크 프로토콜이 아님 |
| `<사례>.script.json` | 평가에서 GPT가 반환한다고 가정한 구조화 추출/비교 응답. 최종 FDS 정답·예상 점수/위험은 포함하지 않음 |
| `expected.json` | 실행 종료 후 상태·사유·호출 수 등을 대조하는 별도 기대값 |
| `*.schema.json` | 현재 Pydantic 모델에서 생성한 계약 7개, 도구 설정/호출/MockScript 3개의 스키마 |
| `<사례>.response.json` | 실제 제공자 실행 응답. 준비 단계에는 없고 `suite` 실행 결과에 생성 |
| `<사례>.execution.json` | 실제 handler/구조화 완료 호출 목록, 자원 생성/정리 수, 입력 바이트 해시, `MOCK`, `NOT_RUN` 표시 |
| `comparisons.json` | 관측값과 기대값 및 항목별 일치 여부 |
| `manifest.json` | 파일별 SHA-256, Python/패키지 버전과 소스 해시, 실행 모드/기대값 읽기 단계 |

단건 출력 파일은 접두어 없이 `request.json`, `response.json` 등을 사용한다. 전달된 요청 바이트는 그대로 보존한다. 원본 파일이나 요청에 포함된 프로필은 수정하지 않는다. 해시는 변조 탐지/재현용이며 서명·진본 인증 수단은 아니다. 다른 코드/런타임에서는 새 입력을 준비해 별도 실험으로 실행한다.

`execution.json`의 `request_sha256`은 전달 바이트의 SHA-256이다. 제공자 응답의 같은 이름 필드는 검증된 모델의 의미 기반 해시이므로 공백/필드 순서가 다른 JSON에서도 의미 연결을 확인한다. 두 해시를 서로 같은 값으로 비교하지 않는다.

Mock은 환경 파일과 실제 GMS 키를 읽지 않는다. 네트워크를 차단하고 한국 시각 2026년 1월의 가상 시계로 실행한다. `server.json`의 `EXAMPLE_ONLY_NOT_A_SERVICE_SECRET`은 공개 예제 값이며 서비스에 배포할 자격 증명이 아니다. 권한과 한도 역시 시연값이다. 가상 시간 예산 검증을 운영 지연/SLA 실측으로 해석하지 않는다. 시계/네트워크 패치는 프로세스 전역이므로 전용 순차 CLI로만 사용한다.

이 도구는 `--mode mock`만 허용한다. 실제 GMS 선택은 자동으로 발생하지 않는다. 기존 [native CLI](native-cli.md)의 명시적 GMS 모드는 별도 검증 경로이며 이번 제공자 Mock 실행 결과와 혼합하지 않는다. 실제 제공자 GMS 관측이 필요하면 사용자 승인 후 별도 요청/신뢰 설정·실측 절차를 준비한다.

## 제공자 호출 계약

실제 Python 연결은 `ProviderRuntime.execute(operation, request_bytes, credential=SecretStr(...))`로 진입한다. 네 작업 모두 Runtime이 JSON 검증·인증·권한·신뢰 설정을 검사하고 실제 handler를 호출한다. 자격 증명은 요청 JSON 밖에서 전달한다. 비동기 취소는 `CancelledError`로 전달하며 HTTP 응답 매핑은 미정이다.

| 작업 | 요청/응답 모델 | 주요 요청 필드 | 실제 handler |
| --- | --- | --- | --- |
| `EVALUATE` | `ProviderEvaluationRequest` / `ProviderEvaluationResponse` | `call`, `configuration`, `evaluation` | `evaluate_handler` |
| `CREATE_PERSONAL_PROFILE` | `ProviderProfileCreateRequest` / `ProviderProfileCreateResponse` | `profile`, `policy`, `replay` | `create_profile_handler` |
| `CREATE_POPULATION_PROFILE` | 위와 동일 | 집단 `profile.cohort`, `policy`, `replay` | `create_profile_handler` |
| `UPDATE_PROFILE` | `ProviderProfileUpdateRequest` / `ProviderProfileUpdateResponse` | 이전 `state`, 기대 버전과 시각을 가진 `update`, `replay` | `update_profile_handler` |

공통 `schema_version`은 `fds-provider-v1`이다. `call`은 `request_id`, `trace_id`, `sent_at`; `configuration`은 `configuration_id`, `version`, `content_sha256`이다. 설정 해시의 내용 일치와 실행 권한은 서로 다른 검사다. JSON Schema는 구조 공유용이고 대상/시각/해시/버전 연결 같은 의미 규칙은 실제 검증 코드와 기존 [평가 계약](provider-evaluation-contract.md), [프로필 계약](provider-profile-contract.md)을 함께 따른다.

Core camelCase 매핑은 외부 DTO 조립 계층에서 확정할 사항이다. 제안 대응은 `request_sha256 ↔ requestSha256`, `behavior_score ↔ behaviorScore`, `outcome.status ↔ evaluationStatus`, `outcome.risk_level ↔ riskLevel`, `outcome.recommendation ↔ recommendation`이다. 현재 제공자 DTO에는 `evaluation_id`가 없으며 `call.request_id`는 호출 식별자다. Core의 `evaluationId` 발급·호출 식별자 연결·재전송 규칙은 외부 조립 계층에서 별도 합의해야 한다. 원본 결과는 Core에서 의미 변환 없이 보존하되 민감한 AI 원장 전체를 Core에 복제하지 않는다.

## 오류·부족 상태

| 응답 코드 | 재시도 분류 | 의미 |
| --- | --- | --- |
| `INVALID_JSON`, `UNSUPPORTED_CONTRACT`, `INVALID_CONTRACT`, `INPUT_LIMIT_EXCEEDED` | `DO_NOT_RETRY` | JSON/계약 버전/구조·연결/입력 크기 수정 필요 |
| `UNAUTHENTICATED`, `FORBIDDEN` | `DO_NOT_RETRY` | 자격 증명 또는 권한 문제. handler 미실행 |
| `CONFIGURATION_UNAVAILABLE`, `CONFIGURATION_MISMATCH` | `DO_NOT_RETRY` | 독립된 신뢰 설정 없음 또는 불일치 |
| `VERSION_CONFLICT` | `REFRESH_STATE` | 후속 저장 계층의 실제 현재 버전 충돌. 이번 Mock은 DB를 구현하지 않음 |
| `DEADLINE_EXCEEDED`, `PROVIDER_UNAVAILABLE` | `CHECK_BUDGET_AND_BACKOFF` | 남은 예산/가용성·중복 여부 확인 필요 |
| `INTERNAL_ERROR` | `DO_NOT_RETRY` | 외부에 내부 원인을 노출하지 않는 실패 |

오류 JSON은 `schema_version: fds-provider-error-v1`, 독립 UUID `error_id`, `code`, `retry`만 포함한다. 원문·키·예외 상세를 붙이지 않는다. 자세한 규칙은 [공통 오류표](provider-call-contract.md)를 따른다.

`INCOMPLETE`와 점수 `null`은 정상/0점이 아니다. 갱신 `REJECTED`/`REPLAY_REQUIRED`는 유효한 요청의 계산 결과이며 계약 오류와 구분한다. 이전 상태와 기대 버전 자체가 맞지 않으면 `INVALID_CONTRACT`다. 상태가 서로 맞아도 DB가 이미 바뀐 경우의 `VERSION_CONFLICT`는 별도 저장 계층이 담당한다.

## 27개 시나리오

| 사례 | 확인할 결과 |
| --- | --- |
| cold-normal / cold-amount / cold-combined | COLD, LOW / MEDIUM / HIGH |
| transition / personalized | TRANSITION / PERSONALIZED |
| expired-profile | 만료된 개인 프로필 대신 COLD |
| gpt-mismatch / gpt-unknown | INTENT_MISMATCH / INTENT_UNKNOWN, 후자는 INCOMPLETE |
| missing-text / missing-history / expired-baseline | INCOMPLETE, 원문 누락 시 GPT 호출 0 |
| extraction-error / comparison-error | FAILED, 구조화 완료 호출 1 / 2 |
| auth-missing / auth-invalid / forbidden / configuration-mismatch | 대응 오류, handler/GPT 미실행 |
| create-personal / create-population / create-empty | CREATED, READY / READY / EMPTY. 집단은 명시적 합성 사용자 2명 |
| update-unchanged-personal / update-unchanged-population | 같은 기준 시각·관측에 UNCHANGED |
| update-withdrawn | UPDATED, WITHDRAWN, 게시 EMPTY |
| update-admitted / update-held | UPDATED, ADMITTED / HELD. 기존 목요일 정오 패턴에서 금액만 달리한 예제 |
| update-replay-required | REPLAY_REQUIRED / RETENTION_GAP |
| update-stale-version | INVALID_CONTRACT, handler 미실행 |

준비용 프로필과 정상 확인 근거는 합성 데이터다. 최종 점수·결제 성공을 학습 정답으로 역사용하지 않는다. 시연의 최소 표본/준비도 설정은 운영 성능 보장이 아니다.

## AI 저장과 Core 결과 보관

1. AI는 신뢰된 원천 이력·원문·의도와 선택된 Core Snapshot을 조립하고 내부 계산 계약을 만든다. Core/Pay DB를 직접 조회하지 않는다.
2. 평가는 프로필을 읽기만 한다. Core는 평가 식별자·요청 해시·선택 Snapshot/digest·AI 응답과 별도의 Core 업무 결정을 연결한다. 평가 TTL과 Snapshot 변경/만료에 따른 재평가는 외부 계약에서 확정한다.
3. 초기 생성 성공의 전체 `state` 또는 갱신 성공의 `result.state`는 AI 저장 계층이 보관한다. 후보 통계, 게시 프로필, 관측·학습 근거, 정책 평가를 분리 보관해도 전체 상태를 손실 없이 복원할 수 있어야 한다.
4. 다음 갱신은 `state.state_version`, `state.candidate.state_version`, `state.candidate.snapshot.profile.reference`의 기대 버전 세 가지와 전체 보관 범위의 관측을 전달한다. `FULL_REPLAY` / `REMOVE`이므로 누락은 철회, 빈 관측은 전체 철회다. 원천 수집의 실제 완전성은 AI 수집/저장 계층에서 보장해야 한다.
5. AI 저장 계층은 읽었던 실제 DB 버전과 현재 버전을 원자적으로 비교하고 후보·게시·정책 상태를 한 트랜잭션으로 저장한다. 저장 충돌 시 새 상태를 읽고 정책에 따라 다시 계산한다. Runtime 계산 성공만으로 저장 성공을 기록하지 않는다. 모든 현재 응답은 `NOT_PERSISTED`다.
6. 동일 이전 상태에 같은 요청 재전송과, 첫 결과의 새 상태에 동일 관측 재적용은 다르다. 영속 멱등성·중복 제거·작업 조회·복구는 후속 저장/서비스 작업이다. Core가 AI 상태 원장을 별도로 소유하도록 요구하지 않는다.

남은 작업은 호출 주체/시점과 전송 DTO·인증 배포 합의, AI 저장/수집·동시 갱신 연결, Core Snapshot/만료 적용, Pay 사실 API, 배포 및 실제 GMS 지연 측정이다. 시간순 탐지 품질·오탐/미탐 평가는 후속 품질 평가 스토리 범위다.

## 완료 기준별 직접 검증

```bash
python -m pytest tests/provider/cli -v --tb=short
```

현재 도구 전용 검증은 **40 passed**가 예상된다. 27개 시나리오를 반복 실행하는 검증이 포함되어 있어 진행 중 잠시 출력이 멈출 수 있다.

이번 관련 회귀는 **931 passed in 237.76s**였다. 제공자·갱신/편입 정책·기존 FDS CLI·추출/비교/GMS 어댑터 Mock·설정 검증을 포함하며 저장소 전체 테스트 결과는 아니다.

```bash
python -m pytest tests/provider tests/learning/updates tests/learning/policy tests/learning/personalization/replay/engine/analysis/finalization/cli tests/analysis tests/test_openai_settings.py tests/test_settings.py -q --tb=short
```

| 완료 기준 | 검증 근거 |
| --- | --- |
| 명세·설정·계약/예제 일치 | test_config.py, test_evaluation_fixtures.py, test_profile_fixtures.py, test_workflow.py의 스키마 대조 |
| 정상·3단계·실패·갱신 보류/충돌 | test_runtime.py, test_scenarios.py 및 위 suite/show 명령 |
| 원본/출력 보호·기대값 분리·미실행 표시 | test_artifacts.py, test_comparison.py, test_workflow.py, test_cli.py |
| 재현·기존 연결 회귀 | verify-suite 명령 및 provider/native CLI/GMS Mock 회귀 |

자동 검증 통과와 사용자 직접 검증은 구분한다. Draft MR의 기존 네 체크박스는 사용자가 검증한 뒤 직접 체크한다.
