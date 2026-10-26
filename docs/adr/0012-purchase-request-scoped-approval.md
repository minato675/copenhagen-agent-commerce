# ADR-0012: 결제 승인 단위는 구매 요청, Payment Credential 단위는 Checkout

- **Status:** Accepted
- **Recorded:** 2026-09-21
- **Last updated:** 2026-09-22
- **Issue:** S15P21A207-486 (Core), S15P21A207-487 (Pay)
- **관련:** ADR-0003(사용자 승인), ADR-0005(Merchant Cart 없이 Checkout 생성), ADR-0008(Payment Credential 소유·보관)

## Context

장바구니에 판매처가 여러 곳 담기면 Core는 Checkout을 판매처마다 만든다(ADR-0005). 여기까지는 의도한
동작이다. 문제는 그 다음이었다. Pay 승인 세션이 Checkout 단위라 판매처가 N곳이면 다음이 전부 N번씩
발생했다.

- 승인 세션 N건 (`authorization_sessions`가 `merchant_account_id`·`external_checkout_id`를 직접 보유)
- `APPROVAL_REQUESTED` 푸시 N건 (이벤트 ID가 `approvalId` 기반)
- 생체 challenge·서명 N회 (challenge가 세션 단위)
- 승인 확정 호출 N회 (`/payment-approvals/{approvalId}/approve`는 단건만 존재)

판매처 세 곳을 한 번에 사면 지문을 세 번 찍어야 했다. `기능요구사항.md`의 "다중 판매자·분할 결제는 별도
정책 필요" 항목이 비어 있던 자리가 이 문제다.

## Decision

**승인 세션의 단위를 구매 요청으로 올린다. Payment Credential은 지금처럼 Checkout 단위로 둔다.**

```text
[변경 전]  승인세션 1 ─ credential 1 ─ checkout 1 ─ merchant 1   → 인증 N회

[변경 후]  승인세션 1 ─┬ item A ─ credential A ─ checkout A      → 인증 1회
                       └ item B ─ credential B ─ checkout B
```

근거는 Pay가 Merchant 결제 요청에서 수행하는 검증(merchant 일치·checkout 일치·금액 일치)이 **credential에
걸려 있지 세션에 걸려 있지 않다**는 점이다. `payment_credential_tokens`가 `merchant_id`·
`external_checkout_id`·`checkout_digest`·`amount_minor`를 자체 보유하므로, 그 위의 "누가 인증했는가"
계층만 묶으면 Merchant 계약은 그대로 성립한다.

함께 확정한 정책은 다음과 같다.

| 항목 | 결정 |
|---|---|
| 부분 실패 | `PARTIALLY_COMPLETED` 유지. 인증 1회 ≠ 결제 1건 |
| 결제수단 | 전 판매처 공통 1개 (세션 단위) |
| 통화 혼합 | 승인 생성 거절 |
| 재승인 | 승인 전체 무효화 후 1회 재인증 |
| 승인 TTL | `max( min(판매처별 checkout 만료), 기본 TTL )` |
| 조립 | Core가 Checkout 전부 확정 후 배치 1회 전송 |
| 승인 후 세션 상태 | 세션은 `APPROVED`에서 종료. 결제 결과는 item이 추적 |

### 승인 후 세션 상태

결제 결과는 **item**이 받는다. 세션은 사용자 인증까지만 책임지고 `APPROVED`에서 종료한다.

세션 1건에 결제가 N건 붙으므로 그대로 두면 깨진다. `PaymentLifecycleService`가
`completeAfterPaymentSuccess()`로 세션을 `COMPLETED`(종료 상태)로 보내는데, 먼저 끝난 결제 하나가
세션을 닫으면 나머지 판매처의 결과를 기록할 수 없다. 판매처가 2곳 이상이면 부분 실패 여부와 무관하게
항상 발생한다.

A몰 성공·B몰 실패를 세션 단일 상태로 표현할 수도 없다. 구매 전체 집계는 Core
`PurchaseWorkflowService.aggregate()`가 이미 담당하므로 Pay가 중복으로 보유할 이유가 없다
(`technical-flow.md` §1의 원본 구분과도 부합한다).

| 계층 | 상태 |
|---|---|
| `authorization_sessions` | `PAYMENT_METHOD_REQUIRED` → `PENDING` → `APPROVED` 에서 종료 |
| `authorization_session_items` | `PENDING` → `COMPLETED` / `INVALIDATED` / `EXPIRED` |

Core 폴링은 `APPROVED`와 `COMPLETED`를 동일하게 처리하므로 이 변경에 영향받지 않는다. 세션 status
CHECK에서 `COMPLETED`는 과거 행 때문에 제거하지 않았다.

## Alternatives considered

### 1. 기존 세션 위에 `approval_groups` 테이블을 얹는다

Checkout 단위 세션을 그대로 두고 그룹이 여러 세션을 묶는 안이다. 하위 4개 테이블이 전부
`UNIQUE(authorization_session_id)`로 1:1 고정이라 기존 스키마를 건드리지 않는다는 장점이 있었다.

**기각 사유는 상태 이중화다.** 그룹과 세션에 상태가 각각 존재하면 어긋난다. A몰 성공·B몰 실패일 때
`completeAfterPaymentSuccess()`는 세션을 `COMPLETED`로, `invalidateAfterDefinitivePaymentFailure()`는
`INVALIDATED`로 보내는데, 그룹이 가질 단일 상태가 없다. 여기에 더해 challenge FK 변경, 알림 이벤트 체계
변경, 결제수단 선택의 멤버 전파가 각각 추가 작업이었다. 세션 통합은 이 넷이 전부 자동으로 해결된다.

일회성 마이그레이션 위험을 감수하고 영구적 런타임 복잡도를 피하는 쪽을 택했다.

### 2. Core가 Checkout을 개별 전송하고 Pay가 개수 충족을 판단한다

Pay가 구매 요청에 속한 Checkout이 다 모였는지 세어 결제를 진행하는 안이다. 기각 사유는 다섯 가지다.

- **대기할 대상이 없다.** Core의 Checkout 생성은 이미 all-or-nothing이다. 하나라도 실패하면 구매 요청
  전체가 반려되므로, Pay가 기다릴 "아직 안 온 Checkout" 상태가 Core에 존재하지 않는다.
- **검증의 실질이 없다.** 기대 개수는 Core만 아는 값이라, Core가 알려준 숫자를 Core가 채웠는지 세는
  구조가 된다.
- **미완성 세션 수명주기가 Pay로 복제된다.** Core는 이미 재시도·만료로 이 문제를 처리하고 있다.
- **사용자가 본 총액이 흔들린다.** 조립 중 세션이 노출되면 상세를 연 뒤 총액이 증가할 수 있고, 생체
  서명이 걸려야 할 "확인한 총액" 불변조건이 깨진다.
- **멱등 계약이 깨진다.** Pay 멱등성은 "같은 키 = 같은 본문"을 `request_hash`로 보장하는데, 증분 추가는
  본문이 계속 바뀐다.

본문 크기가 문제가 되면 `DRAFT` → `items` → `seal` 2단계로 확장할 수 있다. 봉인 선언을 Core가 하므로
책임 경계는 유지된다. 판매처 3~4곳 규모인 현재는 과설계로 판단했다.

## Consequences

- 사용자는 판매처가 몇 곳이든 푸시 1건·주문 확인 1회·인증 1회로 결제한다.
- Merchant 계약(Complete Checkout, Merchant → Pay Payment)과 Order Webhook은 바뀌지 않는다.
- Complete Checkout과 Payment는 그대로 Checkout마다 일어나므로 **부분 완료는 사라지지 않는다.**
  `PARTIALLY_COMPLETED`는 정상 상태로 남는다.
- 승인 생성에 부분 성공이 없어진다. 실패하면 Checkout 전부가 `CREATING`으로 남고 구매 요청 단위로
  재시도한다.
- Pay 마이그레이션은 컬럼 제거 5개·유니크 제약 완화 6건·신규 테이블 1개·백필을 포함해 되돌리기 어렵다.
  진행 중 세션을 드레인한 뒤 배포한다.

## Revision History

| 날짜 | 변경 내용 |
|---|---|
| 2026-09-21 | 최초 기록 (S15P21A207-486) |
| 2026-09-22 | 확정 정책에 "승인 후 세션 상태" 추가. Pay 구현(S15P21A207-487) 과정에서 `PaymentLifecycleService`가 결제 결과로 세션을 종료시키던 경로를 발견해, 결제 결과를 item이 받도록 정리한 내용을 기록했다. 결정 자체는 바뀌지 않았고 비어 있던 항목을 채운 것이다. 함께 Consequences의 컬럼 제거 개수를 4개에서 5개로 정정했다(`display_snapshot`이 item으로 이동). |
