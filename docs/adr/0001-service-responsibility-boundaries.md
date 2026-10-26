# ADR-0001: 시스템 책임 경계 분리

- **Status:** Proposed — 논의 필요
- **Draft period:** 2026-09-03 ~ 2026-09-08
- **Recorded:** 2026-09-08
- **Last updated:** 2026-09-09
- **Note:** 설계 과정에서 정리한 초안이다. 팀 논의 전이므로 확정된 결정이 아니다.

## Context

Agent, MCP Server, Core, Merchant, Pay가 하나의 구매 흐름에 참여하면서 각 시스템이 어떤 상태와 권한을 소유하는지 불명확하면 다음 문제가 발생한다.

- 동일 상태를 여러 서비스가 중복 관리
- 결제 권한이 Agent 또는 Core까지 확장
- Merchant와 Core가 가격·재고·Checkout 상태를 서로 다르게 판단
- 장애 시 어느 시스템의 상태를 기준으로 복구할지 불명확

따라서 서비스별 소유권과 책임 경계를 명확히 정의할 필요가 있다.

## Decision

각 시스템의 책임을 다음과 같이 분리한다.

### Agent
- 사용자의 자연어 요청을 해석한다.
- MCP Tool을 선택·호출한다.
- 쇼핑 및 결제 상태의 원본을 직접 소유하지 않는다.
- 결제 승인 또는 실제 결제를 직접 수행하지 않는다.

### MCP Server
- Agent의 Tool 요청을 인증·접수한다.
- Core API 호출의 진입점 역할을 한다.
- Merchant 또는 Pay를 직접 호출하지 않는다.
- 결제 권한을 소유하지 않는다.

### Core
- 전체 구매 흐름의 Orchestrator 역할을 한다.
- 사용자 통합 Cart를 관리한다.
- Merchant 연동 및 Checkout 흐름을 조정한다.
- 사용자 의도 및 정책을 검증한다.
- Pay 승인 요청을 생성·연결한다.
- 주문·결제 상태를 통합하여 Agent/App에 제공한다.
- 실제 Payment를 실행하지 않는다.

### Merchant
- Product, Price, Stock, Checkout, Order의 원본이다.
- 최신 가격·재고·배송 조건을 기준으로 Checkout을 확정한다.
- 승인된 결제 권한을 이용해 Pay에 Payment를 요청한다.
- Payment 성공 후 Order를 생성한다.

### Pay
- 결제수단, 사용자 인증, Payment Approval, Payment Credential, Payment의 원본이다.
- PIN/생체인증을 처리한다.
- Credential을 발급·검증한다.
- 실제 Payment를 실행한다.

## Alternatives Considered

### A. Core가 Commerce와 Payment 상태를 모두 소유
구현은 단순해질 수 있으나 Merchant/Pay와 원본 상태가 중복되고 책임 경계가 흐려진다.

### B. Agent가 Merchant/Pay를 직접 호출
호출 경로는 짧아지지만 결제 권한과 민감 상태가 Agent 영역까지 확대된다.

## Consequences

### Positive
- 각 상태의 Source of Truth가 명확해진다.
- 장애 및 상태 불일치 시 기준 시스템을 판단하기 쉽다.
- Agent와 결제 권한을 분리할 수 있다.
- API/DTO/DB 설계의 책임 기준이 명확해진다.

### Negative
- 서비스 간 호출과 상태 연결 로직이 증가한다.
- Core의 orchestration 로직이 필요하다.

## Related Decisions
- ADR-0002
- ADR-0003
- ADR-0004
- ADR-0007

## Revision History

| 날짜 | 변경 내용 |
|---|---|
| 2026-09-08 | 최초 기록 |
| 2026-09-09 | Status를 `Accepted`에서 `Proposed — 논의 필요`로 변경. 팀 논의 전 초안임을 명시. **본문 내용은 변경 없음.** |
