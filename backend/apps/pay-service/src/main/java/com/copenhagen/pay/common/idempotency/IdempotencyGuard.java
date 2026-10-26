package com.copenhagen.pay.common.idempotency;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyGuard {

    public Decision check(String existingRequestHash, String requestHash, String currentState) {
        if (existingRequestHash == null || existingRequestHash.isBlank()) {
            return Decision.NEW;
        }

        if (MessageDigest.isEqual(
            existingRequestHash.getBytes(StandardCharsets.UTF_8),
            requestHash.getBytes(StandardCharsets.UTF_8))) {
            return Decision.REPLAY;
        }

        throw new PayException(
            PayErrorCode.IDEMPOTENCY_CONFLICT,
            PayErrorCode.IDEMPOTENCY_CONFLICT.defaultMessage(),
            currentState,
            PayErrorCode.IDEMPOTENCY_CONFLICT.defaultNextAction());
    }

    public enum Decision {
        NEW,
        REPLAY
    }
}
