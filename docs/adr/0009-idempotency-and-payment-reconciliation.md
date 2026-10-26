# ADR-0009: 상태 변경 요청의 멱등성과 Payment UNKNOWN 복구

- **Status:** Proposed — 논의 필요
- **Draft period:** 2026-09-03 ~ 2026-09-08
- **Recorded:** 2026-09-08
- **Last updated:** 2026-09-09
- **Note:** 설계 과정에서 정리한 초안이다. 팀 논의 전이므로 확정된 결정이 아니다.

## Context

네트워크 재시도, 사용자 중복 클릭, Agent/MCP Tool 재호출로 동일 요청이 여러 번 전달될 수 있다.

특히 Payment 요청이 중복 실행되면 실제 중복 결제가 발생할 수 있다.

또한 Payment 요청 후 Timeout이 발생했다고 해서 실제 PG 거래가 실패했다고 단정할 수 없다.

### 멱등성은 프로젝트 선택이 아니라 준수 사항이다

UCP REST 바인딩은 `Idempotency-Key`를 `Request-Id`, `UCP-Agent`와 함께 **필수 요청 헤더로 규정한다.** Complete Checkout의 재시도 규칙도 스펙에 직접 정의되어 있다.

- Get Checkout으로도 Business의 원 요청 처리 여부가 확인되지 않는 경우에만 동일 요청을 **같은 key로** 재전송할 수 있으며, 그 재시도에 **새 key를 사용해서는 안 된다.**
- Checkout이 `ready_for_complete`로 돌아온 뒤의 새로운 Complete 연산은 payload가 같더라도 **새 key를 사용해야 한다.**
- 서버는 key와 결과를 최소 24시간 보관하고, 중복 key에는 캐시된 결과를 반환하며, 같은 key에 다른 파라미터가 오면 `409 Conflict`로 응답한다.

따라서 ADR-0010이 UCP를 "부분 적용"으로 규정하고 있더라도, 멱등성 처리는 범위 축소 대상이 아니다.

시중 간편결제도 같은 패턴을 사용한다. 토스페이먼츠는 모든 POST API에 멱등키 헤더를 지원하며(UUID v4 권장, 최대 300자, 첫 요청일로부터 15일 유효), 첫 요청 처리 중 동일 요청이 들어오면 `409 IDEMPOTENT_REQUEST_PROCESSING`을 반환한다.

### Credential 소진과 Idempotency-Key는 보호 구간이 다르다

Credential은 결제 요청에서 원자적으로 `ISSUED → CONSUMED`가 되므로(ADR-0008) 멱등키가 중복으로 보일 수 있으나, 두 장치가 막는 구간은 다르다.

| 장치 | 보호 구간 | 막는 것 |
|---|---|---|
| Credential `ISSUED → CONSUMED` | Merchant → Pay | 실제 이중 청구 |
| `Idempotency-Key` | Core → Merchant | 재시도 시 원 결과를 잃는 것 |

멱등키가 없으면 Complete 응답 유실 후 재시도했을 때 Merchant가 Pay를 다시 호출하고, Credential이 이미 CONSUMED이므로 **실패로 응답한다.** 실제로는 결제가 성공했는데 Core가 실패로 인식하는 상태가 되므로, Credential 원자성만으로는 대체할 수 없다.

## Decision

외부 상태 변경 요청에는 Idempotency-Key를 사용한다.

- 동일한 Idempotency-Key의 동일 요청은 동일 거래로 처리한다.
- DB Unique Constraint와 상태 전이 검증을 함께 사용한다.
- Complete Checkout 및 Payment와 같은 금전 관련 상태 변경은 중복 실행되지 않도록 한다.

Payment 응답이 Timeout 또는 `UNKNOWN`인 경우 **새 Payment를 자동 실행하지 않는다.**

다음 순서로 복구한다.

```text
Payment Timeout / UNKNOWN
→ 기존 payment/transaction 상태 조회
→ Pay/PG reconcile
→ 최종 상태 확정
```

기존 거래가 실패한 것이 확인되기 전까지 새로운 결제를 생성하지 않는다.

## Alternatives Considered

### A. Timeout 시 동일 Payment 즉시 재호출
구현은 단순하지만 첫 요청이 실제 승인된 경우 중복 결제가 발생할 수 있다.

### B. UUID만 생성하고 별도 멱등성 처리 없음
요청 식별은 가능하지만 동일 요청의 중복 실행을 방지하지 못한다.

## Consequences

### Positive
- 중복 Checkout 완료 및 중복 결제 위험을 낮춘다.
- 네트워크 오류 상황에서도 거래 상태를 안전하게 복구할 수 있다.
- Agent/MCP 재호출에 견고해진다.

### Negative
- Idempotency 저장 및 Unique Constraint 설계가 필요하다.
- UNKNOWN 상태 및 reconcile 로직이 추가된다.

## Required Follow-up

다음은 아직 **확정되지 않았고 팀이 결정해야 하는 항목**이다.

| # | 결정할 것 | 비고 |
|---|---|---|
| 1 | 멱등키 보관 기간과 저장소 | UCP는 최소 24시간을 요구한다. 우리 보관 기간과 저장 위치(DB / Redis)를 정해야 한다. |
| 2 | 같은 key + 다른 파라미터 처리 | UCP는 `409 Conflict`를 요구한다. 서비스 간 공통 오류 형식과 어떻게 맞출지 미정이다. |
| 3 | 멱등키 생성 주체와 형식 | Core가 생성하는지, 형식을 UUID v4로 고정할지 미정이다. |
| 4 | Pay 결제 상태 집합 확정 | `READY`, `PROCESSING`, `SUCCESS`, `FAILED`, `UNKNOWN` 통일 여부는 제안 단계이며 미확정이다. |

## References

- [HTTP/REST Binding — Universal Commerce Protocol](http://ucp.dev/2026-04-08/specification/checkout-rest/) — 필수 요청 헤더, Complete Checkout 멱등성/재시도 규칙
- [Checkout — Universal Commerce Protocol](http://ucp.dev/2026-04-08/specification/checkout/) — Checkout 상태 lifecycle
- [멱등키 — 토스페이먼츠 개발자센터](https://docs.tosspayments.com/guides/using-api/idempotency-key) — 시중 간편결제의 멱등키 운영 기준
- [Idempotency-Key header — MDN](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Idempotency-Key) — HTTP 헤더 표준화 현황

> UCP 참조는 `2026-04-08` 버전 기준으로 확인했다. 스펙 버전 pin은 ADR-0010의 Required Follow-up 항목이며, 버전 확정 시 위 요구 수준을 재확인해야 한다.

## Related Decisions
- ADR-0007
- ADR-0008
- ADR-0010

## Revision History

| 날짜 | 변경 내용 |
|---|---|
| 2026-09-08 | 최초 기록 |
| 2026-09-09 | Context에 UCP REST 필수 헤더 근거, 시중 간편결제 사례, Credential 소진과의 보호 구간 구분 추가. References·Required Follow-up 절 신설. **결정 내용은 변경 없음.** |
| 2026-09-09 | Status를 `Accepted`에서 `Proposed — 논의 필요`로 변경. 팀 논의 전 초안임을 명시. |
