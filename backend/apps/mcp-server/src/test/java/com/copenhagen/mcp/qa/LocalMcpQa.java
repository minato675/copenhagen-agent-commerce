package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.FakeMcpTokenVerifier;
import com.copenhagen.mcp.McpServerApplication;
import com.copenhagen.mcp.auth.VerifiedMcpToken;
import com.copenhagen.mcp.tool.McpToolDefinition;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** test classpath 전용. 운영 JAR에 포함되지 않는 비개인화 연결 QA 실행기. */
public final class LocalMcpQa {

    private LocalMcpQa() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 0) {
            throw new IllegalArgumentException("QA launcher does not accept configuration overrides");
        }
        try (ConfigurableApplicationContext application = new SpringApplicationBuilder(
                McpServerApplication.class, QaConfiguration.class).run(
                        "--server.address=127.0.0.1", "--server.port=8091", "--management.server.port=-1",
                        "--mcp.ui.enabled=true", "--mcp.merchants.enabled=false",
                        "--mcp.auth.issuer=" + FakeMcpTokenVerifier.ISSUER,
                        "--mcp.auth.resource=" + FakeMcpTokenVerifier.RESOURCE,
                        "--mcp.auth.required-scopes=mcp:access",
                        "--mcp.server.allowed-hosts=localhost:8091,127.0.0.1:8091",
                        "--mcp.server.allowed-origins=http://localhost:8091,http://127.0.0.1:8091,https://chatgpt.com",
                        "--services.core.base-url=http://127.0.0.1:1", "--spring.main.banner-mode=off")) {
            Set<String> names = application.getBeansOfType(McpToolDefinition.class).values().stream()
                    .map(definition -> definition.tool().name()).collect(java.util.stream.Collectors.toSet());
            if (!names.equals(Set.of("open_copenhagen", "check_connection"))) {
                throw new IllegalStateException("QA runtime permits connection tools only");
            }
            System.out.println("QA1 ready: http://127.0.0.1:8091/qa | MCP: http://127.0.0.1:8091/mcp");
            System.out.println("Connection-only test runtime; no account, product or payment access. Expires in 4h.");
            new CountDownLatch(1).await();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class QaConfiguration {

        @Bean
        FakeMcpTokenVerifier qaVerifier() {
            return new FakeMcpTokenVerifier();
        }

        @Bean
        FilterRegistrationBean<QaAccessFilter> qaAccessFilter(FakeMcpTokenVerifier verifier) {
            String token = verifier.issue(new VerifiedMcpToken(true, FakeMcpTokenVerifier.ISSUER,
                    Set.of(FakeMcpTokenVerifier.RESOURCE), Instant.now().plusSeconds(14400),
                    Instant.now().minusSeconds(10), "qa-user", "qa-connection", true, Set.of("mcp:access")));
            FilterRegistrationBean<QaAccessFilter> registration =
                    new FilterRegistrationBean<>(new QaAccessFilter(token));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/*");
            registration.setAsyncSupported(true);
            return registration;
        }

        @Bean
        QaPreviewController qaPreviewController() {
            return new QaPreviewController();
        }
    }
}
