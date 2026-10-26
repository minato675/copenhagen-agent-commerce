# MCP 장바구니 일괄 추가 계약 — Task 528

`add_cart_items_batch`는 `cart:write` 권한으로 최대 8개 상품을 추가한다.
기존 `apply_cart_changes`(수정·삭제)는 변경하지 않는다.

```json
{
  "cartVersion": 7,
  "idempotencyKey": "00000000-0000-4000-8000-000000000001",
  "items": [
    {"merchantId":"merchant-a","merchantProductId":"product-id","merchantVariantId":"variant-id","quantity":1,"selectionBasis":"USER_SELECTED"}
  ]
}
```

예시 식별자는 실제 상품 조회 결과로 교체한다. `selectionBasis`는 사용자가 확정한 경우
USER_SELECTED, 명시적으로 선택을 위임한 경우 USER_DELEGATED다. 모호한 옵션은 먼저 질문한다.
동일 판매처·상품·옵션을 중복 입력하면 쓰기 전에 거절한다. 합칠 수량은 호출 전에 확정한다.

- MCP 한 번 호출 안에서 기존 Core POST를 순차 실행한다. 전부 성공하거나 전부 실패하는 트랜잭션은 아니다.
- 응답의 appliedIndexes/unconfirmedIndexes는 원래 items의 0부터 시작하는 위치다.
- 첫 오류에서 후속 추가를 중단한다. 마지막 확인 버전은 lastConfirmedCartVersion이다.
- 응답 유실(UNKNOWN)은 실패 확정이 아니다. 원래 전체 items 순서·본문·cartVersion·키를 그대로 재시도한다.
- Core 저장 응답으로 이미 적용된 앞 항목을 복구하고 다음 버전을 얻는다. MCP 메모리 캐시에 의존하지 않는다.
- 같은 키로 본문을 수정·확장·재정렬하지 않는다. 새 작업은 사용자 의도와 최신 장바구니를 확인한 후 새 키로 실행한다.
- Core 멱등 기록 보존은 24시간이다. 이후 자동 재실행을 하지 않고 장바구니를 확인한다.
- 권한 오류는 호스트 OAuth 처리로 전달한다. 재연결 후에도 원래 키·본문을 유지한다.
- 반환 버전은 재시도 시 과거 스냅샷일 수 있다. 최종 상태는 display_cart로 한 번만 표시한다.
- 신규 도구 자체는 UI 템플릿을 연결하지 않는다. 실패 카드나 중간 상품별 카드를 만들지 않는다.

배포는 MCP 이미지 갱신 및 컨테이너 재생성이 필요하다. Core API/DB 스키마 변경은 없다.
호스트에서 도구 목록을 갱신한 뒤 add_cart_items_batch 노출과 cart:write 동의를 확인한다.
