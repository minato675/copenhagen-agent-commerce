package com.copenhagen.merchantmock.webhook;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** DB 없이 돌리는 발송 대장. 두 UPDATE 의 {@code status = 'PENDING'} 조건을 JDBC 구현과 같게 흉내 낸다. */
final class InMemoryWebhookRepository implements WebhookRepository {

    private final Map<String, WebhookDelivery> rows = new ConcurrentHashMap<>();

    @Override
    public void insert(WebhookDelivery delivery) {
        if (rows.putIfAbsent(delivery.webhookId(), delivery) != null) {
            throw new IllegalStateException("중복 webhook_id: " + delivery.webhookId());
        }
    }

    @Override
    public Optional<WebhookDelivery> findById(String webhookId) {
        return Optional.ofNullable(rows.get(webhookId));
    }

    @Override
    public List<WebhookDelivery> findByOrderId(String orderId) {
        return rows.values().stream()
                .filter(row -> row.orderId().equals(orderId))
                .sorted(Comparator.comparing(WebhookDelivery::createdAt).thenComparing(WebhookDelivery::webhookId))
                .toList();
    }

    @Override
    public boolean markDelivered(String webhookId, int responseStatus, WebhookDelivery.Signed signed) {
        WebhookDelivery row = rows.get(webhookId);
        if (row == null || row.status() != WebhookStatus.PENDING) {
            return false;
        }
        rows.put(webhookId, new WebhookDelivery(row.webhookId(), row.orderId(), row.eventType(), row.payloadJson(),
                signed, WebhookStatus.DELIVERED, row.attemptCount() + 1, null, responseStatus, row.createdAt()));
        return true;
    }

    @Override
    public boolean markRetry(String webhookId, Integer responseStatus, OffsetDateTime nextAttemptAt,
            WebhookDelivery.Signed signed) {
        WebhookDelivery row = rows.get(webhookId);
        if (row == null || row.status() != WebhookStatus.PENDING) {
            return false;
        }
        rows.put(webhookId, new WebhookDelivery(row.webhookId(), row.orderId(), row.eventType(), row.payloadJson(),
                signed, WebhookStatus.PENDING, row.attemptCount() + 1, nextAttemptAt, responseStatus,
                row.createdAt()));
        return true;
    }

    @Override
    public int failExhausted(int maxAttempts, int limit) {
        List<WebhookDelivery> due = rows.values().stream()
                .filter(row -> row.status() == WebhookStatus.PENDING)
                .filter(row -> row.attemptCount() >= maxAttempts)
                .sorted(Comparator.comparing(WebhookDelivery::nextAttemptAt))
                .limit(limit)
                .toList();
        due.forEach(row -> rows.put(row.webhookId(), new WebhookDelivery(row.webhookId(), row.orderId(),
                row.eventType(), row.payloadJson(), row.signed(), WebhookStatus.FAILED, row.attemptCount(),
                row.nextAttemptAt(), row.responseStatus(), row.createdAt())));
        return due.size();
    }

    @Override
    public List<String> claimDue(OffsetDateTime now, OffsetDateTime leaseUntil, int maxAttempts, int limit) {
        List<WebhookDelivery> due = rows.values().stream()
                .filter(row -> row.status() == WebhookStatus.PENDING)
                .filter(row -> !row.nextAttemptAt().isAfter(now))
                .filter(row -> row.attemptCount() < maxAttempts)
                .sorted(Comparator.comparing(WebhookDelivery::nextAttemptAt).thenComparing(WebhookDelivery::webhookId))
                .limit(limit)
                .toList();
        due.forEach(row -> rows.put(row.webhookId(), new WebhookDelivery(row.webhookId(), row.orderId(),
                row.eventType(), row.payloadJson(), row.signed(), row.status(), row.attemptCount(),
                leaseUntil, row.responseStatus(), row.createdAt())));
        return due.stream().map(WebhookDelivery::webhookId).toList();
    }

    /** 테스트가 상태를 직접 심을 때 쓴다. */
    void put(WebhookDelivery delivery) {
        rows.put(delivery.webhookId(), delivery);
    }

    int size() {
        return rows.size();
    }
}
