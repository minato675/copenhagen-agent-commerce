# ADR-0007: Complete Checkout 이후 Merchant가 Pay에 결제 요청

- **Status:** Proposed — 논의 필요
- **Draft period:** 2026-09-03 ~ 2026-09-08
- **Recorded:** 2026-09-08
- **Last updated:** 2026-09-09
- **Note:** 설계 과정에서 정리한 초안이다. 팀 논의 전이므로 확정된 결정이 아니다.

## Context

사용자 승인 후 실제 Payment를 누가 요청할지에 대해 Core 직접 실행과 Merchant 실행 두 가지 구조를 검토했다.

Core가 Pay에 직접 Payment를 요청하면 구현 경로는 짧아질 수 있지만, Commerce 원본인 Merchant가 결제 성공 여부와 Order 생성 사이를 직접 연결하지 못한다.

## Decision

사용자 승인 이후 결제 흐름을 다음과 같이 구성한다.

```text
Core
→ Complete Checkout
Merchant
→ Payment Request
Pay
→ PG / Mock PG
→ Payment Result
Merchant
→ Order 생성
Core
```

Core의 Complete Checkout 요청은 최소한 다음 정보를 사용한다.

```text
checkoutId
credentialId
Idempotency-Key
```

`amount`, `currency`는 Core가 다시 전달하지 않는다.

Merchant는 자신의 authoritative Checkout에서 amount/currency 및 결제에 필요한 Commerce 정보를 가져와 Pay에 Payment를 요청한다.

Pay는 Credential이 해당 Merchant/Checkout/금액/통화에 유효하게 binding 되어 있는지 검증한다.

Payment가 성공한 경우에만 Merchant가 Order를 생성한다.

Merchant는 Order/Payment 결과를 Core에 반환하고, Core는 이를 사용자 통합 상태로 제공한다.

## Alternatives Considered

### A. Core가 Pay에 직접 Payment 요청 후 Merchant에 결과 전달
호출 수는 줄 수 있지만 Commerce와 Payment 완료 책임이 분리되어 Order 생성 정합성이 약해진다.

### B. Pay가 Merchant Order까지 생성
Pay의 책임이 Commerce 영역까지 확장된다.

## Consequences

### Positive
- Merchant가 Checkout → Payment → Order의 Commerce 흐름을 일관되게 소유한다.
- Core는 orchestration과 상태 통합에 집중한다.
- Core가 amount/currency를 중복 전달하지 않아 거래 기준이 명확해진다.

### Negative
- Merchant가 Pay 연동 로직을 구현해야 한다.
- Merchant/Pay 간 상태 복구 전략이 필요하다.

## Required Follow-up

위 Decision은 **어떤 값을 전달하는가**까지만 확정했다. 다음은 아직 **확정되지 않았고 팀이 결정해야 하는 항목**이다.

### 1. 각 값의 전송 위치 (미결정)

`checkoutId`, `credentialId`, `Idempotency-Key`를 한 블록에 나열했을 뿐 경로 / 헤더 / body 구분이 없다. 현재 구현자가 `Idempotency-Key`를 body 필드로 만들 여지가 있다.

UCP REST 기준으로는 다음 배치가 자연스럽다. **확정 필요.**

```text
POST /checkout-sessions/{checkoutId}/complete   ← checkoutId: 경로
Idempotency-Key: <uuid>                          ← 헤더
Request-Id / UCP-Agent                           ← 헤더 (UCP 필수, 도입 여부 미정)

body: payment.instrument.credential.credentialId
```

### 2. Complete 요청 body 스키마와 UCP의 차이 (미결정)

UCP REST의 Complete 요청 body와 현재 프로젝트 표현이 다르다.

| 항목 | UCP REST | 현재 프로젝트 |
|---|---|---|
| 결제수단 | `payment.instruments` **배열** | `payment.instrument` 단수 |
| 핸들러 지정 | `handler_id` 존재 | 없음 |
| credential 내용 | `credential: { type, token }` | `credential: { credentialId }` |

결정해야 할 것은 다음 중 하나다.

- **A.** UCP 스키마를 따르고 credential 내부만 프로젝트 Payment Handler로 채운다.
- **B.** 자체 스키마를 유지하고, 이탈 사실을 ADR-0010 준수 매트릭스에 명시한다.

MVP에서 Pay가 단일이라 `handler_id`를 생략할 수 있는지도 함께 정해야 한다.

### 3. 필드 명명 규약 (제안 단계, 미확정)

다음은 제안만 된 상태이며 확정 시 위 Decision의 필드 목록을 수정해야 한다.

| 제안 | 내용 | 영향 |
|---|---|---|
| Checkout 식별자 통일 | Core → Pay 승인 요청과 Merchant → Pay 결제 요청 모두 `checkoutHash` 사용. 형식 `sha256:<Base64URL 무패딩>`. Merchant가 확정한 값을 Core가 재계산하지 않고 그대로 전달하며, Pay는 승인 시점과 결제 실행 시점의 Hash 동일성을 검증한다. | ADR-0006, technical-flow §5·§7 |
| 금액 필드 통일 | Pay API 금액을 `amountMinor`로 통일(예: 87,000원 → `87000`), 통화는 `currency: "KRW"`. | technical-flow §7 |
| 오류 응답 통일 | `code` / `message` / `requestId` / `currentState` / `nextAction` / `retryable` 형식으로 서비스 간 통일. | 신규 |
| Checkout Mandate-like | MVP에서는 Complete 요청에 추가하지 않고, Pay Credential의 승인 범위에 Checkout ID·Hash·금액·통화를 묶어 검증. | ADR-0006, ADR-0010 |
| 재승인 시 Credential | 사용자가 재승인하면 Pay가 새 `credentialId`를 발급하고 Core는 새 Credential을 Complete 요청에 전달. | ADR-0008 Open Detail을 닫는다 |

## References

- [HTTP/REST Binding — Universal Commerce Protocol](http://ucp.dev/2026-04-08/specification/checkout-rest/) — Complete Checkout 엔드포인트, 요청 body 스키마, 필수 헤더
- [Checkout — Universal Commerce Protocol](http://ucp.dev/2026-04-08/specification/checkout/) — Checkout 연산과 상태 lifecycle

> UCP 참조는 `2026-04-08` 버전 기준으로 확인했다. 스펙 버전 pin은 ADR-0010의 Required Follow-up 항목이다.

## Related Decisions
- ADR-0001
- ADR-0004
- ADR-0006
- ADR-0008
- ADR-0009
- ADR-0010

## Revision History

| 날짜 | 변경 내용 |
|---|---|
| 2026-09-08 | 최초 기록 |
| 2026-09-09 | References 절 신설. Required Follow-up 절을 신설하여 전송 위치·UCP 스키마 차이·필드 명명 규약을 미결정 항목으로 명시. **결정 내용은 변경 없음.** |
| 2026-09-09 | Status를 `Accepted`에서 `Proposed — 논의 필요`로 변경. 팀 논의 전 초안임을 명시. |
