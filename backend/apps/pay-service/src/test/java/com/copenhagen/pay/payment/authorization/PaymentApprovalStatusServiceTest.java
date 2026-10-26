package com.copenhagen.pay.payment.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.copenhagen.contract.internalapi.ApprovalStatus;
import com.copenhagen.contract.internalapi.PaymentApprovalNextAction;
import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class PaymentApprovalStatusServiceTest {

    private static final String APPROVAL_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void returnsWaitForPaymentCreationWhenApprovedPaymentDoesNotExist() {
        PaymentApprovalStatusRepository repository =
                org.mockito.Mockito.mock(PaymentApprovalStatusRepository.class);
        PaymentApprovalStatusService service = new PaymentApprovalStatusService(repository);
        when(repository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(view(
                AuthorizationSessionStatus.APPROVED,
                null,
                null)));

        PaymentApprovalStatusResponse response = service.find(APPROVAL_ID);

        assertEquals(APPROVAL_ID, response.approvalId());
        assertEquals(ApprovalStatus.APPROVED, response.approvalStatus());
        assertNull(response.paymentId());
        assertNull(response.paymentStatus());
        assertEquals(87000, response.amountMinor());
        assertEquals("KRW", response.currency());
        assertEquals(
                PaymentApprovalNextAction.WAIT_FOR_PAYMENT_CREATION,
                response.nextAction());
        verify(repository).findByApprovalId(APPROVAL_ID);
    }

    @Test
    void returnsNoNextActionWhenNonApprovedPaymentDoesNotExist() {
        PaymentApprovalStatusRepository repository =
                org.mockito.Mockito.mock(PaymentApprovalStatusRepository.class);
        PaymentApprovalStatusService service = new PaymentApprovalStatusService(repository);
        when(repository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(view(
                AuthorizationSessionStatus.PENDING,
                null,
                null)));

        PaymentApprovalStatusResponse response = service.find(APPROVAL_ID);

        assertEquals(ApprovalStatus.PENDING, response.approvalStatus());
        assertNull(response.paymentId());
        assertNull(response.paymentStatus());
        assertNull(response.nextAction());
    }

    @Test
    void mapsLivePaymentStatusesToWaitForPaymentStatus() {
        PaymentApprovalStatusRepository repository =
                org.mockito.Mockito.mock(PaymentApprovalStatusRepository.class);
        PaymentApprovalStatusService service = new PaymentApprovalStatusService(repository);

        for (PaymentStatus status : new PaymentStatus[] {
            PaymentStatus.READY,
            PaymentStatus.PROCESSING,
            PaymentStatus.UNKNOWN
        }) {
            when(repository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(view(
                    AuthorizationSessionStatus.APPROVED,
                    "22222222-2222-2222-2222-222222222222",
                    status)));

            PaymentApprovalStatusResponse response = service.find(APPROVAL_ID);

            assertEquals(status.name(), response.paymentStatus().name());
            assertEquals(
                    PaymentApprovalNextAction.WAIT_FOR_PAYMENT_STATUS,
                    response.nextAction());
        }
    }

    @Test
    void mapsTerminalPaymentStatusesToNoNextAction() {
        PaymentApprovalStatusRepository repository =
                org.mockito.Mockito.mock(PaymentApprovalStatusRepository.class);
        PaymentApprovalStatusService service = new PaymentApprovalStatusService(repository);

        for (PaymentStatus status : new PaymentStatus[] {
            PaymentStatus.SUCCESS,
            PaymentStatus.FAILED
        }) {
            when(repository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(view(
                    AuthorizationSessionStatus.APPROVED,
                    "22222222-2222-2222-2222-222222222222",
                    status)));

            PaymentApprovalStatusResponse response = service.find(APPROVAL_ID);

            assertEquals(status.name(), response.paymentStatus().name());
            assertNull(response.nextAction());
        }
    }

    @Test
    void normalizesUppercaseUuidBeforeQuerying() {
        PaymentApprovalStatusRepository repository =
                org.mockito.Mockito.mock(PaymentApprovalStatusRepository.class);
        PaymentApprovalStatusService service = new PaymentApprovalStatusService(repository);
        when(repository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(view(
                AuthorizationSessionStatus.APPROVED,
                null,
                null)));

        service.find(APPROVAL_ID.toUpperCase(java.util.Locale.ROOT));

        verify(repository).findByApprovalId(APPROVAL_ID);
    }

    @Test
    void rejectsMalformedApprovalIdBeforeDatabaseAccess() {
        PaymentApprovalStatusRepository repository =
                org.mockito.Mockito.mock(PaymentApprovalStatusRepository.class);
        PaymentApprovalStatusService service = new PaymentApprovalStatusService(repository);

        PayException exception = assertThrows(
                PayException.class,
                () -> service.find("approval-001"));

        assertEquals(PayErrorCode.INVALID_REQUEST, exception.errorCode());
        verifyNoInteractions(repository);
    }

    @Test
    void returnsNotFoundWhenApprovalSessionDoesNotExist() {
        PaymentApprovalStatusRepository repository =
                org.mockito.Mockito.mock(PaymentApprovalStatusRepository.class);
        PaymentApprovalStatusService service = new PaymentApprovalStatusService(repository);
        when(repository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.empty());

        PayException exception = assertThrows(
                PayException.class,
                () -> service.find(APPROVAL_ID));

        assertEquals(PayErrorCode.RESOURCE_NOT_FOUND, exception.errorCode());
    }

    @Test
    void letsDatabaseFailureReachTheGlobalHandler() {
        PaymentApprovalStatusRepository repository =
                org.mockito.Mockito.mock(PaymentApprovalStatusRepository.class);
        PaymentApprovalStatusService service = new PaymentApprovalStatusService(repository);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(
                "database unavailable");
        when(repository.findByApprovalId(APPROVAL_ID)).thenThrow(failure);

        assertEquals(
                failure,
                assertThrows(
                        DataAccessResourceFailureException.class,
                        () -> service.find(APPROVAL_ID)));
    }

    @Test
    void passesThroughCredentialIdWhenIssued() {
        PaymentApprovalStatusRepository repository =
                org.mockito.Mockito.mock(PaymentApprovalStatusRepository.class);
        PaymentApprovalStatusService service = new PaymentApprovalStatusService(repository);
        java.time.Instant credentialExpiresAt = java.time.Instant.parse("2026-09-18T00:05:00Z");
        when(repository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(
                new PaymentApprovalStatusRepository.PaymentApprovalStatusView(
                        APPROVAL_ID,
                        AuthorizationSessionStatus.APPROVED,
                        87000,
                        "KRW",
                        java.util.List.of(new PaymentApprovalStatusRepository.ItemView(
                                "merchant-001",
                                "checkout-001",
                                "33333333-3333-3333-3333-333333333333",
                                credentialExpiresAt,
                                null,
                                null,
                                "VISA **** 1234")))));

        PaymentApprovalStatusResponse response = service.find(APPROVAL_ID);

        assertEquals("33333333-3333-3333-3333-333333333333", response.credentialId());
        assertEquals(credentialExpiresAt, response.credentialExpiresAt());
        assertEquals("VISA **** 1234", response.paymentLabel());
    }

    @Test
    void credentialFieldsAreNullBeforeApproval() {
        PaymentApprovalStatusRepository repository =
                org.mockito.Mockito.mock(PaymentApprovalStatusRepository.class);
        PaymentApprovalStatusService service = new PaymentApprovalStatusService(repository);
        when(repository.findByApprovalId(APPROVAL_ID)).thenReturn(Optional.of(view(
                AuthorizationSessionStatus.PENDING,
                null,
                null)));

        PaymentApprovalStatusResponse response = service.find(APPROVAL_ID);

        assertNull(response.credentialId());
        assertNull(response.credentialExpiresAt());
    }

    private PaymentApprovalStatusRepository.PaymentApprovalStatusView view(
            AuthorizationSessionStatus approvalStatus,
            String paymentId,
            PaymentStatus paymentStatus) {
        return new PaymentApprovalStatusRepository.PaymentApprovalStatusView(
                APPROVAL_ID,
                approvalStatus,
                87000,
                "KRW",
                java.util.List.of(new PaymentApprovalStatusRepository.ItemView(
                        "merchant-001",
                        "checkout-001",
                        null,
                        null,
                        paymentId,
                        paymentStatus,
                        "Copenhagen Pay")));
    }
}
