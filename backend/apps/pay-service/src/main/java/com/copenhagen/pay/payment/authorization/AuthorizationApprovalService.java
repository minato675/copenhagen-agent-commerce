package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.credential.biometric.BiometricChallengeService;
import com.copenhagen.pay.payment.credential.mandate.IssuedPaymentCredential;
import com.copenhagen.pay.payment.credential.mandate.PaymentCredentialIssuer;
import com.copenhagen.pay.payment.credential.pin.PaymentPinService;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationApprovalService {

    private static final String BIOMETRIC = "BIOMETRIC";
    private static final String PIN = "PIN";
    private static final Logger log = LoggerFactory.getLogger(AuthorizationApprovalService.class);

    private final AuthorizationSessionStore sessionStore;
    private final BiometricChallengeService challengeService;
    private final PaymentPinService paymentPinService;
    private final PaymentCredentialIssuer credentialIssuer;
    private final Clock clock;

    @Autowired
    public AuthorizationApprovalService(
            AuthorizationSessionStore sessionStore,
            BiometricChallengeService challengeService,
            PaymentPinService paymentPinService,
            PaymentCredentialIssuer credentialIssuer,
            Clock clock) {
        this.sessionStore = sessionStore;
        this.challengeService = challengeService;
        this.paymentPinService = paymentPinService;
        this.credentialIssuer = credentialIssuer;
        this.clock = clock;
    }

    AuthorizationApprovalService(
            AuthorizationSessionStore sessionStore,
            BiometricChallengeService challengeService,
            PaymentPinService paymentPinService,
            Clock clock) {
        this(
                sessionStore,
                challengeService,
                paymentPinService,
                (session, item, authMethod) -> new IssuedPaymentCredential(
                        null,
                        session.expiresAt(),
                        null),
                clock);
    }

    @Transactional
    public PreparedDetail prepareDetail(
            AuthorizationSession session,
            String deviceId) {
        if (session.status() != AuthorizationSessionStatus.PENDING
                || !BIOMETRIC.equals(session.requiredAction())) {
            return new PreparedDetail(session, Optional.empty());
        }

        // 생체 미등록은 예외 상황이 아니라 PIN 폴백이라는 정상 분기다. issue()가 던지게
        // 두면 같은 트랜잭션이 rollback-only로 표시돼, 폴백을 마쳐도 커밋에서 터진다.
        if (!challengeService.hasRegisteredCredential(session.userId(), deviceId)) {
            sessionStore.setRequiredAction(
                    session.id(),
                    session.userId(),
                    AuthorizationSessionStatus.PENDING,
                    PIN,
                    clock.instant());
            AuthorizationSession fallbackSession = sessionStore
                    .findByIdAndUserId(session.id(), session.userId())
                    .orElse(session);
            return new PreparedDetail(fallbackSession, Optional.empty());
        }

        BiometricChallengeService.IssuedChallenge challenge = challengeService.issue(
                session.userId(),
                session,
                deviceId);
        return new PreparedDetail(session, Optional.of(challenge));
    }

    @Transactional
    public ApprovalResult approve(
            String userId,
            String approvalId,
            ApproveCommand command) {
        PayLogContext.putFingerprint(PayLogContext.USER_ID, userId);
        PayLogContext.put(PayLogContext.APPROVAL_ID, approvalId);
        log.info("pay.approval.approve.start");
        AuthorizationSession session = loadCurrent(userId, approvalId);
        ensureApprovable(session);
        if (command == null || command.authentication() == null) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }
        if (command.paymentMethodId() != null
                && session.selectedPaymentMethodId() != null
                && !command.paymentMethodId().equals(session.selectedPaymentMethodId())) {
            throw new PayException(
                    PayErrorCode.PAYMENT_METHOD_NOT_FOUND,
                    PayErrorCode.PAYMENT_METHOD_NOT_FOUND.defaultMessage(),
                    session.status().name(),
                    PayErrorCode.PAYMENT_METHOD_NOT_FOUND.defaultNextAction());
        }

        AuthenticationCommand authentication = command.authentication();
        if (BIOMETRIC.equals(authentication.method())) {
            ensureRequiredAction(session, BIOMETRIC);
            BiometricChallengeService.VerificationResult verification = challengeService.verify(
                    userId,
                    approvalId,
                    authentication.challengeId(),
                    authentication.assertion());
            if (verification == BiometricChallengeService.VerificationResult.FALLBACK_TO_PIN) {
                moveToPin(session);
                log.info("pay.approval.approve.fallback approvalId={} nextAction=PIN", approvalId);
                return ApprovalResult.pinFallback(session.id());
            }
            ApprovalResult result = approveSession(session, BIOMETRIC);
            log.info("pay.approval.approve.complete approvalId={} authMethod=BIOMETRIC", approvalId);
            return result;
        }

        // required_action 은 "가능하면 생체"라는 선호일 뿐 위험도 정책이 아니다. 생체를 요구하는
        // 세션에도 PIN 승인을 받는다. 앱이 생체 실패·취소나 사용자 선택으로 PIN 화면을 열어도
        // 서버가 거절하지 않게 한다. 반대로 PIN 세션에 생체를 보내는 것은 계속 막는다 —
        // 그 경우 챌린지가 발급되지 않아 서명할 대상 자체가 없다.
        if (PIN.equals(authentication.method())) {
            paymentPinService.verify(userId, authentication.pin());
            ApprovalResult result = approveSession(session, PIN);
            log.info("pay.approval.approve.complete approvalId={} authMethod=PIN", approvalId);
            return result;
        }

        throw new PayException(
                PayErrorCode.INVALID_REQUEST,
                "authentication.method은 BIOMETRIC 또는 PIN이어야 합니다.",
                session.status().name(),
                "USE_BIOMETRIC_OR_PIN");
    }

    @Transactional
    public ApprovalResult reject(String userId, String approvalId) {
        PayLogContext.putFingerprint(PayLogContext.USER_ID, userId);
        PayLogContext.put(PayLogContext.APPROVAL_ID, approvalId);
        log.info("pay.approval.reject.start");
        AuthorizationSession session = loadCurrent(userId, approvalId);
        Instant now = clock.instant();
        if (!session.status().isActive()) {
            throw stateConflict(session);
        }
        if (!now.isBefore(session.expiresAt())) {
            throw stateConflict(session);
        }
        if (!sessionStore.reject(approvalId, userId, now)) {
            throw stateConflict(loadCurrent(userId, approvalId));
        }
        log.info("pay.approval.reject.complete approvalId={}", approvalId);
        return ApprovalResult.rejected(approvalId);
    }

    private AuthorizationSession loadCurrent(String userId, String approvalId) {
        sessionStore.expireOverdueByIdAndUser(approvalId, userId, clock.instant());
        AuthorizationSession session = sessionStore.findByIdAndUserId(approvalId, userId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        PayLogContext.put(PayLogContext.ORDER_ID, session.purchaseRequestId());
        return session;
    }

    private void ensureApprovable(AuthorizationSession session) {
        if (session.status() != AuthorizationSessionStatus.PENDING) {
            throw stateConflict(session);
        }
        if (!clock.instant().isBefore(session.expiresAt())) {
            throw stateConflict(session);
        }
        if (session.selectedPaymentMethodId() == null) {
            throw new PayException(
                    PayErrorCode.STATE_CONFLICT,
                    "결제수단을 먼저 등록하거나 선택해야 합니다.",
                    session.status().name(),
                    "REGISTER_OR_SELECT_PAYMENT_METHOD");
        }
    }

    private void ensureRequiredAction(
            AuthorizationSession session,
            String expectedAction) {
        if (!expectedAction.equals(session.requiredAction())) {
            throw new PayException(
                    PayErrorCode.STATE_CONFLICT,
                    "현재 승인 요청에서 사용할 인증 방식이 아닙니다.",
                    session.requiredAction(),
                    "USE_" + session.requiredAction());
        }
    }

    private void moveToPin(AuthorizationSession session) {
        if (!sessionStore.setRequiredAction(
                session.id(),
                session.userId(),
                AuthorizationSessionStatus.PENDING,
                PIN,
                clock.instant())) {
            throw stateConflict(loadCurrent(session.userId(), session.id()));
        }
    }

    /**
     * 인증 1회 통과 후 세션의 판매처 수만큼 credential 을 발급한다. 한 트랜잭션이므로
     * item 하나라도 실패하면 승인 전체가 롤백된다. "한 번 승인 = 전부 승인"이 성립하려면
     * 부분 승인 상태가 존재해서는 안 된다.
     */
    private ApprovalResult approveSession(
            AuthorizationSession session,
            String authMethod) {
        Instant now = clock.instant();
        if (!sessionStore.approve(session.id(), session.userId(), authMethod, now)) {
            throw stateConflict(loadCurrent(session.userId(), session.id()));
        }
        AuthorizationSession approvedSession = sessionStore
                .findByIdAndUserId(session.id(), session.userId())
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        List<ApprovalResult.IssuedCredential> credentials =
                new ArrayList<>(approvedSession.items().size());
        for (AuthorizationSessionItem item : approvedSession.items()) {
            IssuedPaymentCredential credential = credentialIssuer.issue(
                    approvedSession,
                    item,
                    authMethod);
            credentials.add(new ApprovalResult.IssuedCredential(
                    item.merchantId(),
                    item.externalCheckoutId(),
                    credential.credentialId(),
                    credential.expiresAt()));
        }
        log.info(
                "pay.approval.transition.success approvalId={} before=PENDING after=APPROVED "
                        + "authMethod={} credentialCount={}",
                session.id(),
                authMethod,
                credentials.size());
        return ApprovalResult.approved(session.id(), authMethod, credentials);
    }

    private PayException stateConflict(AuthorizationSession session) {
        return new PayException(
                PayErrorCode.STATE_CONFLICT,
                PayErrorCode.STATE_CONFLICT.defaultMessage(),
                session.status().name(),
                PayErrorCode.STATE_CONFLICT.defaultNextAction());
    }

    public record ApproveCommand(
            String paymentMethodId,
            AuthenticationCommand authentication) {
    }

    public record AuthenticationCommand(
            String method,
            String challengeId,
            String assertion,
            String pin) {

        @Override
        public String toString() {
            return "AuthenticationCommand["
                    + "method='" + method + '\''
                    + ", challengeId='" + challengeId + '\''
                    + ", assertion=<redacted>"
                    + ", pin=<redacted>"
                    + ']';
        }
    }

    public record PreparedDetail(
            AuthorizationSession session,
            Optional<BiometricChallengeService.IssuedChallenge> challenge) {
    }

    public record ApprovalResult(
            String approvalId,
            String status,
            String requiredAction,
            String authMethod,
            List<IssuedCredential> credentials,
            String nextAction) {

        public ApprovalResult {
            credentials = credentials == null ? List.of() : List.copyOf(credentials);
        }

        /** 판매처별 발급 결과. credential 은 판매처가 Pay 에 결제를 요청할 때 쓴다. */
        public record IssuedCredential(
                String merchantId,
                String checkoutId,
                String credentialId,
                Instant expiresAt) {
        }

        private static ApprovalResult pinFallback(String approvalId) {
            return new ApprovalResult(
                    approvalId,
                    AuthorizationSessionStatus.PENDING.name(),
                    PIN,
                    null,
                    List.of(),
                    "ENTER_PIN");
        }

        private static ApprovalResult approved(
                String approvalId,
                String authMethod,
                List<IssuedCredential> credentials) {
            return new ApprovalResult(
                    approvalId,
                    AuthorizationSessionStatus.APPROVED.name(),
                    null,
                    authMethod,
                    credentials,
                    "COMPLETE_CHECKOUT");
        }

        private static ApprovalResult rejected(String approvalId) {
            return new ApprovalResult(
                    approvalId,
                    AuthorizationSessionStatus.REJECTED.name(),
                    null,
                    null,
                    List.of(),
                    "RETURN_TO_CART");
        }
    }
}
