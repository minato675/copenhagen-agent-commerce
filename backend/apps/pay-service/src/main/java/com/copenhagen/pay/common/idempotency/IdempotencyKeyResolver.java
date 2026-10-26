package com.copenhagen.pay.common.idempotency;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyResolver {

    public static final String HEADER_NAME = "Idempotency-Key";

    public IdempotencyKey require(HttpServletRequest request) {
        String value = request.getHeader(HEADER_NAME);
        if (value == null || value.isBlank()) {
            throw new PayException(PayErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        return parse(value);
    }

    public Optional<IdempotencyKey> resolve(HttpServletRequest request) {
        String value = request.getHeader(HEADER_NAME);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parse(value));
    }

    private IdempotencyKey parse(String value) {
        try {
            return new IdempotencyKey(value);
        } catch (PayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PayException(
                    PayErrorCode.IDEMPOTENCY_KEY_INVALID,
                    PayErrorCode.IDEMPOTENCY_KEY_INVALID.defaultMessage(),
                    null,
                    PayErrorCode.IDEMPOTENCY_KEY_INVALID.defaultNextAction(),
                    exception);
        }
    }
}
