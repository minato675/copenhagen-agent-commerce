package com.copenhagen.pay.common.auth;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.merchant.MerchantAccountRepository;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PayRouteAuthorizer {

    private static final String CORE_SERVICE_SUBJECT = "core-service";

    private final Predicate<String> activeMerchant;

    @Autowired
    public PayRouteAuthorizer(MerchantAccountRepository merchantAccountRepository) {
        this.activeMerchant = merchantId -> merchantAccountRepository
                .findActiveByMerchantId(merchantId)
                .isPresent();
    }

    private PayRouteAuthorizer(Predicate<String> activeMerchant) {
        this.activeMerchant = activeMerchant;
    }

    public static PayRouteAuthorizer allowAllForTests() {
        return new PayRouteAuthorizer((Predicate<String>) merchantId -> true);
    }

    public static PayRouteAuthorizer withMerchantRegistration(
            Predicate<String> activeMerchant) {
        return new PayRouteAuthorizer(activeMerchant);
    }

    public void authorize(String path, PayPrincipal principal) {
        PayAuthenticationPolicy.RequiredPrincipal requiredPrincipal =
                PayAuthenticationPolicy.requiredPrincipal(path);
        switch (requiredPrincipal) {
            case USER -> requireUser(principal);
            case SERVICE -> requireCoreService(principal);
            case MERCHANT -> requireMerchantService(principal);
            case NONE -> {
                return;
            }
        }
    }

    public void requireMerchantId(PayPrincipal principal, String requestedMerchantId) {
        if (!principal.isMerchantService()
                || requestedMerchantId == null
                || !principal.subject().equals(requestedMerchantId)) {
            throw new PayException(PayErrorCode.FORBIDDEN);
        }
    }

    private void requireUser(PayPrincipal principal) {
        if (!principal.isUser()) {
            throw new PayException(PayErrorCode.FORBIDDEN);
        }
    }

    private void requireCoreService(PayPrincipal principal) {
        boolean isCoreService = principal.isCoreService()
                || principal.type() == PayPrincipal.Type.SERVICE
                && CORE_SERVICE_SUBJECT.equals(principal.subject());
        if (!isCoreService) {
            throw new PayException(PayErrorCode.FORBIDDEN);
        }
    }

    private void requireMerchantService(PayPrincipal principal) {
        if (!principal.isMerchantService()
                || !activeMerchant.test(principal.subject())) {
            throw new PayException(PayErrorCode.FORBIDDEN);
        }
    }
}
