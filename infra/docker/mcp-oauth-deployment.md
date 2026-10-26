# MCP OAuth 서버 연결 (S15P21A207-405)

Core OAuth(S15P21A207-337)와 MCP 실제 연동(S15P21A207-400)이 develop에 병합된 이후의
배포 절차다. 코드 병합과 운영 환경 설정을 구분한다. 실제 콜백·서비스 키와 공개키를 등록하기
전에는 `MCP_SPRING_PROFILES_ACTIVE=core-integration`을 활성화하지 않는다.

## ChatGPT 등록·계정 연결을 먼저 진행하는 경우 (S15P21A207-415)

`oauth-registration` 프로필은 실제 Core OAuth 사용자 토큰을 검증하면서 기존 연결 확인
UI와 `open_copenhagen`, `check_connection` 두 도구만 제공한다. 상품·장바구니·구매 API를
호출하지 않으므로 MCP 서비스 개인키, 서비스 토큰 발급 설정은 이 단계에 필요 없다.
Core가 제공하는 OAuth 로그인·동의·토큰 발급·공개키와 실제 ChatGPT callback 등록은 필요하다.
OpenAI API 키나 로컬 터널 키는 사용하지 않는다.

먼저 이 프로필을 포함한 이미지를 기존 develop CI/CD로 배포한다. 그 뒤 서버 배포 폴더의
기존 `.env`에 다음 항목을 반영한다. 실제 서버 `.env`는 Git으로 전송하지 않는다.

```dotenv
MCP_SPRING_PROFILES_ACTIVE=oauth-registration
MCP_AUTH_RESOURCE=https://j15a207.p.ssafy.io/mcp
MCP_AUTH_ISSUER=https://j15a207.p.ssafy.io
MCP_AUTH_JWKS_URI=https://j15a207.p.ssafy.io/.well-known/jwks.json
MCP_ALLOWED_HOSTS=j15a207.p.ssafy.io,mcp-server:8080,localhost:8080
MCP_ALLOWED_ORIGINS=https://chatgpt.com
```

`required-scopes=catalog:read`와 JWT 검증은 프로필이 설정한다. 이 권한은 Core와 합의된
최소 읽기 권한이며 등록 모드에서 상품 도구를 활성화하지 않는다. 인증 설정이 빠졌거나
issuer/resource가 HTTPS가 아니면 시작을 거부한다. 사용자 정보나 토큰을 UI에 반환하지 않는다.
프로필은 `oauth-registration` 또는 `core-integration` 중 하나만 지정한다.

서버 배포 폴더에서 환경변수를 적용하려면 MCP 컨테이너를 재생성한다.

```bash
docker compose --env-file .env -f docker-compose.yml -f infra/docker/https.override.yml \
  up -d --no-deps --force-recreate mcp-server
curl --fail --silent --show-error \
  https://j15a207.p.ssafy.io/.well-known/oauth-protected-resource/mcp
```

metadata가 `200`이며 공개 resource/authorization_servers와 `catalog:read`를 제공하는지
확인한다. 인증 없는 `/mcp`는 계속 `401`이어야 하고 `WWW-Authenticate`에 metadata URL이
포함되어야 한다. `healthy`나 metadata `200`만으로 실제 계정 연결 완료를 판단하지 않는다.

ChatGPT 등록에는 공개 HTTPS `/mcp`와 OAuth를 사용한다. 사전 등록 client ID를 입력할 수
있는 화면에서는 Core의 PUBLIC 클라이언트 `chatgpt-plugin`을 사용하며 임의의 client secret을
만들지 않는다. Core는 현재 동적 클라이언트 등록 endpoint를 제공하지 않으므로 화면이 이 방식을
지원하는지도 확인한다. 실제 callback URL은 ChatGPT 설정에서 확인해 Core 담당자가 정확히
등록해야 한다. 기존 로컬 QA 터널 연결을 계정 연결 검증에 사용하지 않는다.
등록 완료 후 새 대화에서 `코펜하겐 열어줘`를 요청하여 연결 확인 UI와 버튼 호출을 검증한다.
상품 검색·장바구니·결제는 등록 프로필의 검증 범위가 아니다.

추후 Core 업무 API와 MCP 서비스 키/공개키 등록이 준비되면 프로필을
`core-integration`으로 교체하고 재배포한 뒤 ChatGPT 도구를 Refresh한다.
아래 서비스 키 준비 절차는 이 전체 기능 연동 단계에 적용한다.

## 주소와 역할

| 설정/주소 | 값과 역할 |
| --- | --- |
| MCP URL / Agent JWT audience | `https://j15a207.p.ssafy.io/mcp` |
| OAuth issuer | `https://j15a207.p.ssafy.io` (끝에 `/` 없음) |
| 로그인·동의 | `https://j15a207.p.ssafy.io/oauth/authorize` |
| 토큰 발급 | `https://j15a207.p.ssafy.io/oauth/token` |
| Agent 공개키 | `https://j15a207.p.ssafy.io/.well-known/jwks.json` |
| Core 업무 API origin | `http://core-service:8080` (Docker 내부) |
| ChatGPT 콜백 | ChatGPT 연결 설정에서 확인한 정확한 callback URL |
| 앱 결제 승인 진입 | Pay 푸시 알림을 사용자가 선택하면 별도 앱이 구매·승인 ID로 화면을 연다 |

MCP에서 구매를 요청하면 Core가 기본 배송지·재고·금액을 검증하고 Pay가 결제 승인 요청과
푸시 알림을 생성한다. 사용자는 알림을 선택해 별도 앱에서 주문을 확인하고 결제를 승인한다.
배송지가 없으면 앱에서 같은 구매 요청의 배송지를 보완한다. MCP 웹 화면은 상태 확인과
알림 재전송을 제공하며, 앱을 여는 HTTPS URL을 요구하지 않는다.

앱 담당자는 Pay 알림 설치 등록과 수신 payload의 식별자 처리, 로그인 후 구매·승인 화면
복원을 구현한다. 실기기 푸시에는 Pay FCM 설정과 등록된 설치 토큰이 필요하다.
Core의 OAuth 로그인·동의 HTML은 ChatGPT 연결 권한을 부여하는 별도 웹 화면이다.

`CORE_TOKEN_URI`는 위 공개 HTTPS 토큰 주소를 사용한다. MCP는 이 URL을 client assertion의
audience에도 사용하므로 임의로 `http://core-service:8080/oauth/token`으로 바꾸면 Core의
검증값과 달라진다. MCP 컨테이너에서도 공개 도메인의 DNS/TLS 접근이 가능해야 한다.

### 공개 주소 기본값과 기존 서버 설정

`docker-compose.yml`만 실행하는 로컬 환경은 `http://localhost:8080` issuer와
`http://localhost:8080/mcp` audience를 기본값으로 사용한다. 개발 EC2에서는
`infra/docker/https.override.yml`을 함께 적용하여 두 값이 미설정 또는 빈 값이면
위 표의 공개 HTTPS 주소를 사용한다(S15P21A207-406).

서버 `.env`에 `AGENT_OAUTH_ISSUER`나 `AGENT_OAUTH_MCP_AUDIENCE`를 명시했다면 그 값이
우선한다. 기존 값이 localhost라면 공개 주소로 수정해야 한다. 배포는 `.env`의 운영자
설정을 덮어쓰지 않는다. 예제 파일을 변경하거나 Nginx만 재시작해도 실행 중인 Core의
OAuth 주소는 바뀌지 않으므로 Core 컨테이너 재생성이 필요하다.

HTTPS override 변경은 CI에서 Core 테스트·빌드를 선택하며 develop 병합 후에는 smoke를
통과한 뒤 Core 이미지와 컨테이너도 갱신한다. Nginx 설정 파일만 변경하면 기존처럼
Nginx 배포만 수행한다. 작업 브랜치에서는 검증만 실행한다.

배포 후 아래 명령으로 비밀정보 없이 공개 주소 네 항목을 확인한다.

```bash
curl --fail --silent --show-error \
  https://j15a207.p.ssafy.io/.well-known/oauth-authorization-server \
  | jq '{issuer, authorization_endpoint, token_endpoint, jwks_uri}'
```

모든 주소가 `https://j15a207.p.ssafy.io`를 사용해야 한다. 여전히 localhost라면
`core-deploy-dev` 성공 여부와 서버 `.env`의 두 OAuth 주소 설정을 확인한다. 주소 수정만으로
MCP 인증 프로필이나 서비스 키가 활성화되지는 않는다.

## 1. 서버 전용 설정과 키

기존 `<DEV_DEPLOY_PATH>/.env`에 [ec2.env.example](ec2.env.example)의 OAuth 항목을 반영한다.
기존 DB·인증서·이미지 설정을 예제 파일로 덮어쓰지 않는다. Compose는 `.env`를 치환에
사용하고 `docker-compose.yml`에 명시한 값만 컨테이너에 전달한다. 예제 파일은 실제 값을
포함하지 않으며 CI는 운영 `.env`와 키를 저장소에서 복사하지 않는다.

| 주체 | 개인키 형식/설정 | 공개키 등록 위치 |
| --- | --- | --- |
| Core Agent OAuth | P-256 EC PKCS#8 DER의 Base64URL, `AGENT_OAUTH_JWT_PRIVATE_KEY` | Core `/.well-known/jwks.json`이 자동 제공 |
| MCP 서비스 클라이언트 | `kid`가 있는 P-256 EC 또는 RSA 2048bit 이상 개인 JWK 파일 | Core DB `oauth_clients.client_jwks`의 JWK Set |

두 키는 서로 다른 키이며 앱 로그인용 `CORE_AUTH_JWT_PRIVATE_KEY`와도 별개다.
Core 키를 비우면 현재 Core 구현은 시작할 때마다 새 키를 생성하므로 서버에서는 고정된
키와 `AGENT_OAUTH_JWT_KEY_ID`를 지정한다. 개인키를 콘솔에 출력하거나 MR·채팅에 붙이지 않는다.

MCP 전용 비밀 디렉터리에 `client.jwk`만 배치한다. `MCP_SECRETS_DIR`은 이 디렉터리의
호스트 절대경로이며 컨테이너의 `/run/secrets/mcp`에 읽기 전용으로 마운트된다.
Core 개인키, 서버 `.env`, TLS 인증서는 이 디렉터리에 넣지 않는다.

```bash
# 해당 이미지의 app UID/GID 확인 후 키 소유권/읽기 권한을 지정한다.
# root 전용 0600 파일을 그대로 마운트하면 비-root app 사용자는 읽을 수 없다.
# MCP_IMAGE에는 서버 .env의 해당 배포 이미지명을 먼저 지정한다.
docker run --rm --entrypoint id "$MCP_IMAGE" app

# 서버 .env에 MCP_SECRETS_DIR를 지정한 후, 실제 컨테이너 사용자로 읽기 가능 여부 확인
docker compose --env-file .env -f docker-compose.yml -f infra/docker/https.override.yml \
  run --rm --no-deps --entrypoint sh mcp-server \
  -c 'test -r /run/secrets/mcp/client.jwk && test -s /run/secrets/mcp/client.jwk'
```

기본값 `./.secrets/mcp`는 Git에서 제외된다. 기존 비밀 디렉터리를 쓰는 경우 소유권은 그
디렉터리의 실제 운영 방식에 맞춘다. 키 파일 내용을 출력하는 `cat`이나 전체 환경이
노출되는 `docker inspect`/`docker compose config` 결과를 공유하지 않는다.

## 2. Core OAuth 클라이언트 등록

Core V10 마이그레이션은 클라이언트를 생성한다. V14는 PUBLIC `chatgpt-plugin`의
`redirect_uris`에 `https://chatgpt.com/connector_platform_oauth_redirect`를 중복 없이 추가한다.
기존 개별 콜백, scope, 클라이언트 상태와 `copenhagen-mcp` 설정은 보존한다.
이미 적용한 migration을 수정하지 않고 새 migration을 배포한다.

- `chatgpt-plugin`: ChatGPT에서 표시되는 실제 콜백 URI를 `redirect_uris` JSON 배열에
  정확히 등록한다. 초기 `https://chat.openai.com/aip/PLACEHOLDER/oauth/callback`을 그대로
  사용하지 않는다. Actions용 주소를 MCP callback으로 추정하지 않는다.
- `claude-connector`(V20, 이슈 451): Claude 커스텀 커넥터용 PUBLIC 클라이언트다. 콜백은
  `https://claude.ai/api/mcp/auth_callback` 하나이고 Anthropic 공식 문서 "Authentication for
  connectors"의 Callback URLs 절이 지정한 값이다. claude.ai 웹·데스크톱·모바일이 같은 주소를
  쓴다. Core는 `registration_endpoint`(DCR)와 CIMD를 공개하지 않으므로 Claude 연결 화면에서
  "Use your own OAuth client"을 고르고 client ID로 `claude-connector`를 입력한다. 시크릿은
  비운다. Claude Code와 Gemini CLI는 루프백 콜백의 포트가 매번 달라 현재의 완전 일치 검증에
  걸리므로 이 클라이언트로 붙지 않는다.
- `copenhagen-mcp`: `client.jwk`에 대응하는 **공개키만** `{"keys":[...]}` JWK Set으로
  `client_jwks`에 등록한다. `kid`와 서명 알고리즘이 일치해야 하며 개인 필드 `d`, `p`, `q`
  등은 등록하지 않는다. 초기 `NULL`이면 client_credentials 발급이 거부된다.
- 사용자 클라이언트는 PUBLIC/PKCE S256 방식이다. 서비스 클라이언트는
  CONFIDENTIAL/private_key_jwt 방식이다. OpenAI API 키나 터널 런타임 키를 사용하지 않는다.

위 DB 값은 Core 담당자가 등록하고 실제 계정으로 로그인·동의를 검증한다. 비밀번호나
발급된 access/refresh token을 MCP 설정 파일에 고정해서 우회하지 않는다.

### ChatGPT 고정 콜백 전환 (S15P21A207-416)

Core는 RFC 9207에 따라 OAuth discovery에
`authorization_response_iss_parameter_supported: true`를 공개한다. 승인(code), 권한 오류
(`invalid_scope`), 동의 거절(`access_denied`)의 콜백 응답에는 discovery의 `issuer`와
정확히 같은 `iss`를 URL 인코딩하여 붙인다. 요청의 Host·Forwarded 헤더나 입력값으로
issuer를 만들지 않고 기존 `AGENT_OAUTH_ISSUER`를 그대로 사용한다.
미등록 콜백과 존재하지 않거나 만료된 인가 요청은 기존처럼 직접 오류를 표시하며 리다이렉트하지 않는다.

OpenAI는 이 조건을 지원하는 인증 서버의 신규 연결에 고정 콜백을 사용할 수 있다.
기존 연결의 개별 callback ID가 자동으로 바뀐다고 가정하지 않는다.

1. Core 변경과 V14 migration을 같은 배포로 반영한다. MCP 등록 프로필 배포와는 별도 작업이다.
2. 공개 Core discovery의 `issuer`와 지원 플래그, MCP protected resource metadata의
   `authorization_servers`가 가리키는 issuer가 일치하는지 확인한다.
3. ChatGPT의 새 플러그인 등록 화면에서 OAuth 설정을 다시 탐지한다. PUBLIC 클라이언트
   `chatgpt-plugin`, 토큰 인증 방식 `none`을 사용하고 클라이언트 시크릿은 입력하지 않는다.
4. 화면에 표시된 콜백이 `https://chatgpt.com/connector_platform_oauth_redirect`이면 V14에서
   이미 등록한 주소를 사용할 수 있다. 여전히 개별 주소가 표시되면 임의로 교체하지 않고
   discovery 재탐지 및 새 등록 화면을 확인한다. 필요한 기존 개별 주소는 허용 목록에 유지한다.
5. 실제 앱 계정으로 승인·거절을 각각 확인한다. 승인 후 MCP 호출까지 성공해야 연결 완료다.

새 환경변수나 OpenAI API 키는 필요 없다. 각 테스트 사용자는 플러그인에 접근할 수 있는
ChatGPT 계정과 본인 앱 계정으로 연결한다. 고정 콜백 등록이 플러그인 공개·배포 권한이나
사용자 로그인·동의를 대체하지 않는다. 와일드카드 또는 URI 접두사 일치 검증은 추가하지 않는다.

배포 전에는 Core의 다른 미병합 migration과 V14 버전 충돌이 없는지 확인한다. 코드 롤백으로
발급자 식별 지원이 사라지면 고정 콜백 연결을 계속 보장할 수 없으므로 재탐지·재연결을 확인한다.

근거: [OpenAI OAuth 발급자 식별 및 Redirect URL](https://developers.openai.com/plugins/build/auth#protect-callbacks-with-issuer-identification).

## 3. 적용 순서

1. 고정 Core 키, MCP 키·공개키 등록과 실제 callback을 준비한다.
2. Core의 `AGENT_OAUTH_ISSUER`·`AGENT_OAUTH_MCP_AUDIENCE`를 공개 주소로 지정하고 Core를 배포한다.
3. Nginx 변경을 배포하여 OAuth discovery·JWKS·로그인·토큰 요청을 Core로 전달한다.
4. 서버 `.env`에서 `MCP_SPRING_PROFILES_ACTIVE=core-integration`을 활성화하고 MCP를 재배포한다.
5. 아래 외부 검증 후 ChatGPT 연결을 새로고침하고 새 대화에서 확인한다.

기존 develop CI/CD를 사용한다. 작업 브랜치 수동 배포 job은 추가하지 않는다.
`.env`만 변경하면 실행 중인 컨테이너에는 적용되지 않으므로 재생성이 필요하다.
누락된 필수값이 있으면 MCP는 시작을 거부하며 기존 배포 스크립트가 롤백을 시도한다.
`MCP_APP_LINK_BASE`와 `CORE_INTERNAL_APP_LINK_BASE`는 더 이상 사용하지 않는다.
이 변경은 Core와 MCP를 함께 배포한다. Core 구매 응답은 `appLink` 없이 생성 스냅샷을 반환하고,
MCP는 이전 멱등 응답에 남은 `appLink`도 사용자에게 전달하지 않는다.
구매 후속 처리에는 `CORE_PAYMENT_WORKER_ENABLED`, Pay 알림 worker·FCM 설정이 필요하다.
새 이미지 배포 후 ChatGPT 도구를 Refresh하고 새 대화에서 확인한다.

## 4. 외부 연결 검증

| 요청 | 기대 결과 |
| --- | --- |
| GET `/.well-known/oauth-protected-resource/mcp` | 200, resource와 authorization_servers가 공개 HTTPS 주소 |
| GET `/.well-known/oauth-authorization-server` | 200, issuer·authorize·token·jwks 주소가 동일 배포와 일치 |
| GET `/.well-known/jwks.json` | 200, 등록한 Core key ID의 공개키 |
| 인증 없는 POST `/mcp` | 401, WWW-Authenticate에 resource_metadata URL 포함 |
| `/internal`, `/internal/v1/catalog/products` | 외부에서는 404 유지 |

200/401 확인만으로 로그인 완료라고 판단하지 않는다. ChatGPT에서 OAuth 로그인·동의·콜백과
토큰 발급을 완료하고 도구 목록 조회, 검색, 장바구니를 실제 Core로 호출해야 한다.
Core `/internal/v1` 업무 API 준비 여부도 별도로 확인한다. S25 새 대화에서 새 연결을 선택해
`코펜하겐 열어줘`로 시작한다. 기존 `Copenhagen QA4` 로컬 Mock 연결과 구분한다.

로컬 검증: `python scripts/test-mcp-deployment.py`는 Docker daemon 없이 Compose의 실제
치환 결과와 환경변수·읽기 전용 키 마운트를 확인한다. `python scripts/test-nginx-config.py`는
Docker에서 임시 인증서와 구분 가능한 합성 upstream을 띄워 HTTP/HTTPS 전달 대상을 검증한다.
운영 계정, 인증서, DB에는 접근하지 않으며 GitLab `nginx-config-test`에서 두 검증을 실행한다.
