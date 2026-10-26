package com.copenhagen.pay.payment.credential.pin;

import java.time.Instant;
import java.util.Optional;

public interface PaymentPinCredentialRepository {

    /**
     * Reads a user's PIN without requiring a row lock.
     *
     * <p>The default keeps existing repository test doubles source-compatible;
     * the JDBC implementation overrides it with a non-locking query.</p>
     */
    default Optional<PaymentPinCredential> findByUserId(String userId) {
        return findByUserIdForUpdate(userId);
    }

    Optional<PaymentPinCredential> findByUserIdForUpdate(String userId);

    void insert(
            String id,
            String userId,
            String pinHash,
            Instant updatedAt);

    default void insert(
            String id,
            String userId,
            String pinHash,
            String registrationIdempotencyKey,
            String registrationRequestHash,
            Instant updatedAt) {
        insert(id, userId, pinHash, updatedAt);
    }

    void updatePin(
            String id,
            String pinHash,
            Instant updatedAt);

    void updateVerificationFailure(
            String id,
            int failedAttempts,
            Instant lockedUntil,
            PaymentPinStatus status,
            Instant updatedAt);

    void resetVerificationState(String id, Instant updatedAt);
}
