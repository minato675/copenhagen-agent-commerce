package com.copenhagen.core.common.logging;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 나가는 Merchant 호출에 {@code X-Request-Id}를 실어 보내고 결과를 한 줄 남긴다(이슈 374, 구현순서 26).
 *
 * <p>헤더를 싣는 쪽이 핵심이다. Merchant mock은 받은 값이 있으면 그대로 자기 로그에 쓰므로(이슈 187),
 * 앱 → Core → Merchant 한 건을 같은 값으로 이어 볼 수 있다.
 *
 * <p>merchant마다 baseUrl이 다른 RestClient가 하나씩 있어서 {@code @Component}가 아니라 merchant id를
 * 생성자로 받는다({@code ProductConfiguration}이 만든다).
 */
public class MerchantCallLogInterceptor implements ClientHttpRequestInterceptor {

    private static final String EVENT = "http.client";

    private final String target;
    private final String platformProfileUrl;

    public MerchantCallLogInterceptor(String target) {
        this(target, null);
    }

    public MerchantCallLogInterceptor(String target, String platformProfileUrl) {
        this.target = target;
        this.platformProfileUrl = platformProfileUrl;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().set(CoreRequestIdFilter.HEADER, CoreLogContext.requestIdOrNew());
        if (platformProfileUrl != null) {
            request.getHeaders().set("UCP-Agent", "profile=\"" + platformProfileUrl + "\"");
        }
        long startedAt = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            line(request, startedAt).with("status", response.getStatusCode().value()).log();
            return response;
        } catch (IOException | RuntimeException exception) {
            // 예외 메시지 원문은 담지 않는다 — URL·본문 조각이 섞여 들어올 수 있다.
            line(request, startedAt).with("error", exception.getClass().getSimpleName()).log();
            throw exception;
        }
    }

    private AccessLogLine line(HttpRequest request, long startedAt) {
        return AccessLogLine.event(EVENT)
                .with("target", target)
                .with("method", request.getMethod().name())
                .with("path", request.getURI().getPath())
                .with("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }
}
