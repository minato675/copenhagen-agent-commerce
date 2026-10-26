# 주문내역 v2

앱 주문내역이 항목마다 상품을 다시 찾고 이미지를 띄울 수 있도록 v2를 추가했다. v1은 그대로 둔다 —
이미 배포된 앱이 쓰고 있고 필드가 늘어난 것 외에 달라진 계약이 없다.

## 엔드포인트

| | v1 | v2 |
|---|---|---|
| 목록 | `GET /api/core/v1/orders` | `GET /api/core/v2/orders` |
| 상세 | `GET /api/core/v1/orders/{orderId}` | `GET /api/core/v2/orders/{orderId}` |

요청 파라미터(`size`·`cursor`·`status`), 커서 형식, 정렬, 인증 등급은 v1과 같다.

## 달라진 곳

응답에서 바뀐 것은 `items` 한 곳뿐이다.

```jsonc
// v1 — ItemSummary
{ "name": "맨투맨", "option": "블랙 / M", "quantity": 1 }

// v2 — ItemDetail
{
  "merchantId": "merchant-a",
  "merchantProductId": "P-1",
  "merchantVariantId": "P-1-BLK",
  "name": "맨투맨",
  "option": "블랙 / M",
  "imageUrl": "https://product-image.kurly.com/...",
  "quantity": 1
}
```

`orderId`·`title`·`status`·`amountMinor`·`currency`·`createdAt`·`address`·`paymentLabel`은 그대로다.

## imageUrl

주문 당시 캡처한 주소이고, 없으면 필드를 뺀다(`null`을 빈 문자열로 바꾸지 않는다). 비는 경우는 둘이다.

* V22 이전에 만들어진 주문 — 그때는 `checkout_snapshot_items`에 이미지 컬럼이 없었다. 소급해 채우지 않는다.
* 이미지를 주지 않는 merchant — Catalog 응답에 `imageUrl`이 없으면 담기 시점부터 비어 있다.

이미지는 merchant Checkout 응답에 없다. Catalog에서 담기 시점에 `cart_items.image_url`로 캡처해 둔 값을
주문서 스냅샷으로 옮겨 담는다(`OrderValidationService.toSnapshotItems`). 결제가 끝나면 장바구니 행은
지워지므로 조인으로는 되살릴 수 없다.

값은 판매자 CDN 주소를 그대로 쓴다. 판매자가 원본을 내리거나 교체하면 과거 주문내역의 이미지도 따라
바뀐다 — 상품명·금액과 달리 이미지는 주소만 박제되기 때문이다. 자체 보관은 하지 않기로 했다.

## merchantId

응답의 `merchantId`는 설정의 문자열 id(`merchant-a`)다. 설정에서 빠진 merchant의 과거 주문은
`merchants.id` 숫자를 문자열로 그대로 노출한다(`CartMerchants.merchantId`) — 응답을 비우는 것보다 추적에 낫다.

`merchant_id`는 주문서 단위라 `checkout_snapshot_items`에 없다. 항목 쿼리가 `checkout_snapshots` →
`merchant_checkouts`까지 두 단계를 거슬러 읽는다.
