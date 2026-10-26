package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.FakeMcpTokenVerifier;
import com.copenhagen.mcp.McpServerApplication;
import com.copenhagen.mcp.auth.VerifiedMcpToken;
import com.copenhagen.mcp.cart.CoreCart;
import com.copenhagen.mcp.cart.CoreCartEdits;
import com.copenhagen.mcp.cart.CoreCartView;
import com.copenhagen.mcp.catalog.CoreProductCatalog;
import com.copenhagen.mcp.catalog.CoreProductDetails;
import com.copenhagen.mcp.handoff.CorePurchaseHandoff;
import com.copenhagen.mcp.tool.McpToolDefinition;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.beans.factory.annotation.Value;

/** test classpath 한정. 실제 Core/UCP 주소나 실제 사용자 인증을 사용하지 않는 로컬 QA3 실행기. */
public final class LocalCartQa {

    private LocalCartQa() {
    }

    public static void main(String[] args) throws InterruptedException {
        run(args, false);
    }

    static void run(String[] args, boolean handoff) throws InterruptedException {
        if (args.length != 0) {
            throw new IllegalArgumentException("QA launcher does not accept configuration overrides");
        }
        List<String> settings = new ArrayList<>(List.of(
                        "--server.address=127.0.0.1", "--server.port=8093", "--management.server.port=-1",
                        "--mcp.ui.enabled=true", "--mcp.merchants.enabled=false", "--mcp.catalog.enabled=true",
                        "--mcp.catalog.required-scopes=qa:catalog:read",
                        "--mcp.catalog.detail-enabled=true", "--mcp.cart.enabled=true",
                        "--mcp.cart.read-scopes=qa:cart:read", "--mcp.cart.write-scopes=qa:cart:write",
                        "--mcp.auth.issuer=" + FakeMcpTokenVerifier.ISSUER,
                        "--mcp.auth.resource=" + FakeMcpTokenVerifier.RESOURCE,
                        "--mcp.auth.required-scopes=mcp:access",
                        "--mcp.server.allowed-hosts=localhost:8093,127.0.0.1:8093",
                        "--mcp.server.allowed-origins=http://localhost:8093,http://127.0.0.1:8093,https://chatgpt.com",
                        "--services.core.base-url=http://127.0.0.1:1", "--spring.main.banner-mode=off"));
        settings.add("--mcp.handoff.enabled=" + handoff);
        if (handoff) {
            settings.add("--mcp.handoff.write-scopes=qa:handoff:write");
            settings.add("--mcp.handoff.link-base=" + QaPurchaseHandoff.LINK_BASE);
        }
        try (ConfigurableApplicationContext application = new SpringApplicationBuilder(
                McpServerApplication.class, Configuration.class).run(settings.toArray(String[]::new))) {
            Set<String> names = application.getBeansOfType(McpToolDefinition.class).values().stream()
                    .map(definition -> definition.tool().name()).collect(java.util.stream.Collectors.toSet());
            Set<String> allowed = new HashSet<>(Set.of("open_copenhagen", "check_connection", "search_products",
                    "display_products", "get_product_detail", "get_cart", "add_cart_item", "update_cart_item",
                    "delete_cart_item"));
            if (handoff) {
                allowed.add("prepare_purchase_handoff");
            }
            if (!names.equals(allowed)) {
                throw new IllegalStateException("Unexpected tool in QA runtime");
            }
            System.out.println((handoff ? "QA4" : "QA3")
                    + " ready: http://127.0.0.1:8093/qa | MCP: http://127.0.0.1:8093/mcp");
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
        QaCoreServer qaCoreServer(QaCartStore carts) throws IOException {
            return new QaCoreServer(QaCatalogStore.load(Path.of(System.getProperty("copenhagen.qa.catalog"))), carts);
        }

        @Bean
        QaCartStore qaCartStore() throws IOException {
            return new QaCartStore(QaCatalogStore.load(Path.of(System.getProperty("copenhagen.qa.catalog"))),
                    Path.of(System.getProperty("copenhagen.qa.cart")));
        }

        @Bean
        CoreCart qaCartAdapter(QaCoreServer server) {
            return server.cartAdapter();
        }

        @Bean
        CoreCartView qaCartViewAdapter(QaCoreServer server) {
            return server.cartViewAdapter();
        }

        @Bean
        CoreCartEdits qaCartEditsAdapter(QaCoreServer server) {
            return server.cartEditsAdapter();
        }

        @Bean
        @ConditionalOnProperty(name = "mcp.handoff.enabled", havingValue = "true")
        CorePurchaseHandoff qaHandoffAdapter(QaCoreServer server) {
            return server.handoffAdapter();
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
        FilterRegistrationBean<QaAccessFilter> qaAccessFilter(FakeMcpTokenVerifier verifier,
                @Value("${mcp.handoff.enabled:false}") boolean handoff) {
            Set<String> scopes = new HashSet<>(Set.of("mcp:access", "qa:catalog:read",
                    "qa:cart:read", "qa:cart:write"));
            if (handoff) {
                scopes.add("qa:handoff:write");
            }
            String token = verifier.issue(new VerifiedMcpToken(true, FakeMcpTokenVerifier.ISSUER,
                    Set.of(FakeMcpTokenVerifier.RESOURCE), Instant.now().plusSeconds(14400),
                    Instant.now().minusSeconds(10), "qa-user", "qa-connection", true,
                    scopes));
            FilterRegistrationBean<QaAccessFilter> registration =
                    new FilterRegistrationBean<>(new QaAccessFilter(token, true, true, handoff));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/*");
            registration.setAsyncSupported(true);
            return registration;
        }

        @Bean
        CatalogPreviewController catalogPreviewController(@Value("${mcp.handoff.enabled:false}") boolean handoff) {
            return new CatalogPreviewController(true, handoff);
        }
    }
}
