package com.copenhagen.pgmock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PgMockChargeControllerTest {

    private static final PgMockChargeController.ChargeRequest REQUEST =
            new PgMockChargeController.ChargeRequest(
                    "payment-1",
                    "pg-charge-1",
                    "provider-token-1",
                    87_000,
                    "KRW");

    @Test
    void charge_success_returnsApprovedTransaction() {
        PgMockChargeController controller = controller(PgMockOutcome.SUCCESS, 0);

        PgMockChargeController.ChargeResponse response = controller.charge(REQUEST).getBody();

        assertEquals("SUCCESS", response.status());
        assertEquals("pg-pg-charge-1", response.pgTransactionId());
        assertEquals("APPROVED", response.responseCode());
    }

    @Test
    void charge_failed_returnsDeclinedResponse() {
        PgMockChargeController controller = controller(PgMockOutcome.FAILED, 0);

        PgMockChargeController.ChargeResponse response = controller.charge(REQUEST).getBody();

        assertEquals("FAILED", response.status());
        assertEquals("DECLINED", response.responseCode());
    }

    @Test
    void charge_timeout_returnsUnknownAfterConfiguredDelay() {
        PgMockChargeController controller = controller(PgMockOutcome.TIMEOUT, 1);

        PgMockChargeController.ChargeResponse response = controller.charge(REQUEST).getBody();

        assertEquals("UNKNOWN", response.status());
        assertEquals("TIMEOUT", response.responseCode());
    }

    @ParameterizedTest
    @EnumSource(value = PgMockOutcome.class, names = {"SUCCESS", "FAILED", "UNKNOWN"})
    void lookup_afterTimeoutReturnsExistingTransaction(PgMockOutcome outcome) {
        PgMockChargeController controller = new PgMockChargeController(
                new PgMockProperties(PgMockOutcome.TIMEOUT, 1, outcome));
        controller.charge(REQUEST);

        PgMockChargeController.ChargeResponse byRequest = controller.lookup(null, REQUEST.pgRequestId()).getBody();
        PgMockChargeController.ChargeResponse byTransaction = controller.lookup(
                byRequest.pgTransactionId(), null).getBody();

        assertEquals(outcome.name(), byRequest.status());
        assertEquals(byRequest, byTransaction);
        assertEquals(404, controller.lookup(null, "never-charged").getStatusCode().value());
    }

    private PgMockChargeController controller(PgMockOutcome outcome, long delayMillis) {
        return new PgMockChargeController(new PgMockProperties(outcome, delayMillis, PgMockOutcome.UNKNOWN));
    }
}
