# UCP Merchant Mock

로컬 및 개발 환경에서 외부 UCP Business 역할을 대신하는 시뮬레이터입니다.

Core가 UCP REST로 이 모듈을 호출하고, 결제가 필요하면 이 모듈이 `pay-service`를 호출하며, 주문이 생기면 Core로 Order Webhook을 보냅니다. MCP 서버, PG Mock, 모바일 앱과는 직접 통신하지 않습니다.

- `merchant-db`를 단독으로 소유합니다.
- 실제 Merchant 또는 운영 환경에서 사용하지 않습니다.
- Merchant 시나리오 재현에 필요한 최소 계약과 동작만 구현합니다.
- Flyway migration은 이 모듈의 `src/main/resources/db/migration/`에 둡니다.

설계 문서는 담당자(김호준)가 로컬에서 관리합니다. 팀에 공유가 필요한 계약과 결정은 레포 루트 `docs/`와 `docs/adr/`에 올립니다. 기능을 추가하거나 변경할 때는 먼저 문서를 갱신하고 그 내용을 구현합니다.

HTTP 계약은 이 폴더의 [`openapi.yaml`](openapi.yaml) 입니다. Swagger Editor 나 IDE 의 OpenAPI 플러그인으로 열면 됩니다. 컨트롤러와 경로·메서드·상태 값이 어긋나면 `OpenApiContractTest` 가 실패합니다.

`backend/`에서 다음과 같이 검증합니다.

```bash
./gradlew :mocks:ucp-merchant-mock:test
./gradlew :mocks:ucp-merchant-mock:build
```

## 로컬에서 띄우기

JDK 21 과 Docker 가 필요합니다. 방법은 둘입니다. 이 모듈 하나만 확인하려면 1번, 팀 서비스 전체와 붙여 보려면 2번입니다.

### 1. 앱은 호스트에서, DB 만 컨테이너로

베이스 compose 는 `merchant-db` 포트를 호스트에 열지 않습니다. 리포 루트에 `docker-compose.override.yml` 을 만들어 엽니다. 이 파일은 개인 설정이라 Git 에 올리지 않습니다.

```yaml
services:
  merchant-db:
    ports:
      - "15434:5432"
  ucp-merchant-mock:
    ports:
      - "18080:8080"
```

```bash
# 리포 루트에서
docker compose up -d merchant-db
```

앱은 `backend/` 에서 띄웁니다. 호스트 8080 은 compose 의 nginx 가 쓰고 있는 경우가 많아 `SERVER_PORT` 를 바꿉니다. Pay 와 Core 대신 아래 3절의 스텁을 가리킵니다.

```bash
SERVER_PORT=18081 \
MERCHANT_DB_URL=jdbc:postgresql://localhost:15434/merchant-db \
MERCHANT_DB_USER=merchant MERCHANT_DB_PASSWORD=merchant-dev-password \
PAY_SERVICE_URL=http://127.0.0.1:18099 \
CORE_SERVICE_URL=http://127.0.0.1:18099 \
CORE_WEBHOOK_URL=http://127.0.0.1:18099/webhooks/v1/ucp/orders \
PAY_MERCHANT_TOKEN=local-smoke-token \
MERCHANT_AP2_LIKE_ENABLED=true \
MERCHANT_CATALOG_POOL_FILE=classpath:catalog/pool.json.gz \
MERCHANT_CATALOG_POOL_LIMIT=5000 \
./gradlew :mocks:ucp-merchant-mock:bootRun
```

`python tools/e2e_smoke.py --print-launch` 가 같은 명령을 찍어 줍니다(포트만 8080 기준).

### 2. compose 로 전체 스택

```bash
# 리포 루트에서. merchant-db 와 pay-service 가 같이 뜬다
docker compose up -d ucp-merchant-mock
curl -s http://localhost:18080/actuator/health
```

`18080` 은 위 override 의 포트입니다. override 가 없으면 호스트에서 닿지 않고 compose 내부망에서 `http://ucp-merchant-mock:8080` 으로만 닿습니다. 첫 기동은 카탈로그 전량(몰 셋 × 약 10만 건)을 적재해 20초쯤 걸립니다.

### 환경변수

자주 바꾸는 것만 적습니다. 전체 키는 `src/main/resources/application.yml` 에 있습니다.

```
MERCHANT_DB_URL / _USER / _PASSWORD     merchant-db 접속. 기본은 compose 내부 주소
SERVER_PORT                             기본 8080
MERCHANT_PUBLIC_BASE_URL                Profile 의 endpoint 앞부분. 기본 http://ucp-merchant-mock:8080
MERCHANT_CATALOG_POOL_FILE              카탈로그 풀. 비우면 V3 fixture 5건만 뜬다(테스트·CI 가 이 상태)
MERCHANT_CATALOG_POOL_LIMIT             풀 앞 N 건만 싣는다. 0 이 전량. 줄이더라도 1592 이상
MERCHANT_CATALOG_RELATED_ENABLED        연관 상품 켜기. 기본 false — 꺼져 있으면 색인·이웃 파일을 읽지 않고 경로도 없다
MERCHANT_CATALOG_RELATED_MODE           rrf3(기본) · rrf2 · search. 문제가 생기면 한 단계씩 내린다
PAY_SERVICE_URL                         Pay 청구·재조회 주소
PAY_MERCHANT_TOKENS_JSON                몰별 Pay Bearer 토큰. {"merchant-a":"...","merchant-b":"..."}
PAY_MERCHANT_TOKEN                      위 JSON 에 몰이 없을 때 쓰는 토큰 하나
CORE_SERVICE_URL                        Core Platform Profile 을 가져오는 주소
CORE_WEBHOOK_URL                        Profile 에 webhook_url 이 없을 때 주문 웹훅을 보낼 주소
MERCHANT_AP2_LIKE_ENABLED               AP2-like capability 광고. 기본 false
MERCHANT_SIGNING_KID                    서명 키 id. 기본 merchant-a-es256-1
MERCHANT_SIGNING_PRIVATE_KEY_LOCATION   개인키 PKCS#8 PEM 파일 경로
MERCHANT_SIGNING_PRIVATE_KEY_PEM        개인키 PEM 문자열. 배포는 CI/CD Variable 로 넣는다
MERCHANT_SIGNING_PUBLIC_KEY_JWK         개인키와 짝인 공개 JWK
```

Pay 토큰이 비어도 기동은 됩니다. 첫 청구에서 Pay 가 401 을 돌려줍니다.

### 서명 키

로컬에서는 키를 만들 필요가 없습니다. 개인키가 설정되지 않으면 기동 때 P-256 임시 키 쌍을 만들고 그 공개 절반을 Profile `keys[]` 에 싣습니다(기동 로그 WARN). 재기동하면 키가 바뀌므로 이전 기동이 발급한 서명은 검증되지 않습니다.

키를 고정하려면 PKCS#8 PEM 을 만들어 개인키와 공개 JWK 를 함께 넣습니다. 개인키만 넣거나 짝이 안 맞으면 기동이 멈춥니다. 개인키는 커밋하지 않습니다.

```bash
openssl ecparam -genkey -name prime256v1 -noout -out merchant-es256.pem
openssl pkcs8 -topk8 -nocrypt -in merchant-es256.pem -out merchant-es256-pkcs8.pem
```

### 3. 정상 구매 재현

`tools/e2e_smoke.py` 가 Pay 와 Core 자리에 스텁 하나(`127.0.0.1:18099`)를 띄우고 구매 전 구간을 실제 HTTP 로 돕니다. 1번 방법으로 앱을 띄운 뒤 다른 터미널에서 칩니다. 파이썬 3 과 `cryptography` 가 필요합니다.

```bash
pip install cryptography
python tools/e2e_smoke.py --base-url http://localhost:18081
```

단계마다 대응하는 curl 을 같이 찍으므로 그대로 따라 쳐도 됩니다. 도는 순서는 이렇습니다.

```
 1  Profile Discovery                  GET  .../.well-known/ucp
 2  Payment Handler 광고               1번 응답의 ucp.payment_handlers
 3  AP2-like Capability 광고           1번 응답의 ucp.capabilities
 4  카탈로그 검색·상세                 POST .../catalog/search, .../catalog/product
 5  체크아웃 생성                      POST .../checkout-sessions → incomplete (purchase_request_id 없음)
 6  체크아웃 수정                      PUT  .../checkout-sessions/{id} → ready_for_complete + merchant_authorization
 7  서명 검증                          Profile 공개키로 ES256 검증, claim 을 체크아웃과 대조
 8  Pay 호출 0회                       여기까지 결제가 나가지 않았다
 9  Complete                           POST .../complete → completed + order.id
10  Pay 호출 1회                       청구 금액이 totals.total 과 같다
11  주문 조회                          GET  .../orders/{orderId}
12  주문 웹훅                          스텁이 받은 요청의 RFC 9421 서명 검증
13  Complete 재호출                    같은 주문, Pay 호출 1회 그대로
14  PRICE_CHANGED                      PUT 재계산에 경고. 상태는 막지 않는다
15  막는 시나리오 셋                   incomplete·requires_escalation, Pay 호출 증가 0
16  가상 몰 셋                         같은 productId, 몰마다 다른 가격
17  옛 경로 별칭                       /.well-known/ucp·/ucp/v1/** → merchant-a
```

끝에 이렇게 나오면 성공이고 종료 코드가 0 입니다. 실패한 단계가 있으면 무엇이 달랐는지 찍고 1 로 끝납니다.

```
단계 17개, 실패 0개, 건너뜀 0개
Pay 청구 1회, 재조회 0회, 웹훅 1건
```

## 경로와 몰 세그먼트

컨테이너 하나가 가상 몰 셋을 서빙합니다. 어느 몰인지는 경로의 두 번째 세그먼트가 말합니다. 값은 `application.yml` 의 `merchant.malls[].id` 와 같고, Core 의 `services.merchants[].id`·Pay 토큰 JSON 의 키와 한 문자열이어야 합니다. 모르는 값은 `404 MERCHANT_NOT_FOUND` 입니다.

```
GET   /api/ucp/v1/{merchantId}/.well-known/ucp
POST  /api/ucp/v1/{merchantId}/catalog/search | lookup | product
POST  /api/ucp/v1/{merchantId}/catalog/related              연관 상품. 기본 꺼짐(아래)
POST  /api/ucp/v1/{merchantId}/checkout-sessions            GET · PUT · /complete · /cancel
GET   /api/ucp/v1/{merchantId}/orders/{orderId}
GET   /internal/v1/scenarios                                POST 로 바꾼다
```

옛 경로 `/.well-known/ucp` 와 `/ucp/v1/**` 는 첫 몰(`merchant-a`)의 별칭으로 살아 있습니다. Core 는 `services.merchants[]` 의 몰별 base-url(`.../api/ucp/v1/merchant-x`)로 부르지만, Pay 가 Profile 을 여전히 `/.well-known/ucp` 로 가져가기 때문입니다. Pay 가 몰별 주소로 옮기면 지웁니다. `/api/ucp/` 를 Nginx 가 이 컨테이너로 보내는 것은 인프라 몫입니다.

오류는 네 키가 항상 있는 한 모양입니다. 값이 없으면 `null` 입니다.

```json
{"errorCode": "ORDER_NOT_FOUND", "message": "주문을 찾을 수 없다: nope", "currentState": null, "nextAction": "CHECK_PURCHASE_STATUS"}
```

## Business Profile 예시

`GET /api/ucp/v1/merchant-a/.well-known/ucp` 의 실제 응답입니다. `keys[]` 의 `x`·`y` 는 임시 키라 기동마다 바뀝니다. AP2-like 를 켜면 `capabilities` 에 `com.copenhagen.payment.ap2_like` 가 더해집니다.

```json
{
  "ucp": {
    "version": "2026-08-25",
    "services": {
      "dev.ucp.shopping": [
        {"version": "2026-08-25", "transport": "rest", "endpoint": "http://ucp-merchant-mock:8080/api/ucp/v1/merchant-a"}
      ]
    },
    "capabilities": {
      "dev.ucp.shopping.catalog.search": [{"version": "2026-08-25"}],
      "dev.ucp.shopping.catalog.lookup": [{"version": "2026-08-25"}],
      "dev.ucp.shopping.checkout": [{"version": "2026-08-25"}],
      "dev.ucp.shopping.order": [{"version": "2026-08-25"}],
      "dev.ucp.shopping.fulfillment": [{"version": "2026-08-25"}]
    },
    "payment_handlers": {
      "com.copenhagen.pay": [{"id": "copenhagen-pay-handler", "version": "1"}]
    }
  },
  "keys": [
    {"kty": "EC", "use": "sig", "crv": "P-256", "kid": "merchant-a-es256-1", "x": "…", "y": "…", "alg": "ES256"}
  ]
}
```

## 상태 전이

### Checkout

상태는 이 모듈이 정하고 Core 는 응답의 `status` 를 그대로 읽습니다. 전이는 전부 조건부 UPDATE 라 동시 요청이 와도 한 쪽만 옮깁니다.

```
incomplete ──조건이 다 참──▶ ready_for_complete ──Complete──▶ complete_in_progress ──Pay SUCCESS──▶ completed
    ▲   │                          │  ▲                              │   │
    └───┘ PUT 으로 보완            │  └──────── Pay FAILED ──────────┘   └── Pay UNKNOWN: 그대로. 재조회 워커가 확정
requires_escalation                │
    │                              └── expires_at 경과(스윕 워커) ──▶ canceled
    └── incomplete·requires_escalation·ready_for_complete 에서 POST .../cancel ──▶ canceled
```

요청별로 보면 이렇습니다.

```
요청                  받는 상태                                     결과
POST 생성 · PUT 수정  (없음) · incomplete · requires_escalation ·   조건을 다시 판정. 부족하면 incomplete, 다 차면
                      ready_for_complete                            ready_for_complete + 서명. 시나리오면 requires_escalation
                      그 밖                                         409 CHECKOUT_NOT_MODIFIABLE
POST cancel           incomplete · requires_escalation ·            canceled
                      ready_for_complete
                      canceled                                      200, 같은 응답 재생
                      complete_in_progress · completed              409 CHECKOUT_NOT_MODIFIABLE
POST complete         ready_for_complete (만료 전)                  Pay 청구 → completed / ready_for_complete / complete_in_progress
                      ready_for_complete (만료 후)                  409 CHECKOUT_EXPIRED
                      incomplete · requires_escalation              409 CHECKOUT_INCOMPLETE (currentState 로 구별)
                      complete_in_progress                          200, payment.status 만 싣는다. 재청구하지 않는다
                      completed                                     200, 저장된 응답 재생. 재청구하지 않는다
                      canceled                                      409 CHECKOUT_NOT_MODIFIABLE
```

`incomplete` 사유는 `messages[]` 의 `code` 가 말합니다. `ADDRESS_REQUIRED`(배송지 없음), `CHECKOUT_INCOMPLETE`(옵션 미선택·`purchase_request_id` 없음·항목 없음), `CHECKOUT_STOCK_INSUFFICIENT`, `ADDRESS_NOT_SERVICEABLE` 입니다. `requires_escalation` 은 `MERCHANT_HANDOFF_REQUIRED` 입니다. `warning` 인 `CHECKOUT_PRICE_CHANGED`·`CHECKOUT_REMOTE_AREA_SURCHARGE` 는 상태를 막지 않습니다. 상품이 없으면 체크아웃이 만들어지지 않고 `404 PRODUCT_NOT_FOUND` 입니다.

`expires_at` 은 `ready_for_complete` 가 된 시각 + 5분입니다. 만료는 `expired` 가 아니라 `canceled` 로 갑니다. UCP 공식 상태에 `expired` 가 없습니다.

### Payment

Complete 안에서 Pay 에 한 번 청구합니다. 이 모듈이 기록하는 결제 상태입니다.

```
(없음) ──청구 직전──▶ PROCESSING ──▶ SUCCESS   주문 생성, 체크아웃 completed
                                 ├──▶ FAILED    체크아웃 ready_for_complete 로 되돌림. 새 Credential 로 재청구 가능
                                 └──▶ UNKNOWN   체크아웃 complete_in_progress 유지
                                        └──재조회 워커(30초부터 두 배씩, 최대 10회)──▶ SUCCESS 또는 FAILED
```

`UNKNOWN` 에서는 새 결제를 만들지도, 다른 멱등 키로 다시 청구하지도 않습니다. 같은 결제를 조회만 합니다.

### Order

값 집합은 `CREATED` · `CONFIRMED` · `SHIPPED` · `DELIVERED` · `CANCELED` 다섯입니다. 주문은 Pay SUCCESS 때 `CREATED` 로만 생기고, 지금 코드에 다른 상태로 옮기는 경로는 없습니다. 조회 응답의 `fulfillment.status` 는 `status` 와 같은 값입니다.

### Webhook

```
PENDING ──2xx──▶ DELIVERED
   └──실패──▶ PENDING (10초부터 두 배씩)
   └──8번 넘게 실패──▶ FAILED (다시 보내지 않는다)
```

주문과 같은 트랜잭션에서 `order.created` 행이 생기고 커밋 직후 첫 발송을 합니다. 재시도는 같은 `Webhook-Id` 로 갑니다.

## 시연 카탈로그와 가상 몰 셋

이 모듈은 플랫폼 바깥의 독립 쇼핑몰을 흉내 냅니다. 시연에서는 컨테이너 하나 안에 가상 몰 셋(한강마켓 `merchant-a`, 노을상점 `merchant-b`, 별빛몰 `merchant-c`)이 들어가고 상품·체크아웃·주문이 `merchant_id` 컬럼으로 갈립니다. 세 몰이 같은 상품을 똑같이 취급하고, 같은 상품은 몰이 달라도 같은 `productId` 에 몰마다 다른 가격이라 Core 가 가격을 비교할 수 있습니다.

카탈로그는 풀 파일 하나(`src/main/resources/catalog/pool.json.gz`, 100,262건)입니다. 컬리 카테고리 API 81,999건, 다이소 15,174건, 다나와 1,497건, 그리고 기존 시연 시드 1,592건(원본 1,408건과 식재료 수집본 184건)을 합쳤습니다. 기존 시드가 풀 맨 앞에 있어 `MERCHANT_CATALOG_POOL_LIMIT` 을 줄여도 시연 상품은 남습니다. 카탈로그 응답에는 상품 이미지(`imageUrl`)가 함께 나갑니다.

몰마다 다른 것은 둘이고 로더가 기동 때 결정적 해시로 계산합니다. 가격(원본의 -10%~+10%, 10원·100원 단위 반올림)과 품절(약 5%)입니다. 난수를 쓰지 않아 다시 띄워도 같은 값이 나옵니다.

원본에 옵션이 없는 상품은 카테고리를 보고 옵션을 합성합니다. 신발은 사이즈 250~280 × 색상, 의류는 S·M·L, 색이 갈리는 카테고리(휴대폰·태블릿·노트북·가방 등)는 색상입니다. 가전·부품은 합성하지 않습니다.

```bash
# 카탈로그 풀 재생성(ADR 0011). 원본 넷은 Git 에 없다 — 김지호 번들 둘과 컬리 수집본은 담당자 로컬에 있다
python tools/build_catalog_pool.py \
  --clean-sqlite ~/Downloads/korean_products_clean_bundle/korean_products_clean.sqlite \
  --raw-sqlite   ~/Downloads/korean_products_bundle/korean_products.sqlite \
  --kurly-catalog ~/Downloads/kurly-catalog-2026-09-21.json \
  --grocery tools/kurly-grocery.json \
  --out src/main/resources/catalog/pool.json.gz
# 규칙을 바꿨으면 황금값도 다시 뽑는다
python tools/mall_variation_golden.py --pool src/main/resources/catalog/pool.json.gz --out src/test/resources/catalog-pool/golden.json
```

몰 셋은 Core `services.merchants[]` 와 Pay `merchant_accounts` 에 같은 id 로 등록돼 있습니다. 몰마다 결제까지 가려면 이 모듈의 `PAY_MERCHANT_TOKENS_JSON` 에 몰 셋의 토큰이 있고, 그 토큰이 Pay 쪽 설정과 같아야 합니다. Pay 는 토큰 하나를 몰 하나로 읽습니다.

## 연관 상품 (기본 꺼짐)

상품 id 를 주면 비슷한 상품(대체재)을 돌려줍니다. 사용자 식별자는 받지 않습니다. 구매이력으로 개인화하는 것은 부르는 쪽(Core·Agent)이 상품 id 를 넣어 조립합니다. UCP 표준이 아닌 벤더 확장 `com.copenhagen.catalog.related` 이고 Profile 에는 광고하지 않습니다. 설계와 실험은 `DESIGN.md` 6.8절, 결정은 ADR 0014.

```
POST /api/ucp/v1/merchant-a/catalog/related
{ "productIds": ["kurly:5044571"], "limit": 4 }       →  { "items": [ ... search 와 같은 Item ... ] }
```

순위는 셋을 섞습니다 — 씨앗 제목으로 돌린 기존 검색, 제목·카테고리 낱말 TF-IDF, 그리고 빌드 때 미리 계산한 e5-large 이웃 파일(`src/main/resources/catalog/related-e5nb.bin.gz`). 같은 상품의 다른 용량과 품절·단종은 빠지고, id 가 여럿이면 번갈아 꺼냅니다. 실행 중에는 모델을 돌리지 않습니다.

켜려면 `MERCHANT_CATALOG_RELATED_ENABLED=true`. 색인은 기동 뒤 백그라운드에서 만들어 1~2초 걸리고 그동안은 `503 RELATED_WARMING_UP` 입니다. 이웃 파일이 풀과 맞지 않으면 로그 한 줄을 남기고 이웃 없이(rrf2) 돕니다. 풀을 바꾸면 이웃 파일도 다시 만듭니다.

```
C:/Users/SSAFY/.venvs/related-gpu/Scripts/python.exe tools/build_related.py --embed --cuda     # GPU 로 약 10분
```

## 로그로 구매 하나 추적하기

로그 한 줄마다 `req= co= pay= ord= wh=` 다섯 칸이 붙습니다. `req` 는 `X-Request-Id` 입니다. Core 가 보낸 값이 있으면 그대로 쓰고 없으면 이 모듈이 만들어 응답 헤더로 돌려줍니다. 같은 값이 Pay 호출과 Order Webhook 의 `X-Request-Id` 헤더에 실리므로 세 서비스 로그를 한 값으로 이어 볼 수 있습니다. 워커가 집은 일은 `sweep-…`, `recheck-…`, `webhook-retry-…` 접두어의 requestId 를 새로 받습니다.

상태 전이는 `AUDIT` 접두어의 한 줄 JSON 으로 따로 남습니다. `event` 는 `checkout.created|updated|canceled|expired|signed|completing|completed|complete_reverted|complete_replayed`, `payment.started|finished|resolved|recheck_exhausted`, `order.created`, `webhook.enqueued|delivered|retry_scheduled|failed`, `scenario.changed` 입니다. `before`·`after` 가 상태 전후, `reason` 이 사유입니다.

```bash
# 요청 하나 따라가기
docker compose logs ucp-merchant-mock | grep 'req=core-req-42'

# 상태 전이만 보기
docker compose logs ucp-merchant-mock | grep ' AUDIT ' | grep '"checkoutId":"<checkoutId>"'
```

로그에 개인키, `credentialId`, 카드 정보, 상세 배송지는 남기지 않습니다. 감사 이벤트는 그런 이름의 키를 넣으려 하면 예외로 거절합니다.

## 시연용 시나리오 스위치

Core·Pay 가 실패 상황을 잘 복구하는지 보이려면 이 모듈이 실패를 만들어 내야 합니다(설계서 12.5). 재기동 없이 켜고 끄는 내부 API 둘이 있습니다. Nginx 가 `/internal/` 을 막아 compose 내부망이나 override 로 연 포트에서만 닿습니다. 전역 하나이고 재기동하면 `NORMAL` 로 돌아옵니다. 아래 `localhost:8080` 은 띄운 방법에 맞게 바꿉니다(호스트 bootRun 이면 `18081`, override 면 `18080`, compose 안이면 `ucp-merchant-mock:8080`).

```bash
# 지금 켜진 시나리오
curl -s http://localhost:8080/internal/v1/scenarios

# 정상으로 되돌리기
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"NORMAL"}'

# 켠 뒤 생성·수정하는 체크아웃은 단가가 1,000원 오른다. 막지 않으므로 상태는 ready_for_complete 그대로다
# 경고 CHECKOUT_PRICE_CHANGED 는 끈 채로 만든 체크아웃을 켠 뒤 PUT 으로 수정할 때만 실린다. 생성에는 안 실린다
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"PRICE_CHANGED"}'

# 모든 항목이 재고 부족. incomplete + CHECKOUT_STOCK_INSUFFICIENT
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"OUT_OF_STOCK"}'

# 배송 불가 지역. incomplete + ADDRESS_NOT_SERVICEABLE
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"ADDRESS_NOT_SERVICEABLE"}'

# API 로 해결 불가. 상태 requires_escalation + MERCHANT_HANDOFF_REQUIRED
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"REQUIRES_ESCALATION"}'

# 주문 웹훅을 같은 바이트·같은 서명으로 두 번 보낸다. Core Inbox 가 Webhook-Id 로 중복을 무시하는지 본다
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"WEBHOOK_DUPLICATE"}'

# 주문 웹훅을 merchant.webhook.scenario-delay(기본 5초) 뒤에 보낸다. Core 가 주문 조회로 버티는지 본다
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"WEBHOOK_DELAYED"}'

# 다음 체크아웃부터 검증에 실패하는 Merchant Authorization 이 나간다. Pay 가 CHECKOUT_SIGNATURE_INVALID 로 거절하는지 본다
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"INVALID_MERCHANT_SIGNATURE"}'

# Complete 가 결제 전에 merchant.checkout.scenario-delay(기본 10초) 만큼 멈춘다. Core 가 같은 키로 재시도하면 complete_in_progress 를 받는다
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"COMPLETE_TIMEOUT_BEFORE_PROCESS"}'

# Complete 가 주문까지 만든 뒤 응답 직전에 멈춘다. Core 가 재결제 대신 체크아웃 조회(GET)로 completed 를 확인하는지 본다
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"COMPLETE_TIMEOUT_AFTER_ORDER"}'
```

열 종류 전부 읽는 코드가 있습니다. 모르는 이름은 `400 INVALID_REQUEST` 입니다.

### 시연 순서

설계서 31절 시연 시나리오 중 이 모듈이 맡는 자리입니다. 매번 `NORMAL` 에서 시작하고 끝나면 `NORMAL` 로 돌립니다.

```
1  정상 구매 (31.1)        NORMAL 그대로. 앱에서 검색 → 체크아웃 → 승인 → 주문번호 확인
                           이 모듈만으로 보이려면 3절 스모크를 돌린다
2  응답 유실 복구 (31.2)   COMPLETE_TIMEOUT_AFTER_ORDER 를 켜고 구매. 주문은 생겼는데 Core 는 타임아웃을 본다
                           Core 가 다시 결제하지 않고 GET 으로 completed 와 주문 id 를 찾는 것이 보여 줄 점이다
                           로그에서 Pay 청구가 한 번뿐인 것을 AUDIT payment.started 로 확인한다
3  서명 위조 차단 (31.3)   INVALID_MERCHANT_SIGNATURE 를 켜고 구매. 서명 검증에서 막혀 결제가 나가지 않고 주문이 없다
                           어디서 막히는지(Core 검증 또는 Pay CHECKOUT_SIGNATURE_INVALID)는 Core·Pay 구현을 따른다
4  웹훅 중복 (선택)        WEBHOOK_DUPLICATE 를 켜고 구매. Core 에 주문이 하나만 남는다
5  구매 막힘 (선택)        OUT_OF_STOCK · ADDRESS_NOT_SERVICEABLE 을 켜고 체크아웃. incomplete 사유가 앱에 뜨고 결제가 안 나간다
```

31.2 의 "PG 가 결제 뒤 응답을 유실" 자체는 PG Mock 의 설정이고, 이 모듈은 그때 `complete_in_progress` 를 유지하다 재조회 워커로 확정합니다.
