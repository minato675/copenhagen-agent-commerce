package com.copenhagen.pgmock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class PgMockApplicationTest {

    @Test
    void applicationEntryPointExists() {
        assertNotNull(PgMockApplication.class);
    }
}
