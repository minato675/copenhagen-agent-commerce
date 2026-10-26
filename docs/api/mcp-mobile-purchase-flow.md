# MCP 구매와 모바일 결제 승인

관련 이슈: S15P21A207-423. 상품 탐색·비교·장바구니·구매 시작 화면은 MCP UI가 제공하고,
모바일 앱은 로그인, 배송지·결제수단 관리, 주문 내용 확인과 최종 결제 승인을 담당한다.
모바일 앱은 별도 프로젝트에서 구현하며, 이 문서는 해당 앱이 연동할 Core·Pay API와 MCP 흐름을 설명한다.
모바일 클라이언트 구현, 일반 웹 프론트엔드와 AI FDS 연동은 이번 범위에 포함하지 않는다.

## 구매 요청의 수명주기

1. MCP `prepare_purchase_handoff`가 장바구니 ID·버전·UUID 멱등키로 Core 구매 요청을 만든다.
   같은 멱등키의 재전송은 최초 응답을 그대로 돌려준다.
2. Core worker는 배송지를 대신 고르지 않는다. 등록된 배송지 유무와 관계없이 Pay의
   `PURCHASE_HANDOFF` 알림을 보내 앱에서 배송지를 고르게 한다. 이 알림에는 `purchaseRequestId`가
   들어간다. 사용자는 저장된 배송지를 선택하거나 새 배송지를 등록해 같은 구매 요청을 재개한다.
3. 배송지가 정해진 요청은 재고·가격·배송을 검증하고 같은 구매 ID로 Merchant checkout을 생성한다.
   Merchant가 확정한 주문서와 승인 생성 요청을 저장한 뒤 Pay 승인 세션을 생성한다.
   **승인 세션은 구매 요청당 1건이다**(이슈 S15P21A207-486). 판매처가 여러 곳이면 checkout 전부를
   `items`에 담아 한 번만 만들고, 판매처별 checkout은 같은 `approvalId`를 공유한다.
   Pay는 등록된 기본 결제수단을 선택하며, 없으면 `PAYMENT_METHOD_REQUIRED` 상태로 만든다.
4. Pay는 결제수단 등록 여부와 관계없이 `APPROVAL_REQUESTED` 알림을 발행한다.
   이 알림에는 `approvalId`가 들어가고, 승인이 1건이므로 판매처가 몇 곳이든 알림도 1건이다.
5. 별도 앱은 사용자가 알림을 누르면 해당 ID를 조회하고, 로그인하지 않았다면 로그인 후 같은 ID로 재개해야 한다.
   사용자 소유권은 Core·Pay가 각각 검증한다. 앱은 금액을 승인 요청에 보내지 않는다.
6. 앱에서 최종 금액과 상품·수량·결제수단을 확인한 후 서버가 요구한 PIN 또는 생체 인증으로 승인한다.
   판매처가 여러 곳이어도 **인증은 한 번**이고, 화면은 판매처별 소계와 합계를 함께 보여준다.
   생체 인증은 Pay에 등록한 기기 공개키에 대응하는 개인키로 서버 challenge 문자열을 SHA256withECDSA 서명한다.
   서버의 PIN fallback만 사용하며 클라이언트가 요구 인증을 낮추지 않는다.
7. Core가 승인 확인 후 Merchant complete를 호출한다. 인증은 1회지만 complete와 결제는 판매처별로
   일어나므로, 일부만 성공할 수 있다. MCP `get_purchase_status`는 판매처별 결과를
   조회한다. `COMPLETED`만 전체 완료이며 `PARTIALLY_COMPLETED`는 일부 판매처만 완료된 상태다.

앱 이동·푸시 전송 성공·승인 완료를 결제 성공으로 표시하지 않는다.

## Core API

앱 API는 사용자 access token이 필요하다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | /api/core/v1/purchase-requests | 최근 MCP 구매 요청 최대 50개 |
| GET | /api/core/v1/purchase-requests/{id} | 현재 구매·판매처별 승인·알림 상태 |
| POST | /api/core/v1/purchase-requests/{id}/resume | 배송지 선택 후 같은 요청 재개 |
| POST | /api/core/v1/purchase-requests/{id}/notifications | 기존 요청 알림 재전송 |

resume 본문: `{"addressId":"<UUID>","cartVersion":1}`.
쓰기 요청의 `Idempotency-Key`는 UUID다. 소유자·구매 만료·원래 장바구니 버전·배송지 소유자를 확인한다.
결제수단 등록·선택은 기존 Pay API를 사용하므로 새로운 Core 구매 요청을 만들지 않는다.

MCP 내부 조회·재전송 경로는 동일한 접미사를 갖는 `/internal/v1/purchase-requests/{id}`,
`/{id}/notifications`다. `purchase:handoff` scope와 원래 agent connection 소유권을 확인한다.
공개 API로 내부 DB ID, 주소, 결제 credential을 노출하지 않는다.

응답에는 `purchaseRequestId,status,cartId,cartVersion,expiresAt,failureCode,nextAction`,
`merchantCheckouts[]`, `notifications[]`가 있다.
구매 생성 응답은 생성 시점의 영수증이고, 현재 상태는 별도 GET으로 읽어야 한다.

## Pay 알림 API

Core 서비스 인증이 필요한 내부 API다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| POST | /internal/v1/purchase-notifications | userId·purchaseRequestId로 구매 인계 알림 적재 |
| GET | /internal/v1/purchase-notifications/{id}?userId={userId} | 인계·승인 알림 상태 |
| POST | /internal/v1/purchase-notifications/{id}/resend?userId={userId} | 기존 알림 재시도 |

생성 이벤트 ID는 구매 ID에서 결정되므로 반복 호출해도 한 개다. 재전송은 UUID 멱등키를 사용한다.
실행 중 lease와 만료·완료된 승인 세션은 재전송하지 않는다. 최근 발송 성공 후 60초는 재발송하지 않는다.
기기 미등록은 `NO_ACTIVE_INSTALLATION`, FCM 비활성/전송 오류는 실패·재시도 상태이며 `SENT`로 바꾸지 않는다.
`SENT`는 FCM 접수 성공이며 사용자의 알림 확인을 뜻하지 않는다.
모든 기기 전송이 끝나지 않으면 재시도 후 최대 시도 횟수에서 `EXHAUSTED`가 된다.

## 장애 복구

- 승인 생성 전에 정확한 요청 JSON을 DB에 저장한다. worker는 같은 키와 본문으로 재시도한다.
- `COMPLETING` lease가 만료되면 먼저 Merchant checkout을 조회한다.
  이미 완료된 주문은 추가 결제 없이 Core 주문·거래만 복구한다.
- snapshot 행 잠금으로 완료 저장을 직렬화하고, 주문·거래·checkout 상태를 한 트랜잭션에 저장한다.
- Pay 거래 ID가 생기기 전 실패는 nullable 거래 ID와 FAILED 상태로 기록한다.
- Merchant Webhook은 등록된 공개키, RFC 9421 서명, 본문 digest, 시각을 검증한다.
  중복 이벤트와 오래된 상태 갱신은 주문을 다시 생성하거나 상태를 되돌리지 않는다.
- MCP UI는 결과 불명 요청에 같은 키를 유지하고, 같은 장바구니·버전의 준비된 구매는 다시 연다.

## 배포 설정

`infra/docker/ec2.env.example`의 서비스 인증 매핑과 worker 설정이 필요하다.

- `CORE_PAYMENT_WORKER_ENABLED=true`: 구매 검증, 승인 생성 재시도, 승인·완료 확인.
- `PAY_NOTIFICATION_WORKER_ENABLED=true`: 알림 outbox 처리.
- `PAY_FCM_ENABLED=true` 및 실제 Firebase 프로젝트·서비스 계정 설정: 실기기 푸시.
- Merchant의 `PAY_MERCHANT_TOKENS_JSON`과 Pay의 `PAY_AUTH_MERCHANT_SERVICE_TOKENS_JSON`은
  동일한 merchantId→token 매핑이어야 한다. 실제 값은 배포 비밀정보로 주입한다.
- Pay 푸시의 구매 요청·승인 식별자로 별도 앱의 주문 확인·결제 승인 화면을 연다.
  사용자가 알림을 선택하면 앱에서 로그인 상태와 요청 소유권을 확인하고 화면을 복원한다.
- MCP 웹 화면은 결제 상태 확인과 알림 재전송을 제공한다. HTTPS App Link 설정은 필요 없다.
- 실제 상품 검색에는 `core-integration` 프로필과 MCP 서비스 개인키·Core 공개키 등록이 필요하다.

DB migration과 Merchant 옵션 가격 응답을 먼저 배포한 뒤 Core·Pay·MCP를 배포하고 별도 앱과 연동을 확인한다.
기존 Merchant 응답에 옵션별 가격이 없으면 이전 base price fallback을 유지하므로 구버전 Merchant가
추가금을 갖는 상품은 새 가격 계약 배포 후 구매해야 한다.
