package com.copenhagen.pay.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

    @Test
    void blankKeyIsRejected() {
        PayException exception = assertThrows(
                PayException.class,
                () -> new IdempotencyKey(" "));

        assertEquals(PayErrorCode.IDEMPOTENCY_KEY_INVALID, exception.errorCode());
    }

    @Test
    void controlCharacterIsRejected() {
        PayException exception = assertThrows(
                PayException.class,
                () -> new IdempotencyKey("key\nvalue"));

        assertEquals(PayErrorCode.IDEMPOTENCY_KEY_INVALID, exception.errorCode());
    }

    @Test
    void uuidValueIsAccepted() {
        IdempotencyKey key = new IdempotencyKey("550e8400-e29b-41d4-a716-446655440000");

        assertEquals("550e8400-e29b-41d4-a716-446655440000", key.value());
        assertTrue(IdempotencyKey.isUuid(key.value()));
    }

    @Test
    void prefixedUuidIsRejected() {
        PayException exception = assertThrows(
                PayException.class,
                () -> new IdempotencyKey("register-card-550e8400-e29b-41d4-a716-446655440000"));

        assertEquals(PayErrorCode.IDEMPOTENCY_KEY_INVALID, exception.errorCode());
    }

    @Test
    void tooLongKeyIsRejected() {
        PayException exception = assertThrows(
                PayException.class,
                () -> new IdempotencyKey("a".repeat(IdempotencyKey.MAX_LENGTH + 1)));

        assertEquals(PayErrorCode.IDEMPOTENCY_KEY_INVALID, exception.errorCode());
    }
}
