package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionItemStatus;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.time.Instant;
import java.util.List;

/**
 * 테스트용 승인 세션 조립기.
 *
 * <p>{@link #singleMerchant} 는 판매처 단위 세션이던 시절의 인자 순서를 그대로 받아
 * 판매처 1곳짜리 세션을 만든다. 다중 판매처 동작을 검증할 때는 {@link #multiMerchant} 를 쓴다.
 */
public final class AuthorizationSessionFixtures {

    private AuthorizationSessionFixtures() {
    }

    public static AuthorizationSession singleMerchant(
            String sessionId,
            String purchaseRequestId,
            String userId,
            String merchantAccountId,
            String merchantId,
            String merchantName,
            String externalCheckoutId,
            String checkoutDigest,
            String displaySnapshotJson,
            long amountMinor,
            String currency,
            String selectedPaymentMethodId,
            AuthorizationSessionStatus status,
            String idempotencyKey,
            String requestHash,
            String requiredAction,
            Instant expiresAt,
            Instant approvedAt,
            String authMethod,
            Instant createdAt,
            Instant updatedAt,
            int version) {
        AuthorizationSessionItem item = item(
                sessionId + "-item-1",
                sessionId,
                merchantAccountId,
                merchantId,
                merchantName,
                externalCheckoutId,
                checkoutDigest,
                displaySnapshotJson,
                amountMinor,
                currency,
                createdAt,
                updatedAt);
        return new AuthorizationSession(
                sessionId,
                purchaseRequestId,
                userId,
                amountMinor,
                currency,
                selectedPaymentMethodId,
                status,
                idempotencyKey,
                requestHash,
                requiredAction,
                expiresAt,
                approvedAt,
                authMethod,
                createdAt,
                updatedAt,
                version,
                List.of(item));
    }

    public static AuthorizationSession multiMerchant(
            String sessionId,
            String purchaseRequestId,
            String userId,
            String selectedPaymentMethodId,
            AuthorizationSessionStatus status,
            Instant expiresAt,
            Instant now,
            List<AuthorizationSessionItem> items) {
        long total = items.stream().mapToLong(AuthorizationSessionItem::amountMinor).sum();
        return new AuthorizationSession(
                sessionId,
                purchaseRequestId,
                userId,
                total,
                "KRW",
                selectedPaymentMethodId,
                status,
                "idempotency-" + sessionId,
                "request-hash",
                "BIOMETRIC",
                expiresAt,
                null,
                null,
                now,
                now,
                0,
                items);
    }

    public static AuthorizationSessionItem item(
            String itemId,
            String sessionId,
            String merchantAccountId,
            String merchantId,
            String merchantName,
            String externalCheckoutId,
            String checkoutDigest,
            String displaySnapshotJson,
            long amountMinor,
            String currency,
            Instant createdAt,
            Instant updatedAt) {
        return new AuthorizationSessionItem(
                itemId,
                sessionId,
                merchantAccountId,
                merchantId,
                merchantName,
                externalCheckoutId,
                checkoutDigest,
                displaySnapshotJson,
                amountMinor,
                currency,
                AuthorizationSessionItemStatus.PENDING,
                createdAt,
                updatedAt,
                0);
    }
}
