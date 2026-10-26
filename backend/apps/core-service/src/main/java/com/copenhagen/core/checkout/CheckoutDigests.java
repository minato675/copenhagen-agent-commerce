package com.copenhagen.core.checkout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 승인 대상 주문서를 고정하는 해시.
 *
 * <p>입력은 주문서 본문이 아니라 <b>Merchant Authorization(Compact JWS) 문자열</b>이다 — merchant의
 * Complete 경로와 결제 재조회가 모두 그 문자열로 digest를 만든다. 다른 것을 넣으면 Pay가 대조에 실패한다.
 *
 * <p>저장 표현은 hex 64자다({@code merchant_checkouts.checkout_digest CHAR(64)}). Pay가 API로 주고받는
 * 외부 표현 {@code "sha256:" + base64url}은 승인 요청을 보낼 때 변환하며, 그 변환은 Pay 연동 단계의 몫이다.
 */
public final class CheckoutDigests {

    private static final String ALGORITHM = "SHA-256";

    private CheckoutDigests() {
    }

    public static String of(String merchantAuthorizationJws) {
        if (merchantAuthorizationJws == null || merchantAuthorizationJws.isBlank()) {
            throw new IllegalArgumentException("digest를 계산할 Merchant Authorization이 비어 있습니다.");
        }
        try {
            byte[] hash = MessageDigest.getInstance(ALGORITHM)
                    .digest(merchantAuthorizationJws.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
