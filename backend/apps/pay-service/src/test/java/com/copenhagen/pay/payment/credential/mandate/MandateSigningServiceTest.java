package com.copenhagen.pay.payment.credential.mandate;

import com.copenhagen.pay.payment.authorization.AuthorizationSessionFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MandateSigningServiceTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-09-10T00:00:00Z");

    @Test
    void createsEs256CheckoutMandateWithBoundScope() throws Exception {
        MandateSigningService service = new MandateSigningService(
                new MandateProperties("pay-service", "pay-signing-key-1", "", ""));

        String serialized = service.createCheckoutMandate(
                session(),
                session().items().getFirst(),
                "mandate-001",
                ISSUED_AT);
        SignedJWT mandate = SignedJWT.parse(serialized);

        assertEquals(JWSAlgorithm.ES256, mandate.getHeader().getAlgorithm());
        assertEquals("pay-signing-key-1", mandate.getHeader().getKeyID());
        assertEquals("pay-service", mandate.getJWTClaimsSet().getIssuer());
        assertEquals("merchant:merchant-001", mandate.getJWTClaimsSet().getAudience().getFirst());
        assertEquals("mandate-001", mandate.getJWTClaimsSet().getJWTID());
        assertEquals("checkout-001", mandate.getJWTClaimsSet().getStringClaim("checkoutId"));
        assertEquals(
                session().expiresAt(),
                mandate.getJWTClaimsSet().getExpirationTime().toInstant());
    }

    private AuthorizationSession session() {
        return AuthorizationSessionFixtures.singleMerchant(
                "session-001",
                "purchase-001",
                "user-001",
                "merchant-account-001",
                "merchant-001",
                "예시 상점",
                "checkout-001",
                "0".repeat(64),
                "{}",
                87000,
                "KRW",
                "method-001",
                AuthorizationSessionStatus.APPROVED,
                "idempotency-001",
                "request-hash",
                "BIOMETRIC",
                ISSUED_AT.plusSeconds(600),
                ISSUED_AT,
                "BIOMETRIC",
                ISSUED_AT,
                ISSUED_AT,
                1);
    }
}
