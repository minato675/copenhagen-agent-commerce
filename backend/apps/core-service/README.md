# Core Service

UCP 플랫폼의 공개 경계와 구매 흐름 조정을 담당하는 독립 애플리케이션입니다.

## 책임과 경계

- `core-db`를 단독으로 소유합니다.
- `pay-service`와 `ucp-merchant-mock`만 내부 HTTP로 호출할 수 있습니다.
- 서비스 간 데이터 구조는 `ucp-contract`와 `internal-api-contract`를 사용합니다.
- 다른 애플리케이션이 이 모듈의 구현 코드, Entity, Repository를 가져가면 안 됩니다.
- Pay 또는 Merchant의 DB에 직접 연결하면 안 됩니다.

## 주요 경로

```text
src/main/java/          애플리케이션 코드
src/main/resources/     설정과 Core 소유 Flyway migration
src/test/java/          Core 테스트
Dockerfile              독립 이미지 빌드 정의
```

## 개발 명령

`backend/`에서 실행합니다.

```bash
./gradlew :apps:core-service:test
./gradlew :apps:core-service:build
```

## ChatGPT OAuth 고정 콜백

`/.well-known/oauth-authorization-server`는 RFC 9207 발급자 응답 식별 지원을 공개합니다.
`/oauth/authorize`의 승인·콜백 오류 응답은 기존 `AGENT_OAUTH_ISSUER`와 동일한 `iss`를
포함합니다. V14 migration이 기존 콜백을 보존하면서 ChatGPT 고정 콜백을 추가합니다.
실제 전환과 배포 확인은 [MCP OAuth 배포 절차](../../../infra/docker/mcp-oauth-deployment.md#chatgpt-고정-콜백-전환-s15p21a207-416)를 따릅니다.

## OAuth 계정 연결 화면

`GET /oauth/authorize`의 로그인·권한 동의 화면과 연동 불가 화면은 Core의
`ConsentPageRenderer`가 렌더링합니다. MCP 상품 화면과 같은 파란색·회색 테마,
IBM Plex Sans KR 글꼴, 투명 배경 로고를 사용합니다. 로그인 실패 시 같은 요청의
폼에 오류를 표시하며, **연결하지 않기**는 로그인 정보를 입력하지 않아도 동작합니다.

- 스타일·로고·글꼴: `src/main/resources/static/oauth/authorize/assets/v1/`
- 글꼴 원본·라이선스: `mcp-server/ui/fonts/`의 IBM Plex Sans KR과 SIL OFL을 복사해 포함
- 로고 원본: `mcp-server/ui/assets/copenhagen-logo-transparent.png`
- 외부 CDN, JavaScript, 새 환경 변수 없이 Core 이미지에 함께 패키징
- 기존 Nginx `/oauth/authorize/` 라우팅으로 정적 파일 제공
- 실제 요청한 scope만 한국어로 안내하며, 새 scope는 원문으로 표시

화면 변경은 **Core 이미지 배포 후 새로 시작한 계정 연결 화면**에서 확인합니다.
MCP 이미지나 OAuth 발급·검증 설정을 바꿀 필요는 없습니다. 배포 후 로그인 화면에서
로고·글꼴·스타일 로드, 로그인 실패·재시도, 입력 없이 연결 거부를 확인하세요.

## 로그인 흐름 로그 추적

Core 로그인 API도 `X-Request-Id`를 응답 헤더와 로그에 남깁니다. 로그인 ID·Device ID·사용자 ID는 SHA-256 앞 16자리만 기록하고, 비밀번호·Access Token·Refresh Token은 기록하지 않습니다. `X-App-Version`, `X-OS-Version`, `X-Platform` 헤더를 보내면 해당 앱 환경도 로그인 로그에 함께 남습니다.

로그인 시작·성공·실패는 `core.login.*`, 요청 공통 로그는 `core.request.*` 이벤트로 검색할 수 있습니다. 같은 헤더를 이후 Pay 요청에도 전달하면 Core와 Pay 로그를 `requestId`로 연결할 수 있습니다.

실행은 레포 루트에서 `docker compose up --build core-service` 또는 전체 `docker compose up --build`를 권장합니다. `bootRun`을 직접 사용할 때는 개발 PC에서 접근 가능한 PostgreSQL과 의존 서비스 URL을 환경변수로 별도 지정해야 합니다.

Docker Compose에서는 `CORE_DB_URL`, `PAY_SERVICE_URL`, `MERCHANT_SERVICE_URL` 등의 환경변수로 연결 대상을 주입합니다. 서비스 주소는 Nginx가 아니라 Docker DNS 이름을 사용합니다.

## 로그로 요청 하나 추적하기

로그 한 줄마다 `req= usr=` 두 칸이 붙습니다. `req`는 `X-Request-Id`입니다. 앱·MCP가 보낸 값이 있으면 그대로 쓰고 없으면 Core가 만들어 응답 헤더로 돌려줍니다. 같은 값이 Merchant 호출의 `X-Request-Id` 헤더에 실리므로 Core와 `ucp-merchant-mock` 로그를 한 값으로 이어 볼 수 있습니다. `usr`은 Access Token의 사용자 공개 UUID이고 보호 라우트에서만 찍힙니다.

요청·응답은 `ACCESS` 접두어의 한 줄 JSON으로 따로 남습니다. `event`는 들어온 요청이 `http.access`, 나가는 Merchant 호출이 `http.client`입니다.

```bash
# 요청 하나 따라가기
docker compose logs core-service | grep 'req=core-req-42'

# 요청·응답만 보기
docker compose logs core-service | grep ' ACCESS '

# 실패한 요청만
docker compose logs core-service | grep ' ACCESS ' | grep '"errorCode"'
```

```text
ACCESS {"event":"http.access","requestId":"3f2a…","userId":"9c1b…","method":"POST",
        "path":"/api/core/v1/addresses","status":201,"durationMs":62,"requestBytes":159,"clientIp":"127.0.0.1"}
ACCESS {"event":"http.client","requestId":"3f2a…","target":"merchant-a","method":"POST",
        "path":"/ucp/v1/catalog/search","status":200,"durationMs":112}
```

**요청·응답 본문과 헤더는 남기지 않습니다.** 비밀번호·토큰·이메일·배송지가 로그로 새는 경로를 막기 위해서이고(`backend/docs/code-convention.md` §로깅, POLICY-06), 그런 이름의 키를 넣으려 하면 `AccessLogLine`이 예외로 거절합니다. 남는 것은 method·path·status·errorCode·소요시간·요청 바이트 수·clientIp뿐입니다. 쿼리 스트링도 버립니다.

`/actuator/**`는 기록하지 않습니다 — compose healthcheck가 10초마다 칩니다.

끄려면 로거 하나만 내립니다: `logging.level.access=OFF`.

상태 전이 단위의 감사 로그(로그인 실패·잠금·토큰 폐기 등)는 여기에 없습니다. 구현순서 순서 16의 별도 작업이고, 저장 구조는 D-7로 보류 중입니다.

## 로컬 DB 통합 테스트 실행

`AuthSchemaIntegrationTest`처럼 실제 PostgreSQL 제약조건을 검증하는 테스트는 `CORE_TEST_DB_URL` 환경변수가
설정되어 있을 때만 실행되고, 없으면 자동으로 skip됩니다. CI(`core-test` job)에서는 항상 실행됩니다.

로컬에서 실행하려면 별도 PostgreSQL 컨테이너를 띄우고 접속 정보를 환경변수로 지정합니다.

```powershell
docker run --rm -d --name core-test-db -p 15432:5432 `
  -e POSTGRES_DB=core-test -e POSTGRES_USER=core -e POSTGRES_PASSWORD=core-dev-password `
  postgres:17-alpine

$env:CORE_TEST_DB_URL = "jdbc:postgresql://localhost:15432/core-test"
$env:CORE_TEST_DB_USER = "core"
$env:CORE_TEST_DB_PASSWORD = "core-dev-password"

.\gradlew.bat :apps:core-service:test
```

테스트가 끝나면 컨테이너를 정리합니다.

```powershell
docker stop core-test-db
```

## 결제 완료 장바구니 정리 (S15P21A207-474)

V19에서 주문 항목에 구매 당시 cartItem public ID와 구매 요청에 정리 완료 시각을 추가합니다.
전체 판매처 결제가 COMPLETED인 경우 구매 수량만 차감하고 carts.version을 증가시킵니다.
구매 요청 잠금 → 주문 스냅샷 잠금 → 장바구니 잠금 순서와 정리 완료 마커로 중복 완료를 방어합니다.
결제 실패/승인 대기/일부 완료 상태에서는 장바구니를 변경하지 않습니다.
결제 워커가 활성화된 환경에서는 완료된 과거 요청도 주기적으로 정리합니다.

배포 전 스냅샷에는 항목 ID가 없으므로 cart_version이 현재 버전과 같은 경우에만 대조합니다.
과거 APP 주문처럼 버전이 없거나 장바구니가 변경된 경우는 새 항목 오삭제를 막기 위해 자동 차감하지 않습니다.
이런 과거 데이터는 별도 확인 후 정리해야 합니다. 새 결제에는 항목 ID가 저장됩니다.
과거 대화에 이미 출력된 카드는 정적 결과이며 최신 get_cart 조회에 정리 결과가 반영됩니다.
