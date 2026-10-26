# Pay API 멱등성 키 계약

Pay의 상태 변경 요청은 `Idempotency-Key` 요청 헤더를 사용한다. 신규 호출자는 작업명 접두사를 붙이지 않고 UUID 하나를 발급한다.

```http
Idempotency-Key: <uuid>
```

예시는 다음과 같다.

```http
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

## 적용 API

다음 상태 변경 API에는 요청마다 새로운 UUID를 전달한다.

| 메서드 | 경로 | 호출 주체 |
| --- | --- | --- |
| `POST` | `/api/pay/v1/payment-approvals/{approvalId}/approve` | 사용자 |
| `POST` | `/api/pay/v1/payment-approvals/{approvalId}/reject` | 사용자 |
| `PATCH` | `/api/pay/v1/payment-approvals/{approvalId}/payment-method` | 사용자 |
| `POST` | `/api/pay/v1/users/me/biometric-credentials` | 사용자 |
| `POST` | `/api/pay/v1/users/me/payment-pin` | 사용자 |
| `PUT` | `/api/pay/v1/users/me/payment-pin` | 사용자 |
| `POST` | `/api/pay/v1/payment-methods/cards` | 사용자 |
| `PATCH` | `/api/pay/v1/payment-methods/cards/{paymentMethodId}/default` | 사용자 |
| `DELETE` | `/api/pay/v1/payment-methods/cards/{paymentMethodId}` | 사용자 |
| `POST` | `/internal/v1/payment-approvals` | Core 서비스 |
| `POST` | `/internal/v1/purchase-notifications` | Core 서비스 |
| `POST` | `/internal/v1/purchase-notifications/{purchaseId}/resend` | Core 서비스 |
| `POST` | `/merchant/v1/payments` | Merchant 서비스 |

`/merchant/v1/payments`는 `Idempotency-Key`와 별도로 `X-Request-Id`도 요구한다.

## 적용 제외 API

- 모든 `GET` API
- `PUT /api/pay/v1/notification-installations`
- `DELETE /api/pay/v1/notification-installations/{fid}`

## 재시도 규칙

- 하나의 논리적 작업을 시작할 때 UUID를 한 번 발급한다.
- 같은 요청을 재시도할 때는 최초 UUID와 동일한 요청 본문을 그대로 재사용한다.
- 새 작업에는 반드시 새 UUID를 사용한다.
- 작업명이나 접두사를 UUID 앞에 붙이지 않는다.
- 서버 간 호출처럼 같은 논리 작업을 여러 시점·여러 워커가 재시도하는 경우에는 요청마다 새 UUID를 만들지 않고, 대상 식별자에서 파생한 고정 UUID(`UUID.nameUUIDFromBytes`)를 사용한다. `/internal/v1/payment-approvals`와 `/internal/v1/purchase-notifications`가 여기에 해당한다. 이때도 최종 헤더 값은 반드시 표준 UUID 문자열이어야 한다.

같은 범위에서 UUID와 요청 본문이 같으면 최초 결과를 재사용한다. 같은 UUID로 다른 요청 본문을 보내면 `409 IDEMPOTENCY_CONFLICT`를 반환한다.

## 오류 계약

| 상황 | 응답 |
| --- | --- |
| 헤더 누락 | `400 IDEMPOTENCY_KEY_REQUIRED` |
| 빈 값·공백·UUID가 아닌 값·허용 길이 초과 | `400 IDEMPOTENCY_KEY_INVALID` |
| 같은 UUID·같은 요청 재전송 | 최초 응답 재사용 |
| 같은 UUID·다른 요청 | `409 IDEMPOTENCY_CONFLICT` |

Pay의 상태 변경 API 경계에서는 UUID가 아닌 키를 모두 거절한다. 이미 저장된 과거 멱등성 기록은 변경하지 않으며, 새 요청부터 UUID 계약을 적용한다.

## PG Mock 토큰화

PG Mock 토큰화도 동일한 헤더 형식을 사용한다.

```http
POST /api/pg/v1/payment-methods/tokenize
Idempotency-Key: <uuid>
```

상세 요청·응답은 [PG Mock 카드 토큰화 API 레퍼런스](pg-mock-card-tokenization.md)를 참고한다.
