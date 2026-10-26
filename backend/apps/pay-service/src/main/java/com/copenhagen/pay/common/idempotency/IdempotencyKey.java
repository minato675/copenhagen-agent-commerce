package com.copenhagen.pay.common.idempotency;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import java.util.UUID;

public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 100;

    public IdempotencyKey {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw new PayException(PayErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
        if (!isUuid(value)) {
            throw new PayException(PayErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
    }

    /**
     * API 계약에서 사용하는 표준 UUID 문자열인지 확인합니다.
     */
    public static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
