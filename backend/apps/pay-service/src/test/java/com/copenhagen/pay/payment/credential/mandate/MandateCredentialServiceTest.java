package com.copenhagen.pay.payment.credential.mandate;

import com.copenhagen.pay.payment.authorization.AuthorizationSessionFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import com.copenhagen.pay.payment.paymentmethod.PaymentMethod;
import com.copenhagen.pay.payment.paymentmethod.PaymentMethodRepository;
import com.copenhagen.pay.payment.paymentmethod.PaymentMethodStatus;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MandateCredentialServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    @Test
    void issuesThreeBoundArtifactsOnlyForAnUnexpiredApprovedSession() throws Exception {
        CapturingRepository repository = new CapturingRepository();
        MandateProperties properties = new MandateProperties(
                "pay-service",
                "pay-signing-key-1",
                "",
                "");
        ArtifactEncryptionService encryptionService = new ArtifactEncryptionService(properties);
        MandateCredentialService service = new MandateCredentialService(
                properties,
                new MandateSigningService(properties),
                encryptionService,
                repository,
                new InMemoryPaymentMethodRepository(),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        IssuedPaymentCredential credential = service.issue(session(), session().items().getFirst(), "BIOMETRIC");

        assertNotNull(credential.credentialId());
        assertNotNull(credential.checkoutMandate());
        assertEquals(session().expiresAt(), credential.expiresAt());
        assertNotNull(repository.issuance);
        assertEquals("provider-token-001", repository.issuance.paymentMethodRef());
        assertEquals(
                credential.checkoutMandate(),
                encryptionService.decrypt(repository.issuance.checkoutArtifactEncrypted()));
        String paymentMandate = encryptionService.decrypt(
                repository.issuance.paymentArtifactEncrypted());
        assertEquals(
                "PAYMENT",
                SignedJWT.parse(paymentMandate)
                        .getJWTClaimsSet()
                        .getStringClaim("paymentMandateType"));
        String credentialArtifact = encryptionService.decrypt(
                repository.issuance.credentialTokenCiphertext());
        assertTrue(credentialArtifact.contains(repository.issuance.paymentMandateId()));
        assertTrue(credentialArtifact.contains(credential.credentialId()));
    }

    @Test
    void doesNotIssueCredentialBeforeApproval() {
        MandateProperties properties = new MandateProperties(
                "pay-service",
                "pay-signing-key-1",
                "",
                "");
        MandateCredentialService service = new MandateCredentialService(
                properties,
                new MandateSigningService(properties),
                new ArtifactEncryptionService(properties),
                new CapturingRepository(),
                new InMemoryPaymentMethodRepository(),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        AuthorizationSession pendingSession = AuthorizationSessionFixtures.singleMerchant(
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
                AuthorizationSessionStatus.PENDING,
                "idempotency-001",
                "request-hash",
                "BIOMETRIC",
                NOW.plusSeconds(600),
                null,
                null,
                NOW,
                NOW,
                0);
        PayException exception = assertThrows(
                PayException.class,
                () -> service.issue(
                        pendingSession,
                        pendingSession.items().getFirst(),
                        "BIOMETRIC"));

        assertEquals(PayErrorCode.STATE_CONFLICT, exception.errorCode());
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
                NOW.plusSeconds(600),
                NOW,
                "BIOMETRIC",
                NOW,
                NOW,
                1);
    }

    private static final class CapturingRepository implements MandateCredentialRepository {

        private Issuance issuance;

        @Override
        public void insert(Issuance value) {
            issuance = value;
        }
    }

    private static final class InMemoryPaymentMethodRepository implements PaymentMethodRepository {

        private final PaymentMethod paymentMethod = new PaymentMethod(
                "method-001",
                "user-001",
                "PG_MOCK",
                "provider-token-001",
                "VISA",
                "1234",
                12,
                2030,
                PaymentMethodStatus.ACTIVE,
                true);

        @Override
        public List<PaymentMethod> findActiveByUserId(String userId) {
            return List.of(paymentMethod);
        }

        @Override
        public Optional<PaymentMethod> findActiveByIdAndUserIdForUpdate(
                String paymentMethodId,
                String userId) {
            return Optional.of(paymentMethod)
                    .filter(value -> value.id().equals(paymentMethodId))
                    .filter(value -> value.userId().equals(userId));
        }

        @Override
        public void insert(PaymentMethod value) {
        }

        @Override
        public int clearDefaultForUser(String userId) {
            return 0;
        }

        @Override
        public int markDefault(String paymentMethodId, String userId) {
            return 0;
        }

        @Override
        public int softDelete(String paymentMethodId, String userId) {
            return 0;
        }
    }
}
