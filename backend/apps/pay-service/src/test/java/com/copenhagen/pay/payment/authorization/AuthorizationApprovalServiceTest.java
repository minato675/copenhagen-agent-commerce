package com.copenhagen.pay.payment.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.credential.biometric.BiometricChallenge;
import com.copenhagen.pay.payment.credential.biometric.BiometricChallengeRepository;
import com.copenhagen.pay.payment.credential.biometric.BiometricChallengeService;
import com.copenhagen.pay.payment.credential.biometric.BiometricCredential;
import com.copenhagen.pay.payment.credential.biometric.BiometricCredentialRepository;
import com.copenhagen.pay.payment.credential.pin.PaymentPinCredential;
import com.copenhagen.pay.payment.credential.pin.PaymentPinCredentialRepository;
import com.copenhagen.pay.payment.credential.pin.PaymentPinService;
import com.copenhagen.pay.payment.credential.mandate.IssuedPaymentCredential;
import com.copenhagen.pay.payment.credential.mandate.PaymentCredentialIssuer;
import com.copenhagen.pay.payment.credential.pin.PaymentPinStatus;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

class AuthorizationApprovalServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");

    private InMemoryAuthorizationSessionStore sessionStore;
    private PaymentPinService pinService;
    private AuthorizationApprovalService service;

    @BeforeEach
    void setUp() {
        sessionStore = new InMemoryAuthorizationSessionStore(session());
        PaymentPinCredentialRepository pinRepository = new InMemoryPinRepository();
        pinService = new PaymentPinService(
                pinRepository,
                NoOpPasswordEncoder.getInstance(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        pinService.register("user-001", "123456");

        BiometricChallengeService challengeService = new BiometricChallengeService(
                new EmptyChallengeRepository(),
                new EmptyCredentialRepository(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ApprovalProperties(300, 60, true));
        service = new AuthorizationApprovalService(
                sessionStore,
                challengeService,
                pinService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void pinApprovalChangesSessionToApprovedAndRecordsAuthMethod() {
        sessionStore.replace(withRequiredAction("PIN"));

        AuthorizationApprovalService.ApprovalResult result = service.approve(
                "user-001",
                "session-001",
                new AuthorizationApprovalService.ApproveCommand(
                        "method-001",
                        new AuthorizationApprovalService.AuthenticationCommand(
                                "PIN",
                                null,
                                null,
                                "123456")));

        assertEquals(AuthorizationSessionStatus.APPROVED.name(), result.status());
        assertEquals("PIN", result.authMethod());
        assertEquals(
                AuthorizationSessionStatus.APPROVED,
                sessionStore.session().status());
        assertEquals("PIN", sessionStore.session().authMethod());
    }

    @Test
    void pinApprovalIsAcceptedWhenSessionStillRequiresBiometric() {
        sessionStore.replace(withRequiredAction("BIOMETRIC"));

        AuthorizationApprovalService.ApprovalResult result = service.approve(
                "user-001",
                "session-001",
                new AuthorizationApprovalService.ApproveCommand(
                        "method-001",
                        new AuthorizationApprovalService.AuthenticationCommand(
                                "PIN",
                                null,
                                null,
                                "123456")));

        assertEquals(AuthorizationSessionStatus.APPROVED.name(), result.status());
        assertEquals("PIN", result.authMethod());
        assertEquals("PIN", sessionStore.session().authMethod());
    }

    @Test
    void wrongPinIsStillRejectedWhenSessionRequiresBiometric() {
        sessionStore.replace(withRequiredAction("BIOMETRIC"));

        PayException exception = assertThrows(
                PayException.class,
                () -> service.approve(
                        "user-001",
                        "session-001",
                        new AuthorizationApprovalService.ApproveCommand(
                                "method-001",
                                new AuthorizationApprovalService.AuthenticationCommand(
                                        "PIN",
                                        null,
                                        null,
                                        "000000"))));

        assertEquals(PayErrorCode.PIN_INVALID, exception.errorCode());
        assertEquals(
                AuthorizationSessionStatus.PENDING,
                sessionStore.session().status());
    }

    @Test
    void biometricApprovalIsStillRejectedWhenSessionRequiresPin() {
        sessionStore.replace(withRequiredAction("PIN"));

        PayException exception = assertThrows(
                PayException.class,
                () -> service.approve(
                        "user-001",
                        "session-001",
                        new AuthorizationApprovalService.ApproveCommand(
                                "method-001",
                                new AuthorizationApprovalService.AuthenticationCommand(
                                        "BIOMETRIC",
                                        "challenge-001",
                                        "assertion",
                                        null))));

        assertEquals(PayErrorCode.STATE_CONFLICT, exception.errorCode());
        assertEquals("PIN", exception.currentState());
    }

    @Test
    void terminalApprovalCannotBeApprovedAgain() {
        sessionStore.replace(withRequiredAction("PIN"));
        service.approve(
                "user-001",
                "session-001",
                new AuthorizationApprovalService.ApproveCommand(
                        "method-001",
                        new AuthorizationApprovalService.AuthenticationCommand(
                                "PIN",
                                null,
                                null,
                                "123456")));

        PayException exception = assertThrows(
                PayException.class,
                () -> service.approve(
                        "user-001",
                        "session-001",
                        new AuthorizationApprovalService.ApproveCommand(
                                "method-001",
                                new AuthorizationApprovalService.AuthenticationCommand(
                                        "PIN",
                                        null,
                                        null,
                                        "123456"))));

        assertEquals(PayErrorCode.STATE_CONFLICT, exception.errorCode());
    }

    @Test
    void failedPinApprovalNeverApprovesSession() {
        sessionStore.replace(withRequiredAction("PIN"));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(
                    PayException.class,
                    () -> service.approve(
                            "user-001",
                            "session-001",
                            new AuthorizationApprovalService.ApproveCommand(
                                    "method-001",
                                    new AuthorizationApprovalService.AuthenticationCommand(
                                            "PIN",
                                            null,
                                            null,
                                            "000000"))));
        }

        assertEquals(
                AuthorizationSessionStatus.PENDING,
                sessionStore.session().status());
    }

    @Test
    void biometricFailureSwitchesSessionToPinWithoutApproving() {
        BiometricChallengeService challengeService = mock(BiometricChallengeService.class);
        when(challengeService.verify(
                "user-001",
                "session-001",
                "challenge-001",
                "assertion-001"))
                .thenReturn(BiometricChallengeService.VerificationResult.FALLBACK_TO_PIN);
        service = new AuthorizationApprovalService(
                sessionStore,
                challengeService,
                pinService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        AuthorizationApprovalService.ApprovalResult result = service.approve(
                "user-001",
                "session-001",
                new AuthorizationApprovalService.ApproveCommand(
                        "method-001",
                        new AuthorizationApprovalService.AuthenticationCommand(
                                "BIOMETRIC",
                                "challenge-001",
                                "assertion-001",
                                null)));

        assertEquals(AuthorizationSessionStatus.PENDING.name(), result.status());
        assertEquals("PIN", result.requiredAction());
        assertEquals(
                AuthorizationSessionStatus.PENDING,
                sessionStore.session().status());
        assertEquals("PIN", sessionStore.session().requiredAction());
    }

    @Test
    void rejectChangesPendingSessionToRejected() {
        AuthorizationApprovalService.ApprovalResult result = service.reject(
                "user-001",
                "session-001");

        assertEquals(AuthorizationSessionStatus.REJECTED.name(), result.status());
        assertEquals(
                AuthorizationSessionStatus.REJECTED,
                sessionStore.session().status());
    }

    @Test
    void approvingMultiMerchantSessionIssuesOneCredentialPerMerchant() {
        // 인증은 1회인데 credential 은 판매처 수만큼 나와야 한다.
        AuthorizationSession multiMerchant = multiMerchantSession();
        sessionStore.replace(multiMerchant);
        RecordingCredentialIssuer issuer = new RecordingCredentialIssuer(0);
        AuthorizationApprovalService issuingService = serviceWith(issuer);

        AuthorizationApprovalService.ApprovalResult result = issuingService.approve(
                "user-001",
                "session-001",
                pinCommand());

        assertEquals(AuthorizationSessionStatus.APPROVED.name(), result.status());
        assertEquals(2, result.credentials().size());
        assertEquals(
                List.of("merchant-001", "merchant-002"),
                result.credentials().stream()
                        .map(AuthorizationApprovalService.ApprovalResult.IssuedCredential::merchantId)
                        .sorted()
                        .toList());
        assertEquals(2, issuer.issuedItemIds.size());
    }

    @Test
    void oneFailingMerchantRollsBackTheWholeApproval() {
        // "한 번 승인 = 전부 승인"이 성립하려면 부분 승인 상태가 존재해서는 안 된다.
        sessionStore.replace(multiMerchantSession());
        AuthorizationApprovalService issuingService = serviceWith(new RecordingCredentialIssuer(1));

        assertThrows(
                PayException.class,
                () -> issuingService.approve("user-001", "session-001", pinCommand()));
    }

    private AuthorizationApprovalService.ApproveCommand pinCommand() {
        return new AuthorizationApprovalService.ApproveCommand(
                "method-001",
                new AuthorizationApprovalService.AuthenticationCommand("PIN", null, null, "123456"));
    }

    private AuthorizationApprovalService serviceWith(RecordingCredentialIssuer issuer) {
        return new AuthorizationApprovalService(
                sessionStore,
                new BiometricChallengeService(
                        new EmptyChallengeRepository(),
                        new EmptyCredentialRepository(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        new ApprovalProperties(300, 60, true)),
                pinService,
                issuer,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AuthorizationSession multiMerchantSession() {
        AuthorizationSession base = session();
        AuthorizationSessionItem first = base.items().getFirst();
        AuthorizationSessionItem second = AuthorizationSessionFixtures.item(
                "session-001-item-2",
                "session-001",
                "merchant-account-002",
                "merchant-002",
                "두 번째 상점",
                "checkout-002",
                "1".repeat(64),
                "{}",
                39000,
                "KRW",
                NOW,
                NOW);
        return AuthorizationSessionFixtures.multiMerchant(
                "session-001",
                "purchase-001",
                "user-001",
                "method-001",
                AuthorizationSessionStatus.PENDING,
                NOW.plusSeconds(300),
                NOW,
                List.of(first, second));
    }

    /** {@code failAtIndex} 번째 호출에서 터져 롤백을 확인한다. 0 이면 실패하지 않는다. */
    private static final class RecordingCredentialIssuer implements PaymentCredentialIssuer {

        private final int failAtCall;
        private final List<String> issuedItemIds = new java.util.ArrayList<>();

        private RecordingCredentialIssuer(int failAtCall) {
            this.failAtCall = failAtCall;
        }

        @Override
        public IssuedPaymentCredential issue(
                AuthorizationSession session,
                AuthorizationSessionItem item,
                String authMethod) {
            issuedItemIds.add(item.id());
            if (failAtCall > 0 && issuedItemIds.size() == failAtCall + 1) {
                throw new PayException(PayErrorCode.INTERNAL_ERROR);
            }
            return new IssuedPaymentCredential(
                    "credential-" + item.id(),
                    session.expiresAt(),
                    "mandate-" + item.id());
        }
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
                99000,
                "KRW",
                "method-001",
                AuthorizationSessionStatus.PENDING,
                "idempotency-001",
                "request-hash",
                "BIOMETRIC",
                NOW.plusSeconds(300),
                null,
                null,
                NOW,
                NOW,
                0);
    }

    private AuthorizationSession withRequiredAction(String requiredAction) {
        AuthorizationSession current = sessionStore.session();
        return new AuthorizationSession(
                current.id(),
                current.purchaseRequestId(),
                current.userId(),
                current.totalAmountMinor(),
                current.currency(),
                current.selectedPaymentMethodId(),
                current.status(),
                current.idempotencyKey(),
                current.requestHash(),
                requiredAction,
                current.expiresAt(),
                current.approvedAt(),
                current.authMethod(),
                current.createdAt(),
                current.updatedAt(),
                current.version(),
                current.items());
    }

    private static final class InMemoryAuthorizationSessionStore
            implements AuthorizationSessionStore {

        private AuthorizationSession session;

        private InMemoryAuthorizationSessionStore(AuthorizationSession session) {
            this.session = session;
        }

        @Override
        public Optional<AuthorizationSession> findByUserAndIdempotencyKey(
                String userId,
                String idempotencyKey) {
            return Optional.of(session)
                    .filter(value -> value.userId().equals(userId))
                    .filter(value -> value.idempotencyKey().equals(idempotencyKey));
        }

        @Override
        public Optional<AuthorizationSession> findActiveByPurchaseRequest(
                String purchaseRequestId) {
            return Optional.of(session)
                    .filter(value -> value.purchaseRequestId().equals(purchaseRequestId))
                    .filter(value -> value.status().isActive());
        }

        @Override
        public boolean insert(AuthorizationSession value) {
            session = value;
            return true;
        }

        @Override
        public List<AuthorizationSession> findByUserId(
                String userId,
                AuthorizationSessionStatus status) {
            return List.of(session);
        }

        @Override
        public Optional<AuthorizationSession> findByIdAndUserId(
                String authorizationSessionId,
                String userId) {
            return Optional.of(session)
                    .filter(value -> value.id().equals(authorizationSessionId))
                    .filter(value -> value.userId().equals(userId));
        }

        @Override
        public int expireOverdueForUser(String userId, Instant now) {
            return 0;
        }

        @Override
        public int expireOverdueByIdAndUser(
                String authorizationSessionId,
                String userId,
                Instant now) {
            return 0;
        }

        @Override
        public boolean approve(
                String authorizationSessionId,
                String userId,
                String authMethod,
                Instant approvedAt) {
            if (session.status() != AuthorizationSessionStatus.PENDING) {
                return false;
            }
            session = copy(
                    AuthorizationSessionStatus.APPROVED,
                    session.requiredAction(),
                    authMethod,
                    approvedAt);
            return true;
        }

        @Override
        public boolean reject(
                String authorizationSessionId,
                String userId,
                Instant rejectedAt) {
            if (!session.status().isActive()) {
                return false;
            }
            session = copy(
                    AuthorizationSessionStatus.REJECTED,
                    session.requiredAction(),
                    session.authMethod(),
                    session.approvedAt());
            return true;
        }

        @Override
        public boolean setRequiredAction(
                String authorizationSessionId,
                String userId,
                AuthorizationSessionStatus expectedStatus,
                String requiredAction,
                Instant now) {
            if (session.status() != expectedStatus) {
                return false;
            }
            session = copy(
                    session.status(),
                    requiredAction,
                    session.authMethod(),
                    session.approvedAt());
            return true;
        }

        private AuthorizationSession copy(
                AuthorizationSessionStatus status,
                String requiredAction,
                String authMethod,
                Instant approvedAt) {
            return new AuthorizationSession(
                    session.id(),
                    session.purchaseRequestId(),
                    session.userId(),
                    session.totalAmountMinor(),
                    session.currency(),
                    session.selectedPaymentMethodId(),
                    status,
                    session.idempotencyKey(),
                    session.requestHash(),
                    requiredAction,
                    session.expiresAt(),
                    approvedAt,
                    authMethod,
                    session.createdAt(),
                    NOW,
                    session.version() + 1,
                    session.items());
        }

        private AuthorizationSession session() {
            return session;
        }

        private void replace(AuthorizationSession value) {
            session = value;
        }
    }

    private static final class InMemoryPinRepository
            implements PaymentPinCredentialRepository {

        private PaymentPinCredential credential;

        @Override
        public Optional<PaymentPinCredential> findByUserIdForUpdate(String userId) {
            return Optional.ofNullable(credential)
                    .filter(value -> value.userId().equals(userId));
        }

        @Override
        public void insert(String id, String userId, String pinHash, Instant updatedAt) {
            credential = new PaymentPinCredential(
                    id,
                    userId,
                    pinHash,
                    0,
                    null,
                    PaymentPinStatus.ACTIVE);
        }

        @Override
        public void updatePin(String id, String pinHash, Instant updatedAt) {
            insert(id, credential.userId(), pinHash, updatedAt);
        }

        @Override
        public void updateVerificationFailure(
                String id,
                int failedAttempts,
                Instant lockedUntil,
                PaymentPinStatus status,
                Instant updatedAt) {
            credential = new PaymentPinCredential(
                    id,
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
    }

    private static final class EmptyChallengeRepository implements BiometricChallengeRepository {

        @Override
        public int expirePendingForSession(String authorizationSessionId, Instant now) {
            return 0;
        }

        @Override
        public void insert(BiometricChallenge challenge) {
        }

        @Override
        public Optional<BiometricChallenge> findByIdAndSession(
                String challengeId,
                String authorizationSessionId) {
            return Optional.empty();
        }

        @Override
        public boolean markSuccessIfPending(
                String challengeId,
                String authorizationSessionId,
                Instant now) {
            return false;
        }

        @Override
        public boolean markFailedIfPending(
                String challengeId,
                String authorizationSessionId,
                Instant now) {
            return false;
        }

        @Override
        public boolean markExpiredIfPending(
                String challengeId,
                String authorizationSessionId,
                Instant now) {
            return false;
        }
    }

    private static final class EmptyCredentialRepository implements BiometricCredentialRepository {

        @Override
        public Optional<BiometricCredential> findActiveByUserAndDevice(
                String userId,
                String deviceId) {
            return Optional.empty();
        }

        @Override
        public void insert(
                String id,
                String userId,
                String deviceId,
                String publicKey,
                Instant registeredAt) {
        }
    }
}
