package com.copenhagen.merchantmock.paymenthandler;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pay 에 보내는 결제 청구 한 건. 본문 모양은 Pay 의 {@code MerchantPaymentController.PaymentRequest} 가
 * 정했다(DESIGN.md 19.5절).
 *
 * <p>멱등 키는 저장하지 않고 Credential 식별자로부터 결정적으로 계산한 UUID다. Credential 이 승인 한 건마다
 * 새로 발급되므로 타임아웃 재시도는 같은 UUID, 재승인은 새 UUID가 된다.
 */
public record PaymentRequest(
        String merchantId,
        String businessCheckoutId,
        String checkoutHash,
        long amountMinor,
        String currency,
        String credentialId) {

    private static final String IDEMPOTENCY_KEY_NAMESPACE = "copenhagen-merchant-payment:";

    public PaymentRequest {
        requireText(merchantId, "merchantId");
        requireText(businessCheckoutId, "businessCheckoutId");
        requireText(checkoutHash, "checkoutHash");
        requireText(currency, "currency");
        requireText(credentialId, "credentialId");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amountMinor 는 음수일 수 없다: " + amountMinor);
        }
    }

    public String idempotencyKey() {
        return UUID.nameUUIDFromBytes(
                (IDEMPOTENCY_KEY_NAMESPACE + credentialId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** JSON 본문. 키 순서는 Pay 컨트롤러의 record 순서와 같다. 읽는 사람이 대조하기 쉽게 하기 위한 것이다. */
    Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId);
        body.put("businessCheckoutId", businessCheckoutId);
        body.put("checkoutHash", checkoutHash);
        body.put("amountMinor", amountMinor);
        body.put("currency", currency);
        body.put("credentialId", credentialId);
        return body;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 이 비어 있다");
        }
    }
}
