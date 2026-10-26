package com.copenhagen.merchantmock.order;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 주문 조회 응답. 필드는 Core 확정본 4.10 의 다섯이고 그 이상 늘리지 않는다(docs/API_SPEC.md).
 *
 * <p>금액·통화와 주문 항목은 싣지 않는다. 실을 값은 DB 에 있지만 응답에 넣을지는 Core 가 정한다(11절).
 */
public final class OrderMessages {

    private OrderMessages() {
    }

    /**
     * @param fulfillment {@code status} 를 그대로 비춘다. 배송 상태 컬럼을 따로 두지 않는다(DESIGN.md 7.3절).
     */
    public record OrderView(
            String id,
            @JsonProperty("checkout_id") String checkoutId,
            String status,
            Fulfillment fulfillment,
            @JsonProperty("updated_at") String updatedAt) {

        public record Fulfillment(String status) {
        }
    }

    /**
     * 주문을 만든 직후 Complete 응답에 실리는 두 값(docs/API_SPEC.md 체크아웃 완료).
     *
     * <p>176 이 쓴다. 180 은 만들어 두기만 한다.
     */
    public record Created(String id, @JsonProperty("permalink_url") String permalinkUrl) {
    }
}
