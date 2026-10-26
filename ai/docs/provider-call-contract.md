# FDS 제공자 호출 경계와 검증 방법

관련 Task: S15P21A207-299. [평가 계약](provider-evaluation-contract.md)과 [프로필 계약](provider-profile-contract.md)의 공통 호출 경계를 설명한다.

## 합의 상태와 실행 착수 조건

299번 작성 당시 BE 호출·인증·제한 시간은 미정이었다. 후속 Core 회신에서 서비스 토큰 분리·멱등성 원칙과 AI의 원문·프로필·수집 상태 소유를 수용했다. 실제 평가 호출 주체·시점·전송 및 운영 수치는 아직 확정되지 않았다. 300번의 [제공자 실행 기반](provider-runtime.md)은 명시적으로 주입된 설정으로 검증하는 전송 독립 경계다.

| 항목 | 상태 | 실제 실행 전에 필요한 결정 |
| --- | --- | --- |
| 전송/실행 방식 | 미정 | Python 직접 호출인지 HTTP 등 별도 서비스인지, 호출 경로와 호스팅 주체 |
| 서비스 인증 | 토큰 분리 원칙 수용 | 실제 발급·검증·배포 주체, 키 보관/교체와 전송 처리 |
| 권한·허용 범위 | 미정 | 호출 서비스별 작업 권한과 사용자/집단/프로필 접근 범위 |
| 입력·상태 크기 | 미정 | 본문 바이트, 중첩 깊이, 노드/목록 수, 문자열 길이, 정수/실수 크기 제한 |
| 시계 오차 | 미정 | BE·AI 시각 동기화와 미래 전송 시각 허용/거부 정책 |
| 시간 예산·재시도 | 미정 | 제공자 전체 제한 시간, GMS 제한 시간, 취소 전달, 재시도 횟수/간격·중복 처리 |
| 정책/모델 설정 | 미정 | 신뢰 설정의 저장소·승인 주체, 버전/해시 확인, 요청 override 허용 여부 |
| 만료/재사용 | Core가 명시적 만료 저장, 초기 정책 수용 | 5분과 Checkout 만료 중 이른 시각은 초기 정책이며 계산·전달 연결과 실측은 후속 작업. 현재 미산출 만료를 임의 채우지 않음 |
| 프로필 저장·비교 | AI 저장소 소유 수용 | 원자적 버전 비교·저장, 원문·프로필 조회 및 외부 요청 조립 계층은 별도 구현 필요 |

299번 데이터 계약·순수 JSON 검증과 300번의 권한·신뢰 설정·주입 작업 실행·예산/자원 관리 경계를 제공한다. 전체 상태를 포함하는 기존 DTO는 계산 입력으로 재사용하며 Core에 원문·프로필 원장을 다시 소유하도록 요구하는 외부 계약으로 확정하지 않는다. 실제 서버는 합의한 인증·허용 범위·한도·예산을 공급해야 한다. 계약/Mock 검증 통과를 실제 BE 호출 준비 완료로 표시하지 않는다.

모든 값이 명시된 `ProviderInputLimits`를 전달해야 한다. 내장 운영 기본값은 없다. 중첩 깊이 128은 파서 구현 상한이며, 운영에서 128까지 받기로 합의했다는 뜻이 아니다. 이미 보유한 기존 엔진/통계 정책의 기본값도 서비스 운영 승인과 동일하지 않다.

호출자가 보낸 정책·모델 설정과 해시가 서로 같다는 것만으로 실행을 허용하지 않는다. 공급자의 신뢰 설정과 별도 대조하고 요청 주체의 작업/데이터 권한을 확인해야 한다. 본문에는 서비스 인증 토큰과 GMS 키를 추가하지 않는다.

## 공통 오류와 입력 한도

`validate_provider_json(Model, raw_bytes, limits=...)`는 본문 바이트 → UTF-8/중첩 깊이 → 중복 키·비유한 숫자/문자열 → 노드·목록·문자열·숫자 한도 → 계약 버전 → Pydantic 구조·의미 검증 순서로 처리한다. 외부 본문을 직접 `model_validate`해 상세 오류를 반환하는 대신 이 함수를 사용한다. 실제 네트워크 계층은 본문을 모두 수신하기 전부터 바이트 한도와 전체 시간 예산을 적용해야 한다.

`ProviderContractError.error`의 JSON에는 `schema_version`, 서버에서 생성한 UUID `error_id`, 고정 `code`, 코드에 따른 고정 `retry`만 들어간다. 원문·키·입력 trace·필드 경로·Pydantic 오류·stack trace·공급자 원본 예외를 응답에 붙이지 않는다.

| code | 의미 | retry |
| --- | --- | --- |
| `INVALID_JSON` | 잘못된 JSON/UTF-8, 중복 키, NaN/Infinity 등 | `DO_NOT_RETRY` |
| `UNSUPPORTED_CONTRACT` | 지원하지 않거나 빠진 계약 버전 | `DO_NOT_RETRY` |
| `INVALID_CONTRACT` | 필수값·타입·범위·대상/참조/시각 연결 위반 | `DO_NOT_RETRY` |
| `INPUT_LIMIT_EXCEEDED` | 신뢰 설정으로 전달한 입력 한도 초과 | `DO_NOT_RETRY` |
| `UNAUTHENTICATED`, `FORBIDDEN` | 인증되지 않았거나 작업/자료 접근 권한 없음 | `DO_NOT_RETRY` |
| `CONFIGURATION_UNAVAILABLE`, `CONFIGURATION_MISMATCH` | 신뢰 설정 없음/요청과 불일치 | `DO_NOT_RETRY` |
| `VERSION_CONFLICT` | 실행/저장 경계에서 현재 상태 버전 충돌 | `REFRESH_STATE` |
| `DEADLINE_EXCEEDED`, `PROVIDER_UNAVAILABLE` | 요청 예산 소진/일시적 공급자 가용성 실패 | `CHECK_BUDGET_AND_BACKOFF` |
| `INTERNAL_ERROR` | 외부에 원인을 노출하지 않는 내부 오류 | `DO_NOT_RETRY` |

위 함수가 직접 만드는 오류는 JSON·계약·입력 한도 오류다. 인증·서비스 상태·시간 예산 오류는 후속 실행 경계가 이 계약으로 매핑한다. HTTP 상태 코드는 아직 정하지 않았다. `retry`는 자동 재실행 명령이나 성공 보장이 아니다. 남은 예산, 호출 비용, 멱등성/최신 상태를 확인해야 한다.

요청에 함께 보낸 이전 상태와 기대 버전이 서로 다르면 `INVALID_CONTRACT`이다. 서로 일치했더라도 실제 DB 버전이 바뀔 수 있으며 그 충돌은 별도의 `VERSION_CONFLICT`다. 정상 구조로 실행한 프로필 갱신의 `REJECTED`/`REPLAY_REQUIRED`는 업무 결과로 구분한다. 평가 자료 부족은 파싱 오류가 아니라 `INCOMPLETE` 결과다.

## 완료 기준별 직접 확인

Git Bash에서 저장소 위치와 가상환경을 맞춘다.

```bash
cd "C:/Users/SSAFY/Desktop/특화/S15P21A207/ai"
source .venv/Scripts/activate
```

PowerShell에서는 같은 `ai/` 경로에서 `python` 대신 `.\.venv\Scripts\python.exe`를 사용해도 된다. 테스트 파일을 `python test_file.py`로 실행하면 pytest가 실행되지 않으므로 반드시 `python -m pytest`를 사용한다.

| 완료 기준 | 직접 확인 방법 | 예상 결과 |
| --- | --- | --- |
| 요청·응답·오류 및 JSON/스키마 | 아래 예제 내보내기, `tests/provider/test_evaluation.py`·`profiles`·`test_errors.py` | 모든 예제 왕복/연결 검사 통과, 7개 모델의 JSON Schema 생성 |
| 합의 상태와 후속 착수 조건 | 위 합의 표 및 평가/프로필 문서 확인 | 전송·인증·한도·예산·설정/만료 관리의 미정 상태가 명시됨. 임의 운영 기본값 없음 |
| 전체 재생/책임 분리 | `tests/provider/profiles` | 누락 범위/모드 거절, 빈 전체 재생은 이전 관측 제거, 저장 상태는 `NOT_PERSISTED` |
| 정상·부족·잘못된 값·정보 비노출 | `tests/provider` 전체 | 모든 테스트 `PASSED`, 실제 GMS/DB/네트워크 호출 없음 |

각 검증은 S15P21A207-299와 연관된다. 자동 테스트 통과는 사용자의 직접 검증이나 Jira/MR 체크박스 체크를 대신하지 않는다.

```bash
# 계약 전체 검증
python -m pytest tests/provider -v --tb=short

# 정상/부족 평가, 개인/집단/빈 생성, 갱신/제거, 오류 JSON과 스키마 내보내기
python -m pytest tests/provider/examples --provider-examples results/evaluation/provider-contracts-299 -v --tb=short

# 공통 오류·입력 한도·원문 비노출만 확인
python -m pytest tests/provider/test_errors.py -v --tb=short
```

예제 명령은 `1 passed`와 함께 지정 디렉터리에 **메시지 JSON 15개, 모델 스키마 7개, index.json 1개**를 만든다. `index.json`은 각 예제의 모델 이름을 알려준다. 모든 메시지는 저장 전 Pydantic JSON 검증·왕복을 거치고 모든 정상 응답은 요청 연결도 검사한다. JSON Schema는 구조 공유용이며 시각 순서·해시·전체 재생 같은 필드 간 규칙은 Pydantic 검증과 이 문서도 함께 따라야 한다.

`evaluation-response.json`의 점수/단계와 `insufficient-response.json`의 `null`/`UNKNOWN`, `remove-request.json`의 명시적 빈 전체 재생과 대응 응답의 제거 사유를 비교한다. `error.json`에는 입력 원문/trace나 예외 내용이 없어야 한다. 생성된 파일은 `.gitignore`가 이미 제외하는 `ai/results/evaluation/` 하위에 둔다. 합성 예제이며 실제 GMS 성능이나 실제 사기 탐지 정확도 검증 자료가 아니다. 호출 시각·내부 실행 기록 해시는 실행마다 달라질 수 있다.
