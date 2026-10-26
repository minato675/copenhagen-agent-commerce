# Core 실제 연동 (S15P21A207-400)

2026-09-18 Pay 푸시 알림 기반 결제 승인 흐름을 기준으로 한다. canonical resource는
`https://j15a207.p.ssafy.io/mcp`다. HTTPS 인증서 검증 및 미인증 요청의 401 응답을
확인했다. 공개 HTTP 주소로 인증 토큰을 전송하지 않는다.

## 구현 범위

- Core JWKS로 Agent JWT의 서명, issuer, audience, 만료, 사용자, connection_id, scope 검증
- `copenhagen-mcp`의 private_key_jwt 및 client_credentials 기반 Service JWT
- `/internal/v1`의 상품 검색·상세, variant 기반 장바구니, 구매 요청 생성
- 모든 내부 요청에 Service JWT, Agent JWT, UUID 요청 ID 전달
- 변경 요청의 UUID 멱등키와 본문 보존 및 결과 불명 상태 복구
- 다수 판매처 구매 요청, Pay 승인 알림, 구매 상태 확인과 알림 재전송

## 외부 준비 항목

| 담당 | 필요한 정보 또는 설정 |
| --- | --- |
| 인프라 | 공개 HTTPS 인증서와 `/mcp` 프록시: 접근 확인 완료 |
| 인프라 | `/.well-known/oauth-protected-resource` 및 `/mcp` 접미사 경로를 MCP로 전달 |
| Core | OAuth issuer, JWKS URL, token endpoint, 내부 API origin |
| Core | `copenhagen-mcp` confidential client 등록, 허용 서명 알고리즘과 공개키 등록 |
| MCP·인프라 | 대응 개인키를 저장소 밖의 비밀 파일로 배치하고 컨테이너에 읽기 전용 마운트 |
| App·Pay | 알림 설치 등록, FCM 수신 후 구매·승인 ID로 화면 복원 |
| Core | 테스트 계정과 OAuth 동의 화면, 실제 내부 API 준비 |

개인키와 발급된 토큰은 채팅, 문서, Git, 로그에 기록하지 않는다.
Core의 Agent 토큰 audience는 최종 MCP canonical HTTPS URL과 정확히 같아야 한다.
연동 해제 시 refresh token은 즉시 폐기되며 기존 access token은 원래 만료 시각까지
허용한다. 별도 introspection이나 연결 상태 조회 API는 추가하지 않는다.

## 검증 기준

로컬 계약 테스트와 실제 배포 검증을 구분한다. 공개 서버 연결, OAuth 로그인,
Core 업무 API 왕복은 각 담당자의 설정과 배포가 완료된 뒤 검증한다.
배포 적용 절차는 [MCP OAuth 서버 연결](../../../../infra/docker/mcp-oauth-deployment.md)을 따른다.

## 현재 구현과 활성화

`core-integration` 프로필에서 실제 Core 어댑터를 활성화한다. 기본 프로필과 QA 프로필은
기존 동작을 유지한다. 실제 연동과 QA 상품·장바구니·인계 어댑터를 동시에 켜면 시작을 거부한다.
UI는 파란색·회색 테마, 가로 상품 카드,
전체 카테고리 검색, 휴대폰 기본 옵션 선택창을 사용하며 전체화면 버튼을 제공하지 않는다.

환경변수 목록은 [core-integration.env.example](core-integration.env.example)에 있다.
Spring Boot는 `.env`를 자동으로 읽지 않는다. 인프라에서 컨테이너 환경변수로 주입하고
`CORE_MCP_PRIVATE_JWK_FILE`의 파일을 읽기 전용 secret으로 마운트한다. `kid`가 있는
RSA 2048비트 이상 또는 P-256 EC 개인 JWK에 대응하는 공개키를 Core에 등록해야 한다.
OpenAI API 키와 로컬 Secure Tunnel 런타임 키는 이 직접 연결 경로에서 사용하지 않는다.

저장소에서 확인한 내부 주소는 `http://core-service:8080`이다. 기존 앱 JWT의
`copenhagen-core` issuer / `copenhagen-app` audience는 Agent OAuth 계약과 다르므로 재사용하지 않는다.
2026-09-17 develop에 Core OAuth(337)가 병합되었다. 동일 호스트용 issuer·JWKS·token URL은
환경변수 예제에 반영했다. 실제 callback·MCP 서비스 공개키와 `/internal/v1` 업무 API의
준비 상태는 별도 확인한다. 누락된 필수값을 넣기 전에는 실제 연동 프로필을 켜지 않는다.

### 공개 경로 전달

| 공개 경로 | 제공자 | 현재 확인 |
| --- | --- | --- |
| `/mcp` | MCP | HTTPS 401, 인증 전 요청 거절 |
| `/.well-known/oauth-protected-resource/mcp` | MCP | nginx 404, 프록시 전달 필요 |
| `/.well-known/oauth-protected-resource` | MCP | MCP가 제공하는 보조 검색 경로 |
| `/.well-known/oauth-authorization-server` | Core | nginx 404, Core OAuth 배포와 전달 필요 |
| `/.well-known/jwks.json` | Core | nginx 404, Core JWKS 배포와 전달 필요 |
| `/oauth/authorize`, `/oauth/token` | Core | 최종 OAuth 배포 경로 확인 필요 |

위 응답은 2026-09-17 변경 배포 전 확인 결과다. S15P21A207-405에서 공개 경로 프록시를
추가했으며 실제 배포 후 표의 응답을 다시 검증해야 한다. 인증 서버를 다른 HTTPS origin에 배치하면 issuer의 실제
discovery와 JWKS URL을 사용한다. `/internal/v1`은 외부에 공개하지 않는다.

## 도구와 호출 계약

| MCP 도구 | Core HTTP | 필요 scope |
| --- | --- | --- |
| `search_products`, `open_copenhagen`, `display_products` | GET `/internal/v1/catalog/products` | `catalog:read` |
| `get_product_detail` | GET `/internal/v1/merchants/{merchantId}/products/{merchantProductId}` | `catalog:read` |
| `get_cart` | GET `/internal/v1/carts/current` | `cart:read` |
| `add_cart_item` | POST `/internal/v1/carts/current/items` | `cart:write` |
| `update_cart_item` | PATCH `/internal/v1/carts/current/items/{cartItemId}` | `cart:write` |
| `delete_cart_item` | DELETE `/internal/v1/carts/current/items/{cartItemId}?cartVersion=N` | `cart:write` |
| `prepare_purchase_handoff` | POST `/internal/v1/purchase-requests` | `purchase:handoff` |
| `get_purchase_status` | GET `/internal/v1/purchase-requests/{id}` | `purchase:handoff` |
| `get_purchase_history` | GET `/internal/v1/purchase-history` | `purchase:handoff` |
| `resend_purchase_notification` | POST `/internal/v1/purchase-requests/{id}/notifications` | `purchase:handoff` |

`get_purchase_history`는 최근 6개월 구매내역을 데이터로만 반환하는 읽기 전용 도구다.
페이지 이동, nullable 상품 ID, 재구매 안내 및 배포 검증은
[구매내역 MCP 연동](../../../../docs/api/mcp-purchase-history-495.md)을 따른다.

모든 내부 호출은 Service JWT를 `Authorization`, 현재 요청의 Agent JWT를
`X-Agent-Authorization`, UUID를 `X-Request-Id`에 넣는다. Agent JWT를 서버 캐시나
UI 상태에 저장하지 않는다. Service JWT는 만료 30초 전까지만 메모리에서 재사용한다.
변경 도구 입력의 `idempotencyKey`는 Core의 `Idempotency-Key` 헤더로만 전달한다.

장바구니 성공 응답 이후 후속 조회가 실패하면 `APPLIED`와 `REFETCH_CART`를 반환한다.
타임아웃·5xx·해석 불가능한 변경 결과는 `UNKNOWN`으로 남기며 UI가 같은 키와 본문으로
재조회한다. 24시간 복구 기한이 지난 미확인 요청은 임의의 새 키로 재전송하지 않는다.
Core의 version/수량 충돌은 최신 장바구니 확인 또는 옵션 재선택으로 처리한다.

구매 생성의 200/201은 모두 성공으로 처리한다. 재응답은 생성 당시의 스냅샷이며
현재 구매·결제 상태가 아니다. Core는 앱에 배송지 선택 알림을 보내고, 사용자가 고른 배송지로
주문을 검증한 뒤 Pay 승인 알림을 보낸다.
사용자는 알림을 선택해 별도 앱에서 주문 확인·결제 승인을 한다. MCP는 App Link를 생성하거나
열지 않으며 `MCP_APP_LINK_BASE`를 요구하지 않는다. 웹은 상태 확인과 알림 재전송을 제공한다.

구매 응답 필드는 `purchaseRequestId`, `status`, `cartId`, `cartVersion`, `expiresAt`, `nextAction`이다.
최초 `PENDING_ADDRESS`는 worker 처리 전 스냅샷이다. worker는 앱에서 배송지를 고르도록 알리고,
선택이 들어온 뒤에 검증·승인 생성을 진행한다. 최신 상태는 `get_purchase_status`로
조회한다. 만료된 생성 응답을 재생해도 같은 구매 ID를 유지하며 새 구매를 자동으로 만들지 않는다.
Core와 MCP를 함께 배포해야 하며 이전에 저장된 멱등 응답의 `appLink`는 MCP가 제외한다.

## Core 배포 후 맞춰볼 응답 구조

최종 방향 문서에 상세 JSON이 생략된 부분은 `CommerceProjection`에 모아 두었다.
현재 소비자 테스트는 다음 구조를 전제로 한다. Core의 최종 제공 DTO가 다르면 MCP 어댑터를
그 DTO에 맞춰 수정한다. 이 테스트를 Core 제공자와의 계약 검증 완료로 간주하지 않는다.

- `optionGroups`: `id`, `name`, `values[{id,name}]`
- `failedMerchants`: 판매처 공개 ID 문자열 배열
- cart: `cartId`, `version`, `items`, `merchantSummaries`, `summary`
- cart item: `cartItemId`, 판매처·상품·variant ID, `name`, `quantity`, `unitPriceAmount`,
  `lineTotalAmount`, `currency`, `selectedOptions[{name,value}]`
- summary: `currency`, `itemSubtotalAmount`, `discountAmount`, `shippingAmount`, `totalAmount`,
  `status` (`ESTIMATED`, `CONFIRMED`, `INCOMPLETE`, `UNAVAILABLE`)

금액 null은 미확정으로 유지한다. 수집되지 않은 배송비·할인을 0으로 만들지 않는다.

## 이전 검증 결과와 남은 확인

- MCP Java 테스트 225개 통과. RS256/ES256 서명·claim·scope 검증, 서비스 토큰 발급,
  실제 Streamable HTTP를 통한 도구/리소스 등록과 두 인증 헤더 전달을 포함한다.
- UI 단위 테스트 48개 통과, 정적 HTML 빌드 완료.
- 412×850 모바일 크기 브라우저에서 검색→비교→기본 옵션 선택→담기→옵션·수량 변경→
  구매 인계 링크→삭제를 검증했다. 이는 푸시 방식 전환 전 fixture 검증이며 실기기 검증과 구분한다.
- Core OAuth와 실제 업무 API, 공개 metadata 프록시, client 공개키와 Pay FCM이 준비되면
  ChatGPT 계정 연결→실제 Core 왕복→Pay 푸시→별도 앱 승인까지 검증해야 한다.
- 연결 해제 시 refresh token 즉시 폐기 및 기존 Agent 토큰의 원래 만료까지 허용은 Core와
  함께 최종 통합 검증한다.

UI 변경 시 `ui` 디렉터리에서 `node --test src/*.test.js` 및 `node build.mjs`를 실행해
생성된 `src/main/resources/mcp-ui/integration.html`을 함께 커밋한다. HTML 내용 해시가
리소스 URI에 반영된다. 새 이미지 배포 후 ChatGPT 도구 정보를 새로고침하고 새 대화에서
확인한다. 기존 대화의 카드는 이전 HTML을 유지할 수 있다.
