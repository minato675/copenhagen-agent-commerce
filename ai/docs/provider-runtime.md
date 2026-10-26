# 제공자 실행 기반 사용법

관련 Task: S15P21A207-300.

이 모듈은 전송 방식과 무관한 요청 검증·권한·비동기 실행 경계다. HTTP 서버나 실제 BE 호출 경로를 제공하지 않는다. Core·Pay가 평가 시점을 협의 중이므로 서버 주소·경로·호출 주체·운영 제한 시간을 확정한 것으로 해석하지 않는다.

## 권한·입력·설정 검증

`ProviderBoundary.prepare(operation, raw_bytes, credential=SecretStr(...))`는 다음을 검사한 뒤 `AuthorizedRequest`를 반환한다. 인증 토큰은 JSON 본문 밖에서 전달한다.

1. 운영 측에서 주입한 `ServiceTokenAuthenticator`의 자격 증명을 검증한다. 본문의 사용자 ID나 서비스 이름은 인증 수단이 아니다.
2. 해당 서비스의 작업 권한을 확인한다.
3. 299번의 바이트·깊이·목록·문자열 등 한도와 DTO 의미 검증을 수행한다.
4. 현재 입력뿐 아니라 이전 프로필 상태·게시 상태·집단 구성원·학습 재생에 포함된 사용자·프로필·집단 ID를 검사한다.
5. 요청 설정의 ID·버전·내용 해시를 서버의 작업별 `TrustedConfiguration`과 대조한다. 요청자가 설정과 해시를 함께 바꾸어도 등록된 설정과 다르면 거절한다.

`OperationGrant`의 범위는 작업별로 분리한다. 한 권한 안에서 필요한 모든 범위를 허용해야 하며 서로 다른 부분 권한을 합쳐 넓은 권한으로 만들지 않는다. 빈 범위는 모든 사용자/집단을 뜻하지 않는다. 와일드카드나 기본 토큰도 없다.

토큰·범위·설정 목록은 신뢰된 초기화 코드에서 생성한다. 토큰은 환경 변수/비밀 관리 시스템에서 읽어 `SecretStr`로 주입해야 하며 본문이나 로그에 넣지 않는다. 라이브 토큰 발급·회전·헤더 처리·서비스별 허용 범위는 실제 배포 계약에서 결정한다. 모듈 생성만으로 공개 서버나 무인증 경로가 열리지 않는다.

권한 검사는 입력 데이터의 진실성이나 DB의 실제 프로필 버전을 증명하지 않는다. Core가 제공한 사실과 AI 저장소의 이력·프로필을 조립하는 서비스 계층에서 출처·현재 상태·원자적 저장을 확인해야 한다.

## 저장 책임과 기존 DTO의 관계

후속 Core 회신에 따라 AI가 원문·프로필·수집 상태를 소유하고, Core는 구매·Checkout 사실 및 결과·업무 결정을 보관한다. Pay 이력 API를 Core가 중계하는 경로는 Pay 선행 계약 확정 후 연결한다.

현재 모듈이 받는 299번 DTO는 전체 계산 입력과 재생 상태를 포함한다. 향후 Core에 프로필 전체를 보내도록 요구하는 계약으로 확정한 것이 아니다. 참조 중심 외부 요청을 이 계산 입력으로 조립하는 계층과 AI 저장소는 아직 별도 작업이다. 현재 `NOT_PERSISTED` 의미와 순수 계산 엔진은 유지한다.

## 실행 인터페이스

`ProviderRuntime`에는 검증 경계, 작업별 비동기 handler, 요청 제한 시간, 자원 정리 제한 시간, 정상 응답 바이트 한도를 모두 명시적으로 주입한다. 평가는 `evaluate_handler`, 개인·집단 초기 생성은 `create_profile_handler`, 갱신은 `update_profile_handler`를 등록한다. 각각 `provider-evaluation-execution.md`, `provider-profile-creation.md`, `provider-profile-update.md`를 참고한다. 등록되지 않은 작업은 `PROVIDER_UNAVAILABLE`이며 가짜 성공 결과를 만들지 않는다.

```python
# 기존 계약을 처리하는 신뢰된 비동기 handler를 주입하는 형태.
# 아래 이름들은 배포/연결 코드가 공급하며 하드코딩한 운영 기본값은 없다.
from copenhagen_fds.provider_runtime.execution import ProviderRuntime

async with ProviderRuntime(
    boundary=boundary,
    handlers=operation_handlers,
    timeout_seconds=request_timeout_seconds,
    cleanup_timeout_seconds=cleanup_timeout_seconds,
    max_response_bytes=max_response_bytes,
    resource_factory=request_resource_factory,
) as provider:
    response_bytes = await provider.execute(
        operation, received_body_bytes, credential=service_credential,
    )
```

`handler(request, context)`는 해당 작업의 응답 모델을 반환한다. 제공자는 응답 모델을 다시 검증하고 `validate_for_request`로 원래 입력에 연결한 뒤 JSON으로 반환한다. 잘못된 응답·초과 크기·내부 예외는 원인 문자열 없이 기존 고정 오류 계약으로 변환한다. 정상 응답 크기 제한을 넘으면 작은 고정 오류 JSON을 반환하므로 외부 전송 계층은 오류 봉투를 위한 크기도 허용해야 한다. 이 반환값에는 HTTP 상태가 없으며 실제 전송 매핑은 후속 계약이다.

예제의 `service_credential`은 네트워크 수신 계층이 헤더에서 추출한 `SecretStr`이며 실제 검증은 이 경계에서 수행한다. 토큰이나 호출자 문맥을 사용자 JSON에서 생성하지 않는다.

## 시간 예산·취소·종료

- 한 번의 `execute` 진입에서 `AnalysisBudget`을 만들고 인증·입력 검증·handler·응답 검증·직렬화·자원 정리 후 반환 판단까지 측정한다. 이미 수신한 `bytes`를 받으므로 네트워크 수신 이전/수신 대기와 실제 응답 전송 시간은 포함하지 않는다. 실제 서버에서는 그 구간도 별도 전체 deadline에 포함해야 한다.
- 요청의 공유 예산은 신뢰된 엔진 실행 예산을 초과할 수 없다. 예산이 부족한 경우 검증/자원 생성 다음 단계와 후속 모델 호출을 막는다. 서버 예산을 더 작게 설정하는 것은 가능하다.
- `context.budget`을 기존 엔진에 전달하고, 모델 어댑터 자리에는 `context`를 사용할 수 있다. `context.complete(..., budget=context.budget)`는 호출 전후에 예산·취소·수명을 확인하며 다른 예산 객체로 초기화하는 요청을 거절한다.
- 취소는 `asyncio.CancelledError`로 전파한다. 아직 합의하지 않은 새 wire 오류 코드를 추가하지 않는다. 실제 전송 취소/응답 매핑은 서버 연결 작업에서 결정한다.
- `aclose()`는 신규 요청을 거절하고 진행 중인 요청을 취소한 뒤 자원 정리가 끝나기를 기다린다. 정상 종료·오류·취소 모두 요청 자원을 정리한다. 정리가 실패한 결과를 성공으로 발행하지 않는다.
- 취소/요청 만료 후에도 자원을 정리할 별도의 제한 시간을 제공한다. 따라서 전체 반환 시간은 요청 예산보다 길 수 있다. 비동기 취소는 협력적이고 동기 계산·파싱·직렬화는 즉시 강제 중단하지 못한다. 이 모듈은 엄격한 응답 SLA나 프로세스 강제 종료를 제공하지 않는다.
- 실행 문맥·예산·자원은 요청마다 분리하며 종료된 문맥으로 새 호출을 시작할 수 없다. handler는 내부 비동기 작업을 모두 await해야 하며 요청 밖에서 실행될 작업을 남겨서는 안 된다. Runtime은 하나의 이벤트 루프에서 사용한다. CLI의 전역 시계 패치를 서버에 적용하지 않는다.

## GMS 자원 연결

`GmsResourceFactory(trusted_openai_settings)`는 기존 `OpenAIAnalysisAdapter`를 요청마다 생성하고 Runtime이 닫는다. `OpenAISettings`는 신뢰된 환경/비밀 설정 로더에서 공급한다. 이 클래스는 환경 파일을 자동 탐색하지 않으며 요청에서 키를 읽지 않는다.

`LIVE` 설정만 허용하고, 요청의 gateway·model·개별/전체 제한 시간을 신뢰 설정과 대조한 뒤 어댑터를 생성한다. 프로필 생성·갱신에는 GMS 자원을 만들지 않는다. 실제 GMS 호출은 handler가 `context.complete`를 호출할 때 발생한다. 현재 검증은 테스트 전용 자원/SDK 대체 객체만 사용했다.

Runtime은 평가 중복 방지 저장소나 작업 결과 조회 API를 구현하지 않는다. 동일 evaluation_id 재전송을 기존 실행에 연결하는 영속 멱등성 계층은 후속 작업이다. 이 모듈의 동시 요청 격리 테스트가 영속 멱등성·DB 원자성을 증명하지 않는다.

## 직접 검증

Git Bash에서 `ai/`로 이동하고 가상환경을 활성화한 뒤 실행한다.

```bash
source .venv/Scripts/activate
python -m pytest tests/provider/runtime/test_boundary.py -v --tb=short
python -m pytest tests/provider/runtime/test_execution.py tests/provider/runtime/test_resources.py -v --tb=short
```

| 300번 완료 기준 | 검증 명령/범위 | 예상 결과와 남은 부분 |
| --- | --- | --- |
| 호출 경로·작업 전달·응답 변환 | test_execution.py의 injected_handlers·bound_contracts·missing_handler | 주입한 네 작업의 입력/응답 연결과 안전한 오류 검증. 실제 서버 호출 경로는 미정 |
| 인증·권한·한도·신뢰 설정 | test_boundary.py, test_resources.py | 인증 실패·범위 부족·다른 집단/프로필·설정 위조·한도 초과 및 GMS 설정 불일치를 실행 전 거절 |
| 예산·취소·자원·동시 요청 | test_execution.py | 후속 호출 차단, 반복 취소 중 정리, 종료 후 거절, 서로 다른 서비스·설정·예산 격리 검증 |
| 키/네트워크 없는 검증·사용법 | 아래 runtime 전체 및 provider 회귀 | 합성 자료/테스트 자원으로 통과. 실제 GMS 지연이나 BE 연동 SLA 검증은 아님 |

```bash
python -m pytest tests/provider/runtime -v --tb=short
python -m pytest tests/provider -q --tb=short
```

PowerShell에서는 같은 `ai/` 위치에서 `python` 대신 `.\.venv\Scripts\python.exe`를 사용한다. 기본 pytest fixture가 외부 네트워크를 차단하며 Windows 비동기 루프는 기존 `run_async` fixture를 사용한다. 테스트 토큰과 한도는 운영 기본값이 아니다. 체크박스는 사용자의 직접 검증 후 사용자가 체크한다.

300번의 실제 전송 경로 실행 완료 기준은 외부 계약과 서버 연결이 남아 있다. 이 검증 통과를 전체 Task 완료나 실서비스 인증 배포 완료로 표시하지 않는다.

304번의 [BE 인수인계와 Mock 실행 도구](provider-handoff.md)는 위 경계의 네 실제 handler를 연결해 요청/응답·실행/미실행·설정/권한·재현을 확인한다. `prepare-demo`, `execute`, `suite`, `show`, `verify-suite` 명령과 27개 사례를 제공한다. 운영 서버의 대체물이 아니다.
