package com.copenhagen.pay.payment.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copenhagen.pay.common.error.PayException;
import org.junit.jupiter.api.Test;

class CheckoutHashCodecTest {

    private static final String JWT = "header.payload.sig";
    private static final String HASH =
            "sha256:fNGk-G76ixU7me4BhhMZePSVVyfEbhodaSiBnFBJJ14";

    @Test
    void convertsExternalHashToDatabaseDigestAndBack() {
        String internal = CheckoutHashCodec.toInternalDigest(HASH);

        assertEquals(64, internal.length());
        assertEquals(HASH, CheckoutHashCodec.toExternalHash(internal));
    }

    @Test
    void rejectsHashThatDoesNotMatchCheckoutJwt() {
        assertThrows(
                PayException.class,
                () -> CheckoutHashCodec.validateMatchesJwt(
                        "sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        JWT));
    }
}
