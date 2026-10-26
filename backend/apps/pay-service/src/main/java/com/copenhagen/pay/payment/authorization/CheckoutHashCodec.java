package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class CheckoutHashCodec {

    private static final String PREFIX = "sha256:";
    private static final Pattern INTERNAL_HEX_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern EXTERNAL_DIGEST_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private CheckoutHashCodec() {
    }

    public static String toInternalDigest(String checkoutHash) {
        if (checkoutHash == null || checkoutHash.isBlank()) {
            throw invalidHash();
        }
        if (!checkoutHash.startsWith(PREFIX)) {
            throw invalidHash();
        }

        String encodedDigest = checkoutHash.substring(PREFIX.length());
        if (!EXTERNAL_DIGEST_PATTERN.matcher(encodedDigest).matches()) {
            throw invalidHash();
        }
        try {
            byte[] digest = Base64.getUrlDecoder().decode(encodedDigest);
            if (digest.length != 32) {
                throw invalidHash();
            }
            return HexFormat.of().formatHex(digest);
        } catch (IllegalArgumentException exception) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "checkoutHash 형식이 올바르지 않습니다.",
                    null,
                    "CHECK_CHECKOUT_HASH",
                    exception);
        }
    }

    public static String toExternalHash(String internalDigest) {
        if (internalDigest == null || !INTERNAL_HEX_PATTERN.matcher(internalDigest).matches()) {
            throw invalidHash();
        }
        byte[] digest = HexFormat.of().parseHex(internalDigest);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    public static void validateMatchesJwt(String checkoutHash, String checkoutJwt) {
        String internalDigest = toInternalDigest(checkoutHash);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(checkoutJwt.getBytes(StandardCharsets.UTF_8));
            String calculatedDigest = HexFormat.of().formatHex(digest);
            if (!MessageDigest.isEqual(
                    internalDigest.getBytes(StandardCharsets.UTF_8),
                    calculatedDigest.getBytes(StandardCharsets.UTF_8))) {
                throw new PayException(
                        PayErrorCode.INVALID_REQUEST,
                        "checkoutHash가 checkoutJwt 원문과 일치하지 않습니다.",
                        null,
                        "CHECK_CHECKOUT_HASH");
            }
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static PayException invalidHash() {
        return new PayException(
                PayErrorCode.INVALID_REQUEST,
                "checkoutHash 형식이 올바르지 않습니다.",
                null,
                "CHECK_CHECKOUT_HASH");
    }
}
