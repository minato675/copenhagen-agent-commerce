# ADR-0005: Merchant Cart 단계 없이 Checkout 직접 생성

- **Status:** Proposed — 논의 필요
- **Draft period:** 2026-09-08
- **Recorded:** 2026-09-08
- **Last updated:** 2026-09-09
- **Note:** 기존 Tech Flow의 Merchant Cart 단계를 재검토한 초안이다. 팀 논의 전이므로 확정된 결정이 아니다.

## Context

초기 Tech Flow는 다음과 같은 구조였다.

```text
Core Cart
→ Merchant별 상품 분리
→ Merchant Cart 생성
→ merchantCartId 전달
→ Merchant Checkout 생성
```

그러나 프로젝트에서 사용자 장바구니는 이미 Core가 통합 Cart로 관리한다.

Merchant Cart를 별도로 생성하면 다음 문제가 생긴다.

- 동일한 장바구니 상태가 Core와 Merchant에 중복 존재
- Merchant Cart 생성 API 및 DTO가 추가됨
- `merchantCartId` 생명주기 관리가 필요함
- 실제 구매에 필요한 Checkout을 만들기 전에 불필요한 네트워크 단계가 추가됨

또한 현재 프로젝트에서 UCP의 Cart capability를 반드시 구현해야 할 이유가 없으며 Checkout을 `line_items` 기반으로 직접 생성하는 흐름을 사용할 수 있다.

## Decision

Merchant Cart 단계를 제거한다.

Core는 주문 준비 시 Core Cart의 상품을 Merchant별로 분리하여 각 Merchant에 직접 Checkout 생성을 요청한다.

```text
Core Cart
→ Merchant별 line_items 분리
→ Merchant Create Checkout
→ Merchant가 가격·재고·배송비·총액 확정
```

Create Checkout 요청은 개념적으로 다음 정보를 포함한다.

```text
line_items
shipping_address
```

`merchantCartId`는 사용하지 않는다.

Mock Merchant는 MVP에서 Cart capability 없이 Checkout 중심의 capability를 제공할 수 있다.

## Alternatives Considered

### A. Merchant Cart를 생성한 뒤 Checkout 생성
UCP의 Cart 구조를 더 많이 모방할 수 있으나 프로젝트에서는 상태와 API가 중복된다.

### B. Core Cart와 Merchant Cart를 상시 동기화
복잡도와 동기화 실패 가능성이 크게 증가한다.

## Consequences

### Positive
- Checkout 생성까지의 호출 수가 줄어든다.
- Core/Merchant 간 중복 Cart 상태를 제거한다.
- `merchantCartId` 관련 API, DTO, DB 설계가 사라진다.
- 프로젝트 요구에 필요한 최소 Commerce 흐름에 집중할 수 있다.

### Negative
- 향후 Merchant Cart 자체 기능이 필요해지면 capability를 별도로 추가해야 한다.
- 기존 문서와 API 명세에서 Merchant Cart 관련 내용을 제거해야 한다.

## Required Follow-up

다음 문서에서 Merchant Cart 관련 내용을 제거하거나 수정한다.

- Technical Flow
- API 명세
- DTO
- Merchant capability 정의
- 관련 ERD가 존재하는 경우 해당 테이블/필드

## Related Decisions
- ADR-0004
- ADR-0010

## Revision History

| 날짜 | 변경 내용 |
|---|---|
| 2026-09-08 | 최초 기록 |
| 2026-09-09 | Status를 `Accepted`에서 `Proposed — 논의 필요`로 변경. 팀 논의 전 초안임을 명시. **본문 내용은 변경 없음.** |
