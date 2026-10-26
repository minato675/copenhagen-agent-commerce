package com.copenhagen.merchantmock.webhook;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * webhook_deliveries 한 행(DESIGN.md 5.4절). 등기우편 발송 대장이다.
 *
 * <p>{@code webhookId} 와 {@code payloadJson} 은 처음 만든 뒤 바뀌지 않는다(불변 조건 8번). Core 가 그 둘로
 * 중복을 판별한다. 서명 셋은 마지막 발송 기록이고 발송마다 새로 만든다(불변 조건 7번).
 */
public record WebhookDelivery(
        String webhookId,
        String orderId,
        WebhookEventType eventType,
        String payloadJson,
        Signed signed,
        WebhookStatus status,
        int attemptCount,
        OffsetDateTime nextAttemptAt,
        Integer responseStatus,
        OffsetDateTime createdAt) {

    /** 마지막 발송에 붙인 서명 셋. 아직 한 번도 안 보냈으면 null 이다. */
    public record Signed(String signatureInput, String signature, String contentDigest) {
    }

    /**
     * 주문 트랜잭션 안에서 만드는 새 행.
     *
     * <p>{@code nextAttemptAt} 은 지금이 아니라 {@code now + firstDelay} 다. 첫 시도는 커밋 직후
     * 별도 스레드 몫이고 184 워커는 그 뒤를 받는 장치다. 지금으로 두면 갓 INSERT 된 행이 워커 눈에
     * 곧바로 기한 도래로 보여 같은 웹훅이 두 번 나간다(DESIGN.md 8.7절). 앱이 커밋과 발송 사이에
     * 죽으면 이 지연만큼 늦게 집힌다.
     *
     * @param firstDelay {@code merchant.webhook.retry-initial-delay}
     */
    public static WebhookDelivery pending(String webhookId, String orderId, WebhookEventType eventType,
            String payloadJson, OffsetDateTime now, Duration firstDelay) {
        return new WebhookDelivery(webhookId, orderId, eventType, payloadJson, null, WebhookStatus.PENDING,
                0, now.plus(firstDelay), null, now);
    }
}
