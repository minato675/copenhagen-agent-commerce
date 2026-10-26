package com.copenhagen.pay.payment.credential.biometric;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BiometricCredentialRepository {

    Optional<BiometricCredential> findActiveByUserAndDevice(
            String userId,
            String deviceId);

    default List<BiometricCredential> findActiveByUser(String userId) {
        return List.of();
    }

    default Optional<BiometricCredential> findActiveByIdAndUser(
            String credentialId,
            String userId) {
        return Optional.empty();
    }

    void insert(
            String id,
            String userId,
            String deviceId,
            String publicKey,
            Instant registeredAt);

    /** 행을 지우지 않고 REVOKED로 표시한다. 조회는 모두 ACTIVE 기준이라 자동으로 제외된다. */
    default boolean revoke(String userId, String deviceId, Instant revokedAt) {
        return false;
    }
}
