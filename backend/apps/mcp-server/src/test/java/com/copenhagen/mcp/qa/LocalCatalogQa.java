package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.FakeMcpTokenVerifier;
import com.copenhagen.mcp.McpServerApplication;
import com.copenhagen.mcp.auth.VerifiedMcpToken;
import com.copenhagen.mcp.catalog.CoreProductCatalog;
import com.copenhagen.mcp.catalog.CoreProductDetails;
import com.copenhagen.mcp.tool.McpToolDefinition;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** test classpath 한정. 실제 Core/UCP 주소나 실제 사용자 인증을 사용하지 않는 로컬 QA2 실행기. */
public final class LocalCatalogQa {

    private LocalCatalogQa() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 0) {
            throw new IllegalArgumentException("QA launcher does not accept configuration overrides");
        }
        try (ConfigurableApplicationContext application = new SpringApplicationBuilder(
                McpServerApplication.class, Configuration.class).run(
                        "--server.address=127.0.0.1", "--server.port=8092", "--management.server.port=-1",
                        "--mcp.ui.enabled=true", "--mcp.merchants.enabled=false", "--mcp.catalog.enabled=true",
                        "--mcp.catalog.required-scopes=qa:catalog:read",
                        "--mcp.catalog.detail-enabled=true",
                        "--mcp.auth.issuer=" + FakeMcpTokenVerifier.ISSUER,
                        "--mcp.auth.resource=" + FakeMcpTokenVerifier.RESOURCE,
                        "--mcp.auth.required-scopes=mcp:access",
                        "--mcp.server.allowed-hosts=localhost:8092,127.0.0.1:8092",
                        "--mcp.server.allowed-origins=http://localhost:8092,http://127.0.0.1:8092,https://chatgpt.com",
                        "--services.core.base-url=http://127.0.0.1:1", "--spring.main.banner-mode=off")) {
            Set<String> names = application.getBeansOfType(McpToolDefinition.class).values().stream()
                    .map(definition -> definition.tool().name()).collect(java.util.stream.Collectors.toSet());
            if (!names.equals(Set.of("open_copenhagen", "check_connection", "search_products", "display_products",
                    "get_product_detail"))) {
                throw new IllegalStateException("QA2 runtime permits only catalog and connection tools");
            }
            System.out.println("QA2 ready: http://127.0.0.1:8092/qa | MCP: http://127.0.0.1:8092/mcp");
            System.out.println("Local Mock Core; synthetic prices; no account/payment access. Expires in 4h.");
            new CountDownLatch(1).await();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        FakeMcpTokenVerifier qaVerifier() {
            return new FakeMcpTokenVerifier();
        }

        @Bean
        QaCoreServer qaCoreServer() throws IOException {
            return new QaCoreServer(QaCatalogStore.load(Path.of(System.getProperty("copenhagen.qa.catalog"))));
        }

        @Bean
        CoreProductCatalog qaCatalogAdapter(QaCoreServer server) {
            return server.adapter();
        }

        @Bean
        CoreProductDetails qaDetailsAdapter(QaCoreServer server) {
            return server.detailsAdapter();
        }

        @Bean
        FilterRegistrationBean<QaAccessFilter> qaAccessFilter(FakeMcpTokenVerifier verifier) {
            String token = verifier.issue(new VerifiedMcpToken(true, FakeMcpTokenVerifier.ISSUER,
                    Set.of(FakeMcpTokenVerifier.RESOURCE), Instant.now().plusSeconds(14400),
                    Instant.now().minusSeconds(10), "qa-user", "qa-connection", true,
                    Set.of("mcp:access", "qa:catalog:read")));
            FilterRegistrationBean<QaAccessFilter> registration =
                    new FilterRegistrationBean<>(new QaAccessFilter(token, true));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/*");
            registration.setAsyncSupported(true);
            return registration;
        }

        @Bean
        CatalogPreviewController catalogPreviewController() {
            return new CatalogPreviewController();
        }
    }
}
