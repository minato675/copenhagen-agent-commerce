package com.copenhagen.merchantmock.checkout;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.copenhagen.contract.internalapi.PaymentStatus;

/** DB 없이 돌리는 저장소. 조건부 UPDATE 의 version·status 규칙을 JDBC 구현과 같게 흉내 낸다. */
final class InMemoryCheckoutRepository implements CheckoutRepository {

    private final Map<String, CheckoutSession> rows = new ConcurrentHashMap<>();

    @Override
    public boolean insert(CheckoutSession session) {
        return rows.putIfAbsent(session.id(), session) == null;
    }

    @Override
    public Optional<CheckoutSession> findById(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public boolean update(CheckoutSession session, long expectedVersion) {
        CheckoutSession current = rows.get(session.id());
        if (current == null || current.version() != expectedVersion) {
            return false;
        }
        // 결제·완료 컬럼은 UPDATE 문이 건드리지 않는다. 현재 값을 그대로 둔다.
        rows.put(session.id(), session.withVersion(expectedVersion + 1)
                .withPayment(current.paymentStatus(), current.payPaymentId())
                .withCompletionResponse(current.completionResponseJson()));
        return true;
    }

    @Override
    public boolean cancel(String id) {
        CheckoutSession current = rows.get(id);
        if (current == null || !current.status().modifiable()) {
            return false;
        }
        rows.put(id, current.withStatus(CheckoutStatus.CANCELED));
        return true;
    }

    @Override
    public int expireReady(OffsetDateTime now, int limit) {
        List<CheckoutSession> due = rows.values().stream()
                .filter(r -> r.status() == CheckoutStatus.READY_FOR_COMPLETE)
                .filter(r -> r.expiresAt() != null && r.expiresAt().isBefore(now))
                .sorted(Comparator.comparing(CheckoutSession::expiresAt))
                .limit(limit)
                .toList();
        due.forEach(r -> rows.put(r.id(), r.withStatus(CheckoutStatus.CANCELED)));
        return due.size();
    }

    @Override
    public synchronized boolean beginComplete(String id, OffsetDateTime now) {
        CheckoutSession current = rows.get(id);
        if (current == null || current.status() != CheckoutStatus.READY_FOR_COMPLETE
                || current.expiresAt() == null || !current.expiresAt().isAfter(now)) {
            return false;
        }
        rows.put(id, current.withStatus(CheckoutStatus.COMPLETE_IN_PROGRESS));
        return true;
    }

    @Override
    public synchronized boolean finishComplete(String id, String completionResponseJson) {
        CheckoutSession current = rows.get(id);
        if (current == null || current.status() != CheckoutStatus.COMPLETE_IN_PROGRESS) {
            return false;
        }
        rows.put(id, current.withStatus(CheckoutStatus.COMPLETED).withCompletionResponse(completionResponseJson));
        return true;
    }

    @Override
    public synchronized boolean revertComplete(String id) {
        CheckoutSession current = rows.get(id);
        if (current == null || current.status() != CheckoutStatus.COMPLETE_IN_PROGRESS) {
            return false;
        }
        rows.put(id, current.withStatus(CheckoutStatus.READY_FOR_COMPLETE));
        return true;
    }

    @Override
    public synchronized boolean beginPayment(String id, OffsetDateTime requestedAt, String credentialId,
            OffsetDateTime firstRecheckAt) {
        CheckoutSession current = rows.get(id);
        if (current == null
                || (current.paymentStatus() != null && current.paymentStatus() != PaymentStatus.FAILED)) {
            return false;
        }
        rows.put(id, current.withPayment(PaymentStatus.PROCESSING, current.payPaymentId())
                .withVersion(current.version() + 1));
        return true;
    }

    @Override
    public List<PaymentRecheck> claimUnknownDue(OffsetDateTime now, OffsetDateTime leaseUntil,
            int maxAttempts, int limit) {
        return List.of();
    }

    @Override
    public synchronized boolean resolvePayment(String id, PaymentStatus status, String payPaymentId) {
        CheckoutSession current = rows.get(id);
        if (current == null || current.paymentStatus() != PaymentStatus.UNKNOWN) {
            return false;
        }
        rows.put(id, current.withPayment(status, payPaymentId == null ? current.payPaymentId() : payPaymentId));
        return true;
    }

    @Override
    public boolean scheduleNextRecheck(String id, OffsetDateTime nextAttemptAt) {
        throw new UnsupportedOperationException();
    }

    @Override
    public synchronized boolean finishPayment(String id, PaymentStatus status, String payPaymentId) {
        CheckoutSession current = rows.get(id);
        if (current == null || current.paymentStatus() != PaymentStatus.PROCESSING) {
            return false;
        }
        rows.put(id, current.withPayment(status, payPaymentId == null ? current.payPaymentId() : payPaymentId)
                .withVersion(current.version() + 1));
        return true;
    }

    int size() {
        return rows.size();
    }

    /** 테스트가 completed 같은 상태를 직접 심을 때 쓴다. */
    void put(CheckoutSession session) {
        rows.put(session.id(), session);
    }
}
