package com.copenhagen.core.payment;

import java.util.Base64;
import java.util.HexFormat;

/**
 * {@code merchant_checkouts.checkout_digest}(hex 64자, {@code CheckoutDigests})를 Pay가 API로 주고받는
 * 외부 표현으로 바꾼다. {@code CheckoutDigests}의 javadoc이 명시한 "Pay 연동 단계의 몫"이 이 클래스다
 * (이슈 410). Pay의 {@code CheckoutHashCodec.toInternalDigest}와 정확히 대응되는 인코딩이어야 한다 —
 * {@code "sha256:" + Base64URL(무패딩)}.
 */
public final class PayCheckoutHash {

    private static final String PREFIX = "sha256:";

    private PayCheckoutHash() {
    }

    public static String toExternal(String hexDigest) {
        byte[] digest = HexFormat.of().parseHex(hexDigest);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
