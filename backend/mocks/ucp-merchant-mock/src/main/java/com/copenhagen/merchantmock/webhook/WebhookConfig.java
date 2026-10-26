package com.copenhagen.merchantmock.webhook;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 웹훅 발송 스레드. 한 개다. 인스턴스가 하나뿐이고 발송은 순서가 있어도 되므로 더 둘 이유가 없다.
 *
 * <p>Boot 의 {@code applicationTaskExecutor} 를 쓰지 않는다. Boot 4 가 자동설정을 모듈로 쪼개서 그 빈이
 * 있는지 확신할 수 없고(15.2절), 있어도 이름으로 골라 써야 한다. 여기서 만들고 이름으로 주입한다.
 */
@Configuration
public class WebhookConfig {

    public static final String EXECUTOR = "webhookExecutor";

    @Bean(name = EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService webhookExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "webhook-dispatch");
            thread.setDaemon(true);
            return thread;
        });
    }
}
