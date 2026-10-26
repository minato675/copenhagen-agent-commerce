# PG Mock 카드 토큰화 API 레퍼런스

앱 개발자가 개발·테스트 환경에서 카드 정보를 PG Mock에 전달하고, Pay 카드 등록 API에 사용할 토큰화 결과를 받기 위한 연동 문서입니다.

| 항목 | 내용 |
|---|---|
| 대상 | 모바일 앱 / 앱 테스트 클라이언트 |
| 제공 서비스 | `pg-mock` |
| 적용 범위 | 로컬·개발 환경 전용 |
| 운영 사용 | 금지 |
| 계약 버전 | v1 |

## 1. 전체 흐름

```text
앱
  └─ 1. PG Mock 토큰화 API 호출
       └─ providerTokenRef 수신
  └─ 2. Pay 카드 등록 API에 tokenizationResult 전달
       └─ Pay가 결제수단 저장
```

카드번호와 CVC는 Pay로 전달하지 않습니다. PG Mock 토큰화 API의 응답으로 받은 `providerTokenRef`만 Pay 카드 등록 요청에 포함합니다.

PG Mock은 실제 PG가 아니며, 카드번호·CVC를 저장하지 않습니다. 테스트 카드 정보만 사용해야 합니다.

## 2. 접속 주소

앱은 환경별로 `PG_MOCK_BASE_URL`을 주입받아 사용합니다.

```text
${PG_MOCK_BASE_URL}/api/pg/v1/payment-methods/tokenize
```

현재 Docker Compose 기준 `http://pg-mock:8080`은 컨테이너 간 통신용 주소입니다. 앱 에뮬레이터·실기기에서는 이 주소에 접근할 수 없습니다.

앱은 개발용 Nginx의 `/api/pg/` 경로를 통해 PG Mock에 접근합니다. `PG_MOCK_BASE_URL`은 개발 서버의 scheme·host·port까지만 포함하고, 앱은 `/api/pg/v1/payment-methods/tokenize`를 붙여 호출합니다. 실제 개발 서버 주소와 포트는 인프라 담당자가 제공합니다.

## 3. 카드 토큰화

### Request

```http
POST /api/pg/v1/payment-methods/tokenize
Content-Type: application/json
Idempotency-Key: <uuid>
```

PG Mock 로컬·개발 API이므로 별도 사용자 Access Token은 요구하지 않습니다. 개발 게이트웨이에서 별도 인증을 추가하는 경우 앱 환경별 연동 문서에 인증 헤더를 추가합니다.

```json
{
  "cardNumber": "4111111111111111",
  "expiryMonth": 12,
  "expiryYear": 2030,
  "cvc": "123",
  "cardPassword": "1234"
}
```

| 필드 | 타입 | 필수 | 조건 |
|---|---|---:|---|
| `cardNumber` | String | O | 숫자만 입력, 13~19자리, Luhn 검증 통과 |
| `expiryMonth` | Integer | O | `1`~`12` |
| `expiryYear` | Integer | O | 4자리, 현재 월 기준 만료되지 않은 값 |
| `cvc` | String | O | 숫자 3~4자리 |
| `cardPassword` | String | 조건부 | 첫 카드 등록 시 숫자 4자리. 이후 카드 등록에서는 생략 |

앱은 카드번호의 공백과 하이픈을 제거한 숫자 문자열을 전송합니다. `brand`와 `last4`는 요청에 넣지 않으며 PG Mock이 카드번호를 기준으로 산출합니다.

첫 카드 여부는 PG Mock이 사용자의 Pay 결제수단을 조회해서 판단하지 않습니다. 앱은 Pay의 활성 카드 목록과 카드 등록 화면 흐름을 기준으로 첫 카드일 때만 `cardPassword`를 전송합니다. PG Mock은 `cardPassword`가 전송된 경우 4자리 숫자인지만 검증합니다.

### Response: `200 OK`

```json
{
  "provider": "mock-pg",
  "providerTokenRef": "tok_mock_01JPGTOKEN123456789",
  "brand": "VISA",
  "last4": "1111",
  "expiryMonth": 12,
  "expiryYear": 2030
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `provider` | String | Pay에 전달할 PG 식별자. 현재 값은 `mock-pg` |
| `providerTokenRef` | String | PG가 발급한 불투명 토큰. 앱은 값을 해석하거나 변경하지 않음 |
| `brand` | String | `VISA`, `MASTERCARD`, `AMEX`, `UNKNOWN` 중 하나 |
| `last4` | String | 카드번호 마지막 4자리 |
| `expiryMonth` | Integer | 요청과 동일한 만료 월 |
| `expiryYear` | Integer | 요청과 동일한 만료 연도 |

`providerTokenRef`는 Pay 카드 등록 API의 `tokenizationResult.providerTokenRef`에 그대로 전달합니다.

## 4. 오류 응답

### 멱등성 키 누락 또는 형식 오류: `400 Bad Request`

헤더가 없으면 `IDEMPOTENCY_KEY_REQUIRED`, 작업명 접두사가 포함되거나 UUID 형식이 아니면
`IDEMPOTENCY_KEY_INVALID`를 반환합니다.

```json
{
  "code": "IDEMPOTENCY_KEY_INVALID",
  "message": "Idempotency-Key는 UUID 형식이어야 합니다."
}
```

### 잘못된 카드 정보: `400 Bad Request`

```json
{
  "code": "INVALID_CARD_DATA",
  "message": "카드 정보가 올바르지 않습니다."
}
```

오류 응답에는 카드번호, CVC, 전체 토큰화 요청 본문을 포함하지 않습니다.

### 같은 멱등성 키에 다른 요청 사용: `409 Conflict`

```json
{
  "code": "IDEMPOTENCY_CONFLICT",
  "message": "같은 Idempotency-Key로 다른 요청을 보낼 수 없습니다."
}
```

### 토큰 발급 실패: `500 Internal Server Error`

```json
{
  "code": "INTERNAL_ERROR",
  "message": "토큰화 결과를 생성할 수 없습니다."
}
```

## 5. 멱등성 사용법

- 하나의 토큰화 작업마다 새로운 `Idempotency-Key`를 생성합니다.
- `Idempotency-Key`는 작업명 접두사 없이 UUID 문자열 하나만 사용합니다.
- 같은 키와 같은 요청 본문을 재전송하면 최초 토큰화 응답을 재사용합니다.
- 같은 키로 다른 카드 정보를 보내면 `409 IDEMPOTENCY_CONFLICT`를 반환합니다.
- Mock의 멱등성 기록은 메모리에만 유지되며 PG Mock 재시작 시 사라집니다.
- 토큰화 멱등성 키와 Pay 카드 등록 멱등성 키는 서로 다른 작업이므로 별도 키를 사용합니다.

## 6. Pay 카드 등록 연동

토큰화 API 응답을 받은 후 앱은 Pay에 다음 요청을 보냅니다.

```http
POST /api/pay/v1/payment-methods/cards
Authorization: Bearer <user-access-token>
Idempotency-Key: <uuid>
Content-Type: application/json
```

```json
{
  "tokenizationResult": {
    "provider": "mock-pg",
    "providerTokenRef": "tok_mock_01JPGTOKEN123456789",
    "brand": "VISA",
    "last4": "1111",
    "expiryMonth": 12,
    "expiryYear": 2030
  }
}
```

Pay는 이 요청에서 PG Mock을 다시 호출하지 않고 `providerTokenRef`를 결제수단으로 저장합니다. 카드 등록 성공 후 실제 결제 실행 단계에서만 Pay가 PG Mock의 내부 결제 API를 호출합니다.

앱은 PG Mock의 다음 내부 결제 API를 직접 호출하지 않습니다.

```http
POST /internal/v1/charges
GET /internal/v1/charges
```

## 7. 보안 및 로깅 규칙

- 실카드번호와 실CVC를 사용하지 않습니다.
- 실카드 비밀번호를 사용하지 않습니다. 테스트 전용 4자리 값만 사용합니다.
- 카드번호·CVC를 앱 로그, 네트워크 디버그 로그, 오류 메시지에 남기지 않습니다.
- `cardPassword`를 앱 로그, 네트워크 디버그 로그, 오류 메시지에 남기지 않습니다.
- Pay 요청에는 카드번호·CVC를 포함하지 않습니다.
- Pay 요청에는 `cardPassword`도 포함하지 않습니다.
- `providerTokenRef`는 앱에서 화면 표시나 로그 출력 대상으로 사용하지 않습니다.
- 응답의 `last4`만 카드 식별용으로 사용할 수 있습니다.
- PG Mock은 로컬·개발 환경 밖에 공개하지 않습니다.

## 8. 연동 체크리스트

1. 앱 환경에 실제 개발용 `PG_MOCK_BASE_URL`을 설정합니다.
2. Pay의 활성 카드 목록을 확인하고, 첫 카드 등록이면 4자리 `cardPassword`를 포함해 PG Mock 토큰화 API를 호출합니다.
3. 응답의 `tokenizationResult`를 Pay 카드 등록 요청에 전달합니다.
4. Pay 응답의 `paymentMethodId`를 앱에서 관리합니다.
5. 동일한 카드 등록 재시도에는 동일한 Pay `Idempotency-Key`를 사용합니다.
6. Pay 결제 실행 API가 PG Mock을 호출하는지 확인하고, 앱에서 `/internal/v1/charges`를 직접 호출하지 않습니다.

## 9. 관련 계약

- Pay 카드 등록 API: `POST /api/pay/v1/payment-methods/cards`
- Pay 카드 등록 구현: `backend/apps/pay-service/src/main/java/com/copenhagen/pay/payment/paymentmethod/PaymentMethodController.java`
- PG Mock 결제 API: `backend/mocks/pg-mock/src/main/java/com/copenhagen/pgmock/PgMockChargeController.java`
