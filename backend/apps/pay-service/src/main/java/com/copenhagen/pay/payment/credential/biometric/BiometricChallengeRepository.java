package com.copenhagen.pay.payment.credential.biometric;

import java.time.Instant;
import java.util.Optional;

public interface BiometricChallengeRepository {

    int expirePendingForSession(String authorizationSessionId, Instant now);

    void insert(BiometricChallenge challenge);

    Optional<BiometricChallenge> findByIdAndSession(
            String challengeId,
            String authorizationSessionId);

    boolean markSuccessIfPending(
            String challengeId,
            String authorizationSessionId,
            Instant now);

    boolean markFailedIfPending(
            String challengeId,
            String authorizationSessionId,
            Instant now);

    boolean markExpiredIfPending(
            String challengeId,
            String authorizationSessionId,
            Instant now);
}
