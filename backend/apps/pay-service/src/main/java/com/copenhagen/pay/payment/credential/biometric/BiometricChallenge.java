package com.copenhagen.pay.payment.credential.biometric;

import java.time.Instant;

public record BiometricChallenge(
        String id,
        String biometricCredentialId,
        String authorizationSessionId,
        String challengeHash,
        String challengeValue,
        BiometricChallengeStatus status,
        Instant expiresAt,
        Instant usedAt,
        Instant createdAt) {

    public BiometricChallenge(
            String id,
            String biometricCredentialId,
            String authorizationSessionId,
            String challengeHash,
            BiometricChallengeStatus status,
            Instant expiresAt,
            Instant usedAt,
            Instant createdAt) {
        this(
                id,
                biometricCredentialId,
                authorizationSessionId,
                challengeHash,
                null,
                status,
                expiresAt,
                usedAt,
                createdAt);
    }

    @Override
    public String toString() {
        return "BiometricChallenge["
                + "id='" + id + '\''
                + ", biometricCredentialId='" + biometricCredentialId + '\''
                + ", authorizationSessionId='" + authorizationSessionId + '\''
                + ", challengeHash=<redacted>"
                + ", challengeValue=<redacted>"
                + ", status=" + status
                + ", expiresAt=" + expiresAt
                + ", usedAt=" + usedAt
                + ", createdAt=" + createdAt
                + ']';
    }
}
