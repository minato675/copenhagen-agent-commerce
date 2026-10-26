package com.copenhagen.merchantmock.webhook;

import java.net.URI;

/**
 * 웹훅을 보낼 주소. 구현은 {@link ProfileWebhookTargetResolver}. 테스트는 고정 주소를 넣는다.
 */
public interface WebhookTargetResolver {

    URI resolve();
}
