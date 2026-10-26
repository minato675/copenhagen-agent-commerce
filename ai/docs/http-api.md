# FDS FastAPI 단독 서버

관련 Task: S15P21A207-394

기존 `ProviderRuntime`과 실제 FDS 평가 실행기를 HTTP로 연결한다. 행동 피처 계산·정책 적용은 기존 구현을 사용하며 GPT 분석은 서버 설정에 따라 명시적 Mock 또는 GMS로 실행한다. Mock은 지정된 응답을 돌려주는 연동 검증용이며 실제 원문 분석이나 탐지 성능 검증이 아니다.

## 제공하는 경로

| 경로 | 의미 |
| --- | --- |
| `GET /health/live` | 프로세스 응답 확인, 200 |
| `GET /health/ready` | 설정과 Runtime 초기화 완료 시 200, 미설정·종료 시 503 |
| `POST /internal/fds/evaluations` | 인증 후 내부 평가 DTO를 기존 FDS 실행기에 전달 |
| `GET /docs`, `GET /openapi.json` | Bearer 인증과 요청·응답 스키마 확인 |

상태 확인은 실제 GMS 요청이나 결제 처리를 하지 않는다. READY는 GMS 도달 가능성, 운영 데이터 완전성 또는 BE 통합 완료를 의미하지 않는다.

현재 요청은 `ProviderEvaluationRequest` 전체다. 정책·이력·프로필까지 포함하는 기존 내부 DTO이며, Core가 최종적으로 전송할 사실·참조 중심 DTO로 확정한 것이 아니다. [제공자 계약](provider-evaluation-contract.md)을 함께 참고한다. 프로필 변경 API는 이번 서버에 노출하지 않는다.

## 로컬 설치와 Mock 준비

**ai 디렉터리**에서 실행한다. PowerShell은 `python` 대신 `.\.venv\Scripts\python.exe`를 사용할 수 있다.

```sh
python -m pip install -r requirements-dev.txt
python -m copenhagen_fds.api.demo --output results/evaluation/http-demo-394
```

두 번째 명령은 현재 시각 기준의 합성 `request.json`과 서버 전용 `server.json`을 만든다. GMS 호출이나 비밀키 생성·저장은 하지 않으며 기존 출력 폴더를 덮어쓰지 않는다. 다시 준비할 때는 새 폴더 이름을 사용한다.

## PowerShell에서 실행하고 호출

같은 PowerShell 창에서 아래 설정과 실행을 진행한다. 토큰은 메모리에만 생성하며 출력하지 않는다. 준비한 Mock 설정이므로 기존 GMS 키가 있더라도 실제 GMS를 호출하지 않는다.

```powershell
$env:AI_HTTP_CONFIG = (Resolve-Path 'results/evaluation/http-demo-394/server.json').Path
$env:AI_HTTP_SERVICE_TOKEN = [Guid]::NewGuid().ToString('N')
$pythonPath = (Resolve-Path '.venv/Scripts/python.exe').Path
$apiProcess = Start-Process -FilePath $pythonPath -ArgumentList @('-m', 'uvicorn', 'copenhagen_fds.api.app:create_app', '--factory', '--host', '127.0.0.1', '--port', '8000', '--no-access-log', '--no-proxy-headers') -WindowStyle Hidden -PassThru
```

서버 시작 후 같은 창에서 확인한다. 연결이 아직 되지 않으면 잠시 뒤 다시 실행한다. 이미 8000 포트를 사용하는 서버가 있다면 실행 명령과 호출 URL의 포트를 함께 바꾼다.

```powershell
Invoke-RestMethod 'http://127.0.0.1:8000/health/ready'
$headers = @{ Authorization = "Bearer $env:AI_HTTP_SERVICE_TOKEN" }
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8000/internal/fds/evaluations' -Headers $headers -ContentType 'application/json' -InFile 'results/evaluation/http-demo-394/request.json'
```

기대 결과는 READY/MOCK, 평가 HTTP 200, `outcome.status=SUCCESS`, 9개 피처 및 `storage_status=NOT_PERSISTED`다. `X-FDS-Mode: MOCK` 응답 헤더로 실행 모드를 구분한다. HTTP 200은 평가 결과 전달 성공이며 결제 허용을 의미하지 않는다. `outcome`의 상태·위험 수준·권고를 별도로 읽어야 한다. 종료는 같은 창에서 실행한다.

```powershell
Stop-Process -Id $apiProcess.Id
Remove-Item Env:AI_HTTP_SERVICE_TOKEN
Remove-Item Env:AI_HTTP_CONFIG
```

터미널 전면에서 실행하려면 같은 환경변수 설정 후 `python -m uvicorn copenhagen_fds.api.app:create_app --factory --host 127.0.0.1 --port 8000 --no-access-log --no-proxy-headers`를 사용한다. 다른 호출 터미널에는 같은 서비스 토큰을 별도로 전달해야 한다.

## 신뢰 설정과 실제 GMS 모드

- `AI_HTTP_CONFIG`: 운영자가 제공하는 JSON 설정 파일 경로. 자동 `.env` 탐색은 하지 않는다.
- `AI_HTTP_SERVICE_TOKEN`: 32자 이상, 공백 없는 서비스 토큰. JSON·요청 본문에 넣지 않는다. GMS 키와 별개다.
- `grants`: 해당 서비스가 평가할 수 있는 사용자·프로필·집단 식별자. 와일드카드나 요청에서 유추한 권한을 허용하지 않는다.
- `configurations`: 서버가 신뢰하는 정책·실행 설정의 ID·버전·해시. 요청의 설정 전체 해시와 대조한다.
- 본문 크기·구조, 본문 수신 시간, 실행·정리 시간, 응답 크기, 동시 처리 한도는 서버 설정에서 명시한다. 생성된 Mock 수치는 검증용 시작값이며 운영 SLA가 아니다.

`AI_HTTP_CONFIG`가 없으면 서버는 시작하지만 평가와 readiness는 503이다. 설정 경로가 지정됐는데 파일·토큰·모드 등이 잘못되면 값을 노출하지 않는 오류로 시작을 중단한다. 설정 변경과 토큰 교체는 프로세스를 재시작해 반영한다.

실제 GMS를 사용하려면 서버 설정을 `mode=GMS`, `mock=null`로 두고 `FDS_MODE=LIVE`, `GMS_KEY`, `FDS_OPENAI_MODEL`, `FDS_OPENAI_TIMEOUT_SECONDS`, `FDS_EVALUATION_TIMEOUT_SECONDS`를 프로세스 환경에 주입한다. 요청의 `evaluation.execution`과 신뢰 설정도 GMS gateway·model·제한 시간에 맞춰 다시 구성하고 해시를 갱신해야 한다. Mock 설정의 mode만 바꿔 실행하지 않는다. GMS 응답은 요청별 자원으로 처리하고 종료 시 닫는다.

## 오류와 요청 수명

| 상황 | HTTP | 응답 코드 |
| --- | --- | --- |
| 누락·잘못된·중복 인증 헤더 | 401 | UNAUTHENTICATED |
| 사용자·프로필·집단 권한 부족 | 403 | FORBIDDEN |
| 잘못된 JSON·중복 키 | 400 | INVALID_JSON |
| DTO·버전 오류 | 422 | INVALID_CONTRACT / UNSUPPORTED_CONTRACT |
| 본문 크기·구조 한도 초과 | 413 | INPUT_LIMIT_EXCEEDED |
| JSON 외 Content-Type·압축 본문 | 415 | INVALID_CONTRACT |
| 본문 수신 시간 초과 | 408 | DEADLINE_EXCEEDED |
| 신뢰 설정 불일치 | 409 | CONFIGURATION_MISMATCH |
| 실행 예산 초과 | 504 | DEADLINE_EXCEEDED |
| 미설정·동시 처리 한도·제공자 불가 | 503 | PROVIDER_UNAVAILABLE 등 |
| 예상하지 못한 내부 오류 | 500 | INTERNAL_ERROR |

오류에는 고정 코드·서버 생성 ID·재시도 지침만 반환하며 원문·토큰·내부 예외를 반영하지 않는다. 본문은 스트리밍으로 크기를 제한하며 인증을 먼저 확인한다. 동시 처리 한도가 차면 대기열에 쌓지 않고 503으로 거절한다. 한도는 프로세스별이므로 초기에는 worker 1개로 실행한다.

클라이언트 연결 종료 시 실행 작업을 취소하고 요청 자원을 정리한다. 연결이 이미 종료된 경우 내부적으로 499로 마무리하며 클라이언트 수신을 보장하지 않는다. 서버 종료도 Runtime의 진행 작업을 취소·정리한다. 취소와 시간 제한은 협력적이며 동기 피처 계산·검증을 즉시 강제 중단하지 못하므로 엄격한 응답 SLA는 아니다.

## 직접 테스트

Task S15P21A207-394의 API 설정·정상 요청·예외·종료 처리는 다음 명령으로 검증한다. 실제 네트워크와 GMS는 테스트에서 차단된다.

```sh
python -m pytest tests/api -v --tb=short
python -m pytest tests/provider tests/analysis tests/test_settings.py tests/test_openai_settings.py tests/contract -q --tb=short
```

첫 명령은 HTTP 요청에서 실제 FDS 실행까지의 Mock 연결, 인증·권한·설정 위조, 스트리밍 크기·시간 제한, 과부하, 연결 종료·서버 종료, 스키마 및 안전한 설정 로딩을 검증한다. 두 번째는 기존 제공자·GMS 어댑터 Mock·입출력 계약 회귀다. 사용자 검증 체크박스는 사용자가 직접 체크한다.

## 남은 서비스 통합

실제 Core/Pay의 호출 시점·DTO 변환·결제 허용/보류/차단 적용, AI 저장소와 이력 수집, 평가 ID의 영속 멱등성, 평가 유효기간과 재사용 정책, 서버 배포와 운영 제한 시간은 별도 통합 작업이다. 현재 응답의 NOT_PERSISTED·NOT_ASSESSED를 보관 완료나 결제 허가로 해석하면 안 된다.
