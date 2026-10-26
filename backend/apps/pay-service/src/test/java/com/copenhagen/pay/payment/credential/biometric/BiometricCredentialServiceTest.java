package com.copenhagen.pay.payment.credential.biometric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import com.copenhagen.pay.payment.authorization.AuthorizationSessionStore;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BiometricCredentialServiceTest {

    @Test
    void registersPublicKeyWithoutReceivingBiometricRawData() {
        InMemoryBiometricCredentialRepository repository =
                new InMemoryBiometricCredentialRepository();
        BiometricCredentialService service = service(
                repository,
                Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC));
        String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "public-key-bytes".getBytes());

        BiometricCredential credential = service.register(
                "user-001",
                "device-001",
                publicKey,
                "PLATFORM_BIOMETRIC");

        assertEquals(publicKey, credential.publicKey());
        assertEquals(BiometricCredentialStatus.ACTIVE, credential.status());
        assertEquals(publicKey, repository.credentials().get(credential.id()).publicKey());
    }

    @Test
    void rejectsInvalidPublicKeyEncoding() {
        BiometricCredentialService service = service(
                new InMemoryBiometricCredentialRepository(),
                Clock.systemUTC());

        PayException exception = assertThrows(
                PayException.class,
                () -> service.register(
                        "user-001",
                        "device-001",
                        "not-base64-url!",
                        "PLATFORM_BIOMETRIC"));

        assertEquals(PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID, exception.errorCode());
    }

    @Test
    void rejectsDuplicateActiveDeviceCredential() {
        InMemoryBiometricCredentialRepository repository =
                new InMemoryBiometricCredentialRepository();
        BiometricCredentialService service = service(repository, Clock.systemUTC());
        String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "public-key-bytes".getBytes());

        service.register("user-001", "device-001", publicKey, "PLATFORM_BIOMETRIC");
        PayException exception = assertThrows(
                PayException.class,
                () -> service.register(
                        "user-001",
                        "device-001",
                        publicKey,
                        "PLATFORM_BIOMETRIC"));

        assertEquals(
                PayErrorCode.BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED,
                exception.errorCode());
    }

    @Test
    void revokeMarksCredentialRevokedAndFallsBackPendingSessionsToPin() {
        InMemoryBiometricCredentialRepository repository =
                new InMemoryBiometricCredentialRepository();
        RecordingSessionStore sessionStore = new RecordingSessionStore();
        BiometricCredentialService service = new BiometricCredentialService(
                repository,
                sessionStore,
                Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC));
        String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "public-key-bytes".getBytes());
        service.register("user-001", "device-001", publicKey, "PLATFORM_BIOMETRIC");

        service.revoke("user-001", "device-001");

        assertEquals(Optional.empty(), service.findActive("user-001", "device-001"));
        assertEquals(List.of("user-001"), sessionStore.fallbackCalls());
    }

    @Test
    void revokeWithoutActiveCredentialStillSucceeds() {
        RecordingSessionStore sessionStore = new RecordingSessionStore();
        BiometricCredentialService service = new BiometricCredentialService(
                new InMemoryBiometricCredentialRepository(),
                sessionStore,
                Clock.systemUTC());

        service.revoke("user-001", "device-001");

        assertEquals(List.of("user-001"), sessionStore.fallbackCalls());
    }

    @Test
    void revokedDeviceCanRegisterAgain() {
        InMemoryBiometricCredentialRepository repository =
                new InMemoryBiometricCredentialRepository();
        BiometricCredentialService service = service(repository, Clock.systemUTC());
        String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "public-key-bytes".getBytes());
        service.register("user-001", "device-001", publicKey, "PLATFORM_BIOMETRIC");
        service.revoke("user-001", "device-001");

        BiometricCredential reregistered = service.register(
                "user-001",
                "device-001",
                publicKey,
                "PLATFORM_BIOMETRIC");

        assertEquals(BiometricCredentialStatus.ACTIVE, reregistered.status());
    }

    @Test
    void blankDeviceIdIsRejectedOnStatusAndRevoke() {
        BiometricCredentialService service = service(
                new InMemoryBiometricCredentialRepository(),
                Clock.systemUTC());

        assertEquals(
                PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID,
                assertThrows(PayException.class, () -> service.findActive("user-001", " "))
                        .errorCode());
        assertEquals(
                PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID,
                assertThrows(PayException.class, () -> service.revoke("user-001", null))
                        .errorCode());
    }

    private static BiometricCredentialService service(
            BiometricCredentialRepository repository,
            Clock clock) {
        return new BiometricCredentialService(repository, new RecordingSessionStore(), clock);
    }

    /** 해제가 승인 세션을 PIN으로 돌리라고 지시했는지만 본다. */
    private static final class RecordingSessionStore implements AuthorizationSessionStore {

        private final List<String> fallbackCalls = new ArrayList<>();

        @Override
        public int fallbackPendingToPin(String userId, Instant now) {
            fallbackCalls.add(userId);
            return fallbackCalls.size();
        }

        private List<String> fallbackCalls() {
            return fallbackCalls;
        }

        @Override
        public Optional<AuthorizationSession> findByUserAndIdempotencyKey(
                String userId,
                String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public Optional<AuthorizationSession> findActiveByPurchaseRequest(
                String purchaseRequestId) {
            return Optional.empty();
        }

        @Override
        public boolean insert(AuthorizationSession session) {
            return false;
        }

        @Override
        public List<AuthorizationSession> findByUserId(
                String userId,
                AuthorizationSessionStatus status) {
            return List.of();
        }

        @Override
        public Optional<AuthorizationSession> findByIdAndUserId(
                String authorizationSessionId,
                String userId) {
            return Optional.empty();
        }

        @Override
        public int expireOverdueForUser(String userId, Instant now) {
            return 0;
        }

        @Override
        public int expireOverdueByIdAndUser(
                String authorizationSessionId,
                String userId,
                Instant now) {
            return 0;
        }
    }

    private static final class InMemoryBiometricCredentialRepository
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

        @Override
        public boolean revoke(String userId, String deviceId, Instant revokedAt) {
            return findActiveByUserAndDevice(userId, deviceId)
                    .map(credential -> {
                        credentials.put(
                                credential.id(),
                                new BiometricCredential(
                                        credential.id(),
                                        credential.userId(),
                                        credential.deviceId(),
                                        credential.publicKey(),
                                        BiometricCredentialStatus.REVOKED,
                                        credential.registeredAt(),
                                        revokedAt));
                        return true;
                    })
                    .orElse(false);
        }

        private Map<String, BiometricCredential> credentials() {
            return credentials;
        }
    }
}
