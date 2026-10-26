package com.copenhagen.pay.payment.credential.biometric;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.authorization.ApprovalProperties;
import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BiometricChallengeService {

    private static final int CHALLENGE_BYTES = 32;

    private final BiometricChallengeRepository challengeRepository;
    private final BiometricCredentialRepository credentialRepository;
    private final BiometricAssertionVerifier assertionVerifier;
    private final Clock clock;
    private final ApprovalProperties approvalProperties;
    private final SecureRandom secureRandom;

    @Autowired
    public BiometricChallengeService(
            BiometricChallengeRepository challengeRepository,
            BiometricCredentialRepository credentialRepository,
            Clock clock,
            ApprovalProperties approvalProperties) {
        this(
                challengeRepository,
                credentialRepository,
                new BiometricAssertionVerifier(),
                clock,
                approvalProperties,
                new SecureRandom());
    }

    BiometricChallengeService(
            BiometricChallengeRepository challengeRepository,
            BiometricCredentialRepository credentialRepository,
            BiometricAssertionVerifier assertionVerifier,
            Clock clock,
            ApprovalProperties approvalProperties,
            SecureRandom secureRandom) {
        this.challengeRepository = challengeRepository;
        this.credentialRepository = credentialRepository;
        this.assertionVerifier = assertionVerifier;
        this.clock = clock;
        this.approvalProperties = approvalProperties;
        this.secureRandom = secureRandom;
    }

    /**
     * 생체 credential 등록 여부만 확인한다. 미등록을 예외로 알리면 호출자가 그것을
     * 삼켜도 트랜잭션이 rollback-only로 남으므로, PIN 폴백은 이 조회로 판단한다.
     */
    @Transactional(readOnly = true)
    public boolean hasRegisteredCredential(String userId, String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            return credentialRepository.findActiveByUserAndDevice(userId, deviceId).isPresent();
        }
        return !credentialRepository.findActiveByUser(userId).isEmpty();
    }

    @Transactional
    public IssuedChallenge issue(
            String userId,
            AuthorizationSession session,
            String deviceId) {
        BiometricCredential credential = selectCredential(userId, deviceId);
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(approvalProperties.challengeExpirationSeconds());
        if (expiresAt.isAfter(session.expiresAt())) {
            expiresAt = session.expiresAt();
        }
        byte[] rawChallenge = new byte[CHALLENGE_BYTES];
        secureRandom.nextBytes(rawChallenge);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(rawChallenge);
        BiometricChallenge challengeRecord = new BiometricChallenge(
                UUID.randomUUID().toString(),
                credential.id(),
                session.id(),
                hash(challenge),
                challenge,
                BiometricChallengeStatus.PENDING,
                expiresAt,
                null,
                now);
        challengeRepository.expirePendingForSession(session.id(), now);
        challengeRepository.insert(challengeRecord);
        return new IssuedChallenge(
                challengeRecord.id(),
                challenge,
                challengeRecord.expiresAt(),
                credential.id());
    }

    @Transactional
    public VerificationResult verify(
            String userId,
            String authorizationSessionId,
            String challengeId,
            String assertion) {
        if (challengeId == null || challengeId.isBlank()) {
            throw new PayException(PayErrorCode.BIOMETRIC_CHALLENGE_INVALID);
        }
        BiometricChallenge storedChallenge = challengeRepository
                .findByIdAndSession(challengeId, authorizationSessionId)
                .orElseThrow(() -> new PayException(PayErrorCode.BIOMETRIC_CHALLENGE_INVALID));
        BiometricCredential credential = credentialRepository
                .findActiveByIdAndUser(storedChallenge.biometricCredentialId(), userId)
                .orElseThrow(() -> new PayException(PayErrorCode.BIOMETRIC_CHALLENGE_INVALID));
        Instant now = clock.instant();
        if (storedChallenge.status() != BiometricChallengeStatus.PENDING) {
            throw new PayException(
                    PayErrorCode.BIOMETRIC_CHALLENGE_REPLAYED,
                    PayErrorCode.BIOMETRIC_CHALLENGE_REPLAYED.defaultMessage(),
                    storedChallenge.status().name(),
                    PayErrorCode.BIOMETRIC_CHALLENGE_REPLAYED.defaultNextAction());
        }
        if (!now.isBefore(storedChallenge.expiresAt())) {
            if (!challengeRepository.markExpiredIfPending(
                    challengeId,
                    authorizationSessionId,
                    now)) {
                throw new PayException(PayErrorCode.BIOMETRIC_CHALLENGE_REPLAYED);
            }
            return VerificationResult.FALLBACK_TO_PIN;
        }
        String challenge = storedChallenge.challengeValue();
        if (challenge == null || challenge.isBlank()
                || !MessageDigest.isEqual(
                storedChallenge.challengeHash().getBytes(StandardCharsets.UTF_8),
                hash(challenge).getBytes(StandardCharsets.UTF_8))
                || assertion == null || assertion.isBlank()) {
            markFailed(authorizationSessionId, challengeId, now);
            return VerificationResult.FALLBACK_TO_PIN;
        }
        if (!assertionVerifier.verify(credential.publicKey(), challenge, assertion)) {
            markFailed(authorizationSessionId, challengeId, now);
            return VerificationResult.FALLBACK_TO_PIN;
        }
        if (!challengeRepository.markSuccessIfPending(challengeId, authorizationSessionId, now)) {
            throw new PayException(PayErrorCode.BIOMETRIC_CHALLENGE_REPLAYED);
        }
        return VerificationResult.SUCCESS;
    }

    private BiometricCredential selectCredential(String userId, String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            return credentialRepository.findActiveByUserAndDevice(userId, deviceId)
                    .orElseThrow(() -> new PayException(
                            PayErrorCode.BIOMETRIC_CREDENTIAL_NOT_REGISTERED));
        }
        List<BiometricCredential> credentials = credentialRepository.findActiveByUser(userId);
        if (credentials.isEmpty()) {
            throw new PayException(PayErrorCode.BIOMETRIC_CREDENTIAL_NOT_REGISTERED);
        }
        if (credentials.size() > 1) {
            throw new PayException(PayErrorCode.BIOMETRIC_DEVICE_REQUIRED);
        }
        return credentials.getFirst();
    }

    private void markFailed(String authorizationSessionId, String challengeId, Instant now) {
        if (!challengeRepository.markFailedIfPending(challengeId, authorizationSessionId, now)) {
            throw new PayException(PayErrorCode.BIOMETRIC_CHALLENGE_REPLAYED);
        }
    }

    private String hash(String challenge) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(challenge.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public enum VerificationResult {
        SUCCESS,
        FALLBACK_TO_PIN
    }

    public record IssuedChallenge(
            String challengeId,
            String challenge,
            Instant expiresAt,
            String biometricCredentialId) {

        @Override
        public String toString() {
            return "IssuedChallenge["
                    + "challengeId='" + challengeId + '\''
                    + ", challenge=<redacted>"
                    + ", expiresAt=" + expiresAt
                    + ", biometricCredentialId='" + biometricCredentialId + '\''
                    + ']';
        }
    }
}
