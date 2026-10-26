package com.copenhagen.pay.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PayAuthenticationPolicyTest {

    @Test
    void appApiRequiresUserPrincipal() {
        assertEquals(
                PayAuthenticationPolicy.RequiredPrincipal.USER,
                PayAuthenticationPolicy.requiredPrincipal("/api/pay/v1/payment-methods/cards"));
    }

    @Test
    void internalPaymentApprovalApiRequiresServicePrincipal() {
        assertEquals(
                PayAuthenticationPolicy.RequiredPrincipal.SERVICE,
                PayAuthenticationPolicy.requiredPrincipal(
                        "/internal/v1/payment-approvals/approval-001/status"));
    }

    @Test
    void merchantApiRequiresMerchantPrincipal() {
        assertEquals(
                PayAuthenticationPolicy.RequiredPrincipal.MERCHANT,
                PayAuthenticationPolicy.requiredPrincipal("/merchant/v1/payments"));
    }

    @Test
    void similarButUnsupportedPathIsNotProtectedByPayPolicy() {
        assertEquals(
                PayAuthenticationPolicy.RequiredPrincipal.NONE,
                PayAuthenticationPolicy.requiredPrincipal("/api/pay/v10/payment-methods"));
    }

    @Test
    void healthEndpointKeepsNoPrincipalRequirement() {
        assertEquals(
                PayAuthenticationPolicy.RequiredPrincipal.NONE,
                PayAuthenticationPolicy.requiredPrincipal("/actuator/health"));
    }
}
