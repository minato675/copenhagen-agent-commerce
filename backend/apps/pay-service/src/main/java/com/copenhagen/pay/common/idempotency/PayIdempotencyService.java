package com.copenhagen.pay.common.idempotency;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.credential.mandate.ArtifactEncryptionService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PayIdempotencyService {

    private static final Duration PROCESSING_WAIT = Duration.ofSeconds(10);
    private static final long POLL_INTERVAL_MILLIS = 25;

    private final IdempotencyRepository repository;
    private final IdempotencyGuard idempotencyGuard;
    private final ArtifactEncryptionService encryptionService;
    private final Clock clock;

    public PayIdempotencyService(
            IdempotencyRepository repository,
            IdempotencyGuard idempotencyGuard,
            ArtifactEncryptionService encryptionService,
            Clock clock) {
        this.repository = repository;
        this.idempotencyGuard = idempotencyGuard;
        this.encryptionService = encryptionService;
        this.clock = clock;
    }

    public AcquireResult acquire(IdempotencyScope scope, String requestHash) {
        requireRequestHash(requestHash);
        long deadline = System.nanoTime() + PROCESSING_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<IdempotencyRecord> current = repository.findByScope(scope);
            if (current.isEmpty()) {
                IdempotencyRecord pending = processingRecord(scope, requestHash);
                if (repository.insert(pending)) {
                    return AcquireResult.newOwner();
                }
                continue;
            }

            IdempotencyRecord record = current.get();
            ensureSameRequest(record, requestHash);
            if (record.status() == IdempotencyRecord.Status.COMPLETED) {
                return AcquireResult.replay(record);
            }
            sleepUntilRetry(deadline);
        }

        throw new PayException(
                PayErrorCode.INTERNAL_ERROR,
                "동일한 멱등성 요청이 아직 처리 중입니다.",
                null,
                "RETRY_LATER");
    }

    public void complete(
            IdempotencyScope scope,
            int responseStatus,
            String responseContentType,
            byte[] responseBody) {
        String encryptedBody = encryptResponseBody(responseBody);
        if (!repository.complete(
                scope,
                responseStatus,
                responseContentType,
                encryptedBody,
                clock.instant())) {
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    PayErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction());
        }
    }

    public void release(IdempotencyScope scope) {
        repository.deleteProcessing(scope);
    }

    public void replay(
            HttpServletResponse response,
            IdempotencyRecord record) throws IOException {
        if (record.responseStatus() == null
                || record.status() != IdempotencyRecord.Status.COMPLETED) {
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    PayErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction());
        }

        response.setStatus(record.responseStatus());
        if (record.responseContentType() != null && !record.responseContentType().isBlank()) {
            response.setContentType(record.responseContentType());
        }
        if (record.responseBodyEncrypted() == null) {
            return;
        }

        try {
            String body = encryptionService.decrypt(record.responseBodyEncrypted());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    PayErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
    }

    private IdempotencyRecord processingRecord(
            IdempotencyScope scope,
            String requestHash) {
        return new IdempotencyRecord(
                UUID.randomUUID().toString(),
                scope,
                requestHash,
                IdempotencyRecord.Status.PROCESSING,
                null,
                null,
                null,
                clock.instant(),
                null);
    }

    private void ensureSameRequest(IdempotencyRecord record, String requestHash) {
        idempotencyGuard.check(record.requestHash(), requestHash, null);
    }

    private String encryptResponseBody(byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) {
            return null;
        }
        return encryptionService.encrypt(
                new String(responseBody, StandardCharsets.UTF_8)).ciphertext();
    }

    private void requireRequestHash(String requestHash) {
        if (requestHash == null || requestHash.length() != 64) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }
    }

    private void sleepUntilRetry(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            return;
        }
        long sleepMillis = Math.min(
                POLL_INTERVAL_MILLIS,
                Math.max(1, Duration.ofNanos(remainingNanos).toMillis()));
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    PayErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
    }

    public record AcquireResult(
            boolean owner,
            IdempotencyRecord record) {

        private static AcquireResult newOwner() {
            return new AcquireResult(true, null);
        }

        private static AcquireResult replay(IdempotencyRecord record) {
            return new AcquireResult(false, record);
        }
    }
}
