package com.copenhagen.core.common.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CoreLogContextTest {

    @AfterEach
    void clearContext() {
        CoreLogContext.clear();
    }

    @Test
    void fingerprintIsStableAndDoesNotExposeOriginalValue() {
        String value = "login-id-for-test";

        String fingerprint = CoreLogContext.fingerprint(value);

        assertNotNull(fingerprint);
        assertEquals(16, fingerprint.length());
        assertNotEquals(value, fingerprint);
        assertEquals(fingerprint, CoreLogContext.fingerprint(value));
    }

    @Test
    void requestIdOrNewStoresGeneratedIdInContext() {
        String requestId = CoreLogContext.requestIdOrNew();

        assertEquals(requestId, CoreLogContext.get(CoreLogContext.REQUEST_ID));
        assertNull(CoreLogContext.get(CoreLogContext.USER_ID));
    }
}
