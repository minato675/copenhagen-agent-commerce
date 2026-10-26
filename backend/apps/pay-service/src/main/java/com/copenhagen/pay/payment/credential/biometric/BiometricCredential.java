package com.copenhagen.pay.payment.credential.biometric;

import java.time.Instant;

public record BiometricCredential(
        String id,
        String userId,
        String deviceId,
        String publicKey,
        BiometricCredentialStatus status,
        Instant registeredAt,
        Instant revokedAt) {

    @Override
    public String toString() {
        return "BiometricCredential["
                + "id='" + id + '\''
                + ", userId='" + userId + '\''
                + ", deviceId='" + deviceId + '\''
                + ", status=" + status
                + ", registeredAt=" + registeredAt
                + ", revokedAt=" + revokedAt
                + ']';
    }
}
