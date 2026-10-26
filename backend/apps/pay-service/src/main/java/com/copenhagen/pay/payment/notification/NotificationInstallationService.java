package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.common.validation.RequestValidation;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationInstallationService {

    private static final int MAX_FID_LENGTH = 255;
    private static final Logger log = LoggerFactory.getLogger(NotificationInstallationService.class);

    private final NotificationInstallationRepository repository;
    private final Clock clock;

    public NotificationInstallationService(
            NotificationInstallationRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void register(String userId, String fid) {
        RequestValidation.requireLengthAtMost(userId, "userId", 36);
        RequestValidation.requireLengthAtMost(fid, "fid", MAX_FID_LENGTH);
        String fidHash = PayLogContext.fingerprint(fid);
        try (PayLogContext.Scope ignored = PayLogContext.open(
                PayLogContext.USER_ID, PayLogContext.fingerprint(userId),
                PayLogContext.FID_HASH, fidHash)) {
            log.info("notification.installation.register.start");
            try {
                repository.registerActive(userId, fid, clock.instant());
            } catch (RuntimeException exception) {
                log.error(
                        "notification.installation.register.failed exceptionType={}",
                        exception.getClass().getSimpleName());
                throw exception;
            }
            log.info("notification.installation.register.success");
        }
    }

    @Transactional
    public void unregister(String userId, String fid) {
        RequestValidation.requireLengthAtMost(userId, "userId", 36);
        RequestValidation.requireLengthAtMost(fid, "fid", MAX_FID_LENGTH);
        // The user predicate makes this privacy-safe for another user's FID.
        String fidHash = PayLogContext.fingerprint(fid);
        try (PayLogContext.Scope ignored = PayLogContext.open(
                PayLogContext.USER_ID, PayLogContext.fingerprint(userId),
            PayLogContext.FID_HASH, fidHash)) {
            log.info("notification.installation.unregister.start");
            int deactivated;
            try {
                deactivated = repository.deactivate(userId, fid, clock.instant());
            } catch (RuntimeException exception) {
                log.error(
                        "notification.installation.unregister.failed exceptionType={}",
                        exception.getClass().getSimpleName());
                throw exception;
            }
            if (deactivated == 0 && repository.existsActiveForAnotherUser(userId, fid)) {
                log.warn("notification.installation.unregister.rejected reason=FID_OWNED_BY_ANOTHER_USER");
                throw new PayException(PayErrorCode.RESOURCE_NOT_FOUND);
            }
            log.info(
                    "notification.installation.unregister.success deactivated={}",
                    deactivated == 1);
        }
    }
}
