package com.copenhagen.pay.payment.credential.mandate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.payment.authorization.CheckoutHashCodec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PaymentCredentialConsumptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    @Test
    void consumesIssuedCredentialOnceAndReturnsExistingPaymentOnReplay() {
        InMemoryRepository repository = new InMemoryRepository();
        PaymentCredentialConsumptionService service = new PaymentCredentialConsumptionService(
                repository,
                new IdempotencyGuard(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        PaymentCredentialConsumptionService.ConsumeCommand command = command(
                "idempotency-001",
                "request-hash-001");

        CreatedPayment first = service.consume(command);
        CreatedPayment replay = service.consume(command);

        assertEquals(first.id(), replay.id());
        assertEquals(PaymentCredentialStatus.CONSUMED, repository.credential.status());
    }

    @Test
    void rejectsDifferentKeyAfterCredentialWasConsumed() {
        InMemoryRepository repository = new InMemoryRepository();
        PaymentCredentialConsumptionService service = new PaymentCredentialConsumptionService(
                repository,
                new IdempotencyGuard(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        service.consume(command("idempotency-001", "request-hash-001"));

        PayException exception = assertThrows(
                PayException.class,
                () -> service.consume(command("idempotency-002", "request-hash-002")));

        assertEquals(PayErrorCode.CREDENTIAL_ALREADY_CONSUMED, exception.errorCode());
    }

    @Test
    void rejectsSameKeyWithDifferentRequestHash() {
        InMemoryRepository repository = new InMemoryRepository();
        PaymentCredentialConsumptionService service = new PaymentCredentialConsumptionService(
                repository,
                new IdempotencyGuard(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        service.consume(command("idempotency-001", "request-hash-001"));

        PayException exception = assertThrows(
                PayException.class,
                () -> service.consume(command("idempotency-001", "request-hash-002")));

        assertEquals(PayErrorCode.IDEMPOTENCY_CONFLICT, exception.errorCode());
    }

    @Test
    void rechecksIdempotentPaymentAfterCredentialLock() {
        InMemoryRepository repository = new InMemoryRepository() {
            private boolean firstLookup = true;

            @Override
            public Optional<CreatedPayment> findPaymentByMerchantAndIdempotencyKey(
                    String merchantAccountId,
                    String idempotencyKey) {
                if (firstLookup) {
                    firstLookup = false;
                    return Optional.empty();
                }
                return super.findPaymentByMerchantAndIdempotencyKey(
                        merchantAccountId,
                        idempotencyKey);
            }
        };
        PaymentCredentialConsumptionService service = new PaymentCredentialConsumptionService(
                repository,
                new IdempotencyGuard(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        CreatedPayment first = service.consume(command("idempotency-001", "request-hash-001"));
        CreatedPayment replay = service.consume(command("idempotency-001", "request-hash-001"));

        assertEquals(first.id(), replay.id());
    }

    private PaymentCredentialConsumptionService.ConsumeCommand command(
            String idempotencyKey,
            String requestHash) {
        return new PaymentCredentialConsumptionService.ConsumeCommand(
                "merchant-account-001",
                "merchant-001",
                "checkout-001",
                CheckoutHashCodec.toExternalHash("0".repeat(64)),
                87000,
                "KRW",
                "credential-001",
                idempotencyKey,
                requestHash);
    }

    private static class InMemoryRepository
            implements PaymentCredentialConsumptionRepository {

        private PaymentCredentialRecord credential = new PaymentCredentialRecord(
                "credential-001",
                "session-001",
                "merchant-account-001",
                "merchant-001",
                "purchase-001",
                "checkout-001",
                "0".repeat(64),
                87000,
                "KRW",
                PaymentCredentialStatus.ISSUED,
                NOW.plusSeconds(600),
                null);
        private CreatedPayment payment;

        @Override
        public Optional<CreatedPayment> findPaymentByMerchantAndIdempotencyKey(
                String merchantAccountId,
                String idempotencyKey) {
            return Optional.ofNullable(payment)
                    .filter(value -> value.merchantAccountId().equals(merchantAccountId))
                    .filter(value -> value.idempotencyKey().equals(idempotencyKey));
        }

        @Override
        public Optional<PaymentCredentialRecord> findCredentialForUpdate(String credentialId) {
            return Optional.of(credential)
                    .filter(value -> value.id().equals(credentialId));
        }

        @Override
        public void insertPayment(CreatedPayment value) {
            payment = value;
        }

        @Override
        public boolean consumeCredential(String credentialId, Instant consumedAt) {
            if (!credential.id().equals(credentialId)
                    || credential.status() != PaymentCredentialStatus.ISSUED) {
                return false;
            }
            credential = new PaymentCredentialRecord(
                    credential.id(),
                    credential.authorizationSessionId(),
                    credential.merchantAccountId(),
                    credential.merchantId(),
                    credential.purchaseRequestId(),
                    credential.externalCheckoutId(),
                    credential.checkoutDigest(),
                    credential.amountMinor(),
                    credential.currency(),
                    PaymentCredentialStatus.CONSUMED,
                    credential.expiresAt(),
                    consumedAt);
            return true;
        }
    }
}
