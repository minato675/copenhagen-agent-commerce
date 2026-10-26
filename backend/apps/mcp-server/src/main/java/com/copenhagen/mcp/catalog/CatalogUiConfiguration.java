package com.copenhagen.mcp.catalog;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.handoff.HandoffProperties;
import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.HtmlUtils;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"mcp.catalog.enabled", "mcp.ui.enabled"}, havingValue = "true")
public class CatalogUiConfiguration {

    @Bean
    CatalogResource catalogResource(@Value("${mcp.cart.enabled:false}") boolean cartEnabled,
            ObjectProvider<HandoffProperties> handoffProvider)
            throws IOException, NoSuchAlgorithmException {
        HandoffProperties handoff = handoffProvider.getIfAvailable();
        boolean enabled = handoff != null && handoff.enabled();
        String linkBase = enabled ? handoff.linkBase().toASCIIString() : "";
        List<String> redirects = enabled
                ? List.of(handoff.linkBase().getScheme() + "://" + handoff.linkBase().getHost()) : List.of();
        String html = new ClassPathResource("mcp-ui/catalog.html").getContentAsString(StandardCharsets.UTF_8)
                .replace("__CART_ENABLED__", Boolean.toString(cartEnabled))
                .replace("__HANDOFF_ENABLED__", Boolean.toString(enabled))
                .replace("__HANDOFF_LINK_BASE__", HtmlUtils.htmlEscape(linkBase));
        String version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(html.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        return new CatalogResource("ui://copenhagen/catalog-" + version + ".html", html, redirects);
    }

    @Bean
    SyncResourceSpecification catalogSpecification(CatalogResource resource, CatalogProperties properties) {
        String mime = "text/html;profile=mcp-app";
        List<String> images = List.of("https://3p-image.kurly.com", "https://cdn.daisomall.co.kr",
                "https://img-cf.kurly.com", "https://img.danuri.io", "https://product-image.kurly.com");
        Map<String, Object> meta = Map.of("ui", Map.of("prefersBorder", true,
                "csp", Map.of("connectDomains", List.of(), "resourceDomains", images)),
                "openai/widgetCSP", Map.of("connect_domains", List.of(), "resource_domains", images,
                        "redirect_domains", resource.redirectDomains()),
                "openai/widgetDescription", "상품 4개와 판매처 비교를 제공하는 코펜하겐 화면입니다. "
                        + "화면 뒤에 같은 목록이나 화면을 열었다는 설명을 반복하지 마세요.");
        return new SyncResourceSpecification(McpSchema.Resource.builder(resource.uri(), "copenhagen-catalog")
                .title("코펜하겐 상품 탐색").mimeType(mime).meta(meta).build(), (exchange, request) -> {
                    McpIdentity.from(exchange).requireScopes(properties.requiredScopes());
                    return new McpSchema.ReadResourceResult(List.of(
                            new McpSchema.TextResourceContents(resource.uri(), mime, resource.html(), meta)));
                });
    }

    @Bean
    McpToolDefinition displayProducts(CatalogResource resource, ProductCatalogService service,
            CatalogProperties properties) {
        return display("display_products", "확정된 검색 조건으로 상품 카드 표시",
                "search_products의 검색 조건으로 한 번만 호출하세요. 상품군을 선택하지 않아도 전체 결과를 표시합니다. "
                        + "후속 상세 설명은 채팅에서 하고 UI 뒤에 상품 목록을 중복하지 마세요.", resource, service, properties);
    }

    @Bean
    McpToolDefinition openCatalog(CatalogResource resource, ProductCatalogService service,
            CatalogProperties properties) {
        return display("open_copenhagen", "코펜하겐 상품 탐색 열기",
                "사용자가 코펜하겐 화면을 요청하면 한 번 호출합니다. 상품군 선택 없이 전체 상품 탐색과 가격 비교를 제공합니다. "
                        + "QA 모드의 가격은 가상 값이며 실제 구매나 결제를 지원하지 않습니다.", resource, service, properties);
    }

    private McpToolDefinition display(String name, String title, String description, CatalogResource resource,
            ProductCatalogService service, CatalogProperties properties) {
        return new McpToolDefinition(McpSchema.Tool.builder(name, CatalogSchemas.SEARCH_INPUT)
                .title(title).description(description).outputSchema(CatalogSchemas.SEARCH_OUTPUT)
                .meta(Map.of("ui", Map.of("resourceUri", resource.uri(), "visibility", List.of("model")),
                        "openai/outputTemplate", resource.uri(), "openai/widgetAccessible", true))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(true).build()).build(), properties.requiredScopes(),
                (identity, request) -> service.search(identity, request.arguments()));
    }

    record CatalogResource(String uri, String html, List<String> redirectDomains) {
    }
}
