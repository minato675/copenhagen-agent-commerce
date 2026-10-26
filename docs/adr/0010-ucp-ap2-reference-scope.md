# ADR-0010: UCP/AP2는 설계 기준으로 부분 적용하고 완전 호환을 목표로 하지 않음

- **Status:** Proposed — 논의 필요
- **Draft period:** 2026-09-03 ~ 2026-09-08
- **Recorded:** 2026-09-08
- **Last updated:** 2026-09-09
- **Note:** 설계 과정에서 정리한 초안이다. 팀 논의 전이므로 확정된 결정이 아니다.

## Context

프로젝트는 Agentic Commerce와 안전한 결제 승인을 구현하기 위해 UCP 및 AP2의 개념을 참고한다.

그러나 교육 프로젝트의 MVP에서 표준 전체를 구현하거나 공식 호환성을 주장하면 구현 범위가 과도하게 커지고, 실제 구현하지 않은 capability까지 지원하는 것처럼 보일 수 있다.

## Decision

UCP/AP2를 **설계 참고 기준**으로 사용하되 완전한 상용 호환 또는 인증을 목표로 하지 않는다.

### UCP에서 참고하는 개념
- Merchant Profile / Capability 확인
- API 기반 Merchant 연동
- Catalog / Checkout / Complete / Order 흐름
- Merchant가 Commerce 상태의 원본이라는 구조

Cart는 UCP에서 사용할 수 있는 capability이지만 프로젝트의 최신 MVP 흐름에서는 필수로 사용하지 않는다.

따라서 Merchant Cart를 제거하고 `line_items` 기반 Create Checkout을 사용할 수 있다.

### AP2에서 참고하는 개념
- 사용자 최종 승인
- 승인 대상 거래의 명확한 binding
- Mandate-like 승인 증빙
- 승인 범위에 제한된 Payment Credential
- Agent와 실제 Payment 권한의 분리

프로젝트 문서에서는 `AP2-like`, `AP2-inspired`, `UCP 참고` 수준으로 표현하며, 실제로 구현하지 않은 공식 capability나 완전 호환성을 광고하지 않는다.

## Alternatives Considered

### A. UCP/AP2 전체 구조를 그대로 구현
표준과의 유사성은 높아지지만 MVP 요구와 무관한 API/상태/보안 구현이 크게 증가한다.

### B. 표준을 전혀 참고하지 않고 독자 구조 설계
구현 자유도는 높지만 Agentic Commerce/Payment 구조에 대한 외부 설계 근거가 약해진다.

## Consequences

### Positive
- 표준의 책임 분리와 승인 모델을 활용하면서 MVP 복잡도를 통제할 수 있다.
- 프로젝트에 불필요한 Merchant Cart 등 선택 기능을 제거할 수 있다.
- 발표 및 면접에서 설계 근거를 설명할 수 있다.

### Negative
- 공식 UCP/AP2 호환 서비스로 표현할 수 없다.
- 향후 실제 표준 연동 시 추가 구현이 필요하다.

## Required Follow-up

"부분 적용"이라는 결정만으로는 **스펙이 강제하는 항목과 우리가 선택한 항목이 구분되지 않는다.** 다음은 아직 **확정되지 않았고 팀이 결정해야 하는 항목**이다.

### 1. UCP 스펙 버전 pin (해결)

Merchant Mock의 Business Profile 구현과 맞춰 `2026-08-25`로 고정한다. Core Platform Profile,
Merchant Profile Discovery, Capability Negotiation은 이 버전이 정확히 일치할 때만 활성화한다.
기존 `2026-01-23`·`2026-04-08` 참고 링크는 설계 비교용이며 구현 계약의 기준이 아니다.

### 2. 준수 / 이탈 매트릭스 작성 (미착수)

참고 개념을 흐름 수준("Catalog / Checkout / Complete / Order")으로만 나열하고 있어, 스키마·헤더 단위로 어디를 따르고 어디를 벗어났는지 추적되지 않는다.

현재까지 확인된 항목은 다음과 같다.

| 항목 | UCP 요구 수준 | 현재 프로젝트 | 상태 |
|---|---|---|---|
| `Idempotency-Key` 헤더 | 필수 | 사용 (ADR-0009) | 준수 |
| `Request-Id` 헤더 | 필수 | 미도입 | **이탈 — 결정 필요** |
| `UCP-Agent` 헤더 | 필수 | Core가 모든 Merchant 호출에 Platform Profile URI를 전달 | 구현 예정 (409) |
| Complete body `payment.instruments` | 배열 | `instrument` 단수 | **이탈 — 결정 필요 (ADR-0007)** |
| `handler_id` | 존재 | 없음 | **이탈 — 결정 필요 (ADR-0007)** |
| `credential` 내용 | `{ type, token }` | `{ credentialId }` | **이탈 — 결정 필요 (ADR-0007)** |
| Cart capability | 선택 | 미사용 | 의도적 이탈 (ADR-0005) |

이 표는 계속 갱신되는 대조표이므로 ADR이 아니라 `docs/ucp/`에 두는 것이 맞다. **`docs/ucp/` 디렉터리는 `docs/README.md` 인덱스에 등재되어 있으나 아직 존재하지 않는다.** 생성 여부와 담당을 정해야 한다.

### 3. AP2 Mandate 적용 범위 (제안 단계)

MVP에서는 Checkout Mandate-like를 Complete 요청에 추가하지 않고, Pay Credential의 승인 범위에 Checkout ID·Hash·금액·통화를 묶어 검증하는 방향이 제안되었다. 미확정이며 ADR-0006과 함께 결정해야 한다.

## References

- [Universal Commerce Protocol](https://ucp.dev/) — 스펙 홈, 버전 목록
- [Overview — UCP 2026-04-08](http://ucp.dev/2026-04-08/specification/overview/)
- [Checkout — UCP 2026-04-08](http://ucp.dev/2026-04-08/specification/checkout/)
- [HTTP/REST Binding — UCP 2026-04-08](http://ucp.dev/2026-04-08/specification/checkout-rest/)
- [HTTP/REST Binding — UCP 2026-01-23](http://ucp.dev/2026-01-23/specification/checkout-rest/) — 버전 비교용

## Related Decisions
- ADR-0002
- ADR-0003
- ADR-0005
- ADR-0006
- ADR-0007
- ADR-0009

## Revision History

| 날짜 | 변경 내용 |
|---|---|
| 2026-09-08 | 최초 기록 |
| 2026-09-09 | References 절 신설. Required Follow-up 절을 신설하여 스펙 버전 pin, 준수/이탈 매트릭스, AP2 Mandate 적용 범위를 미결정 항목으로 명시. **결정 내용은 변경 없음.** |
| 2026-09-09 | Status를 `Accepted`에서 `Proposed — 논의 필요`로 변경. 팀 논의 전 초안임을 명시. |
| 2026-09-18 | Merchant Mock 구현과 맞춰 UCP version을 `2026-08-25`로 pin. Core Profile의 Order Webhook 경로를 `/webhooks/v1/ucp/orders`로 확정. |
