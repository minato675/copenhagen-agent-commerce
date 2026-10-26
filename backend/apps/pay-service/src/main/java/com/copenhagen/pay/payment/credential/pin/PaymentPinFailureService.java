package com.copenhagen.pay.payment.credential.pin;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentPinFailureService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    private final PaymentPinCredentialRepository repository;
    private final Clock clock;

    @Autowired
    public PaymentPinFailureService(
            PaymentPinCredentialRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureResult record(String userId) {
        PaymentPinCredential credential = repository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new PayException(PayErrorCode.PIN_NOT_REGISTERED));
        Instant now = clock.instant();
        if (credential.status() == PaymentPinStatus.LOCKED
                && credential.lockedUntil() != null
                && now.isBefore(credential.lockedUntil())) {
            return new FailureResult(
                    credential.failedAttempts(),
                    credential.status(),
                    credential.lockedUntil());
        }

        int failedAttempts = Math.min(credential.failedAttempts() + 1, MAX_FAILED_ATTEMPTS);
        if (failedAttempts == MAX_FAILED_ATTEMPTS) {
            Instant lockedUntil = now.plus(LOCK_DURATION);
            repository.updateVerificationFailure(
                    credential.id(),
                    failedAttempts,
                    lockedUntil,
                    PaymentPinStatus.LOCKED,
                    now);
            return new FailureResult(
                    failedAttempts,
                    PaymentPinStatus.LOCKED,
                    lockedUntil);
        }

        repository.updateVerificationFailure(
                credential.id(),
                failedAttempts,
                null,
                PaymentPinStatus.ACTIVE,
                now);
        return new FailureResult(
                failedAttempts,
                PaymentPinStatus.ACTIVE,
                null);
    }

    public record FailureResult(
            int failedAttempts,
            PaymentPinStatus status,
            Instant lockedUntil) {

        public boolean locked() {
            return status == PaymentPinStatus.LOCKED;
        }
    }
}
