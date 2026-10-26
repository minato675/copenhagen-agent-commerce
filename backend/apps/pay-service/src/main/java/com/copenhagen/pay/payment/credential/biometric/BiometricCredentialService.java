package com.copenhagen.pay.payment.credential.biometric;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.authorization.AuthorizationSessionStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BiometricCredentialService {

    private static final String PLATFORM_BIOMETRIC = "PLATFORM_BIOMETRIC";
    private static final int MAX_PUBLIC_KEY_LENGTH = 8192;
    private static final int MAX_DEVICE_ID_LENGTH = 150;

    private final BiometricCredentialRepository repository;
    private final AuthorizationSessionStore sessionStore;
    private final Clock clock;

    public BiometricCredentialService(
            BiometricCredentialRepository repository,
            AuthorizationSessionStore sessionStore,
            Clock clock) {
        this.repository = repository;
        this.sessionStore = sessionStore;
        this.clock = clock;
    }

    /** 앱이 로컬 상태만으로는 서버 등록 여부를 알 수 없어, 기기 기준 단건으로 알려준다. */
    @Transactional(readOnly = true)
    public Optional<BiometricCredential> findActive(String userId, String deviceId) {
        validateDeviceId(deviceId);
        return repository.findActiveByUserAndDevice(userId, deviceId);
    }

    /**
     * 기기의 credential을 무효화하고, 진행 중인 승인 세션을 PIN으로 돌린다.
     *
     * <p>세션을 같이 돌리지 않으면 사용자가 PIN을 입력해도 {@code ensureRequiredAction}에서
     * 거절된다. 해제 대상이 없어도 예외를 던지지 않는다 — 호출 후 상태는 항상 "이 기기에
     * ACTIVE credential이 없다"로 같다.
     */
    @Transactional
    public void revoke(String userId, String deviceId) {
        validateDeviceId(deviceId);
        Instant now = clock.instant();
        repository.revoke(userId, deviceId, now);
        sessionStore.fallbackPendingToPin(userId, now);
    }

    @Transactional
    public BiometricCredential register(
            String userId,
            String deviceId,
            String publicKey,
            String credentialType) {
        validate(deviceId, publicKey, credentialType);
        if (repository.findActiveByUserAndDevice(userId, deviceId).isPresent()) {
            throw new PayException(PayErrorCode.BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED);
        }

        String credentialId = UUID.randomUUID().toString();
        Instant registeredAt = clock.instant();
        try {
            repository.insert(
                    credentialId,
                    userId,
                    deviceId,
                    publicKey,
                    registeredAt);
        } catch (DuplicateKeyException exception) {
            throw new PayException(
                    PayErrorCode.BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED,
                    PayErrorCode.BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED.defaultMessage(),
                    null,
                    PayErrorCode.BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED.defaultNextAction(),
                    exception);
        }
        return new BiometricCredential(
                credentialId,
                userId,
                deviceId,
                publicKey,
                BiometricCredentialStatus.ACTIVE,
                registeredAt,
                null);
    }

    private void validateDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > MAX_DEVICE_ID_LENGTH) {
            throw new PayException(PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID);
        }
    }

    private void validate(
            String deviceId,
            String publicKey,
            String credentialType) {
        validateDeviceId(deviceId);
        if (!PLATFORM_BIOMETRIC.equals(credentialType)
                || publicKey == null
                || publicKey.isBlank()
                || publicKey.length() > MAX_PUBLIC_KEY_LENGTH) {
            throw new PayException(PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID);
        }
        try {
            byte[] decodedPublicKey = Base64.getUrlDecoder().decode(publicKey);
            if (decodedPublicKey.length == 0) {
                throw new IllegalArgumentException("Public key is empty");
            }
        } catch (IllegalArgumentException exception) {
            throw new PayException(
                    PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID,
                    PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID.defaultMessage(),
                    null,
                    PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID.defaultNextAction(),
                    exception);
        }
    }
}
