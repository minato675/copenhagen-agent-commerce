package com.copenhagen.pay.payment.credential.pin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.credential.PepperedPasswordEncoder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PaymentPinServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440500";

    private InMemoryPaymentPinCredentialRepository repository;
    private PasswordEncoder passwordEncoder;
    private PaymentPinService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPaymentPinCredentialRepository();
        passwordEncoder = new PepperedPasswordEncoder(
                new Argon2PasswordEncoder(16, 32, 1, 64 * 1024, 3),
                "test-pepper");
        service = new PaymentPinService(
                repository,
                passwordEncoder,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void registerStoresArgon2idHashInsteadOfRawPin() {
        service.register("user-001", "123456");

        PaymentPinCredential credential = repository.credential().orElseThrow();
        assertNotEquals("123456", credential.pinHash());
        assertTrue(passwordEncoder.matches("123456", credential.pinHash()));
        assertFalse(passwordEncoder.matches("654321", credential.pinHash()));
    }

    @Test
    void failedVerificationIncrementsCounterAndLocksAfterFiveAttempts() {
        service.register("user-001", "123456");

        for (int attempt = 0; attempt < 4; attempt++) {
            PayException exception = assertThrows(
                    PayException.class,
                    () -> service.verify("user-001", "000000"));
            assertEquals(PayErrorCode.PIN_INVALID, exception.errorCode());
        }

        PayException lockedException = assertThrows(
                PayException.class,
                () -> service.verify("user-001", "000000"));

        assertEquals(PayErrorCode.PIN_TEMPORARILY_LOCKED, lockedException.errorCode());
        assertEquals(5, repository.credential().orElseThrow().failedAttempts());
        assertEquals(
                PaymentPinStatus.LOCKED,
                repository.credential().orElseThrow().status());
    }

    @Test
    void successfulVerificationResetsFailureState() {
        service.register("user-001", "123456");

        assertThrows(
                PayException.class,
                () -> service.verify("user-001", "000000"));
        service.verify("user-001", "123456");

        PaymentPinCredential credential = repository.credential().orElseThrow();
        assertEquals(0, credential.failedAttempts());
        assertEquals(PaymentPinStatus.ACTIVE, credential.status());
        assertEquals(null, credential.lockedUntil());
    }

    @Test
    void correctPinIsRejectedWhileCredentialIsLocked() {
        service.register("user-001", "123456");

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(
                    PayException.class,
                    () -> service.verify("user-001", "000000"));
        }

        PayException exception = assertThrows(
                PayException.class,
                () -> service.verify("user-001", "123456"));

        assertEquals(PayErrorCode.PIN_TEMPORARILY_LOCKED, exception.errorCode());
        assertEquals(5, repository.credential().orElseThrow().failedAttempts());
    }

    @Test
    void failedCurrentPinDuringChangeIsPersistedBeforeError() {
        service.register("user-001", "123456");

        PayException exception = assertThrows(
                PayException.class,
                () -> service.change("user-001", "000000", "654321"));

        assertEquals(PayErrorCode.PIN_INVALID, exception.errorCode());
        PaymentPinCredential credential = repository.credential().orElseThrow();
        assertEquals(1, credential.failedAttempts());
        assertEquals(PaymentPinStatus.ACTIVE, credential.status());
        assertTrue(passwordEncoder.matches("123456", credential.pinHash()));
    }

    @Test
    void statusReturnsNotRegisteredWhenCredentialDoesNotExist() {
        assertEquals(
                PaymentPinStatusResponse.notRegistered(),
                service.status("user-001"));
    }

    @Test
    void statusReturnsActiveWithoutExposingCredentialDetails() {
        service.register("user-001", "123456");

        assertEquals(
                new PaymentPinStatusResponse(true, PaymentPinStatus.ACTIVE, "NONE"),
                service.status("user-001"));
    }

    @Test
    void statusMapsLockedCredentialToRetryAction() {
        service.register("user-001", "123456");
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(
                    PayException.class,
                    () -> service.verify("user-001", "000000"));
        }

        assertEquals(
                new PaymentPinStatusResponse(true, PaymentPinStatus.LOCKED, "WAIT_AND_RETRY"),
                service.status("user-001"));
    }

    @Test
    void statusMapsRevokedCredentialToReauthenticationAction() {
        repository.insert(
                "pin-001",
                "user-001",
                passwordEncoder.encode("123456"),
                NOW);
        repository.updateVerificationFailure(
                "pin-001",
                0,
                null,
                PaymentPinStatus.REVOKED,
                NOW);

        assertEquals(
                new PaymentPinStatusResponse(true, PaymentPinStatus.REVOKED, "REAUTHENTICATE"),
                service.status("user-001"));
    }

    @Test
    void registerReusesSuccessfulResultForSameIdempotencyKeyAndRequest() {
        service.register("user-001", "123456", IDEMPOTENCY_KEY, "hash-001");

        service.register("user-001", "123456", IDEMPOTENCY_KEY, "hash-001");

        assertEquals(IDEMPOTENCY_KEY, repository.credential().orElseThrow().registrationIdempotencyKey());
        assertEquals("hash-001", repository.credential().orElseThrow().registrationRequestHash());
    }

    @Test
    void registerRejectsSameIdempotencyKeyWithDifferentRequest() {
        service.register("user-001", "123456", IDEMPOTENCY_KEY, "hash-001");

        PayException exception = assertThrows(
                PayException.class,
                () -> service.register("user-001", "123456", IDEMPOTENCY_KEY, "hash-002"));

        assertEquals(PayErrorCode.IDEMPOTENCY_CONFLICT, exception.errorCode());
    }

    private static final class InMemoryPaymentPinCredentialRepository
            implements PaymentPinCredentialRepository {

        private PaymentPinCredential credential;

        @Override
        public Optional<PaymentPinCredential> findByUserIdForUpdate(String userId) {
            if (credential == null || !credential.userId().equals(userId)) {
                return Optional.empty();
            }
            return Optional.of(credential);
        }

        @Override
        public void insert(
                String id,
                String userId,
                String pinHash,
                Instant updatedAt) {
            insert(id, userId, pinHash, null, null, updatedAt);
        }

        @Override
        public void insert(
                String id,
                String userId,
                String pinHash,
                String registrationIdempotencyKey,
                String registrationRequestHash,
                Instant updatedAt) {
            credential = new PaymentPinCredential(
                    id,
                    userId,
                    pinHash,
                    0,
                    null,
                    PaymentPinStatus.ACTIVE,
                    registrationIdempotencyKey,
                    registrationRequestHash);
        }

        @Override
        public void updatePin(
                String id,
                String pinHash,
                Instant updatedAt) {
            credential = new PaymentPinCredential(
                    credential.id(),
                    credential.userId(),
                    pinHash,
                    0,
                    null,
                    PaymentPinStatus.ACTIVE);
        }

        @Override
        public void updateVerificationFailure(
                String id,
                int failedAttempts,
                Instant lockedUntil,
                PaymentPinStatus status,
                Instant updatedAt) {
            credential = new PaymentPinCredential(
                    credential.id(),
                    credential.userId(),
                    credential.pinHash(),
                    failedAttempts,
                    lockedUntil,
                    status);
        }

        @Override
        public void resetVerificationState(String id, Instant updatedAt) {
            credential = new PaymentPinCredential(
                    credential.id(),
                    credential.userId(),
                    credential.pinHash(),
                    0,
                    null,
                    PaymentPinStatus.ACTIVE);
        }

        private Optional<PaymentPinCredential> credential() {
            return Optional.ofNullable(credential);
        }
    }
}
