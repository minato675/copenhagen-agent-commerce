# Pay 구매 요청 단위 결제 승인

판매처가 여러 곳인 장바구니를 살 때 사용자 인증을 1회로 만드는 승인 계약입니다.

| 항목 | 내용 |
|---|---|
| 대상 | Core, 모바일 앱 |
| 제공 서비스 | `pay-service` |
| 계약 버전 | v1 |
| 관련 이슈 | S15P21A207-487 |

## 1. 모델

승인 세션은 **구매 요청 단위**입니다. 판매처가 몇 곳이든 세션은 1개이고 인증도 1회입니다.
판매처별 주문서는 `authorization_session_items` 로 보관하며 **credential 은 판매처(= checkout)
당 1장**씩 발급됩니다.

```text
authorization_sessions              구매 요청 1건, 사용자 인증 1회
  └─ authorization_session_items    판매처 N곳
       └─ payment_credential_tokens credential N장 (판매처당 1장)
            └─ payments             결제 N건 (판매처당 1건)
```

Merchant → Pay 결제 계약은 바뀌지 않습니다. credential 이 `merchant_id`·`external_checkout_id`·
`checkout_digest`·`amount_minor` 를 자체 보유하므로 결제 검증은 credential 만으로 성립합니다.

### 상태

| 계층 | 상태 |
|---|---|
| 세션 | `PAYMENT_METHOD_REQUIRED` → `PENDING` → `APPROVED` 에서 **종료**. 승인 전 경로로 `REJECTED`·`EXPIRED`·`INVALIDATED` |
| item | `PENDING` → `COMPLETED` / `INVALIDATED` / `EXPIRED` |

**결제 결과는 item 이 받습니다.** 세션 하나에 결제가 N건 붙으므로, 먼저 끝난 결제가 세션을 종료
상태로 보내면 나머지 판매처의 결과를 기록할 수 없기 때문입니다. 판매처 A 성공 · 판매처 B 실패를
세션 하나의 상태로 표현할 수도 없습니다. 구매 전체 집계는 Core `PurchaseWorkflowService.aggregate()`
가 담당합니다.

## 2. 승인 세션 생성 — Core → Pay

```text
POST /internal/v1/payment-approvals
```

```jsonc
{
  "purchaseRequestId": "…",
  "userId": "…",
  "totalAmountMinor": 131000,
  "currency": "KRW",
  "items": [
    { "merchantId": "merchant-a", "checkoutId": "…", "checkoutHash": "sha256:…",
      "checkoutJwt": "…", "amountMinor": 92000, "currency": "KRW",
      "display": { "merchantName": "A마트",
                   "summary": [ { "name": "무선 이어폰", "option": null,
                                  "quantity": 1, "amountMinor": 89000 } ] } },
    { "merchantId": "merchant-b", "…": "…" }
  ]
}
```

검증 규칙:

- `items` 는 1개 이상 20개 이하. 빈 배열은 거절
- 같은 `merchantId` 또는 같은 `checkoutId` 가 두 번 들어오면 거절
- `currency` 는 top-level 과 item 이 **모두 일치**해야 하며 아니면 `400`
- `totalAmountMinor` 가 `items[].amountMinor` 합과 다르면 `400`. 사용자가 승인 화면에서 본
  합계와 실제 결제 합계가 어긋나는 것을 여기서 막습니다
- item 마다 `checkoutJwt` 서명을 검증합니다. 판매처 공개키 조회가 판매처 수만큼 발생합니다

멱등키는 **구매 요청 단위**입니다. DB 유니크도 `(user_id, idempotency_key)` 로 바뀌었습니다.

### 구 단건 계약 동시 수용

`items` 가 없고 평면 필드(`merchantId`·`checkoutId`·`checkoutHash`·`checkoutJwt`·`display`·
`amountMinor`)가 오면 판매처 1곳짜리 세션으로 만듭니다. Core 배포 시점을 Pay 와 분리하기 위한
전환기 계약이며, Core 전환이 끝나면 제거합니다.

### 세션 만료

```text
expires_at = max( min(판매처별 checkout 만료), pay.approval.expiration-seconds )
```

하한이 없으면 짧은 만료를 주는 판매처 하나가 세션 전체를 끌어내려 인증을 1회로 줄인 효과가
상쇄됩니다. 하한은 `pay.approval.expiration-floor-enabled=false` 로 끌 수 있습니다.

그 대가로 세션 만료가 일부 checkout 만료보다 늦어질 수 있고, 그 판매처의 Complete Checkout 이
실패할 수 있습니다. 이 실패는 부분완료 정책으로 흡수됩니다.

## 3. 승인 상태 조회 — Core → Pay

```text
GET /internal/v1/payment-approvals/{approvalId}/status
```

응답에 판매처별 `items[]` 가 추가되었습니다.

```jsonc
"items": [
  { "merchantId": "merchant-a", "checkoutId": "…", "credentialId": "…",
    "credentialExpiresAt": "…", "paymentId": "…", "paymentStatus": "SUCCESS",
    "paymentLabel": "VISA •••• 1234" }
]
```

평면 필드(`paymentId`·`credentialId`·`paymentStatus`·`paymentLabel`)는 구 계약 호환을 위해
남아 있으며 **첫 판매처의 값**을 담습니다. `amountMinor` 는 전 판매처 합계입니다. 판매처가 여러
곳인 구매를 다룰 때는 `items` 를 사용해야 합니다.

`nextAction` 은 가장 덜 진행된 판매처를 기준으로 계산합니다. 하나라도 결과가 남아 있으면 Core 가
계속 폴링해야 하기 때문입니다.

## 4. 앱 API

### 승인 상세 — `GET /api/pay/v1/payment-approvals/{approvalId}`

판매처 정보가 평면 필드에서 배열로 바뀌었습니다.

| 변경 | 필드 |
|---|---|
| 제거 | `merchantName`, `checkoutId`, `checkoutHash`, `summary` |
| 추가 | `merchants[] { merchantId, merchantName, checkoutId, checkoutHash, summary[], subtotalAmountMinor }` |
| 의미 변경 | `totalAmountMinor` — 판매처 1곳 총액 → **전 판매처 합계** |

`subtotalAmountMinor` 는 배송비를 포함하므로 `summary` 의 상품 금액 합보다 클 수 있습니다.
`biometricChallenge` 는 판매처 수와 관계없이 **1개**입니다.

### 승인 실행 — `POST /api/pay/v1/payment-approvals/{approvalId}/approve`

**요청 본문 무변경.** 인증 1회로 전 판매처가 승인되고 credential 이 판매처 수만큼 발급됩니다.
한 트랜잭션이므로 item 하나라도 실패하면 승인 전체가 롤백됩니다 — "한 번 승인 = 전부 승인"이
성립하려면 부분 승인 상태가 존재해서는 안 됩니다.

응답에서 `credentialId`·`credentialExpiresAt`·`checkoutMandate` 를 제거하고 `credentialCount` 로
대체했습니다. credential 은 판매처가 Pay 에 결제를 요청할 때 쓰는 값이라 앱이 쓸 일이 없습니다.

승인 1회에 판매처 수만큼 내부 처리가 일어나 응답이 느려집니다. 타임아웃 후 재시도할 때
`Idempotency-Key` 를 **새로 만들지 말고 유지**해야 합니다. 새 키로 보내면 이미 승인된 상태라
`STATE_CONFLICT` 가 돌아오는데, 실제로는 결제가 정상 진행 중인 상황입니다.

### 목록 — `GET /api/pay/v1/payment-approvals`

`merchantName` 은 대표 판매처, `merchantCount` 는 판매처 수입니다("A마트 외 2곳"). 판매처마다
다른 `checkoutId` 와 `summary` 는 카드에서 제외했습니다.

### 무변경

`PATCH …/{approvalId}/payment-method` — 세션 단위라 한 번 고른 카드가 전 판매처에 적용됩니다.
`POST …/{approvalId}/reject` — **전체 거절**입니다. 일부 판매처만 빼는 기능은 없으며, 빼려면
장바구니에서 조정해야 합니다.

승인 요청 푸시(`APPROVAL_REQUESTED`)도 형식은 그대로이고 건수만 구매당 1건으로 줄어듭니다.

## 5. 마이그레이션 (V15)

| 대상 | 변경 |
|---|---|
| `authorization_sessions` | `merchant_account_id`·`external_checkout_id`·`checkout_digest`·`amount_minor`·`display_snapshot` 제거, `total_amount_minor` 신설 |
| 유니크 | 멱등키 `(user_id, idempotency_key)`, 활성 세션 `(active_purchase_request_key)` 단독 |
| 신규 | `authorization_session_items` |
| `pay_verified_checkout_evidence`, `checkout_mandates`, `payment_mandates`, `payment_credential_tokens` | `session_item_id` 추가, 세션 UNIQUE → item UNIQUE |
| `biometric_challenges`, `payments` | 무변경 |

**비가역 마이그레이션입니다.** 배포 전 진행 중 세션을 드레인해야 합니다. 기준은
`PAYMENT_METHOD_REQUIRED`·`PENDING`·`APPROVED` 세션이 0 이 되는 시점이며, 그동안 Core 의 승인
생성 호출이 멈춰야 합니다.

드레인하지 않으면 같은 구매 요청의 활성 세션이 여러 개 남아 마이그레이션이 가장 먼저 만들어진
하나만 남기고 나머지를 `EXPIRED` 로 정리합니다.

### V14 회귀 주의

V14 는 `chk_authorization_sessions_payment_method_state` 가 만료 스윕의
`PAYMENT_METHOD_REQUIRED → EXPIRED` 전이를 막아 승인 목록·상세 조회가 영구히 500 이 되었던
사건의 수정입니다. V15 는 컬럼 제거와 백필을 동반하므로 같은 유형의 회귀 여지가 더 큽니다.

`AuthorizationSessionSchemaPostgresIntegrationTest` 가 세션과 item 의 **모든 상태 전이**가 DB
제약을 통과하는지 전수로 확인합니다. 스키마를 다시 손댈 때 이 테스트를 같이 갱신하세요.

세션 status CHECK 에서 `COMPLETED` 는 제거하지 않았습니다. 애플리케이션은 더 이상 세션을
`COMPLETED` 로 보내지 않지만 과거 행이 남아 있기 때문입니다.

## 6. 만료 스윕과 item

만료 스윕은 세션을 `EXPIRED` 로 바꾸면서 남은 item 도 정리합니다. 단 `approved_at IS NULL` 인
세션만 대상입니다. 이미 승인된 세션은 credential 이 발급되어 판매처가 결제를 진행 중일 수 있고,
그 item 을 `EXPIRED` 로 바꾸면 결제 성공을 기록할 수 없게 됩니다.
