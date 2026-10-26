# ADR-0003: 결제 직전 사용자 최종 승인(HITL)

- **Status:** Proposed — 논의 필요
- **Draft period:** 2026-09-03 ~ 2026-09-08
- **Recorded:** 2026-09-08
- **Last updated:** 2026-09-09
- **Note:** 설계 과정에서 정리한 초안이다. 팀 논의 전이므로 확정된 결정이 아니다.

## Context

Agent가 상품 탐색부터 Checkout 준비까지 자동화하더라도 최종 구매 조건은 가격, 배송비, 옵션, 수량 등에 따라 달라질 수 있다.

실제 Payment 직전에는 사용자가 어떤 Checkout에 대해 결제를 승인했는지 명확히 고정할 필요가 있다.

## Decision

기본 거래는 Human-in-the-Loop 방식으로 처리한다.

1. Merchant가 최종 Checkout을 확정한다.
2. 사용자는 Pay App에서 승인 대상 Checkout을 확인한다.
3. 사용자가 결제수단을 선택한다.
4. 사용자가 PIN 또는 생체인증을 수행한다.
5. Pay가 승인 성공 시 해당 Checkout 범위에 제한된 Payment Credential을 발급한다.

Agent, MCP, Core는 사용자를 대신하여 PIN/생체인증을 수행하거나 최종 결제 승인을 생성할 수 없다.

PIN 원문과 생체정보는 Pay 영역에서만 처리한다.

## Alternatives Considered

### A. Agent의 구매 요청 자체를 최종 승인으로 간주
사용자 경험은 단순하지만 최초 요청 이후 가격·배송 조건이 달라질 수 있다.

### B. Core에서 PIN/생체인증 처리
흐름은 단순해질 수 있으나 결제 인증 책임이 Core까지 확장된다.

## Consequences

### Positive
- 사용자가 실제 결제 직전 구매 조건을 확인한다.
- Agent 자동화와 결제 승인 권한을 분리할 수 있다.
- PIN/생체정보의 처리 범위를 Pay로 제한할 수 있다.

### Negative
- 완전 무인 구매는 지원하지 않는다.
- Pay App 승인 UX가 추가된다.

## Related Decisions
- ADR-0002
- ADR-0006
- ADR-0008

## Revision History

| 날짜 | 변경 내용 |
|---|---|
| 2026-09-08 | 최초 기록 |
| 2026-09-09 | Status를 `Accepted`에서 `Proposed — 논의 필요`로 변경. 팀 논의 전 초안임을 명시. **본문 내용은 변경 없음.** |
