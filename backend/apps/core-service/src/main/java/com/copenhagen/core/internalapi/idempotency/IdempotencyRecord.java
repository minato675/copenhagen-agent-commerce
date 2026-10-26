package com.copenhagen.core.internalapi.idempotency;

/** {@code internal_idempotency_keys} 한 행 중 재전송 판단에 필요한 값만. */
public record IdempotencyRecord(String requestHash, int responseStatus, String responseBody) {
}
