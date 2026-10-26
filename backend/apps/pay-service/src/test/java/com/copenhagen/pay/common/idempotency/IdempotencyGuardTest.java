package com.copenhagen.pay.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import org.junit.jupiter.api.Test;

class IdempotencyGuardTest {

    private final IdempotencyGuard guard = new IdempotencyGuard();

    @Test
    void unseenRequestIsNew() {
        assertEquals(
                IdempotencyGuard.Decision.NEW,
                guard.check(null, "request-hash", null));
    }

    @Test
    void sameHashIsReplay() {
        assertEquals(
                IdempotencyGuard.Decision.REPLAY,
                guard.check("request-hash", "request-hash", "PENDING"));
    }

    @Test
    void differentHashIsConflict() {
        PayException exception = assertThrows(
                PayException.class,
                () -> guard.check("old-hash", "new-hash", "PENDING"));

        assertEquals(PayErrorCode.IDEMPOTENCY_CONFLICT, exception.errorCode());
        assertEquals("PENDING", exception.currentState());
    }
}
