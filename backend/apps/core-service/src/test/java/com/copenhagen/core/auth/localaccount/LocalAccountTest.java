package com.copenhagen.core.auth.localaccount;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAccountTest {

    @Test
    @DisplayName("toString()은 password_hash 원문을 노출하지 않는다")
    void toString_doesNotExposePasswordHash() {
        LocalAccount localAccount = new LocalAccount(
                1L,
                1L,
                "user1",
                "secret-hash-value",
                0,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH);

        String result = localAccount.toString();

        assertFalse(result.contains("secret-hash-value"));
        assertTrue(result.contains("loginId='user1'"));
    }
}
