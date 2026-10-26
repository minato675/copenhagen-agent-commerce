package com.copenhagen.core.checkout;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * UCP Checkout({@code /ucp/v1/checkout-sessions})의 요청·응답 계약. {@code ucp-merchant-mock}의
 * {@code CheckoutMessages}와 1:1로 대응하며 표기는 snake_case다.
 *
 * <p>Core는 이 값을 재계산하지 않는다 — 가격·재고·배송비·총액의 authoritative source는 Merchant다(ADR-0004).
 */
public final class MerchantCheckoutMessages {

    private MerchantCheckoutMessages() {
    }

    /**
     * @param purchaseRequestId Merchant Authorization의 필수 claim이라 비어 있으면 merchant가
     *                          {@code incomplete}로 내린다. Core가 merchant를 부르기 전에
     *                          {@code purchase_requests}를 먼저 만드는 이유다.
     */
    public record CheckoutRequest(
            @JsonProperty("line_items") List<LineItemRequest> lineItems,
            Fulfillment fulfillment,
            @JsonProperty("purchase_request_id") String purchaseRequestId) {
    }

    /** {@code optionValueIds}는 merchant variant의 SKU다. merchant는 첫 값만 본다. */
    public record LineItemRequest(
            @JsonProperty("product_id") String productId,
            @JsonProperty("option_value_ids") List<String> optionValueIds,
            Integer quantity) {
    }

    public record Fulfillment(String type, Destination destination) {
    }

    /**
     * UCP 배송지는 네 필드뿐이다. 연락처·배송 요청사항은 자리가 없어 merchant에 전달하지 않는다
     * (Core 보관은 주문서 스냅샷의 몫이다).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Destination(
            String recipient,
            @JsonProperty("postal_code") String postalCode,
            @JsonProperty("address_line1") String addressLine1,
            @JsonProperty("address_line2") String addressLine2) {
    }

    /**
     * 생성·조회·수정이 같이 쓰는 최신 스냅샷.
     *
     * @param merchantAuthorization ES256 Compact JWS. {@code ready_for_complete}일 때만 실린다.
     *                              {@code merchant_checkouts.checkout_digest}의 입력이다
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
            @JsonProperty("merchant_authorization") String merchantAuthorization) {

        /** {@code totals}에서 한 종류의 금액을 꺼낸다. 없으면 0이다. */
        public long amountOf(String type) {
            if (totals == null) {
                return 0L;
            }
            return totals.stream()
                    .filter(total -> type.equals(total.type()))
                    .mapToLong(Total::amount)
                    .findFirst()
                    .orElse(0L);
        }
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

    /** {@code severity}가 {@code error}면 {@code ready_for_complete}로 갈 수 없다. {@code warning}은 알림이다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(String code, String severity, String message, String target) {

        public boolean blocking() {
            return "error".equals(severity);
        }
    }

    /**
     * Complete 요청(Merchant Mock {@code CheckoutMessages.CompleteRequest}와 동일 계약, 이슈 410).
     * {@code payment.instrument.credential.credentialId} 하나가 전부다.
     */
    public record CompleteRequest(Payment payment) {

        public static CompleteRequest of(String credentialId) {
            return new CompleteRequest(new Payment(new Instrument(new Credential(credentialId))));
        }

        public record Payment(Instrument instrument) {
        }

        public record Instrument(Credential credential) {
        }

        public record Credential(String credentialId) {
        }
    }

    /**
     * Complete 응답 세 갈래. {@code status}가 {@code completed}/{@code ready_for_complete}/
     * {@code complete_in_progress} 중 하나다.
     */
    public record CompleteResult(String id, String status, OrderRef order, PaymentRef payment) {
    }

    public record OrderRef(String id, @JsonProperty("permalink_url") String permalinkUrl) {
    }

    public record PaymentRef(String paymentId, String status, String errorCode) {
    }
}
