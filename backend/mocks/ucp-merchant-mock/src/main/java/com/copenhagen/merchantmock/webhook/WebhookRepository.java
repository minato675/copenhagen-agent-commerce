package com.copenhagen.merchantmock.webhook;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 웹훅 발송 대장 저장 경계. 구현은 {@link JdbcWebhookRepository}. 테스트는 인메모리 구현으로 돌린다.
 *
 * <p>두 UPDATE 는 {@code status = 'PENDING'} 인 행만 바꾼다. 커밋 직후 첫 발송과 184 재전송 워커가 같은 행을
 * 동시에 집어도 결과를 두 번 기록하지 않기 위해서다.
 */
public interface WebhookRepository {

    void insert(WebhookDelivery delivery);

    Optional<WebhookDelivery> findById(String webhookId);

    /** 한 주문의 발송 이력. 생성 순서다. */
    List<WebhookDelivery> findByOrderId(String orderId);

    /** 2xx 를 받았다. {@code DELIVERED} 로 닫고 응답 코드와 이번 서명을 남긴다. */
    boolean markDelivered(String webhookId, int responseStatus, WebhookDelivery.Signed signed);

    /**
     * 못 보냈다. {@code attempt_count} 를 올리고 다음 시도 시각을 적는다. 상태는 {@code PENDING} 그대로다.
     *
     * @param responseStatus 받은 상태 코드. 타임아웃·연결 실패면 null
     */
    boolean markRetry(String webhookId, Integer responseStatus, OffsetDateTime nextAttemptAt,
            WebhookDelivery.Signed signed);

    /**
     * 한도를 넘긴 {@code PENDING} 을 {@code FAILED} 로 닫는다. 184 워커가 주기마다 먼저 부른다(DESIGN.md 8.7절).
     *
     * <p>{@code FAILED} 는 끝이다. 다시 보내지 않고 사람이 보라고 남긴다.
     *
     * @param maxAttempts 이 횟수 이상 시도한 행을 닫는다
     * @return 닫은 건수
     */
    int failExhausted(int maxAttempts, int limit);

    /**
     * 기한이 된 {@code PENDING} 을 집는다. 집으면서 {@code next_attempt_at} 을 {@code leaseUntil} 로 밀어
     * 이어지는 스캔이 같은 행을 다시 집지 않게 한다(DESIGN.md 8.7절).
     *
     * <p>{@code attempt_count >= maxAttempts} 는 집지 않는다. 그쪽은 {@link #failExhausted} 가 닫는다.
     *
     * @return 집은 행의 webhookId. 받은 순서대로 보낸다
     */
    List<String> claimDue(OffsetDateTime now, OffsetDateTime leaseUntil, int maxAttempts, int limit);
}
