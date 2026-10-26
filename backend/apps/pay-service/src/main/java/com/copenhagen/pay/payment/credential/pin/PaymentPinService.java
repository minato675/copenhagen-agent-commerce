package com.copenhagen.pay.payment.credential.pin;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentPinService {

    private static final Pattern PIN_PATTERN = Pattern.compile("\\d{6}");

    private final PaymentPinCredentialRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final IdempotencyGuard idempotencyGuard;
    private final PaymentPinFailureService failureService;

    public PaymentPinService(
            PaymentPinCredentialRepository repository,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this(
                repository,
                passwordEncoder,
                clock,
                new IdempotencyGuard(),
                new PaymentPinFailureService(repository, clock));
    }

    public PaymentPinService(
            PaymentPinCredentialRepository repository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            IdempotencyGuard idempotencyGuard) {
        this(
                repository,
                passwordEncoder,
                clock,
                idempotencyGuard,
                new PaymentPinFailureService(repository, clock));
    }

    @Autowired
    public PaymentPinService(
            PaymentPinCredentialRepository repository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            IdempotencyGuard idempotencyGuard,
            PaymentPinFailureService failureService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.idempotencyGuard = idempotencyGuard;
        this.failureService = failureService;
    }

    @Transactional
    public void register(String userId, String pin) {
        register(userId, pin, null, null);
    }

    @Transactional
    public void register(
            String userId,
            String pin,
            String idempotencyKey,
            String requestHash) {
        validatePin(pin);
        PaymentPinCredential existing = repository.findByUserIdForUpdate(userId).orElse(null);
        if (existing != null) {
            if (isReplay(existing, idempotencyKey, requestHash)) {
                return;
            }
            throw new PayException(PayErrorCode.PIN_ALREADY_REGISTERED);
        }

        try {
            repository.insert(
                    UUID.randomUUID().toString(),
                    userId,
                    passwordEncoder.encode(pin),
                    idempotencyKey,
                    requestHash,
                    clock.instant());
        } catch (DuplicateKeyException exception) {
            throw new PayException(
                    PayErrorCode.PIN_ALREADY_REGISTERED,
                    PayErrorCode.PIN_ALREADY_REGISTERED.defaultMessage(),
                    null,
                    PayErrorCode.PIN_ALREADY_REGISTERED.defaultNextAction(),
                    exception);
        }
    }

    private boolean isReplay(
            PaymentPinCredential existing,
            String idempotencyKey,
            String requestHash) {
        if (idempotencyKey == null
                || !idempotencyKey.equals(existing.registrationIdempotencyKey())
                || requestHash == null) {
            return false;
        }
        return idempotencyGuard.check(
                existing.registrationRequestHash(),
                requestHash,
                existing.status().name()) == IdempotencyGuard.Decision.REPLAY;
    }

    @Transactional
    public void change(String userId, String currentPin, String newPin) {
        validatePin(currentPin);
        validatePin(newPin);

        PaymentPinCredential credential = findCredential(userId);
        ensureNotLocked(credential);
        if (!passwordEncoder.matches(currentPin, credential.pinHash())) {
            recordFailure(userId);
        }
        if (passwordEncoder.matches(newPin, credential.pinHash())) {
            throw new PayException(PayErrorCode.PIN_MUST_DIFFER);
        }

        repository.updatePin(
                credential.id(),
                passwordEncoder.encode(newPin),
                clock.instant());
    }

    @Transactional
    public void verify(String userId, String pin) {
        validatePin(pin);

        PaymentPinCredential credential = findCredential(userId);
        ensureNotLocked(credential);
        if (!passwordEncoder.matches(pin, credential.pinHash())) {
            recordFailure(userId);
        }

        repository.resetVerificationState(credential.id(), clock.instant());
    }

    @Transactional(readOnly = true)
    public PaymentPinStatusResponse status(String userId) {
        return repository.findByUserId(userId)
                .map(PaymentPinStatusResponse::from)
                .orElseGet(PaymentPinStatusResponse::notRegistered);
    }

    private PaymentPinCredential findCredential(String userId) {
        return repository.findByUserId(userId)
                .orElseThrow(() -> new PayException(PayErrorCode.PIN_NOT_REGISTERED));
    }

    private void ensureNotLocked(PaymentPinCredential credential) {
        Instant lockedUntil = credential.lockedUntil();
        if (credential.status() == PaymentPinStatus.LOCKED
                && (lockedUntil == null || clock.instant().isBefore(lockedUntil))) {
            throw new PayException(
                    PayErrorCode.PIN_TEMPORARILY_LOCKED,
                    PayErrorCode.PIN_TEMPORARILY_LOCKED.defaultMessage(),
                    credential.status().name(),
                    lockedUntil == null
                            ? PayErrorCode.PIN_TEMPORARILY_LOCKED.defaultNextAction()
                            : "TRY_AGAIN_AFTER_" + lockedUntil);
        }
    }

    private void recordFailure(String userId) {
        PaymentPinFailureService.FailureResult failure = failureService.record(userId);
        if (failure.locked()) {
            throw new PayException(
                    PayErrorCode.PIN_TEMPORARILY_LOCKED,
                    PayErrorCode.PIN_TEMPORARILY_LOCKED.defaultMessage(),
                    PaymentPinStatus.LOCKED.name(),
                    "TRY_AGAIN_AFTER_" + failure.lockedUntil());
        }
        throw new PayException(PayErrorCode.PIN_INVALID);
    }

    private void validatePin(String pin) {
        if (pin == null || !PIN_PATTERN.matcher(pin).matches()) {
            throw new PayException(PayErrorCode.PIN_POLICY_VIOLATION);
        }
    }
}
