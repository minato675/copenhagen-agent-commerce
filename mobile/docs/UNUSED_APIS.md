# 페이앱 미사용 API와 기능 확장 후보

작성일: 2026-09-20

현재 Android 페이앱 코드와 Notion API 명세를 비교해, 아직 앱에서 호출하지 않는 API와 만들 수 있는 기능을 정리했다. 용도와 호출 방법이 비교적 명확한 항목, 계약·연동 확인이 필요한 항목, 서버 구현이 필요한 항목을 구분한다.

## 구현 진행

아래 조사표와 미사용 수량은 최초 조사 시점의 기록이다. 이후 클라이언트 구현 상태는 이 표에서 추적한다. 클라이언트 검증 통과는 배포 서버/실기기 연동 완료를 뜻하지 않는다.

| 대상 | Task | 클라이언트 구현 및 검증 |
|---|---|---|
| A1 | S15P21A207-464 | FID 해제 → Core 로그아웃 → 세션 정리. 실패·타임아웃·취소·경로 로그 마스킹 회귀 검증. 실제 Firebase/Pay 연동은 미검증. |
| A2·A3 | S15P21A207-465 | 설정의 AI 서비스 목록·연결 해제. 배열/nullable 메타데이터, 204 후 재조회, 해제 실패·재조회 실패 회귀 검증. 실제 서버/화면 E2E는 미검증. |
| A4 | S15P21A207-466 | 지갑의 장바구니 조회·예상 금액 표시. null cartId/빈 목록, Long 금액·옵션·버전, 조회 실패/재시도 및 불완전 응답 검증. AI 장바구니 공유는 미검증. |
| A5·A6 | S15P21A207-467 | 수량 증감·삭제 확인, 최신 버전 요청 및 성공 후 재조회. 충돌·응답 유실 후 쓰기 자동 반복 방지, 마지막 상품 삭제·입력 검증·취소 회귀 검증. 실제 서버/화면 E2E는 미검증. |
| A7 | S15P21A207-468 | 검색어·판매처·가격·정렬·페이지 및 offers 비교. 조건 매핑·nullable 가격·판매처별 동일 ID·부분 실패·늦은 응답/세션 정리 검증. 브랜드·카테고리 필터는 서버 미지원으로 미노출. 실제 서버/화면 E2E는 미검증. |
| A8 | S15P21A207-469 | 선택한 판매처의 상품 상세·옵션 재조회. 필수/선택 쿼리·경로 인코딩·nullable 정보·품절·상품 없음·실패/재시도·늦은 응답 폐기 검증. 실제 서버/화면 E2E는 미검증. |

## 1. 조사 기준과 결과

- 원본: [API 명세](https://app.notion.com/p/18985e9160e683d4acaf817b7fc62311)
- 우선 기준: [API 명세서 v5 — develop 8de6480](https://app.notion.com/p/5db85e9160e68298935c019737353986)
- 서버 구현 스냅샷: 2026-09-18, `8de6480ac4dbabfb5fd9ad9d258c8abacf46b118`
- 비교 대상: 저장소 `mobile/app/src/main` 아래 API 호출부와 화면·알림 처리 코드
- 보조 기준: v4 명세와 정책/TBD/내부 데이터베이스

**미사용은 현재 앱 코드에 해당 Method·경로의 호출이 없다는 뜻이다.** 서버 전체에서 사용되지 않는다는 의미는 아니다. 실행 로그나 배포 서버를 통한 연동 검증은 수행하지 않았다. 문서의 ‘완료’ 역시 코드 구현이 있다는 의미이며 배포·E2E 성공을 보장하지 않는다. [v5 기준 설명](https://app.notion.com/p/b7485e9160e683c8bba8010d64e5a879)

| 구분 | Core | Pay | 합계 |
|---|---:|---:|---:|
| v5에서 호출자를 앱/웹으로 명시한 API | 26 | 15 | 41 |
| 현재 앱에 호출이 있는 API | 13 | 14 | 27 |
| 현재 앱에 호출이 없는 API | 13 | 1 | 14 |
| 미사용 중 용도·호출 방법이 비교적 명확한 API | 7 | 1 | 8 |
| 미사용 중 계약·연동 확인이 필요한 API | 6 | 0 | 6 |

수량은 Method와 경로 조합으로 센다. 같은 API를 여러 화면에서 호출해도 한 개로 집계한다. PG Mock 토큰화는 앱에 호출이 있지만 위 표의 Core·Pay 앱/웹 API 집계에는 포함하지 않는다. v4에만 있는 미구현 API와 서버 간 연동 API도 14개에 포함하지 않는다.

우선 적용 후보는 **FCM 연결 해제와 Agent 연결 관리**다. 다음으로 **구매 요청 조회 → 배송지 선택 → 구매 재개 → 기존 결제 승인 화면**을 검토할 만하다.

## 2. 용도와 호출 방법이 비교적 명확한 미사용 API 8개

아래 8개는 v5에서 앱/웹용·구현 완료로 명시돼 있다. 기능 활용안은 명세를 바탕으로 제안한 것이며, 실제 배포 상태는 연결 시 확인해야 한다.

| 번호 | API | 만들 수 있는 기능 | 활용 방법 |
|---|---|---|---|
| A1 | **Pay · FCM 연결 해제**<br>`DELETE /api/pay/v1/notification-installations/{fid}`<br>[명세](https://app.notion.com/p/9de85e9160e683169deb01db9511ad2f) | 로그아웃·계정 전환 시 해당 계정의 푸시 연결 해제 | 현재 기기의 FID로 서버 연결을 해제한다. 앱 Access Token이 필요하므로 토큰을 정리하기 전에 호출한다. 성공은 204이며 별도 응답 본문은 없다. |
| A2 | **Core · Agent 연동 목록**<br>`GET /api/core/v1/users/me/agent-connections`<br>[명세](https://app.notion.com/p/a5985e9160e682e1b5a681bde34a5494) | 설정 → 연결된 AI 서비스 | 서비스 이름·로고, 허용 권한, 연결일, 마지막 사용일을 표시한다. 반환값은 `AgentConnectionResponse` 목록이다. |
| A3 | **Core · Agent 연동 해제**<br>`DELETE /api/core/v1/users/me/agent-connections/{connectionId}`<br>[명세](https://app.notion.com/p/ed785e9160e68374abdf01e0cf0d98b8) | AI 서비스 연결 끊기 | A2에서 선택한 `connectionId`를 해제하고 목록을 다시 조회한다. 성공은 204다. A2와 하나의 설정 기능으로 묶기 좋다. |
| A4 | **Core · 장바구니 조회**<br>`GET /api/core/v1/cart`<br>[명세](https://app.notion.com/p/2e785e9160e683509a0b019a7f59e474) | 결제 전 상품 확인 화면 | 상품·옵션·수량·단가와 상품 금액, 할인, 배송비, 예상 합계를 표시한다. `cartId`와 `version`은 후속 수정·검증 요청에 사용한다. 합계는 확정 결제 금액과 구분해 표시한다. |
| A5 | **Core · 장바구니 수량 변경**<br>`PATCH /api/core/v1/cart/items/{cartItemId}`<br>[명세](https://app.notion.com/p/a9b85e9160e6825e894a015dd4817d1f) | 결제 전 수량 조절 | 본문에 `cartVersion`, `quantity`를 보낸다. 성공 시 새 `cartVersion`을 반영한다. `CART_VERSION_CONFLICT`가 발생하면 장바구니를 다시 조회한다. |
| A6 | **Core · 장바구니 상품 삭제**<br>`DELETE /api/core/v1/cart/items/{cartItemId}`<br>[명세](https://app.notion.com/p/4e285e9160e68262aeda01faaafdb61b) | 구매할 상품에서 항목 제외 | 쿼리에 `cartVersion`을 넣는다. 성공 응답은 `200 + cartVersion`이다. 삭제 후 목록과 합계를 다시 조회한다. |
| A7 | **Core · 상품 검색**<br>`POST /api/core/v1/products/search`<br>[명세](https://app.notion.com/p/cdc85e9160e68345b27b81368ff47652) | 앱 안 상품 검색·판매처 가격 비교 | 검색어·카테고리·브랜드·판매처·가격 범위·정렬·페이지 조건을 활용한다. 상품별 `offers`의 판매처·가격·판매 가능 상태로 비교 화면을 구성한다. |
| A8 | **Core · 상품 상세 조회**<br>`GET /api/core/v1/products/{productId}`<br>[명세](https://app.notion.com/p/f7985e9160e6832aa01581208cdcc27b) | 구매 전 상품·옵션·배송 정보 확인 | `merchantId`가 필수이고 `optionId`는 선택이다. 상품 설명·사양·옵션·재고·배송비와 옵션 구매 가능 여부를 표시한다. |

### 현재 앱에 적용할 위치

**A1 — 알림 연결 해제**

현재 앱에는 FID 등록·갱신만 있고, 로그아웃 시 FID 해제 호출이 없다. 로그아웃은 Core 로그아웃과 로컬 세션 정리로 끝난다. 계정 전환 시에도 이전 계정의 서버 연결을 정리하는 흐름을 검토할 수 있다.

- [FID 등록 코드](../app/src/main/kotlin/com/copenhagen/wallet/AppModel.kt#L140)
- [로그아웃과 세션 정리 코드](../app/src/main/kotlin/com/copenhagen/wallet/AppModel.kt#L255)

기존 [CONTRACT.md](CONTRACT.md)는 당시 제공된 명세에 FID 해제 API가 없었다고 기록한다. 이번에 확인한 v5에는 A1이 있으므로, 실제 구현에 착수할 때 이 계약 문서도 함께 갱신해야 한다.

**A2·A3 — 연결된 AI 서비스 관리**

현재 설정 화면에 연결 서비스 목록·해제 기능이 없다. 설정 화면에 목록 진입점을 추가하고, 권한·최근 사용일 확인과 연결 해제를 한 흐름으로 구성할 수 있다.

- [현재 설정 화면](../app/src/main/kotlin/com/copenhagen/wallet/ui/HistorySettingsScreens.kt#L73)

**A4·A5·A6 — 결제 전 장바구니 확인·편집**

API 자체의 호출 형식은 명확하지만, ChatGPT에서 담은 장바구니와 앱에서 조회하는 장바구니가 동일하게 연결되는지는 추가 확인이 필요하다. 공개 API는 `productId`·`optionValueIds`를 사용하고, MCP 내부 API는 `merchantProductId`·`merchantVariantId`와 별도 응답 구조를 사용한다. 따라서 ‘AI와 장바구니 공유’까지 구현됐다고 단정하지 않는다. [내부 장바구니 조회](https://app.notion.com/p/73585e9160e683c5b71f01542d4aa691), [내부 장바구니 추가](https://app.notion.com/p/01285e9160e682e5a80d81ff09a2eab9)

**A7·A8 — 상품 탐색**

페이앱을 쇼핑 기능까지 확장할 때 유용하다. 현재 앱의 결제 승인 역할을 먼저 완성하려면 알림·Agent 관리·구매 인계보다 후순위로 두는 것이 적절하다.

## 3. 활용 가능성이 있지만 계약·연동 확인이 필요한 API 6개

이 항목들도 v5에는 구현 완료로 적혀 있다. 다만 앱에서 사용하기 전에 해결하거나 확인해야 할 구체적인 문제가 있다.

| 번호 | API | 만들 수 있는 기능 | 확인해야 할 점 |
|---|---|---|---|
| B1 | **Core · 최근 구매 요청 목록**<br>`GET /api/core/v1/purchase-requests`<br>[명세](https://app.notion.com/p/e4d85e9160e682568bd70149a6f39811) | AI가 요청한 구매 모아보기, 진행 중 구매 복구 | 명세 기준 커밋의 인증 보호 경로에 `purchase-requests`가 누락돼 있다. 컨트롤러는 인증된 사용자 정보를 요구하므로 정상 인증 연동이 수정됐는지 확인해야 한다. |
| B2 | **Core · 구매 요청 상세·진행 상태**<br>`GET /api/core/v1/purchase-requests/{id}`<br>[명세](https://app.notion.com/p/df585e9160e682e094c401527f47797d) | 구매 상태·실패 사유 확인, 기존 결제 승인 화면 진입 | B1과 같은 인증 문제를 확인해야 한다. 응답의 `status`, `nextAction`, `failureCode`, `merchantCheckouts[].approvalId`를 어떤 화면으로 연결할지 정해야 한다. |
| B3 | **Core · 배송지 선택 후 구매 재개**<br>`POST /api/core/v1/purchase-requests/{id}/resume`<br>[명세](https://app.notion.com/p/e4f85e9160e68329ab27016b6b163115) | 배송지를 선택한 뒤 중단된 구매 계속하기 | 본문은 `addressId`, `cartVersion`, 헤더는 UUID 형식의 `Idempotency-Key`다. 공통 인증 문제와 함께 어떤 `status`·`nextAction`일 때 재개할 수 있는지 확인해야 한다. |
| B4 | **Core · 구매 알림 재전송**<br>`POST /api/core/v1/purchase-requests/{id}/notifications`<br>[명세](https://app.notion.com/p/3e685e9160e68371bdeb01ada565cbb7) | 구매 요청 알림 다시 보내기, 알림 실패 복구 | UUID 형식의 `Idempotency-Key`가 필요하다. 공통 인증 문제 외에 재전송 간격·횟수 제한과 만료 요청 처리 정책을 확인해야 한다. 전송 상태를 결제 성공으로 해석하지 않는다. |
| B5 | **Core · 주문 검증**<br>`POST /api/core/v1/orders/validations`<br>[명세](https://app.notion.com/p/e2385e9160e683978101015c5d0dc564) | 결제 전 가격·재고·배송 가능 여부 확인, 변경 금액 안내 | 입력은 `cartId`, `cartVersion`, `addressId`다. 응답은 `validationId`와 판매처별 Checkout 정보이며 기존 승인 화면에 필요한 `approvalId`가 직접 없다. 구매 요청 조회·승인으로 이어지는 식별자 관계와 호출 순서를 확인해야 한다. |
| B6 | **Core · 장바구니 상품 추가**<br>`POST /api/core/v1/cart/items`<br>[명세](https://app.notion.com/p/0a285e9160e6821e884e81c0005dd1dc) | 상품 상세에서 장바구니 담기, 구매 후보 추가 | 요청에는 `productId`, `merchantId`, `optionValueIds[]`, `quantity`, `cartVersion`이 들어간다. 상품 상세가 반환하는 `variants[].optionId`와 `optionValueIds[]`의 변환 관계가 명확하지 않다. MCP 내부 추가 API의 `merchantVariantId`와도 구분해야 한다. |

### B1~B4의 공통 선행 확인

1. **인증 경로 수정 여부:** v5의 `CoreAuthenticationPolicy` 보호 경로 목록에서 `/api/core/v1/purchase-requests`가 누락됐다는 지적이 현재 서버에서 해결됐는지 확인한다. 문서 스냅샷 이후 수정 여부는 이번 조사로 확인하지 않았다.
2. **구매 인계 진입 규약:** 구매 인계는 `purchaseRequestId` 중심으로 정의돼 있다. 실제 FCM payload와 딥링크 규약을 확인해야 한다. 현재 앱의 푸시 수신은 `approvalId`만 처리한다.
3. **상태별 화면 연결:** 구매 요청 상세의 `nextAction`에 따라 배송지 선택·승인 화면·대기 화면으로 이동하는 규칙을 확정한다.
4. **승인 완료와 결제 완료 구분:** 구매 요청 생성, 알림 발송, 사용자 승인, 실제 결제 완료를 서로 다른 단계로 표시한다.

근거: [v5 알려진 인증 문제](https://app.notion.com/p/b7485e9160e683c8bba8010d64e5a879), [MCP 구매 인계 응답](https://app.notion.com/p/ab985e9160e683638bca012a1e5466ac), [Pay 구매 인계 알림 입력](https://app.notion.com/p/0a785e9160e6826997df0151958ceee1), [현재 푸시 수신 코드](../app/src/main/kotlin/com/copenhagen/wallet/ApprovalMessagingService.kt#L23), [현재 링크 진입 코드](../app/src/main/kotlin/com/copenhagen/wallet/MainActivity.kt#L40)

### B5의 식별자 관계

v4는 `validationId`가 `purchase_requests.public_id`, `orderSnapshotId`가 `checkout_snapshots.public_id`에 대응한다고 설명한다. v5의 응답은 `validationId`와 `checkouts[]` 안의 `orderSnapshotId`·`merchantCheckoutId` 등으로 정리돼 있다. 이 설명만으로 검증 직후 기존 승인 화면까지의 연결을 확정하지 말고, 구매 요청 조회와의 관계를 확인해야 한다. [v4 주문 검증](https://app.notion.com/p/1d585e9160e6834e854501f448e1b4a6), [v5 주문 검증](https://app.notion.com/p/e2385e9160e683978101015c5d0dc564)

검증 과정에서 Merchant Checkout을 생성하므로 단순한 읽기 전용 견적 조회처럼 반복 호출하는 흐름도 피해야 한다. 호출·재시도 정책은 구현에 앞서 확인한다.

## 4. v4에만 남아 있으며 서버 구현이 필요한 API 3개

다음 API는 기능 아이디어로는 유효하지만, 조사한 문서에서 미구현으로 명시돼 v5 구현 목록에서 빠져 있다. 위의 미사용 앱용 API 14개와 별도로 관리한다.

| API | 만들 수 있는 기능 | 문서상 상태·미확정 사항 |
|---|---|---|
| `POST /api/core/v1/auth/oauth/google`<br>[명세](https://app.notion.com/p/10585e9160e6821e98058118c2300f86) | Google 간편 로그인·회원가입 | `idToken`을 보내는 계약은 있지만 해당 엔드포인트 컨트롤러가 미구현이다. v4 상태는 ‘검토 전’이다. |
| `PATCH /api/core/v1/users/me`<br>[명세](https://app.notion.com/p/59085e9160e68371a2ea816871661e7c) | 프로필 이름 등 기본정보 수정 | PATCH 핸들러가 미구현이다. `name` 외 수정 가능 필드와 응답 형식도 미정이다. v4 상태는 ‘시작 전’이다. |
| `GET /api/core/v1/events`<br>[명세](https://app.notion.com/p/af485e9160e6829fb5d9016c23b6433b) | 열어둔 화면의 결제·주문 상태 실시간 갱신 | SSE 설계는 있으나 미구현이다. 현재 구현된 상태 전달 방식은 Pay FCM이다. v4 상태는 ‘시작 전’이다. |

구현 여부에 대한 공통 근거: [v5 포함·제외 기준](https://app.notion.com/p/b7485e9160e683c8bba8010d64e5a879).

## 5. 서버 간 연동을 먼저 설계해야 하는 기능 후보

아래는 앱에서 직접 호출하는 API로 명시돼 있지 않다. 백엔드에서 이미 사용 중일 수 있으므로 ‘전체 시스템에서 미사용’이라고 판단하지 않는다. 앱에 기능을 제공하려면 사용자용 API와 화면에 노출할 결과를 별도로 설계해야 한다.

| 관련 API | 기능 후보 | 앱에 제공하기 전 필요한 작업 |
|---|---|---|
| **AI · FDS 내부 평가**<br>`POST /internal/fds/evaluations`<br>[명세](https://app.notion.com/p/71485e9160e6831f97b2018ff9ba929f) | 이상거래 안내, 의심 거래 추가 확인 화면 | 내부 서비스용 독립 AI 어댑터다. Core 최종 연동 DTO, 결과 저장, 결제 판단과의 연결을 확인하고 앱에 전달할 결과·문구를 정의해야 한다. HTTP 성공을 결제 허용으로 해석하지 않는다. |
| **Merchant · Checkout 취소**<br>`POST /api/ucp/v1/{merchantId}/checkout-sessions/{checkoutId}/cancel`<br>[명세](https://app.notion.com/p/94f85e9160e6838c9857010013291a45) | 결제 전 진행 중 구매 취소 | 호출자는 Core이며 UCP Merchant Mock 구현이다. 앱용 진입 API와 취소 가능 상태를 정의해야 한다. 이미 결제된 주문의 취소·환불·반품을 지원한다는 의미가 아니다. |

취소·환불·반품 범위는 별도 정책 문서에서도 TBD다. Checkout 취소 API 하나만으로 환불 기능까지 만들 수 있다고 판단하지 않는다. [취소·환불·반품 정책](https://app.notion.com/p/d7e85e9160e682deb7ae01c2521074e4)

## 6. 이미 앱에서 사용하는 기능과 집계에서 제외한 항목

현재 앱에 다음 호출이 있으므로 신규 미사용 후보에서 제외했다.

| 영역 | 현재 호출이 있는 기능 | 코드 근거 |
|---|---|---|
| Core 인증 | 회원가입, 로그인, 로그아웃, 토큰 갱신 | [Api.kt](../app/src/main/kotlin/com/copenhagen/wallet/data/Api.kt) |
| Core 계정·배송·주문 | 프로필 조회, 배송지 목록·상세·등록·수정·삭제·기본 지정, 주문 목록·상세 | [Account.kt](../app/src/main/kotlin/com/copenhagen/wallet/features/Account.kt) |
| Pay 카드 | 카드 목록·등록·삭제·기본 지정 | [Cards.kt](../app/src/main/kotlin/com/copenhagen/wallet/features/Cards.kt) |
| Pay PIN·알림 | PIN 상태·등록·변경, FCM FID 등록·갱신 | [AppModel.kt](../app/src/main/kotlin/com/copenhagen/wallet/AppModel.kt) |
| Pay 생체인증 | 생체인증 공개키 등록 | [Biometrics.kt](../app/src/main/kotlin/com/copenhagen/wallet/features/Biometrics.kt) |
| Pay 승인 | 승인 요청 목록·상세, 결제수단 선택, 승인·거절 | [Approvals.kt](../app/src/main/kotlin/com/copenhagen/wallet/features/Approvals.kt) |

호출이 있다는 것은 실제 서버 연결이 검증됐다는 의미는 아니다. 운영 헬스체크, 서비스 간 결제 실행·상태 조회, MCP 전송·도구, 폐기된 API도 앱용 미사용 14개에 포함하지 않았다.

## 7. 권장 개발 순서

아래 순서는 현재 페이앱에 대한 적합성과 계약 확인 범위를 고려한 제안이다.

| 순서 | 작업 | 이유·선행 조건 |
|---|---|---|
| 1 | **FCM 연결 해제 + 연결된 Agent 조회·해제** | 기존 로그인·설정 화면에 연결하기 좋고, 앱용 계약과 응답이 비교적 명확하다. |
| 2 | **구매 요청 조회 → 배송지 선택 → 구매 재개 → 기존 승인 화면** | AI 구매 인계를 페이앱에서 이어받는 핵심 흐름이다. 구매 요청 인증 문제, 알림·딥링크 규약, 상태별 화면 연결을 먼저 확인한다. |
| 3 | **장바구니 조회·수량 변경·삭제** | 결제 전 사용자가 구매 내용을 확인하고 조정할 수 있다. AI 장바구니와 공유하려면 동일 장바구니·식별자 관계를 확인한다. |
| 4 | **상품 검색·상세·장바구니 담기** | 앱의 쇼핑 기능 확장이다. 장바구니 추가 시 옵션 식별자 변환 계약을 먼저 확정한다. |
| 별도 기획 | **Google 로그인·프로필 수정·SSE·FDS 안내·Checkout 취소** | 미구현 서버 기능 또는 앱용 연결 설계가 선행돼야 한다. |
