# PG Mock

로컬 및 개발 환경에서 외부 결제 게이트웨이 역할을 대신하는 시뮬레이터입니다.

- DB를 소유하지 않으며 다른 애플리케이션을 호출하지 않습니다.
- 실제 결제 시스템이나 운영 환경에서 사용하지 않습니다.
- Pay 연동을 재현하는 데 필요한 최소 계약과 동작만 구현합니다.
- 제품 비즈니스 규칙을 이 모듈에 넣지 않습니다.

## 카드 토큰화

앱은 개발 환경에서 `POST /api/pg/v1/payment-methods/tokenize`를 호출해 Pay 카드 등록에 사용할
`providerTokenRef`를 발급받을 수 있습니다. 첫 카드 등록일 때만 4자리 `cardPassword`를
전달하며, PG Mock은 사용자별 카드 보유 여부를 조회하지 않고 값의 형식만 검증합니다.

```http
POST /api/pg/v1/payment-methods/tokenize
Content-Type: application/json
Idempotency-Key: <uuid>
```

`Idempotency-Key`는 UUID 단독 형식만 허용합니다. `tokenize-{uuid}`처럼 작업명 접두사가
포함된 값은 `400 IDEMPOTENCY_KEY_INVALID`로 거절합니다.

```json
{
  "cardNumber": "4111111111111111",
  "expiryMonth": 12,
  "expiryYear": 2030,
  "cvc": "123",
  "cardPassword": "1234"
}
```

성공 응답은 Pay의 `tokenizationResult`와 같은 모양입니다.

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

카드번호, CVC, 카드 비밀번호는 저장하거나 로그에 기록하지 않습니다. 앱은 응답을 Pay의
`POST /api/pay/v1/payment-methods/cards`에 전달하고, PG Mock의 `/internal/v1/charges`는
직접 호출하지 않습니다. 상세 계약은 [카드 토큰화 API 레퍼런스](../../../docs/api/pg-mock-card-tokenization.md)를
참고합니다.

`backend/`에서 다음과 같이 검증합니다.

```bash
./gradlew :mocks:pg-mock:test
./gradlew :mocks:pg-mock:build
```

Pay의 `POST /internal/v1/charges` 호출에 대한 결과는 환경변수로 선택합니다.

```text
PG_MOCK_OUTCOME=SUCCESS
PG_MOCK_OUTCOME=FAILED
PG_MOCK_OUTCOME=TIMEOUT
```

`TIMEOUT`은 `PG_MOCK_RESPONSE_DELAY_MILLIS`만큼 응답을 지연합니다. 값을 생략하면
Pay의 요청 타임아웃을 초과하도록 5초 지연을 사용합니다. Mock은 결제 원장이나 카드정보를
저장하지 않습니다.

## 기존 거래 조회

`GET /internal/v1/charges?pgTransactionId=...` 또는 `?pgRequestId=...`로 기존 거래를 조회합니다.
Mock은 접수한 요청 식별자와 결과만 메모리에 보관하며 카드 토큰·결제 요청 원문은 보관하지 않습니다.
재시작하면 사라지므로 미발견 응답은 Pay에서 UNKNOWN으로 취급합니다.

`PG_MOCK_OUTCOME=TIMEOUT`일 때 조회 결과는 `PG_MOCK_LOOKUP_OUTCOME`으로 지정합니다.
`SUCCESS`, `FAILED`, `UNKNOWN`을 지원하며 기본값은 `UNKNOWN`입니다. 조회는 새 거래를 만들지 않습니다.
