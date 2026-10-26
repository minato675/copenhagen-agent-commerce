package com.copenhagen.pay.common.web;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;

public final class PayRequestContext {

    private static final String PRINCIPAL_ATTRIBUTE = PayRequestContext.class.getName() + ".principal";
    private static final String IDEMPOTENCY_KEY_ATTRIBUTE =
            PayRequestContext.class.getName() + ".idempotencyKey";
    private static final String REQUEST_HASH_ATTRIBUTE =
            PayRequestContext.class.getName() + ".requestHash";

    private PayRequestContext() {
    }

    public static void setPrincipal(HttpServletRequest request, PayPrincipal principal) {
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
    }

    public static Optional<PayPrincipal> findPrincipal(HttpServletRequest request) {
        return Optional.ofNullable(request.getAttribute(PRINCIPAL_ATTRIBUTE))
                .map(PayPrincipal.class::cast);
    }

    public static PayPrincipal requirePrincipal(HttpServletRequest request) {
        return findPrincipal(request).orElseThrow(
                () -> new PayException(PayErrorCode.AUTHENTICATION_REQUIRED));
    }

    public static PayPrincipal requireMerchantPrincipal(
            HttpServletRequest request,
            String requestedMerchantId) {
        PayPrincipal principal = requirePrincipal(request);
        if (!principal.isMerchantService()
                || !Objects.equals(principal.subject(), requestedMerchantId)) {
            throw new PayException(PayErrorCode.FORBIDDEN);
        }
        return principal;
    }

    public static void setIdempotencyKey(HttpServletRequest request, IdempotencyKey key) {
        request.setAttribute(IDEMPOTENCY_KEY_ATTRIBUTE, key);
    }

    public static Optional<IdempotencyKey> findIdempotencyKey(HttpServletRequest request) {
        return Optional.ofNullable(request.getAttribute(IDEMPOTENCY_KEY_ATTRIBUTE))
                .map(IdempotencyKey.class::cast);
    }

    public static void setRequestHash(HttpServletRequest request, String requestHash) {
        request.setAttribute(REQUEST_HASH_ATTRIBUTE, requestHash);
    }

    public static Optional<String> findRequestHash(HttpServletRequest request) {
        return Optional.ofNullable(request.getAttribute(REQUEST_HASH_ATTRIBUTE))
                .map(String.class::cast);
    }
}
