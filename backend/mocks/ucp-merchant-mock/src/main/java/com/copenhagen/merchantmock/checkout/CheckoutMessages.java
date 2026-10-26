package com.copenhagen.merchantmock.checkout;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 체크아웃 엔드포인트 다섯의 요청·응답. 필드 표기는 Core 확정본을 따라 snake_case 다.
 *
 * <p>응답 {@link Checkout} 은 그대로 직렬화해 checkout_sessions.canonical_checkout 에 넣는다. 서명 대상은
 * 이 본문이 아니라 claim set 이라(DESIGN.md 6.2절) JCS 정규화는 하지 않는다. 값이 없으면 키를 생략한다(17절).
 */
public final class CheckoutMessages {

    private CheckoutMessages() {
    }

    /**
     * @param purchaseRequestId Core 의 PurchaseRequest id. Merchant Authorization 의 필수 claim 이라 없으면
     *                          {@code incomplete} 다(DESIGN.md 6.4절). PUT 은 전체 교체라 PUT 에도 실어야 한다
     */
    public record CheckoutRequest(
            @JsonProperty("line_items") List<LineItemRequest> lineItems,
            Fulfillment fulfillment,
            @JsonProperty("purchase_request_id") String purchaseRequestId) {

        /** 172 까지의 두 필드 생성자. 테스트가 쓴다. */
        public CheckoutRequest(List<LineItemRequest> lineItems, Fulfillment fulfillment) {
            this(lineItems, fulfillment, null);
        }

        public record LineItemRequest(
                @JsonProperty("product_id") String productId,
                @JsonProperty("option_value_ids") List<String> optionValueIds,
                Integer quantity) {
        }

        public record Fulfillment(String type, Destination destination) {
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Destination(
                String recipient,
                @JsonProperty("postal_code") String postalCode,
                @JsonProperty("address_line1") String addressLine1,
                @JsonProperty("address_line2") String addressLine2) {
        }
    }

    /**
     * 생성·조회·수정이 같이 쓰는 최신 스냅샷.
     *
     * @param merchantAuthorization ES256 Compact JWS. {@code ready_for_complete} 일 때만 있다(DESIGN.md 6.4절).
     *                              Core 가 Pay {@code checkoutJwt} 에 그대로 넣는 값이다
     * @param order                 {@code completed} 조회에만 얹는다. 저장된 Complete 응답에서 온다(7.1절)
     * @param payment               {@code completed}·{@code complete_in_progress} 조회에 얹는다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Checkout(
            String id,
            String status,
            String currency,
            @JsonProperty("line_items") List<LineItem> lineItems,
            FulfillmentResult fulfillment,
            List<Total> totals,
            List<Message> messages,
            @JsonProperty("expires_at") String expiresAt,
            @JsonProperty("merchant_authorization") String merchantAuthorization,
            OrderRef order,
            PaymentRef payment) {

        /** 판정 직후의 여덟 필드 생성자. 서명과 완료 결과는 뒤에 얹는다. */
        public Checkout(String id, String status, String currency, List<LineItem> lineItems,
                FulfillmentResult fulfillment, List<Total> totals, List<Message> messages, String expiresAt) {
            this(id, status, currency, lineItems, fulfillment, totals, messages, expiresAt, null, null, null);
        }

        /**
         * 저장된 스냅샷은 그대로 두고 나가는 응답의 상태만 바꾼다(DESIGN.md 7.1절).
         *
         * <p>취소·만료·완료로 상태가 달라져도 저장된 바이트를 다시 쓰지 않고 이 자리에서만 덮는다.
         */
        Checkout withStatus(String newStatus) {
            return new Checkout(id, newStatus, currency, lineItems, fulfillment, totals, messages, expiresAt,
                    merchantAuthorization, order, payment);
        }

        /** 서명을 얹는다. 스냅샷에 저장되기 전에 한 번 부른다. */
        Checkout withMerchantAuthorization(String jws) {
            return new Checkout(id, status, currency, lineItems, fulfillment, totals, messages, expiresAt,
                    jws, order, payment);
        }

        /** 완료 결과를 얹는다. 조회 응답을 만들 때만 부르고 스냅샷에는 넣지 않는다. */
        Checkout withCompletion(OrderRef newOrder, PaymentRef newPayment) {
            return new Checkout(id, status, currency, lineItems, fulfillment, totals, messages, expiresAt,
                    merchantAuthorization, newOrder, newPayment);
        }

        /** totals 의 total. 없으면 0. 서명 claim 과 결제 청구가 이 값을 쓴다. */
        public long totalAmount() {
            return totals == null ? 0L : totals.stream()
                    .filter(t -> "total".equals(t.type()))
                    .mapToLong(Total::amount)
                    .findFirst()
                    .orElse(0L);
        }
    }

    /**
     * Complete 요청(Core 확정본 4.8). {@code payment.instrument.credential.credentialId} 하나가 전부다.
     * 금액·통화는 오지 않는다. 내 체크아웃에서 읽는다(DESIGN.md 6.5절).
     */
    public record CompleteRequest(Payment payment) {

        public record Payment(Instrument instrument) {
        }

        public record Instrument(Credential credential) {
        }

        public record Credential(String credentialId) {
        }

        /** 중간 객체가 하나라도 비어 있으면 null. 검증은 서비스가 한다. */
        public String credentialId() {
            if (payment == null || payment.instrument() == null || payment.instrument().credential() == null) {
                return null;
            }
            return payment.instrument().credential().credentialId();
        }
    }

    /**
     * Complete 응답. 세 갈래 다 이 모양이고 {@code status} 가 갈래를 말한다(DESIGN.md 7.1절).
     * SUCCESS 응답만 {@code completion_response_json} 에 저장돼 재생된다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompleteResult(String id, String status, OrderRef order, PaymentRef payment) {
    }

    /** Complete 성공 응답의 주문 둘(Core 확정본 4.8). */
    public record OrderRef(String id, @JsonProperty("permalink_url") String permalinkUrl) {
    }

    /**
     * Pay 결과 중계. {@code paymentId}·{@code status} 는 Core 확정본 4.8, {@code errorCode} 는 FAILED 일 때
     * Pay 가 준 사유다(8.5절). 없는 값은 키를 생략한다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaymentRef(String paymentId, String status, String errorCode) {
    }

    /**
     * 취소 응답. 전체 체크아웃이 아니라 두 필드뿐이다(docs/API_SPEC.md).
     *
     * <p>취소에 금액과 항목을 다시 실을 이유가 없다.
     */
    public record CancelResult(String id, String status) {
    }

    public record LineItem(String id, Item item, int quantity) {
    }

    public record Item(String id, String title, long price) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FulfillmentResult(String type, String status, Long amount) {
    }

    public record Total(String type, long amount) {
    }

    /**
     * incomplete 사유. 형태는 DESIGN.md 7.1절의 {code, severity, message, target}.
     * severity 가 error 면 ready_for_complete 로 갈 수 없다. warning 은 알림만이다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(String code, String severity, String message, String target) {

        static Message error(String code, String message, String target) {
            return new Message(code, "error", message, target);
        }

        static Message warning(String code, String message, String target) {
            return new Message(code, "warning", message, target);
        }

        boolean blocking() {
            return "error".equals(severity);
        }
    }
}
