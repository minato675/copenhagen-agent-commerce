package com.copenhagen.merchantmock.webhook;

/**
 * 발송 대장의 행 하나를 실제로 보낸다. 구현은 {@link HttpWebhookDispatcher}.
 *
 * <p>커밋 직후 첫 시도({@link OrderWebhookPublisher})와 184 재전송 워커가 같은 메서드를 부른다.
 */
public interface WebhookDispatcher {

    enum Result {
        /** 2xx. DELIVERED 로 닫았다. */
        DELIVERED,
        /** 응답이 2xx 가 아니거나 못 보냈다. PENDING 에 다음 시도 시각을 적었다. */
        RETRY_SCHEDULED,
        /** 개인키가 없어 서명할 수 없다. 행은 그대로 둔다. */
        SKIPPED_NO_SIGNING_KEY,
        /** 행이 없거나 이미 PENDING 이 아니다. */
        SKIPPED_NOT_PENDING
    }

    Result dispatch(String webhookId);
}
