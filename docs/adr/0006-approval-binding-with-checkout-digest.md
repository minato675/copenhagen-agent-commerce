# ADR-0006: 별도 Order Snapshot 대신 Approval에 승인 대상 고정

- **Status:** Proposed — 논의 필요
- **Draft period:** 2026-09-08
- **Recorded:** 2026-09-08
- **Last updated:** 2026-09-09
- **Note:** 기존 Tech Flow의 별도 Order Snapshot 단계를 재검토한 초안이다. 팀 논의 전이므로 확정된 결정이 아니다.

## Context

초기 설계에서는 Core가 Merchant Checkout을 받은 뒤 별도의 Order Snapshot을 생성하고, 그 Snapshot을 기준으로 Pay Approval을 생성했다.

그러나 Merchant Checkout 자체가 이미 구매 직전 Commerce 상태의 원본이며, 동일한 내용을 Core Snapshot에 다시 복제하면 승인 상태를 고정하기 위한 데이터가 중복된다.

## Decision

별도의 Core Order Snapshot을 승인 흐름의 필수 Artifact로 두지 않는다.

대신 Payment Approval이 승인 대상을 불변적으로 고정한다.

Approval은 개념적으로 다음 정보에 binding 된다.

- Merchant
- Checkout ID
- Checkout payload 또는 digest/hash
- Amount
- Currency

사용자가 승인한 이후 Checkout의 결제 의미에 영향을 주는 내용이 변경되면 기존 Approval과 Credential을 재사용하지 않는다.

대상 변경 예:

- 상품
- 옵션
- 수량
- 가격
- 배송비
- 총액

변경이 필요한 경우 Merchant Checkout을 **Update**한 뒤 새로운 Approval을 생성하고 사용자가 다시 승인한다.

```text
Update Checkout
→ 새 Approval
→ 사용자 재승인
→ 새 Credential
```

## Alternatives Considered

### A. Core에 별도 Order Snapshot 저장
승인 이력을 독립적으로 조회하기 쉽지만 Checkout과 중복된 거래 데이터를 유지해야 한다.

### B. Checkout ID만 Approval에 저장
구현은 단순하지만 동일 Checkout ID의 내용이 변경될 경우 승인 대상이 모호해질 수 있다.

## Consequences

### Positive
- 승인 대상 고정과 Payment 권한을 하나의 Approval 모델로 연결한다.
- 중복 Snapshot 모델과 테이블을 줄일 수 있다.
- 사용자가 승인한 거래 내용을 digest/hash로 검증할 수 있다.

### Negative
- Approval에 immutable payload/digest 관리가 필요하다.
- Checkout 변경 시 승인 무효화 규칙을 명확히 구현해야 한다.

## Required Follow-up

- 기존 `Order Snapshot 생성` 단계 제거
- Approval DTO/DB에 Checkout binding 정보 반영
- 승인 이후 Checkout 변경 시 Approval/Credential 무효화
- Checkout 변경 흐름은 재생성이 아니라 Update를 기본으로 사용

## Related Decisions
- ADR-0003
- ADR-0004
- ADR-0007

## Revision History

| 날짜 | 변경 내용 |
|---|---|
| 2026-09-08 | 최초 기록 |
| 2026-09-09 | Status를 `Accepted`에서 `Proposed — 논의 필요`로 변경. 팀 논의 전 초안임을 명시. **본문 내용은 변경 없음.** |
