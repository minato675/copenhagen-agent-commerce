# MCP Server

Agent 요청을 Core 내부 API 계약으로 변환하는 MCP 어댑터입니다.

## 책임과 경계

- DB를 소유하지 않습니다.
- 애플리케이션 중에서는 `core-service`만 내부 HTTP로 호출합니다.
- Pay, Merchant, PG를 직접 호출하면 안 됩니다.
- Core 구현 모듈을 의존하지 않고 `internal-api-contract`의 계약만 사용합니다.
- 도메인 규칙은 MCP에 구현하지 않고 책임 서비스에 둡니다.

## 주요 경로

```text
src/main/java/          애플리케이션과 MCP 어댑터 코드
src/main/resources/     실행 설정
src/test/java/          MCP 테스트
Dockerfile              독립 이미지 빌드 정의
```

## 개발 명령

서버 통합 배포 설정·토큰 구분·CI 업데이트 절차는 [배포 준비 문서](docs/deployment.md)를 참고하세요.

ChatGPT 등록·계정 연결을 먼저 진행하려면 `oauth-registration` 프로필을 사용합니다.
Core OAuth 인증과 연결 확인 UI만 제공하며 App Link·Core 업무 API·MCP 서비스 개인키는 필요 없습니다.
최신 서버 설정과 전체 기능 전환은 [OAuth 배포 절차](../../../infra/docker/mcp-oauth-deployment.md)를 따릅니다.

`backend/`에서 실행합니다.

```bash
./gradlew :apps:mcp-server:test
./gradlew :apps:mcp-server:build
```

실행은 레포 루트에서 `docker compose up --build mcp-server` 또는 전체 `docker compose up --build`를 권장합니다. Core 주소는 `CORE_SERVICE_URL` 환경변수로 주입하며 Docker Compose 기본값은 `http://core-service:8080`입니다.

## MCP 기반 구현 (S15P21A207-314)

- 공식 Java MCP SDK **2.0.1**, Jackson 3, Servlet 기반 **Streamable HTTP `/mcp`**를 사용합니다.
- SDK 2.0.1의 MCP 기준 규격은 **2025-11-25**입니다. 기존 `/actuator/health`는 유지합니다.
- `initialize` → `notifications/initialized` → `tools/list` → `tools/call`을 지원합니다.
- 기본 설정의 Tool 목록은 **비어 있습니다**. 315의 인증 경계와 316의 쇼핑몰 조회 Tool을 구현했으며,
  실제 인증 제공자와 Core 판매처 API는 아직 연결하지 않았습니다. 316의 활성화 조건은 아래를 참고합니다.
- Fake Core와 `fixture_*` Tool은 `src/test`에서만 등록하며 실행 JAR에는 포함하지 않습니다.

공식 근거: [SDK 2.0.1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.1),
[서버 구성](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/docs/server.md).

### 호출·오류 처리

`McpToolDefinition` bean은 입력·출력 JSON Schema와 handler를 함께 정의합니다.
`McpToolExecutor`가 입력을 검사한 후 handler를 실행하고, 검증된 결과 하나에서
`structuredContent`와 JSON `TextContent`를 생성합니다. 오류는 `isError=true`와
`{"error":{"code":"...","message":"..."}}` 형식으로 반환합니다.
성공 응답의 구조는 각 Tool의 `outputSchema`를 따릅니다.

| 구분 | 표현 | 의미 |
| --- | --- | --- |
| JSON·MCP 형식 오류, 알 수 없는 메서드/Tool | HTTP/JSON-RPC 오류 | Tool 업무 실행 전의 프로토콜 오류 |
| `INVALID_ARGUMENT` | Tool 오류 | 입력 schema 불일치, handler 실행 안 함 |
| `CORE_SERVICE_ERROR` | Tool 오류 | Core의 비정상 HTTP 상태, redirect 포함 |
| `CORE_TIMEOUT` | Tool 오류 | 응답 시간 초과, 처리 결과 미확정 |
| `CORE_UNAVAILABLE` | Tool 오류 | 통신 실패, 처리 결과 미확정 |
| `CORE_INVALID_RESPONSE` | Tool 오류 | JSON·Content-Type·응답 schema·크기 검증 실패 |
| `INTERNAL_ERROR` | Tool 오류 | handler 예외 또는 Tool 출력 schema 불일치 |

SDK의 파싱 오류가 요청 원문을 포함할 수 있어 `McpRequestFilter`에서 크기와 기본 형식을
먼저 검사합니다. 검증 상세값, Core 오류 본문, 내부 예외 메시지를 Tool 결과에 넣지 않습니다.
타임아웃을 업무 실패나 결제 성공으로 바꾸지 않고 자동 재시도하지 않습니다.

### Core HTTP 경계

`CoreClient`는 구성된 Core origin에만 GET/POST를 보냅니다. 업무 endpoint는 아직 등록하지
않았습니다. 제공자와 합의한 고정 경로·응답 schema를 서버 코드에서 전달해야 합니다.
Tool 입력을 목적지 URL·인증 헤더로 전달하지 않습니다. 현재 고정 경로에는 쿼리·인코딩된
동적 경로를 허용하지 않으며, 검색 조건과 사용자·서비스 인증 전달은 후속 계약에 맞춰 추가합니다.

redirect는 따라가지 않습니다. 연결 제한 시간과 전체 응답 제한 시간을 구분하고,
본문 수신 중 멈춤에도 제한 시간을 적용합니다. Content-Length와 무관하게 실제 수신량을
제한합니다. Core 업무 API 연동 완료를 의미하지 않으며, 실제 제공자 계약이 준비되면
공유 DTO와 소비자·제공자 테스트를 함께 연결해야 합니다.

### 환경변수

| 변수 | 기본값 | 용도 |
| --- | --- | --- |
| `CORE_SERVICE_URL` | `http://core-service:8080` | 인증정보·경로·쿼리 없는 Core HTTP(S) origin |
| `CORE_CONNECT_TIMEOUT` | `2s` | 연결 제한 시간 |
| `CORE_REQUEST_TIMEOUT` | `5s` | 본문 완료까지의 응답 제한 시간 |
| `CORE_MAX_RESPONSE_BYTES` | `262144` | Core 응답 최대 크기 |
| `MCP_REQUEST_TIMEOUT` | `10s` | SDK 요청 제한 시간 |
| `MCP_MAX_REQUEST_BYTES` | `262144` | MCP 요청 최대 크기 |
| `MCP_ALLOWED_ORIGINS` | `http://localhost:*,http://127.0.0.1:*` | 쉼표로 구분한 허용 Origin |
| `MCP_ALLOWED_HOSTS` | `localhost:*,127.0.0.1:*,[::1]:*,mcp-server:*` | 쉼표로 구분한 허용 Host |

Origin이 있는 요청은 허용 목록과 일치해야 합니다. 원격 프록시·클라이언트 연결 시 실제
Origin과 전달되는 Host를 명시적으로 설정해야 합니다. 이 검사는 계정 인증을 대신하지 않습니다.

### 로컬 검증과 실행

Java 21을 준비하고 `backend/`에서 실행합니다. 테스트는 임의의 로컬 포트를 사용하며
Core·Pay·Merchant·PG 서버나 실제 인증정보가 필요하지 않습니다.

```powershell
.\gradlew.bat :apps:mcp-server:test :apps:mcp-server:checkstyleMain :apps:mcp-server:checkstyleTest
.\gradlew.bat :apps:mcp-server:build
# 기본 서버 시작. 인증 계약·검증기 미설정 상태에서 /mcp는 요청을 차단합니다.
.\gradlew.bat :apps:mcp-server:bootRun --args="--server.port=8081"
```

| 테스트 | 확인 범위 |
| --- | --- |
| `McpServerApplicationIntegrationTest` | 기본 설정 기동, 인증 미설정 요청 차단, health endpoint |
| `McpTransportIntegrationTest` | 실제 SDK 클라이언트와 원시 HTTP 초기화·알림·목록·호출, schema·오류·Origin·크기 검사 |
| `CoreClientIntegrationTest` | Fake Core HTTP 정상/오류/redirect/잘못된 응답/통신 실패/헤더 및 본문 타임아웃 |

314의 검증 수준은 **로컬 MCP transport + Fake Core**입니다. 315의 추가 범위와
실제 인증·ChatGPT 연결의 미완료 조건은 아래를 참고합니다.

## 인증 및 접근 제어 (S15P21A207-315)

현재 단계는 **인증 경계 구현 + 로컬 검증**입니다. Core 담당자와 MCP OAuth 계약이 아직
확정되지 않아 운영 `McpTokenVerifier` 구현은 등록하지 않았습니다. 임의의 JWT를 decode하거나,
헤더의 사용자 ID를 신뢰하는 대체 인증은 제공하지 않습니다. 운영 설정과 검증기가 준비되지
않으면 인증 요청은 실패하며 `/actuator/health`만 계속 사용할 수 있습니다.

### 요청 처리

1. `/mcp`의 GET·POST·DELETE 요청마다 Authorization Bearer 헤더를 검사합니다.
   중복 헤더·잘못된 형식·쿼리 파라미터를 거부하고 토큰은 헤더로만 받습니다.
2. `McpTokenVerifier`가 토큰 진위와 현재 연결 상태를 검증합니다.
   불투명 토큰은 합의된 검증 API, JWT는 합의된 서명·키 계약을 사용해야 합니다.
   이 인터페이스와 `VerifiedMcpToken`은 MCP 내부 경계이며 공유 API 계약이 아닙니다.
3. 검증 결과의 active·issuer·audience·만료·not-before·연결 상태·필수 scope를 검사합니다.
   만료 시각과 현재 시각이 같으면 거부합니다.
4. 검증된 공개 사용자 참조·연결 참조·scope를 `McpIdentity`로 만들어 SDK의 요청별
   transport context에 전달합니다. 원문 토큰은 Tool handler에 전달하지 않습니다.
5. Tool 정의의 `requiredScopes`를 검사한 뒤 handler에 `McpIdentity`를 전달합니다.
   자원 소유권은 Core에서 확인한 소유자 공개 참조와 `requireOwner`로 비교합니다.
   Core도 자신의 API 경계에서 권한·소유권 검증을 수행해야 합니다.

세션 ID는 생성 시 사용자와 연결 참조에 묶입니다. 다른 사용자·다른 연결의
POST·GET 재연결/재생·DELETE를 거부합니다. 같은 연결에서 갱신한 토큰은 사용할 수 있으나,
매 HTTP 요청에서 토큰을 다시 검증하므로 기존 세션이 인증을 대신하지 않습니다.
연결을 해제했다가 다시 연결하면 제공자는 새 connectionRef를 반환해야 합니다.
연결 참조와 세션 매핑은 프로세스 메모리에만 존재하고 세션 DELETE 시 제거합니다.

Tool 입력 schema는 불필요한 사용자 식별자 필드를 받지 않도록 정의해야 합니다.
`user_id`·`userId`·임의 사용자 헤더는 인증 주체를 결정하는 데 사용하지 않습니다.
PIN·카드·원문 토큰을 모델 입력·Tool 결과·일반 로그에 넣지 않습니다.

### 인증 discovery 및 오류

`/.well-known/oauth-protected-resource/mcp`와 루트 metadata 경로를 제공합니다.
설정된 resource·issuer와 전역/Tool scope를 반환하며 Host·X-Forwarded-*로 URL을 만들지 않습니다.
설정이 없으면 가짜 인증 서버 URL을 광고하지 않고 503을 반환합니다.
401에는 `WWW-Authenticate` challenge, 권한 부족에는 필요한 scope를 포함합니다.
Tool 단계의 인증 오류는 `_meta["mcp/www_authenticate"]`로 호스트에 전달합니다.

| 상황 | HTTP / Tool 코드 | 다음 행동 |
| --- | --- | --- |
| 인증 누락 | 401 / AUTHENTICATION_REQUIRED | REAUTHENTICATE |
| 변조·철회·만료·issuer/audience 불일치 | 401 / INVALID_TOKEN | REAUTHENTICATE |
| 계정 미연결·연결 해제 | 401 / ACCOUNT_NOT_CONNECTED | RECONNECT_ACCOUNT |
| 권한 부족 | 403 / INSUFFICIENT_SCOPE | REQUEST_PERMISSION |
| 다른 사용자 자원·세션 | 403 / ACCESS_DENIED | CHECK_RESOURCE |
| 인증 설정·제공자 구현 없음 | 503 / AUTH_NOT_CONFIGURED | CONTACT_OPERATOR |
| 인증 제공자 장애 | 503 / AUTH_PROVIDER_UNAVAILABLE | RETRY_LATER |

Tool 처리 중 발생한 오류는 HTTP 상태 대신 `isError=true` 결과의 code·nextAction으로 반환합니다.
검증기의 예외 원문은 노출하지 않으며, 연결 상태를 캐시하지 않습니다.

### 설정과 실제 Core 연동 전 확정할 사항

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| MCP_AUTH_ISSUER | 미설정 | 신뢰하는 인증 제공자의 정확한 issuer |
| MCP_AUTH_RESOURCE | 미설정 | MCP용 토큰 audience와 일치하는 canonical URL. 경로는 /mcp |
| MCP_AUTH_REQUIRED_SCOPES | 미설정 | 요청마다 필요한 scope. 쉼표로 구분 |

설정 URL은 HTTPS를 사용하며 HTTP는 명시적 loopback 주소에만 허용합니다.
이 설정만으로 인증 서버가 생성되지는 않습니다. 테스트의 `mcp:access`·`fixture:read`와
example.test URL은 로컬 검증용이며 운영 계약으로 확정한 값이 아닙니다.

- Core에는 내부 `long id`와 공개 `UUID publicId`가 존재합니다. MCP에서는 내부 ID를
  외부에 노출하지 않습니다. 공개 ID와 토큰 subject의 연결 방식은 Core 담당자와 확정해야 합니다.
- `publicUserRef`·`connectionRef`는 현재 MCP 내부 타입입니다. Pay 사용자 참조와의 변환은
  Core가 소유하는 서버 간 계약으로 정의하며 동일 ID라고 추정하지 않습니다.
- OAuth authorization-code + PKCE, client 등록 방식, callback, metadata, scope,
  토큰 검증·철회·재연결 계약은 인증 제공자 구현과 함께 연결해야 합니다.
- 현재 CoreClient는 사용자 Bearer 토큰을 복사하지 않습니다. 서버 간 인증·토큰 교환 및
  사용자 문맥 전달은 공유 계약 확정 후 추가해야 하며 현재 실제 업무 API 연동은 미완료입니다.
- 현재 권한 재검증 단위는 HTTP 요청입니다. 운영 알림/장시간 SSE를 도입하기 전에 이미 열린
  스트림의 만료·연결 해제 처리와 세션 수명·분산 저장 정책을 함께 정해야 합니다.

공식 기준: [MCP 인증 규격](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization),
[OpenAI 인증 연결](https://developers.openai.com/plugins/build/auth).

### 검증 결과

315 구현 시점에 Java 21, Windows에서 MCP 모듈의 build·Checkstyle과 **84개 테스트**를 통과했습니다.
실패·오류·건너뜀은 0건입니다. 기존 37개에 인증 단위 23개·HTTP 통합 24개를 추가했습니다.

- 검증된 문맥 전달, 정상 호출, 입력 사용자 ID·헤더 위조, 토큰 변조/만료/철회/연결 해제
- issuer·audience·시간 경계·scope·제공자 장애·인증 설정 누락
- 타 사용자 자원·세션 및 재연결 우회, 갱신 토큰, 동시 사용자 8명의 문맥 분리
- 인증 discovery·HTTP/Tool challenge 및 사용자 토큰의 Core 미전달

Fake 토큰 검증기·사용자·자원은 `src/test`에만 존재하며 배포 JAR에 포함하지 않습니다.
실제 Core OAuth 로그인, 서명/JWKS 또는 introspection 연동, ChatGPT, 월렛 UI, 전체 BE/CI 검증은
이 결과에 포함하지 않습니다. 315의 Jira 상태는 실제 인증 계약 연동 전까지 진행 중으로 유지합니다.

현재 Windows 체크아웃에서는 기본 출력 경로로 실행한 Gradle test worker가
`ClassNotFoundException`을 일으켰습니다. 같은 소스로 빌드 출력만 영문 임시 경로로 바꾸어
검증했습니다. 공용 Gradle 설정은 변경하지 않았으며 다음으로 재현할 수 있습니다.

```powershell
# backend/에서 Java 21 JAVA_HOME을 설정한 뒤 실행
$verificationRoot = (Join-Path $env:TEMP 'copenhagen-mcp-315-build').Replace('\', '/')
$initPath = Join-Path $env:TEMP 'copenhagen-mcp-315-build.gradle'
$initContent = "allprojects { layout.buildDirectory = file('" + $verificationRoot +
    "' + project.path.replace(':', '/')) }"
[System.IO.File]::WriteAllText($initPath, $initContent, [System.Text.UTF8Encoding]::new($false))
.\gradlew.bat :apps:mcp-server:build --no-configuration-cache --init-script $initPath
```

## 쇼핑몰 목록·연결 상태 (S15P21A207-316)

`list_shopping_malls`는 인증된 사용자의 쇼핑몰 목록을 조회하는 읽기 전용 Tool입니다.
현재 구현·검증 범위는 **MCP Tool → CoreClient HTTP → Fake Core**입니다.
현재 Core에는 판매처 등록·사용자별 연결·어댑터 조회 API가 없고 `internal-api-contract`에도
관련 계약이 없습니다. Merchant Mock의 Profile·상품·체크아웃 코드를 확인했지만 MCP에서 직접
호출하지 않습니다. 실제 Core 계약을 반영한 `CoreMerchantDirectory` 구현이 후속으로 필요합니다.

### Tool 입출력과 활성화

- 입력은 `{}`이며 사용자 ID, 판매처 ID, URL, endpoint, cursor를 받지 않습니다.
- 검증된 `McpIdentity`를 Core 어댑터에 전달하고 응답의 사용자·연결 참조가 같은지 확인합니다.
  사용자 토큰을 복사하지 않으며 공개 사용자·연결 참조도 Tool 결과에는 넣지 않습니다.
- 호출마다 목록을 다시 조회합니다. 연결 상태 캐시나 연결/결제 변경 기능을 추가하지 않았습니다.
- 판매처마다 `merchantId`, `displayName`, `connectionStatus`, `status`, `nextAction`,
  `coreCapabilities`, `availableMcpTools`를 반환합니다. 빈 목록은 `{"shops":[]}`입니다.
- `coreCapabilities`는 Core 어댑터의 검증된 기능입니다. 현재 판매처별 구매 Tool은 없으므로
  `availableMcpTools`는 빈 배열입니다. `AVAILABLE`을 MCP 상품 검색·결제 구현 완료로 해석하면 안 됩니다.
- 최대 100개 판매처·판매처당 32개 capability를 받습니다. 미완성 페이지나 초과 목록을 잘라서
  전체 결과처럼 반환하지 않습니다. 페이지네이션은 실제 Core 계약이 정해질 때 함께 추가합니다.
- SDK 2.0.1에서 `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`,
  `openWorldHint=true`를 제공합니다. 이 힌트와 별개로 기존 인증 필터와 Tool scope 검사를 적용합니다.

| 환경변수 | 기본값 | 활성화 조건 |
| --- | --- | --- |
| `MCP_MERCHANTS_ENABLED` | `false` | `true`일 때만 Tool 등록 |
| `MCP_MERCHANTS_REQUIRED_SCOPES` | 미설정 | 활성화 시 합의된 읽기 scope를 명시. 쉼표로 구분 |

활성화하려면 `CoreMerchantDirectory` bean과 읽기 scope가 모두 필요합니다.
어댑터나 scope 없이 켜면 기동에 실패합니다. 설정만으로 Fake 판매처를 반환하지 않습니다.
이 조건을 충족해도 315의 인증 설정·실제 `McpTokenVerifier`가 있어야 요청이 허용됩니다.
테스트의 `fixture:shops:read`는 운영 권한 계약이 아닙니다.

### 상태와 기능 검증

| status | 의미 | nextAction |
| --- | --- | --- |
| `AVAILABLE` | Core가 해당 판매처를 사용 가능으로 판정 | `NONE` |
| `CONNECTION_REQUIRED` | 쇼핑몰 계정 연결 필요 | `CONNECT_SHOP` |
| `CONNECTION_REQUIRED` + `EXPIRED` | 기존 쇼핑몰 연결 만료 | `RECONNECT_SHOP` |
| `UNSUPPORTED` | 미지원 또는 서버 등록 목록에 없는 판매처 | `CHOOSE_ANOTHER_SHOP` |
| `UNAVAILABLE` | 해당 판매처의 일시적 장애 | `RETRY_LATER` |
| `PERMISSION_REQUIRED` | 해당 판매처 사용 권한 부족 | `REQUEST_PERMISSION` |
| `CAPABILITY_MISMATCH` | Profile·구현·계약 검증 불일치 | `CONTACT_OPERATOR` |

`connectionStatus`는 `CONNECTED`, `NOT_CONNECTED`, `EXPIRED`, `NOT_REQUIRED`, `UNKNOWN` 중 하나입니다.
확인되지 않은 연결 상태를 미연결이나 연결 완료로 바꾸지 않습니다. 한 판매처의 장애·기능 불일치가
있어도 다른 판매처의 정상 상태를 보존합니다. Core 전체 통신 실패는 빈 목록으로 바꾸지 않고
기존 `CORE_*` Tool 오류를 반환합니다. Tool scope 부족은 315의 `INSUFFICIENT_SCOPE`와 인증 challenge로
반환하며 Core 호출 전 차단합니다.

`CoreMerchantDirectory.registeredCapabilities()`는 서버에서 등록하고 구현·계약을 확인한
판매처별 capability 이름·버전 목록을 제공합니다. MCP는 이를 시작 시 불변 복사합니다.
Profile 응답이나 Tool 인자로 이 목록을 생성하면 안 됩니다. Core가 `verifiedCapabilities`로
제시한 각 이름·버전이 Profile과 등록 목록 양쪽에 정확히 있어야 합니다.
선언만 있고 검증되지 않은 capability는 광고하지 않으며, 검증됐다고 주장한 capability가
허용 목록이나 Profile과 다르면 해당 판매처를 `CAPABILITY_MISMATCH`로 표시하고 기능 목록을 비웁니다.
등록 정보 변경은 현재 서버 재시작이 필요합니다.

임의 판매처 ID를 목적지로 변환하지 않습니다. Core가 등록되지 않은 판매처를 반환하면
`UNSUPPORTED`·연결 `UNKNOWN`·빈 기능 목록으로 표시합니다. URL 형태의 ID, 응답의 endpoint 같은
추가 필드, 중복 판매처 ID, 잘못된 schema, 모순되는 연결 상태는 `CORE_INVALID_RESPONSE`입니다.
원본 Profile URL·서명 키·내부 진단·인증정보를 Tool 결과로 전달하지 않습니다.

### Core 담당 연동 조건

`MerchantSchemas.CORE_RESPONSE`와 `CoreMerchantDirectory`는 **MCP 내부 정규화 형식**입니다.
공유 DTO나 확정된 HTTP API가 아닙니다. `/fixture/merchant-directory`와 테스트 요청 본문은
`src/test`에만 있으며 실제 Core API로 사용하면 안 됩니다.

| 필요한 기능 | 책임 | 제안하는 정보와 검증 |
| --- | --- | --- |
| 사용자별 판매처 목록·연결 상태 조회 | Core | 인증된 공개 사용자·연결 문맥 → 사용자·연결 참조와 전체 목록. 권한·소유권을 Core에서도 검사 |
| 등록 판매처와 목적지 관리 | Core | 서버 등록 ID·허용 origin·redirect 정책. MCP가 Merchant 주소를 입력받지 않음 |
| Profile·어댑터·계약 일치 확인 | Core | 선언된 capability와 실제 검증된 이름·버전. 현재 지원하지 않는 기능은 검증 완료로 표시하지 않음 |
| 판매처 상태 판정 | Core | 연결 필요·만료·미지원·장애·권한 부족의 구분. 일부 장애와 Core 전체 실패 분리 |
| MCP–Core 서비스 인증·문맥 전달 | Core 및 인증 담당 | 합의된 서버 간 인증 또는 토큰 교환, 공개 ID 매핑·연결 해제 확인. 사용자 Bearer 무조건 전달 금지 |

계약이 확정되면 공유 계약 모듈 → Core 제공자 → MCP의 HTTP 어댑터 순서로 연결하고 제공자·소비자
테스트를 함께 검증해야 합니다. 이 Task는 Core·Pay·Merchant·공유 DTO·DB·Compose·CI를 변경하지 않습니다.

### 로컬 확인과 남은 범위

Java 21, Windows에서 MCP 모듈의 **138개 테스트(기존 84 + 신규 54)**와 build·Checkstyle을 통과했습니다.
새 테스트는 상태·capability 검증 31개, 활성화 조건 4개, MCP HTTP 통합 19개입니다.
실패·오류·건너뜀은 0건입니다. 위의 Windows 출력 경로 우회 설정을 그대로 사용할 수 있습니다.

```powershell
# backend/에서 Java 21과 앞 절의 $initPath 설정 후 실행
.\gradlew.bat :apps:mcp-server:test --tests '*Merchant*' --no-configuration-cache --init-script $initPath
.\gradlew.bat :apps:mcp-server:build --no-configuration-cache --init-script $initPath
```

테스트가 임의의 loopback 포트에서 MCP 서버·Fake Core·테스트 인증기를 시작하고 종료합니다.
`MerchantToolIntegrationTest`에서 initialize → tools/list → tools/call의 실제 HTTP 흐름을 확인합니다.
정상 목록·빈 목록·미연결·만료·미지원·일부 장애·권한 부족·다른 사용자/연결·위조 입력·허위 capability·
redirect·잘못된 Core 응답·타임아웃과 동시 사용자 8명의 상태 격리를 검증합니다.
Core fixture가 받는 본문은 검증된 사용자·연결 참조뿐이며 사용자 Bearer가 전달되지 않는지 확인합니다.

실제 Core 제공자·Merchant 어댑터·OAuth 로그인·ChatGPT 연결은 아직 검증하지 않았습니다.
316은 실제 Core 계약 연결 전까지 진행 중으로 유지합니다. ChatGPT에서 첫 쇼핑몰 조회를 확인하려면
315의 인증 제공자와 316의 실제 Core 어댑터를 연결한 뒤 별도의 ChatGPT 연결 검증이 필요합니다.
이 테스트를 실행해도 ChatGPT 플러그인이나 월렛 화면이 열리는 것은 아닙니다.

제공된 SQL/SQLite 카탈로그를 바탕으로 여러 로컬 쇼핑몰의 가격을 다르게 만드는 작업은
상품 검색 Task에서 Merchant Mock 담당 경계와 맞춰 진행합니다. Wallet demo 디자인은 상품 결과·
승인 화면 연결 단계의 참고 자료로 유지합니다. 이번에는 카탈로그 원본이나 공용 DB를 변경하지 않았습니다.

Tool 형식 근거: [MCP 2025-11-25 Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).

## 단계별 모바일 QA

Story 362부터 ChatGPT 연결·최소 UI를 먼저 검증하고 검색·장바구니·앱 결제 인계를 단계적으로 추가합니다.
[전체 계획](docs/roadmap.md)과 [QA 실행 및 휴대폰 확인 방법](docs/mobile-qa.md)을 참고하세요.
UI는 `MCP_UI_ENABLED=true`일 때 등록되며 운영 기본 인증 차단은 유지됩니다.

구매 인계 Mock과 런타임 키 환경파일 설정은 [QA4 실행 문서](docs/qa4.md)를 참고하세요.
상품 탐색은 [QA2 실행 문서](docs/qa2.md), 장바구니 구성과 상태 유지는
[QA3 실행 문서](docs/qa3.md)를 참고하세요. QA3의 장바구니 저장은 테스트용 Mock Core가 담당하며,
실제 Core API 계약이 확정되면 내부 포트의 어댑터를 교체합니다.

### Core 연동 프로필의 쇼핑 권한 동의

`core-integration`은 최초 연결부터 `catalog:read cart:read cart:write`를 요구합니다.
검색 전용 토큰으로 연결한 뒤 장바구니에서 추가 동의를 반복하던 경로를 줄이기 위한 정책입니다.
검색만 사용하는 클라이언트에도 이 프로필에서는 장바구니 권한 동의가 필요합니다.
결제 요청 권한 `purchase:handoff`는 기존처럼 해당 기능 사용 시 별도로 동의합니다.
Core는 요청한 권한을 그대로 승인 화면에 표시하며, 동의 없이 DB 권한을 확장하지 않습니다.

배포 후 ChatGPT 연결의 도구/인증 정보를 새로고침하고 다시 동의해야 합니다.
호스트에 scope가 `catalog:read`로 고정돼 있다면 연결 설정의 OAuth scope를 위 세 권한으로
수정하거나 변경된 서버 정보를 사용해 연결을 다시 등록해야 합니다.
서버가 보낸 challenge만으로 호스트에 저장된 설정이 자동 변경되는 것은 보장하지 않습니다.
완료 기준은 최신 요청의 requested_scopes, 연결의 granted_scopes, 갱신 토큰 scopes에
세 권한이 모두 포함되고 prepare_cart/get_cart가 재인증 없이 성공하는 것입니다.
