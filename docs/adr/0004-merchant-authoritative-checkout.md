# ADR-0004: Merchant Checkout을 Commerce 거래 기준으로 사용

- **Status:** Proposed — 논의 필요
- **Draft period:** 2026-09-03 ~ 2026-09-08
- **Recorded:** 2026-09-08
- **Last updated:** 2026-09-09
- **Note:** 설계 과정에서 정리한 초안이다. 팀 논의 전이므로 확정된 결정이 아니다.

## Context

Core Cart에 보이는 상품 정보는 사용자가 탐색·선택한 상태이며 실제 구매 시점의 가격, 재고, 배송 가능 여부, 배송비가 달라질 수 있다.

Core가 Merchant의 Commerce 데이터를 복제하여 동일한 검증을 반복하면 두 시스템 사이에 상태 불일치가 발생할 수 있다.

## Decision

구매 직전 Commerce 상태의 최종 기준은 Merchant Checkout으로 한다.

Merchant는 Checkout 생성/갱신 시 자신의 최신 데이터를 기준으로 다음을 확정한다.

- 상품 및 옵션
- 수량
- 가격
- 재고
- 배송 가능 여부
- 배송비
- 총액
- 통화

Core는 Merchant의 Commerce 원본을 별도로 재계산하여 대체하지 않는다.

Core의 검증 책임은 다음에 집중한다.

- 사용자의 요청/의도와 Checkout의 일치 여부
- 프로젝트 정책 위반 여부
- 승인 가능한 상태인지 여부

## Alternatives Considered

### A. Core가 가격·재고·배송비를 다시 계산
검증이 강화되는 것처럼 보이지만 Merchant와 동일한 Commerce 규칙을 Core가 중복 구현해야 한다.

### B. Agent가 탐색 당시 가격을 구매 가격으로 사용
구현은 단순하지만 최신 가격·재고를 보장할 수 없다.

## Consequences

### Positive
- Commerce 상태의 Source of Truth가 하나로 유지된다.
- Merchant와 Core 간 중복 비즈니스 로직을 줄인다.
- 결제 금액과 실제 Merchant 주문 금액의 불일치 가능성을 낮춘다.

### Negative
- Merchant Checkout API의 신뢰성과 가용성에 의존한다.
- Core는 Merchant별 Checkout 응답을 공통 모델로 연결해야 한다.

## Related Decisions
- ADR-0001
- ADR-0005
- ADR-0006
- ADR-0007

## Revision History

| 날짜 | 변경 내용 |
|---|---|
| 2026-09-08 | 최초 기록 |
| 2026-09-09 | Status를 `Accepted`에서 `Proposed — 논의 필요`로 변경. 팀 논의 전 초안임을 명시. **본문 내용은 변경 없음.** |
