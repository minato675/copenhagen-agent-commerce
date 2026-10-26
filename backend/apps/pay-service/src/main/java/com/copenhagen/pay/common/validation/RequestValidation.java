package com.copenhagen.pay.common.validation;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;

public final class RequestValidation {

    private RequestValidation() {
    }

    public static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    fieldName + "은(는) 필수입니다.",
                    null,
                    "CHECK_REQUEST");
        }
        return value;
    }

    public static String requireLengthAtMost(String value, String fieldName, int maxLength) {
        requireNonBlank(value, fieldName);
        if (value.length() > maxLength) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    fieldName + "의 길이가 너무 깁니다.",
                    null,
                    "CHECK_REQUEST");
        }
        return value;
    }
}
