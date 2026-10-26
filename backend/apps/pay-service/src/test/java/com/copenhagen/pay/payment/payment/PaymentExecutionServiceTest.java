package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.payment.authorization.AuthorizationSessionItemRepository;
import com.copenhagen.pay.payment.policy.AuthorizationSessionItemStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.payment.authorization.AuthorizationSessionLifecycleService;
import com.copenhagen.pay.payment.authorization.AuthorizationSessionRepository;
import com.copenhagen.pay.payment.credential.mandate.ArtifactEncryptionService;
import com.copenhagen.pay.payment.credential.mandate.MandateProperties;
import com.copenhagen.pay.payment.pg.PaymentGateway;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PaymentExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PAYMENT_ID = "payment-1";
    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440203";
    private static final String REQUEST_HASH = "request-hash-1";

    private InMemoryPaymentExecutionRepository executionRepository;
    private RecordingPaymentGateway gateway;
    private RecordingAuthorizationSessionRepository authorizationSessionRepository;
    private PaymentExecutionService service;

    @BeforeEach
    void setUp() {
        executionRepository = new InMemoryPaymentExecutionRepository();
        gateway = new RecordingPaymentGateway();
        authorizationSessionRepository = new RecordingAuthorizationSessionRepository();
        PaymentLifecycleService lifecycleService = new PaymentLifecycleService(
                new RecordingPaymentRepository(executionRepository),
                new AuthorizationSessionLifecycleService(
                        authorizationSessionRepository,
                        authorizationSessionRepository));
        PaymentExecutionPreparationService preparationService =
                new PaymentExecutionPreparationService(
                        executionRepository,
                        lifecycleService,
                        new IdempotencyGuard(),
                        CLOCK);
        ArtifactEncryptionService encryptionService = new ArtifactEncryptionService(
                new MandateProperties("pay-service", "test-key", "", ""));
        PaymentExecutionSettlementService settlementService =
                new PaymentExecutionSettlementService(
                        executionRepository,
                        lifecycleService,
                        new PaymentReceiptService(executionRepository, encryptionService, new ObjectMapper()),
                        CLOCK);
        service = new PaymentExecutionService(preparationService, settlementService, gateway);
    }

    @Test
    void execute_success_createsReceiptAndCompletesAuthorization() {
        gateway.result = PaymentGateway.ChargeResult.success("pg-tx-1", "APPROVED");

        PaymentExecutionResult result = service.execute(command());

        assertEquals(PaymentStatus.SUCCESS, result.status());
        assertEquals("pg-tx-1", result.pgTransactionId());
        assertEquals(1, gateway.calls);
        assertEquals(1, executionRepository.attemptInsertCount);
        assertEquals(1, executionRepository.receiptInsertCount);
        assertEquals(
                AuthorizationSessionItemStatus.COMPLETED,
                authorizationSessionRepository.itemStatus);
    }

    @Test
    void execute_failed_doesNotCreateReceiptAndInvalidatesAuthorization() {
        gateway.result = PaymentGateway.ChargeResult.failed(null, "DECLINED");

        PaymentExecutionResult result = service.execute(command());

        assertEquals(PaymentStatus.FAILED, result.status());
        assertEquals(1, gateway.calls);
        assertEquals(0, executionRepository.receiptInsertCount);
        assertEquals(
                AuthorizationSessionItemStatus.INVALIDATED,
                authorizationSessionRepository.itemStatus);
    }

    @Test
    void execute_unknown_createsReconciliationJobWithoutRetry() {
        gateway.result = PaymentGateway.ChargeResult.unknown("PG_TIMEOUT");

        PaymentExecutionResult result = service.execute(command());

        assertEquals(PaymentStatus.UNKNOWN, result.status());
        assertEquals(1, gateway.calls);
        assertEquals(0, executionRepository.receiptInsertCount);
        assertEquals(1, executionRepository.reconciliationJobInsertCount);
        assertEquals(AuthorizationSessionStatus.APPROVED, authorizationSessionRepository.status);
    }

    @Test
    void execute_sameRequestReplay_returnsExistingResultWithoutSecondCharge() {
        gateway.result = PaymentGateway.ChargeResult.success("pg-tx-1", "APPROVED");

        PaymentExecutionResult first = service.execute(command());
        PaymentExecutionResult replay = service.execute(command());

        assertEquals(first, replay);
        assertEquals(1, gateway.calls);
        assertEquals(1, executionRepository.attemptInsertCount);
        assertEquals(1, executionRepository.receiptInsertCount);
    }

    @Test
    void execute_sameKeyDifferentRequestHash_throwsConflict() {
        gateway.result = PaymentGateway.ChargeResult.success("pg-tx-1", "APPROVED");
        service.execute(command());

        PayException exception = assertThrows(
                PayException.class,
                () -> service.execute(new PaymentExecutionCommand(
                        PAYMENT_ID,
                        IDEMPOTENCY_KEY,
                        "different-request")));

        assertEquals(PayErrorCode.IDEMPOTENCY_CONFLICT, exception.errorCode());
        assertEquals(1, gateway.calls);
    }

    @Test
    void execute_unconsumedCredential_isRejectedBeforeGatewayCall() {
        executionRepository.credentialStatus = "ISSUED";

        PayException exception = assertThrows(
                PayException.class,
                () -> service.execute(command()));

        assertEquals(PayErrorCode.STATE_CONFLICT, exception.errorCode());
        assertEquals(0, gateway.calls);
        assertEquals(PaymentStatus.READY, executionRepository.paymentStatus);
    }

    @Test
    void execute_gatewayException_isRecordedAsUnknown() {
        gateway.failure = new IllegalStateException("connection lost");

        PaymentExecutionResult result = service.execute(command());

        assertEquals(PaymentStatus.UNKNOWN, result.status());
        assertEquals(1, executionRepository.reconciliationJobInsertCount);
    }

    private PaymentExecutionCommand command() {
        return new PaymentExecutionCommand(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST_HASH);
    }

    private static final class RecordingPaymentGateway implements PaymentGateway {

        @Override
        public StatusResult lookup(StatusRequest request) {
            throw new AssertionError("결제 실행은 대사 조회를 호출하지 않아야 합니다.");
        }

        private ChargeResult result = ChargeResult.success("pg-tx-1", "APPROVED");
        private RuntimeException failure;
        private int calls;

        @Override
        public ChargeResult charge(ChargeRequest request) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static final class RecordingPaymentRepository implements PaymentRepository {

        private final InMemoryPaymentExecutionRepository delegate;

        private RecordingPaymentRepository(InMemoryPaymentExecutionRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<PaymentStatus> findStatus(String paymentId) {
            return Optional.of(delegate.paymentStatus);
        }

        @Override
        public Optional<String> findAuthorizationSessionId(String paymentId) {
            return Optional.of("authorization-session-1");
        }

        @Override
        public Optional<String> findSessionItemId(String paymentId) {
            return Optional.of("authorization-session-1-item-1");
        }

        @Override
        public boolean existsLiveByPurchaseRequest(String merchantAccountId, String purchaseRequestId) {
            return delegate.paymentStatus.isLive();
        }

        @Override
        public boolean transitionStatus(
                String paymentId,
                PaymentStatus expectedStatus,
                PaymentStatus targetStatus) {
            if (delegate.paymentStatus != expectedStatus) {
                return false;
            }
            delegate.paymentStatus = targetStatus;
            return true;
        }
    }

    private static final class RecordingAuthorizationSessionRepository
            implements AuthorizationSessionRepository, AuthorizationSessionItemRepository {

        private AuthorizationSessionStatus status = AuthorizationSessionStatus.APPROVED;

        @Override
        public Optional<AuthorizationSessionStatus> findStatus(String authorizationSessionId) {
            return Optional.of(status);
        }

        @Override
        public boolean transitionStatus(
                String authorizationSessionId,
                AuthorizationSessionStatus expectedStatus,
                AuthorizationSessionStatus targetStatus) {
            if (status != expectedStatus) {
                return false;
            }
            status = targetStatus;
            return true;
        }

        private AuthorizationSessionItemStatus itemStatus = AuthorizationSessionItemStatus.PENDING;

        @Override
        public Optional<AuthorizationSessionItemStatus> findItemStatus(String sessionItemId) {
            return Optional.of(itemStatus);
        }

        @Override
        public boolean transitionItemStatus(
                String sessionItemId,
                AuthorizationSessionItemStatus expectedStatus,
                AuthorizationSessionItemStatus targetStatus) {
            if (itemStatus != expectedStatus) {
                return false;
            }
            itemStatus = targetStatus;
            return true;
        }
    }

    private static final class InMemoryPaymentExecutionRepository
            implements PaymentExecutionRepository {

        private PaymentStatus paymentStatus = PaymentStatus.READY;
        private String credentialStatus = "CONSUMED";
        private String attemptId;
        private String pgRequestId;
        private String attemptStatus;
        private String pgTransactionId;
        private String pgTransactionStatus;
        private String pgResponseCode;
        private String receiptId;
        private int attemptInsertCount;
        private int receiptInsertCount;
        private int reconciliationJobInsertCount;

        @Override
        public Optional<PaymentExecutionRecord> findByIdForUpdate(String paymentId) {
            return Optional.of(record());
        }

        @Override
        public Optional<PaymentExecutionRecord> findById(String paymentId) {
            return Optional.of(record());
        }

        @Override
        public void insertChargeAttempt(
                String attemptId,
                String paymentId,
                String requestHash,
                String pgRequestId,
                Instant startedAt) {
            this.attemptId = attemptId;
            this.pgRequestId = pgRequestId;
            this.attemptStatus = PaymentStatus.PROCESSING.name();
            attemptInsertCount++;
        }

        @Override
        public void insertPgTransaction(
                String transactionId,
                String attemptId,
                Instant requestedAt) {
            this.pgTransactionStatus = PaymentStatus.PROCESSING.name();
        }

        @Override
        public boolean updateAttemptResult(
                String attemptId,
                String status,
                String errorCode,
                Instant completedAt) {
            if (!attemptId.equals(this.attemptId)
                    || !PaymentStatus.PROCESSING.name().equals(attemptStatus)) {
                return false;
            }
            this.attemptStatus = status;
            return true;
        }

        @Override
        public boolean updatePgTransactionResult(
                String attemptId,
                String status,
                String pgTransactionId,
                String responseCode,
                Instant completedAt,
                Instant lastCheckedAt) {
            if (!attemptId.equals(this.attemptId)
                    || !PaymentStatus.PROCESSING.name().equals(pgTransactionStatus)) {
                return false;
            }
            this.pgTransactionStatus = status;
            this.pgTransactionId = pgTransactionId;
            this.pgResponseCode = responseCode;
            return true;
        }

        @Override
        public void insertReceipt(PaymentReceipt receipt) {
            receiptId = receipt.id();
            receiptInsertCount++;
        }

        @Override
        public void insertReconciliationJob(
                String attemptId,
                String reason,
                Instant nextRunAt) {
            reconciliationJobInsertCount++;
        }

        private PaymentExecutionRecord record() {
            return new PaymentExecutionRecord(
                    PAYMENT_ID,
                    "authorization-session-1",
                    "merchant-account-1",
                    "merchant-1",
                    "checkout-1",
                    "purchase-1",
                    "credential-1",
                    credentialStatus,
                    "provider-token-1",
                    87_000,
                    "KRW",
                    paymentStatus,
                    IDEMPOTENCY_KEY,
                    REQUEST_HASH,
                    attemptId,
                    pgRequestId,
                    attemptStatus,
                    pgTransactionId,
                    pgTransactionStatus,
                    pgResponseCode,
                    receiptId);
        }
    }
}
