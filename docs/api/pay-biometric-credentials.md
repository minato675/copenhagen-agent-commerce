# Pay 생체인증 credential API 레퍼런스

앱이 기기 단위로 생체인증 결제를 켜고 끄기 위한 연동 문서입니다.

| 항목 | 내용 |
|---|---|
| 대상 | 모바일 앱 |
| 제공 서비스 | `pay-service` |
| 인증 주체 | 사용자 (`USER`) |
| 계약 버전 | v1 |
| 기준 경로 | `/api/pay/v1/users/me/biometric-credentials` |

## 1. 모델

생체인증 credential은 **기기 단위**로 존재합니다. 개인키가 해당 기기의 Keystore에만 있으므로
A기기에서 등록한 credential을 B기기에서 사용할 수 없습니다. 등록·조회·해제 모두 한 기기를
대상으로 동작합니다.

credential은 `ACTIVE`와 `REVOKED` 두 상태를 가집니다. 해제는 행을 삭제하지 않고 `REVOKED`로
표시하며, 조회와 승인 흐름은 `ACTIVE`만 대상으로 합니다.

```text
미등록 ──POST──> ACTIVE ──DELETE──> REVOKED
                    ^                   │
                    └───────POST────────┘
```

`REVOKED` 상태에서 다시 `POST` 하면 새 공개키로 `ACTIVE` credential이 만들어집니다. 기존
`REVOKED` 행은 이력으로 남습니다.

## 2. 기기 식별자 전달 방식

| 메서드 | 전달 위치 |
|---|---|
| `POST` | 요청 본문의 `deviceId` |
| `GET` | `X-Device-Id` 헤더 |
| `DELETE` | `X-Device-Id` 헤더 |

`GET`과 `DELETE`는 요청 본문이 없어 헤더를 사용합니다. 승인 상세 조회
(`GET /api/pay/v1/payment-approvals/{approvalId}`)가 같은 헤더를 쓰고 있습니다.

## 3. 승인 흐름과의 관계

승인 세션은 항상 `required_action = BIOMETRIC` 으로 생성됩니다. 해당 기기에 `ACTIVE`
credential이 없으면 승인 상세 조회 시 `PIN` 으로 폴백합니다.

`DELETE` 는 credential 무효화와 함께, 해당 사용자의 만료 전 `PENDING` 승인 세션을
`required_action = PIN` 으로 전환합니다. 이 전환이 없으면 사용자가 PIN을 입력해도 승인
확정이 `STATE_CONFLICT` 로 거절됩니다.

`PAYMENT_METHOD_REQUIRED` 세션은 `BIOMETRIC` 으로 남습니다. 카드 선택 후 상세를 다시 열 때
기존 폴백이 `PIN` 으로 바꿔줍니다.

이미 화면에 생체 챌린지를 들고 있는 상태에서 해제한 경우는 다음과 같이 흘러갑니다.

```text
DELETE
  └─ credential REVOKED
  └─ PENDING 세션 required_action = PIN
       ↓
옛 화면에서 생체 제출 → 422 BIOMETRIC_CHALLENGE_INVALID
       ↓
앱이 PIN 입력 화면으로 전환
       ↓
PIN 승인 → required_action 이 이미 PIN → 성공
```

## 4. 공통 요청 헤더

| 헤더 | 필수 | 설명 |
|---|---:|---|
| `Authorization` | O | `Bearer <accessToken>` |
| `X-Device-Id` | 조건부 | `GET`·`DELETE` 에 필수. 1~150자 |
| `Idempotency-Key` | 조건부 | `POST`·`DELETE` 에 필수. UUID 단독 형식 |

`Idempotency-Key` 규칙은 [Pay API 멱등성 키 계약](pay-idempotency.md)을 따릅니다. 작업명
접두사를 붙이면 `400 IDEMPOTENCY_KEY_INVALID` 입니다.

## 5. 등록 여부 조회

### Request

```http
GET /api/pay/v1/users/me/biometric-credentials
Authorization: Bearer <accessToken>
X-Device-Id: <deviceId>
```

### Response 200

등록된 경우입니다.

```json
{
  "registered": true,
  "status": "ACTIVE",
  "credentialId": "8b1f0c62-1f2f-4f3a-9a71-0c2f6a3b5d10",
  "registeredAt": "2026-09-18T08:22:00Z"
}
```

등록되지 않은 경우입니다.

```json
{
  "registered": false,
  "status": null,
  "credentialId": null,
  "registeredAt": null
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `registered` | Boolean | 이 기기에 `ACTIVE` credential 존재 여부 |
| `status` | String | `ACTIVE` 또는 `null` |
| `credentialId` | String | credential 식별자. 미등록이면 `null` |
| `registeredAt` | String | ISO-8601 UTC. 미등록이면 `null` |

미등록은 오류가 아닙니다. `404` 가 아니라 `200` 과 `registered: false` 로 응답합니다.

## 6. 등록

### Request

```http
POST /api/pay/v1/users/me/biometric-credentials
Authorization: Bearer <accessToken>
Idempotency-Key: <uuid>
Content-Type: application/json
```

```json
{
  "deviceId": "3f1a9c22-...",
  "publicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
  "credentialType": "PLATFORM_BIOMETRIC"
}
```

| 필드 | 타입 | 필수 | 조건 |
|---|---|---:|---|
| `deviceId` | String | O | 1~150자 |
| `publicKey` | String | O | Base64URL, 1~8192자 |
| `credentialType` | String | O | `PLATFORM_BIOMETRIC` 고정 |

### Response 201

```json
{
  "credentialId": "8b1f0c62-1f2f-4f3a-9a71-0c2f6a3b5d10",
  "registeredAt": "2026-09-18T08:22:00Z"
}
```

## 7. 해제

### Request

```http
DELETE /api/pay/v1/users/me/biometric-credentials
Authorization: Bearer <accessToken>
X-Device-Id: <deviceId>
Idempotency-Key: <uuid>
```

요청 본문은 없습니다.

### Response 204

본문이 없습니다.

해제 대상이 없어도 `204` 입니다. 이미 해제된 기기에 다시 호출해도 결과가 같습니다. 호출
시점의 상태와 무관하게 "이 기기에 `ACTIVE` credential이 없다"가 보장됩니다.

## 8. 오류 계약

| 상황 | 응답 |
|---|---|
| `X-Device-Id` 누락·공백·150자 초과 | `400 BIOMETRIC_CREDENTIAL_INVALID` |
| `publicKey` 또는 `credentialType` 형식 위반 | `400 BIOMETRIC_CREDENTIAL_INVALID` |
| 이미 등록된 기기에 `POST` | `409 BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED` |
| `Idempotency-Key` 누락 | `400 IDEMPOTENCY_KEY_REQUIRED` |
| UUID가 아닌 `Idempotency-Key` | `400 IDEMPOTENCY_KEY_INVALID` |
| 같은 키·같은 요청 재전송 | 최초 응답 재사용 |
| 같은 키·다른 요청 | `409 IDEMPOTENCY_CONFLICT` |
| 인증 실패 | `401 AUTHENTICATION_REQUIRED` |

## 9. 앱 연동 시나리오

### 설정 화면 진입

Keystore에 개인키가 있는지로 토글 상태를 판단합니다. 서버 호출이 필요 없습니다.

재설치 직후에는 키가 없어 OFF로 표시되는데, 실제로 생체 결제가 불가능한 상태이므로 맞는
표시입니다. 서버에 남은 credential은 짝이 되는 개인키가 없어 사용할 수 없습니다.

### 토글 ON

```text
Keystore 키페어 생성
POST -> 201
```

### 토글 OFF

```text
DELETE -> 204
(Keystore 키 삭제는 앱 정책에 따름)
```

### 앱 재설치·기기 초기화 복구

Keystore의 개인키는 앱과 함께 삭제되지만 서버의 공개키는 `ACTIVE` 로 남습니다. 이 고아
credential 때문에 재등록이 `409` 로 막힙니다.

사용자가 다시 켜려 할 때 정리합니다. 평소에는 추가 호출이 없고, 어긋난 경우에만 한 번 더
왕복합니다.

```text
토글 ON -> POST -> 409 BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED
                     └─ DELETE -> 204
                          └─ 키페어 재생성
                               └─ POST -> 201
```

기기에서 지문·얼굴을 새로 등록해 Keystore 키가 무효화된 경우
(`setInvalidatedByBiometricEnrollment`)도 같은 경로로 복구합니다.

### GET의 용도

상태 확인과 디버깅용입니다. 위 연동 흐름에는 필요하지 않습니다. 앱이 서버 등록 상태를
명시적으로 확인해야 할 때만 사용합니다.

## 10. 범위 밖

- 사용자의 모든 기기를 한 번에 해제하는 계정 단위 API는 제공하지 않습니다.
- 등록된 기기 목록 조회는 제공하지 않습니다. `GET` 은 `X-Device-Id` 기준 단건입니다.
- 생체 챌린지 발급은 이 문서 범위가 아닙니다. 승인 상세 조회 응답에 포함됩니다.
