package com.copenhagen.pay.common.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PayLogContextTest {

    @AfterEach
    void clearContext() {
        PayLogContext.clear();
    }

    @Test
    void fingerprintIsStableAndDoesNotExposeOriginalValue() {
        String fid = "fcm-installation-token-for-test";

        String fingerprint = PayLogContext.fingerprint(fid);

        assertNotNull(fingerprint);
        assertEquals(16, fingerprint.length());
        assertNotEquals(fid, fingerprint);
        assertEquals(fingerprint, PayLogContext.fingerprint(fid));
    }

    @Test
    void scopeRestoresPreviousValuesAfterNestedOperation() {
        PayLogContext.put(PayLogContext.USER_ID, "outer-user");

        try (PayLogContext.Scope ignored = PayLogContext.open(
                PayLogContext.USER_ID, "inner-user",
                PayLogContext.PAYMENT_ID, "payment-1")) {
            assertEquals("inner-user", PayLogContext.get(PayLogContext.USER_ID));
            assertEquals("payment-1", PayLogContext.get(PayLogContext.PAYMENT_ID));
        }

        assertEquals("outer-user", PayLogContext.get(PayLogContext.USER_ID));
        assertNull(PayLogContext.get(PayLogContext.PAYMENT_ID));
    }

    @Test
    void workerContextCreatesUniqueRequestId() {
        try (PayLogContext.Scope ignored = PayLogContext.openWorker("notification")) {
            String requestId = PayLogContext.get(PayLogContext.REQUEST_ID);

            assertNotNull(requestId);
            assertTrue(requestId.startsWith("notification-"));
        }

        assertNull(PayLogContext.get(PayLogContext.REQUEST_ID));
    }

    @Test
    void requestIdOrNewStoresGeneratedIdInContext() {
        String requestId = PayLogContext.requestIdOrNew();

        assertEquals(requestId, PayLogContext.get(PayLogContext.REQUEST_ID));
    }

    @Test
    void fingerprintContextDoesNotStoreOriginalValue() {
        PayLogContext.putFingerprint(PayLogContext.USER_ID, "user-001");

        assertNotEquals("user-001", PayLogContext.get(PayLogContext.USER_ID));
        assertEquals(
                PayLogContext.fingerprint("user-001"),
                PayLogContext.get(PayLogContext.USER_ID));
    }
}
