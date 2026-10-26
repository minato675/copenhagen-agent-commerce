package com.copenhagen.pay.payment.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyKey;
import com.copenhagen.pay.common.idempotency.RequestHashGenerator;
import com.copenhagen.pay.common.web.PayRequestContext;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthorizationSessionControllerTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-09-15T00:05:00Z");

    @Test
    void legacySingleMerchantRequestBecomesOneItemSession() {
        // Core 배포 시점을 분리할 수 있도록 구 단건 계약(평면 필드)도 함께 받는다.
        AuthorizationSessionService service = org.mockito.Mockito.mock(
                AuthorizationSessionService.class);
        AuthorizationApprovalService approvalService = org.mockito.Mockito.mock(
                AuthorizationApprovalService.class);
        RequestHashGenerator requestHashGenerator = org.mockito.Mockito.mock(
                RequestHashGenerator.class);
        AuthorizationSessionController controller = new AuthorizationSessionController(
                service,
                approvalService,
                requestHashGenerator);
        MockHttpServletRequest request = new MockHttpServletRequest();
        PayRequestContext.setIdempotencyKey(request, new IdempotencyKey("3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"));
        when(requestHashGenerator.generate(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn("hash-001");
        when(service.create(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"),
                org.mockito.ArgumentMatchers.eq("hash-001")))
                .thenReturn(new AuthorizationSessionService.CreateResult(session(), false));

        controller.create(
                new AuthorizationSessionController.CreateAuthorizationSessionRequest(
                        "purchase-001",
                        "user-001",
                        null,
                        "KRW",
                        null,
                        "merchant-001",
                        "checkout-001",
                        "sha256:hash",
                        "aaa.bbb.ccc",
                        new AuthorizationSessionController.DisplaySnapshotRequest(
                                "예시 상점",
                                java.util.List.of(
                                        new AuthorizationSessionController.SummaryItemRequest(
                                                "러닝화",
                                                null,
                                                1,
                                                99000))),
                        99000L),
                request);

        org.mockito.ArgumentCaptor<CreateAuthorizationSessionCommand> captor =
                org.mockito.ArgumentCaptor.forClass(CreateAuthorizationSessionCommand.class);
        verify(service).create(
                captor.capture(),
                org.mockito.ArgumentMatchers.eq("3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"),
                org.mockito.ArgumentMatchers.eq("hash-001"));
        CreateAuthorizationSessionCommand command = captor.getValue();
        assertEquals(1, command.items().size());
        assertEquals("merchant-001", command.items().getFirst().merchantId());
        assertEquals(99000, command.totalAmountMinor());
    }

    @Test
    void selectsPaymentMethodWithAuthenticatedUserAndReturnsContractResponse() {
        AuthorizationSessionService service = org.mockito.Mockito.mock(
                AuthorizationSessionService.class);
        AuthorizationApprovalService approvalService = org.mockito.Mockito.mock(
                AuthorizationApprovalService.class);
        RequestHashGenerator requestHashGenerator = org.mockito.Mockito.mock(
                RequestHashGenerator.class);
        AuthorizationSessionController controller = new AuthorizationSessionController(
                service,
                approvalService,
                requestHashGenerator);
        AuthorizationSession session = session();
        when(service.selectPaymentMethod("user-001", "approval-001", "method-002"))
                .thenReturn(session);
        HttpServletRequest request = authenticatedRequest(true);

        ResponseEntity<AuthorizationSessionController.PaymentMethodSelectionResponse> response =
                controller.selectPaymentMethod(
                        "approval-001",
                        new AuthorizationSessionController.PaymentMethodSelectionRequest(
                                "method-002"),
                        request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(
                new AuthorizationSessionController.PaymentMethodSelectionResponse(
                        "approval-001",
                        "PENDING",
                        "method-002",
                        "BIOMETRIC",
                        EXPIRES_AT,
                        "APPROVE_IN_APP"),
                response.getBody());
        verify(service).selectPaymentMethod("user-001", "approval-001", "method-002");
    }

    @Test
    void requiresIdempotencyKeyBeforeChangingPaymentMethod() {
        AuthorizationSessionService service = org.mockito.Mockito.mock(
                AuthorizationSessionService.class);
        AuthorizationSessionController controller = new AuthorizationSessionController(
                service,
                org.mockito.Mockito.mock(AuthorizationApprovalService.class),
                org.mockito.Mockito.mock(RequestHashGenerator.class));

        PayException exception = assertThrows(
                PayException.class,
                () -> controller.selectPaymentMethod(
                        "approval-001",
                        new AuthorizationSessionController.PaymentMethodSelectionRequest(
                                "method-002"),
                        authenticatedRequest(false)));

        assertEquals(PayErrorCode.IDEMPOTENCY_KEY_REQUIRED, exception.errorCode());
        verifyNoInteractions(service);
    }

    private HttpServletRequest authenticatedRequest(boolean withIdempotencyKey) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PATCH",
                "/api/pay/v1/payment-approvals/approval-001/payment-method");
        PayRequestContext.setPrincipal(
                request,
                new PayPrincipal(PayPrincipal.Type.USER, "user-001"));
        if (withIdempotencyKey) {
            PayRequestContext.setIdempotencyKey(
                    request,
                    new IdempotencyKey("550e8400-e29b-41d4-a716-446655440013"));
        }
        return request;
    }

    private AuthorizationSession session() {
        return AuthorizationSessionFixtures.singleMerchant(
                "approval-001",
                "purchase-001",
                "user-001",
                "merchant-account-001",
                "merchant-001",
                "예시 상점",
                "checkout-001",
                "a".repeat(64),
                "{\"merchantName\":\"예시 상점\"}",
                99000,
                "KRW",
                "method-002",
                AuthorizationSessionStatus.PENDING,
                "create-001",
                "b".repeat(64),
                "BIOMETRIC",
                EXPIRES_AT,
                null,
                null,
                Instant.parse("2026-09-15T00:00:00Z"),
                Instant.parse("2026-09-15T00:00:01Z"),
                1);
    }
}
