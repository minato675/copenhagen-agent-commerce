package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.common.validation.RequestValidation;
import com.copenhagen.pay.payment.merchant.MerchantAccount;
import com.copenhagen.pay.payment.merchant.MerchantAccountRepository;
import com.copenhagen.pay.payment.notification.NotificationEventPublisher;
import com.copenhagen.pay.payment.paymentmethod.PaymentMethod;
import com.copenhagen.pay.payment.paymentmethod.PaymentMethodRepository;
import com.copenhagen.pay.payment.policy.AuthorizationSessionItemStatus;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 구매 요청 단위 승인 세션. 판매처가 몇 곳이든 세션은 1개이고 사용자 인증도 1회다.
 * 판매처별 주문서는 {@link AuthorizationSessionItem} 으로 보관하며 credential 도 item 당
 * 1장씩 발급된다.
 */
@Service
public class AuthorizationSessionService {

    private static final String KRW = "KRW";
    private static final String BIOMETRIC = "BIOMETRIC";
    private static final int MAX_SUMMARY_ITEMS = 100;
    private static final int MAX_ITEM_NAME_LENGTH = 200;
    private static final int MAX_OPTION_LENGTH = 200;
    private static final int MAX_MERCHANTS = 20;
    private static final Logger log = LoggerFactory.getLogger(AuthorizationSessionService.class);

    private final AuthorizationSessionStore store;
    private final MerchantAccountRepository merchantAccountRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final IdempotencyGuard idempotencyGuard;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ApprovalProperties approvalProperties;
    private final MerchantCheckoutVerifier checkoutVerifier;
    private final VerifiedCheckoutEvidenceRepository evidenceRepository;
    private NotificationEventPublisher notificationEventPublisher = NotificationEventPublisher.noop();

    public AuthorizationSessionService(
            AuthorizationSessionStore store,
            MerchantAccountRepository merchantAccountRepository,
            PaymentMethodRepository paymentMethodRepository,
            IdempotencyGuard idempotencyGuard,
            ObjectMapper objectMapper,
            Clock clock,
            ApprovalProperties approvalProperties,
            MerchantCheckoutVerifier checkoutVerifier,
            VerifiedCheckoutEvidenceRepository evidenceRepository) {
        this.store = store;
        this.merchantAccountRepository = merchantAccountRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.idempotencyGuard = idempotencyGuard;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.approvalProperties = approvalProperties;
        this.checkoutVerifier = checkoutVerifier;
        this.evidenceRepository = evidenceRepository;
    }

    @Autowired(required = false)
    public void setNotificationEventPublisher(NotificationEventPublisher notificationEventPublisher) {
        if (notificationEventPublisher != null) {
            this.notificationEventPublisher = notificationEventPublisher;
        }
    }

    @Transactional
    public CreateResult create(
            CreateAuthorizationSessionCommand command,
            String idempotencyKey,
            String requestHash) {
        validate(command, idempotencyKey, requestHash);
        PayLogContext.putFingerprint(PayLogContext.USER_ID, command.userId());
        PayLogContext.put(PayLogContext.ORDER_ID, command.purchaseRequestId());
        log.info(
                "pay.approval.create.start merchantCount={} totalAmountMinor={} currency={}",
                command.items().size(),
                command.totalAmountMinor(),
                command.currency());

        Optional<AuthorizationSession> existing = store.findByUserAndIdempotencyKey(
                command.userId(),
                idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyGuard.Decision decision = idempotencyGuard.check(
                    existing.get().requestHash(),
                    requestHash,
                    existing.get().status().name());
            if (decision == IdempotencyGuard.Decision.REPLAY) {
                PayLogContext.put(PayLogContext.APPROVAL_ID, existing.get().id());
                log.info(
                        "pay.approval.create.replay approvalId={} status={}",
                        existing.get().id(),
                        existing.get().status());
                return new CreateResult(existing.get(), true);
            }
        }

        store.findActiveByPurchaseRequest(command.purchaseRequestId())
                .ifPresent(session -> {
                    throw stateConflict(session.status());
                });

        Instant now = clock.instant();
        String sessionId = UUID.randomUUID().toString();
        List<PreparedItem> prepared = prepareItems(command, sessionId);
        PaymentMethod selectedPaymentMethod = selectPaymentMethod(command.userId());
        AuthorizationSessionStatus status = selectedPaymentMethod == null
                ? AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED
                : AuthorizationSessionStatus.PENDING;

        AuthorizationSession session = new AuthorizationSession(
                sessionId,
                command.purchaseRequestId(),
                command.userId(),
                command.totalAmountMinor(),
                command.currency(),
                selectedPaymentMethod == null ? null : selectedPaymentMethod.id(),
                status,
                idempotencyKey,
                requestHash,
                BIOMETRIC,
                expiresAt(prepared, now),
                null,
                null,
                now,
                now,
                0,
                prepared.stream().map(PreparedItem::item).toList());
        PayLogContext.put(PayLogContext.APPROVAL_ID, session.id());

        if (store.insert(session)) {
            for (PreparedItem preparedItem : prepared) {
                VerifiedCheckoutEvidence evidence = VerifiedCheckoutEvidence.from(
                        UUID.randomUUID().toString(),
                        sessionId,
                        preparedItem.item().id(),
                        preparedItem.command(),
                        preparedItem.item().checkoutDigest(),
                        preparedItem.verification(),
                        now);
                if (!evidenceRepository.insert(evidence)) {
                    throw new PayException(PayErrorCode.INTERNAL_ERROR);
                }
            }
            notificationEventPublisher.publishApprovalRequested(session);
            log.info(
                    "pay.approval.create.complete approvalId={} status={} merchantCount={}",
                    session.id(),
                    session.status(),
                    session.merchantCount());
            return new CreateResult(session, false);
        }

        CreateResult concurrentResult = resolveConcurrentInsert(
                command.userId(),
                command.purchaseRequestId(),
                idempotencyKey,
                requestHash);
        PayLogContext.put(PayLogContext.APPROVAL_ID, concurrentResult.session().id());
        log.info(
                "pay.approval.create.concurrent_result approvalId={} replay={}",
                concurrentResult.session().id(),
                concurrentResult.replay());
        return concurrentResult;
    }

    @Transactional
    public List<AuthorizationSession> findForUser(
            String userId,
            AuthorizationSessionStatus status) {
        RequestValidation.requireLengthAtMost(userId, "userId", 36);
        PayLogContext.putFingerprint(PayLogContext.USER_ID, userId);
        log.debug("pay.approval.list.start status={}", status);
        Instant now = clock.instant();
        store.expireOverdueForUser(userId, now);
        List<AuthorizationSession> sessions = store.findByUserId(userId, status);
        log.debug("pay.approval.list.complete count={}", sessions.size());
        return sessions;
    }

    @Transactional
    public AuthorizationSession findDetail(String userId, String authorizationSessionId) {
        RequestValidation.requireLengthAtMost(userId, "userId", 36);
        RequestValidation.requireLengthAtMost(
                authorizationSessionId,
                "approvalId",
                36);
        PayLogContext.putFingerprint(PayLogContext.USER_ID, userId);
        PayLogContext.put(PayLogContext.APPROVAL_ID, authorizationSessionId);
        log.debug("pay.approval.detail.start");
        Instant now = clock.instant();
        store.expireOverdueByIdAndUser(authorizationSessionId, userId, now);
        AuthorizationSession session = store.findByIdAndUserId(authorizationSessionId, userId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        PayLogContext.put(PayLogContext.ORDER_ID, session.purchaseRequestId());
        log.debug("pay.approval.detail.complete status={}", session.status());
        return session;
    }

    /** 결제수단은 세션 단위다. 한 번 고르면 전 판매처에 적용된다. */
    @Transactional
    public AuthorizationSession selectPaymentMethod(
            String userId,
            String authorizationSessionId,
            String paymentMethodId) {
        RequestValidation.requireLengthAtMost(userId, "userId", 36);
        RequestValidation.requireLengthAtMost(
                authorizationSessionId,
                "approvalId",
                36);
        RequestValidation.requireLengthAtMost(
                paymentMethodId,
                "paymentMethodId",
                36);
        PayLogContext.putFingerprint(PayLogContext.USER_ID, userId);
        PayLogContext.put(PayLogContext.APPROVAL_ID, authorizationSessionId);
        log.info("pay.approval.payment_method.select.start");

        Instant now = clock.instant();
        store.expireOverdueByIdAndUser(authorizationSessionId, userId, now);
        AuthorizationSession session = store.findByIdAndUserId(
                        authorizationSessionId,
                        userId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        ensurePaymentMethodSelectionAllowed(session, now);

        paymentMethodRepository.findActiveByIdAndUserIdForUpdate(paymentMethodId, userId)
                .orElseThrow(() -> new PayException(PayErrorCode.PAYMENT_METHOD_NOT_FOUND));

        if (!store.selectPaymentMethod(
                authorizationSessionId,
                userId,
                paymentMethodId,
                now)) {
            AuthorizationSession current = store.findByIdAndUserId(
                            authorizationSessionId,
                            userId)
                    .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
            throw stateConflict(current.status());
        }

        AuthorizationSession updated = store.findByIdAndUserId(authorizationSessionId, userId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        if (session.status() == AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED
                && updated.status() == AuthorizationSessionStatus.PENDING) {
            notificationEventPublisher.publishApprovalRequested(updated);
        }
        log.info(
                "pay.approval.payment_method.select.complete status={}",
                updated.status());
        return updated;
    }

    /** 표시용 주문 요약은 판매처별이다. */
    public AuthorizationDisplaySnapshot displaySnapshot(AuthorizationSessionItem item) {
        try {
            return objectMapper.readValue(
                    item.displaySnapshotJson(),
                    AuthorizationDisplaySnapshot.class);
        } catch (JacksonException exception) {
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    PayErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
    }

    /**
     * 판매처별 주문서를 검증해 item 을 만든다. 하나라도 실패하면 예외가 그대로 전파되어
     * 세션 전체가 만들어지지 않는다.
     */
    private List<PreparedItem> prepareItems(
            CreateAuthorizationSessionCommand command,
            String sessionId) {
        Instant now = clock.instant();
        List<PreparedItem> prepared = new ArrayList<>(command.items().size());
        for (CreateAuthorizationSessionCommand.Item item : command.items()) {
            MerchantAccount merchantAccount = merchantAccountRepository
                    .findActiveByMerchantId(item.merchantId())
                    .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
            MerchantCheckoutVerifier.VerificationResult verification =
                    checkoutVerifier.verify(command.purchaseRequestId(), item);
            String checkoutDigest = CheckoutHashCodec.toInternalDigest(item.checkoutHash());
            AuthorizationSessionItem sessionItem = new AuthorizationSessionItem(
                    UUID.randomUUID().toString(),
                    sessionId,
                    merchantAccount.id(),
                    merchantAccount.merchantId(),
                    merchantAccount.displayName(),
                    item.checkoutId(),
                    checkoutDigest,
                    serializeDisplaySnapshot(item.displaySnapshot()),
                    item.amountMinor(),
                    item.currency(),
                    AuthorizationSessionItemStatus.PENDING,
                    now,
                    now,
                    0);
            prepared.add(new PreparedItem(item, sessionItem, verification));
        }
        return prepared;
    }

    /**
     * 세션 만료는 판매처별 checkout 만료 중 가장 이른 시각으로 잡되, 기본 TTL 을 하한으로 둔다.
     * 하한이 없으면 짧은 만료를 주는 판매처 하나가 세션 전체를 끌어내려 인증을 1회로 줄인
     * 효과가 상쇄된다.
     */
    private Instant expiresAt(List<PreparedItem> prepared, Instant now) {
        Instant floor = now.plus(Duration.ofSeconds(approvalProperties.expirationSeconds()));
        Instant earliestCheckout = prepared.stream()
                .map(item -> item.verification().expiresAt())
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(floor);
        if (!approvalProperties.floorEnabled()) {
            return earliestCheckout;
        }
        return earliestCheckout.isAfter(floor) ? earliestCheckout : floor;
    }

    private CreateResult resolveConcurrentInsert(
            String userId,
            String purchaseRequestId,
            String idempotencyKey,
            String requestHash) {
        Optional<AuthorizationSession> existing = store.findByUserAndIdempotencyKey(
                userId,
                idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyGuard.Decision decision = idempotencyGuard.check(
                    existing.get().requestHash(),
                    requestHash,
                    existing.get().status().name());
            if (decision == IdempotencyGuard.Decision.REPLAY) {
                return new CreateResult(existing.get(), true);
            }
        }

        Optional<AuthorizationSession> active = store.findActiveByPurchaseRequest(purchaseRequestId);
        if (active.isPresent()) {
            throw stateConflict(active.get().status());
        }
        throw new PayException(PayErrorCode.INTERNAL_ERROR);
    }

    private PaymentMethod selectPaymentMethod(String userId) {
        List<PaymentMethod> paymentMethods = paymentMethodRepository.findActiveByUserId(userId);
        return paymentMethods.stream()
                .filter(PaymentMethod::defaultMethod)
                .findFirst()
                .orElseGet(() -> paymentMethods.stream().findFirst().orElse(null));
    }

    private String serializeDisplaySnapshot(AuthorizationDisplaySnapshot displaySnapshot) {
        try {
            return objectMapper.writeValueAsString(displaySnapshot);
        } catch (JacksonException exception) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "표시용 상품 요약을 저장할 수 없습니다.",
                    null,
                    "CHECK_DISPLAY_SNAPSHOT",
                    exception);
        }
    }

    private void validate(
            CreateAuthorizationSessionCommand command,
            String idempotencyKey,
            String requestHash) {
        if (command == null) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }
        RequestValidation.requireLengthAtMost(command.purchaseRequestId(), "purchaseRequestId", 36);
        RequestValidation.requireLengthAtMost(command.userId(), "userId", 36);
        RequestValidation.requireLengthAtMost(idempotencyKey, "idempotencyKey", 100);
        RequestValidation.requireLengthAtMost(requestHash, "requestHash", 64);
        if (command.totalAmountMinor() < 0) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }
        if (!KRW.equals(command.currency())) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "MVP에서는 KRW 통화만 사용할 수 있습니다.",
                    null,
                    "USE_KRW_CURRENCY");
        }
        if (command.items().isEmpty() || command.items().size() > MAX_MERCHANTS) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "items는 1개 이상 " + MAX_MERCHANTS + "개 이하여야 합니다.",
                    null,
                    "CHECK_ITEMS");
        }

        Set<String> merchantIds = new HashSet<>();
        Set<String> checkoutIds = new HashSet<>();
        long sum = 0;
        for (CreateAuthorizationSessionCommand.Item item : command.items()) {
            validateItem(item);
            if (!merchantIds.add(item.merchantId())) {
                throw new PayException(
                        PayErrorCode.INVALID_REQUEST,
                        "같은 판매처가 items에 중복으로 들어올 수 없습니다.",
                        null,
                        "CHECK_ITEMS");
            }
            if (!checkoutIds.add(item.checkoutId())) {
                throw new PayException(
                        PayErrorCode.INVALID_REQUEST,
                        "같은 checkoutId가 items에 중복으로 들어올 수 없습니다.",
                        null,
                        "CHECK_ITEMS");
            }
            if (!command.currency().equals(item.currency())) {
                throw new PayException(
                        PayErrorCode.INVALID_REQUEST,
                        "items의 통화는 요청 통화와 모두 같아야 합니다.",
                        null,
                        "CHECK_CURRENCY");
            }
            sum += item.amountMinor();
        }
        // 사용자가 승인 화면에서 본 합계와 실제 결제 합계가 어긋나는 것을 여기서 막는다.
        if (sum != command.totalAmountMinor()) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "totalAmountMinor가 items 금액 합과 다릅니다.",
                    null,
                    "CHECK_TOTAL_AMOUNT");
        }
    }

    private void validateItem(CreateAuthorizationSessionCommand.Item item) {
        if (item == null) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }
        RequestValidation.requireLengthAtMost(item.merchantId(), "merchantId", 100);
        RequestValidation.requireLengthAtMost(item.checkoutId(), "checkoutId", 150);
        RequestValidation.requireLengthAtMost(item.checkoutHash(), "checkoutHash", 100);
        if (!isCompactJws(item.checkoutJwt())) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "checkoutJwt는 Compact JWS 형식이어야 합니다.",
                    null,
                    "CHECK_CHECKOUT_JWT");
        }
        CheckoutHashCodec.toInternalDigest(item.checkoutHash());
        CheckoutHashCodec.validateMatchesJwt(item.checkoutHash(), item.checkoutJwt());
        if (item.amountMinor() < 0) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }
        validateDisplaySnapshot(item.displaySnapshot());
    }

    private boolean isCompactJws(String checkoutJwt) {
        if (checkoutJwt == null || checkoutJwt.isBlank()) {
            return false;
        }
        String[] segments = checkoutJwt.split("\\.", -1);
        if (segments.length != 3
                || java.util.Arrays.stream(segments)
                .anyMatch(segment -> segment.isBlank() || !segment.matches("[A-Za-z0-9_-]+"))) {
            return false;
        }
        try {
            for (String segment : segments) {
                java.util.Base64.getUrlDecoder().decode(segment);
            }
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void validateDisplaySnapshot(AuthorizationDisplaySnapshot displaySnapshot) {
        if (displaySnapshot == null
                || displaySnapshot.merchantName() == null
                || displaySnapshot.merchantName().isBlank()
                || displaySnapshot.merchantName().length() > 200
                || displaySnapshot.summary().isEmpty()
                || displaySnapshot.summary().size() > MAX_SUMMARY_ITEMS) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "display 요약 형식이 올바르지 않습니다.",
                    null,
                    "CHECK_DISPLAY_SNAPSHOT");
        }
        displaySnapshot.summary().forEach(this::validateSummaryItem);
    }

    private void validateSummaryItem(AuthorizationDisplaySnapshot.SummaryItem item) {
        if (item == null
                || item.name() == null
                || item.name().isBlank()
                || item.name().length() > MAX_ITEM_NAME_LENGTH
                || item.option() != null && item.option().length() > MAX_OPTION_LENGTH
                || item.quantity() <= 0
                || item.amountMinor() < 0) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "display.summary 항목 형식이 올바르지 않습니다.",
                    null,
                    "CHECK_DISPLAY_SNAPSHOT");
        }
    }

    private void ensurePaymentMethodSelectionAllowed(
            AuthorizationSession session,
            Instant now) {
        if (session.status() != AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED
                && session.status() != AuthorizationSessionStatus.PENDING) {
            throw stateConflict(session.status());
        }
        if (!now.isBefore(session.expiresAt())) {
            throw stateConflict(session.status());
        }
    }

    private PayException stateConflict(AuthorizationSessionStatus currentStatus) {
        return new PayException(
                PayErrorCode.STATE_CONFLICT,
                PayErrorCode.STATE_CONFLICT.defaultMessage(),
                currentStatus.name(),
                PayErrorCode.STATE_CONFLICT.defaultNextAction());
    }

    private record PreparedItem(
            CreateAuthorizationSessionCommand.Item command,
            AuthorizationSessionItem item,
            MerchantCheckoutVerifier.VerificationResult verification) {
    }

    public record CreateResult(
            AuthorizationSession session,
            boolean replay) {
    }
}
