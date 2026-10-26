package com.copenhagen.merchantmock.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * checkoutDigest 계산과 대조(DESIGN.md 6.3절).
 *
 * <p>{@code "sha256:" + base64url_nopad(SHA-256(canonical_checkout))} 이다.
 * 저장하지 않고 필요할 때 계산한다(5.4절). 입력은 저장된 정규화 문자열 그대로이고
 * 재정규화하지 않는다(불변 조건 5번).
 *
 * <p>접두어는 설계서 11.1 공식에는 없지만 11.2·11.4·11.5 의 Mandate·Credential·Receipt
 * 예시가 전부 {@code sha256:...} 이라 붙이는 쪽이 설계 의도로 판단했다.
 */
public final class CheckoutDigest {

    public static final String PREFIX = "sha256:";

    private static final String ALGORITHM = "SHA-256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private CheckoutDigest() {
    }

    /** 정규화된 Checkout 문자열의 다이제스트. */
    public static String of(String canonicalCheckout) {
        if (canonicalCheckout == null || canonicalCheckout.isEmpty()) {
            throw new IllegalArgumentException("다이제스트를 계산할 정규화 문자열이 비어 있다");
        }
        byte[] hash = sha256(canonicalCheckout.getBytes(StandardCharsets.UTF_8));
        return PREFIX + ENCODER.encodeToString(hash);
    }

    /**
     * 받은 다이제스트가 내 것과 같은지 본다.
     *
     * <p>{@code equals} 가 아니라 {@link MessageDigest#isEqual} 로 비교한다. 길이가 같으면
     * 내용과 무관하게 같은 시간이 걸려 어디까지 맞았는지가 시간으로 새지 않는다.
     */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance(ALGORITHM).digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", exception);
        }
    }
}
