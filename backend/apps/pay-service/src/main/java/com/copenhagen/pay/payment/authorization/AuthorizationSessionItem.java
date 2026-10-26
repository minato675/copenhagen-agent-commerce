package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionItemStatus;
import java.time.Instant;

/** 승인 세션에 묶인 판매처 한 곳. credential 은 이 단위로 1장씩 발급된다. */
public record AuthorizationSessionItem(
        String id,
        String authorizationSessionId,
        String merchantAccountId,
        String merchantId,
        String merchantName,
        String externalCheckoutId,
        String checkoutDigest,
        String displaySnapshotJson,
        long amountMinor,
        String currency,
        AuthorizationSessionItemStatus status,
        Instant createdAt,
        Instant updatedAt,
        int version) {
}
