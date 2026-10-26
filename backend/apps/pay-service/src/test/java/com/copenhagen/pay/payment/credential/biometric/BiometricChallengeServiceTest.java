package com.copenhagen.pay.payment.credential.biometric;

import com.copenhagen.pay.payment.authorization.AuthorizationSessionFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.authorization.ApprovalProperties;
import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BiometricChallengeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");

    private InMemoryChallengeRepository challengeRepository;
    private InMemoryCredentialRepository credentialRepository;
    private BiometricChallengeService service;
    private KeyPair keyPair;
    private BiometricCredential credential;
    private AuthorizationSession session;

    @BeforeEach
    void setUp() throws Exception {
        challengeRepository = new InMemoryChallengeRepository();
        credentialRepository = new InMemoryCredentialRepository();
        keyPair = KeyPairGenerator.getInstance("EC").generateKeyPair();
        credential = new BiometricCredential(
                "credential-001",
                "user-001",
                "device-001",
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                        keyPair.getPublic().getEncoded()),
                BiometricCredentialStatus.ACTIVE,
                NOW,
                null);
        credentialRepository.credentials.put(credential.id(), credential);
        session = AuthorizationSessionFixtures.singleMerchant(
                "session-001",
                "purchase-001",
                "user-001",
                "merchant-account-001",
                "merchant-001",
                "예시 상점",
                "checkout-001",
                "0".repeat(64),
                "{}",
                99000,
                "KRW",
                "method-001",
                AuthorizationSessionStatus.PENDING,
                "idempotency-001",
                "request-hash",
                "BIOMETRIC",
                NOW.plusSeconds(300),
                null,
                null,
                NOW,
                NOW,
                0);
        service = new BiometricChallengeService(
                challengeRepository,
                credentialRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ApprovalProperties(300, 60, true));
    }

    @Test
    void challengeIsHashedAndAValidAssertionCanBeConsumedOnlyOnce() throws Exception {
        BiometricChallengeService.IssuedChallenge issued = service.issue(
                "user-001",
                session,
                "device-001");
        String assertion = sign(issued.challenge());

        BiometricChallengeService.VerificationResult result = service.verify(
                "user-001",
                session.id(),
                issued.challengeId(),
                assertion);

        assertEquals(BiometricChallengeService.VerificationResult.SUCCESS, result);
        assertEquals(
                issued.challenge(),
                challengeRepository.challenges.get(issued.challengeId()).challengeValue());
        assertNotEquals(issued.challenge(), challengeRepository.challenges
                .get(issued.challengeId()).challengeHash());
        assertEquals(
                BiometricChallengeStatus.SUCCESS,
                challengeRepository.challenges.get(issued.challengeId()).status());

        PayException replay = assertThrows(
                PayException.class,
                () -> service.verify(
                        "user-001",
                        session.id(),
                        issued.challengeId(),
                        assertion));
        assertEquals(PayErrorCode.BIOMETRIC_CHALLENGE_REPLAYED, replay.errorCode());
    }

    @Test
    void invalidAssertionConsumesChallengeAndRequestsPinFallback() {
        BiometricChallengeService.IssuedChallenge issued = service.issue(
                "user-001",
                session,
                "device-001");

        BiometricChallengeService.VerificationResult result = service.verify(
                "user-001",
                session.id(),
                issued.challengeId(),
                "invalid-signature");

        assertEquals(BiometricChallengeService.VerificationResult.FALLBACK_TO_PIN, result);
        assertEquals(
                BiometricChallengeStatus.FAILED,
                challengeRepository.challenges.get(issued.challengeId()).status());
    }

    @Test
    void challengeCannotBeUsedWithAnotherAuthorizationSession() {
        BiometricChallengeService.IssuedChallenge issued = service.issue(
                "user-001",
                session,
                "device-001");

        PayException exception = assertThrows(
                PayException.class,
                () -> service.verify(
                        "user-001",
                        "session-002",
                        issued.challengeId(),
                        "invalid-signature"));

        assertEquals(PayErrorCode.BIOMETRIC_CHALLENGE_INVALID, exception.errorCode());
        assertEquals(
                BiometricChallengeStatus.PENDING,
                challengeRepository.challenges.get(issued.challengeId()).status());
    }

    @Test
    void expiredChallengeIsConsumedAndFallsBackToPin() {
        BiometricChallengeService.IssuedChallenge issued = service.issue(
                "user-001",
                session,
                "device-001");
        BiometricChallenge stored = challengeRepository.challenges.get(issued.challengeId());
        challengeRepository.challenges.put(
                issued.challengeId(),
                new BiometricChallenge(
                        stored.id(),
                        stored.biometricCredentialId(),
                        stored.authorizationSessionId(),
                        stored.challengeHash(),
                        stored.challengeValue(),
                        stored.status(),
                        NOW.minusSeconds(1),
                        stored.usedAt(),
                        stored.createdAt()));

        BiometricChallengeService.VerificationResult result = service.verify(
                "user-001",
                session.id(),
                issued.challengeId(),
                "invalid-signature");

        assertEquals(BiometricChallengeService.VerificationResult.FALLBACK_TO_PIN, result);
        assertEquals(
                BiometricChallengeStatus.EXPIRED,
                challengeRepository.challenges.get(issued.challengeId()).status());
    }

    private String sign(String challenge) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(challenge.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private static final class InMemoryCredentialRepository
            implements BiometricCredentialRepository {

        private final Map<String, BiometricCredential> credentials = new HashMap<>();

        @Override
        public Optional<BiometricCredential> findActiveByUserAndDevice(
                String userId,
                String deviceId) {
            return credentials.values().stream()
                    .filter(credential -> credential.userId().equals(userId))
                    .filter(credential -> credential.deviceId().equals(deviceId))
                    .filter(credential -> credential.status() == BiometricCredentialStatus.ACTIVE)
                    .findFirst();
        }

        @Override
        public List<BiometricCredential> findActiveByUser(String userId) {
            return credentials.values().stream()
                    .filter(credential -> credential.userId().equals(userId))
                    .filter(credential -> credential.status() == BiometricCredentialStatus.ACTIVE)
                    .toList();
        }

        @Override
        public Optional<BiometricCredential> findActiveByIdAndUser(
                String credentialId,
                String userId) {
            return Optional.ofNullable(credentials.get(credentialId))
                    .filter(credential -> credential.userId().equals(userId))
                    .filter(credential -> credential.status() == BiometricCredentialStatus.ACTIVE);
        }

        @Override
        public void insert(
                String id,
                String userId,
                String deviceId,
                String publicKey,
                Instant registeredAt) {
            credentials.put(
                    id,
                    new BiometricCredential(
                            id,
                            userId,
                            deviceId,
                            publicKey,
                            BiometricCredentialStatus.ACTIVE,
                            registeredAt,
                            null));
        }
    }

    private static final class InMemoryChallengeRepository
            implements BiometricChallengeRepository {

        private final Map<String, BiometricChallenge> challenges = new HashMap<>();

        @Override
        public int expirePendingForSession(String authorizationSessionId, Instant now) {
            int count = 0;
            for (BiometricChallenge challenge : new ArrayList<>(challenges.values())) {
                if (challenge.authorizationSessionId().equals(authorizationSessionId)
                        && challenge.status() == BiometricChallengeStatus.PENDING) {
                    challenges.put(challenge.id(), new BiometricChallenge(
                            challenge.id(),
                            challenge.biometricCredentialId(),
                            challenge.authorizationSessionId(),
                            challenge.challengeHash(),
                            challenge.challengeValue(),
                            BiometricChallengeStatus.EXPIRED,
                            challenge.expiresAt(),
                            now,
                            challenge.createdAt()));
                    count++;
                }
            }
            return count;
        }

        @Override
        public void insert(BiometricChallenge challenge) {
            challenges.put(challenge.id(), challenge);
        }

        @Override
        public Optional<BiometricChallenge> findByIdAndSession(
                String challengeId,
                String authorizationSessionId) {
            return Optional.ofNullable(challenges.get(challengeId))
                    .filter(challenge -> challenge.authorizationSessionId()
                            .equals(authorizationSessionId));
        }

        @Override
        public boolean markSuccessIfPending(
                String challengeId,
                String authorizationSessionId,
                Instant now) {
            return transition(
                    challengeId,
                    authorizationSessionId,
                    now,
                    BiometricChallengeStatus.SUCCESS,
                    true);
        }

        @Override
        public boolean markFailedIfPending(
                String challengeId,
                String authorizationSessionId,
                Instant now) {
            return transition(
                    challengeId,
                    authorizationSessionId,
                    now,
                    BiometricChallengeStatus.FAILED,
                    true);
        }

        @Override
        public boolean markExpiredIfPending(
                String challengeId,
                String authorizationSessionId,
                Instant now) {
            return transition(
                    challengeId,
                    authorizationSessionId,
                    now,
                    BiometricChallengeStatus.EXPIRED,
                    false);
        }

        private boolean transition(
                String challengeId,
                String authorizationSessionId,
                Instant now,
                BiometricChallengeStatus target,
                boolean requiresUnexpired) {
            BiometricChallenge challenge = challenges.get(challengeId);
            if (challenge == null
                    || !challenge.authorizationSessionId().equals(authorizationSessionId)
                    || challenge.status() != BiometricChallengeStatus.PENDING
                    || (requiresUnexpired
                    ? !challenge.expiresAt().isAfter(now)
                    : challenge.expiresAt().isAfter(now))) {
                return false;
            }
            challenges.put(challengeId, new BiometricChallenge(
                    challenge.id(),
                    challenge.biometricCredentialId(),
                    challenge.authorizationSessionId(),
                    challenge.challengeHash(),
                    challenge.challengeValue(),
                    target,
                    challenge.expiresAt(),
                    now,
                    challenge.createdAt()));
            return true;
        }
    }
}
