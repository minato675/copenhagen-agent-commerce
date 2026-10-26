package com.copenhagen.pay.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RequestHashGeneratorTest {

    private final RequestHashGenerator generator = new RequestHashGenerator(new ObjectMapper());

    @Test
    void objectPropertyOrderDoesNotChangeHash() {
        String first = generator.generate(
                "POST",
                "/api/pay/v1/payment-approvals",
                Map.of("status", List.of("PENDING")),
                Map.of("amountMinor", 10000, "currency", "KRW"));
        String second = generator.generate(
                "post",
                "/api/pay/v1/payment-approvals",
                Map.of("status", List.of("PENDING")),
                Map.of("currency", "KRW", "amountMinor", 10000));

        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    void methodPathQueryOrBodyChangeChangesHash() {
        String first = generator.generate(
                "POST",
                "/api/pay/v1/payment-approvals",
                Map.of(),
                Map.of("amountMinor", 10000));
        String changed = generator.generate(
                "POST",
                "/api/pay/v1/payment-approvals",
                Map.of(),
                Map.of("amountMinor", 20000));

        assertNotEquals(first, changed);
    }

    @Test
    void emptyBodyIsHashedInsteadOfRejected() {
        String hash = generator.generateBody(new byte[0]);

        assertEquals(64, hash.length());
        assertEquals(hash, generator.generateBody((byte[]) null));
    }

    @Test
    void emptyBodyHashDiffersFromEmptyObjectBody() {
        String empty = generator.generateBody(new byte[0]);
        String emptyObject = generator.generateBody("{}".getBytes(StandardCharsets.UTF_8));

        assertNotEquals(empty, emptyObject);
    }

    @Test
    void rawJsonUsesJcsBeforeSha256() {
        String first = generator.generateBody(
                "{ \"amount\": 1.0, \"items\": [true, null] }"
                        .getBytes(StandardCharsets.UTF_8));
        String second = generator.generateBody(
                "{\"items\":[true,null],\"amount\":1}"
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(first, second);
    }
}
