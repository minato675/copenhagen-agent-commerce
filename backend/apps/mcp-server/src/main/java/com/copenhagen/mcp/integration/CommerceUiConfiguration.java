package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpIdentity;
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
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"mcp.integration.enabled", "mcp.ui.enabled"}, havingValue = "true")
public class CommerceUiConfiguration {
    @Bean
    CommerceViews commerceViews() throws IOException, NoSuchAlgorithmException {
        Map<String, CommerceResource> views = new java.util.LinkedHashMap<>();
        for (String view : List.of("search", "detail", "cart", "purchase")) {
            String html = new ClassPathResource("mcp-ui/commerce-" + view + ".html")
                    .getContentAsString(StandardCharsets.UTF_8);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(html.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            views.put(view, new CommerceResource("ui://copenhagen/" + view + "-" + hash + ".html", html));
        }
        return new CommerceViews(Map.copyOf(views));
    }

    @Bean
    SyncResourceSpecification searchResourceSpecification(CommerceViews views) {
        return specification(views.resources().get("search"), "상품 검색 결과");
    }

    @Bean
    SyncResourceSpecification detailResourceSpecification(CommerceViews views) {
        return specification(views.resources().get("detail"), "상품 상세 및 옵션 선택");
    }

    @Bean
    SyncResourceSpecification cartResourceSpecification(CommerceViews views) {
        return specification(views.resources().get("cart"), "장바구니와 결제 요청");
    }

    @Bean
    SyncResourceSpecification purchaseResourceSpecification(CommerceViews views) {
        return specification(views.resources().get("purchase"), "앱 결제 승인 요청 상태");
    }

    private SyncResourceSpecification specification(CommerceResource resource, String title) {
        String mime = "text/html;profile=mcp-app";
        List<String> images = List.of("https://3p-image.kurly.com", "https://cdn.daisomall.co.kr",
                "https://img-cf.kurly.com", "https://img.danuri.io", "https://product-image.kurly.com");
        Map<String, Object> metadata = Map.of("ui", Map.of("prefersBorder", false,
                "csp", Map.of("connectDomains", List.of(), "resourceDomains", images)),
                "openai/widgetCSP", Map.of("connect_domains", List.of(), "resource_domains", images),
                "openai/widgetDescription", title + ". 다음 행동은 대화에서 별도 도구와 새 카드로 이어집니다.");
        return new SyncResourceSpecification(McpSchema.Resource.builder(resource.uri(), title)
                .mimeType(mime).meta(metadata).build(), (exchange, request) -> {
                    // 정적 UI에는 사용자 데이터가 없다. 데이터 권한은 각 도구에서 별도로 검증한다.
                    McpIdentity.from(exchange);
                    return new McpSchema.ReadResourceResult(List.of(
                            new McpSchema.TextResourceContents(resource.uri(), mime, resource.html(), metadata)));
                });
    }

    @Bean
    McpToolDefinition coreOpenCopenhagen(CommerceViews views, CoreCommerceService service) {
        return display("open_copenhagen", views, service);
    }

    @Bean
    McpToolDefinition coreDisplayProducts(CommerceViews views, CoreCommerceService service) {
        return display("display_products", views, service);
    }

    @Bean
    McpToolDefinition coreDisplayCart(CommerceViews views, CoreCommerceService service) {
        return new McpToolDefinition(McpSchema.Tool.builder("display_cart", CommerceSchemas.EMPTY)
                .title("최종 장바구니 표시")
                .description("사용자가 장바구니를 보여달라고 하거나 요청한 모든 변경이 끝난 뒤 한 번 호출합니다. "
                        + "중간 조회·추가·삭제 단계에서는 호출하지 않습니다. 최신 서버 상태만 표시하며 변경하지 않습니다.")
                .outputSchema(CommerceSchemas.OUTPUT).meta(views.metadata("display_cart", "cart:read"))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(true).build()).build(), Set.of("cart:read"),
                (identity, request) -> service.cart(identity));
    }

    @Bean
    McpToolDefinition coreDisplayProductDetail(CommerceViews views, CoreCommerceService service) {
        return new McpToolDefinition(McpSchema.Tool.builder("display_product_detail", CommerceSchemas.DETAIL)
                .title("상품 옵션 카드 표시")
                .description("사용자가 특정 상품의 옵션 카드를 요청한 경우에만 호출합니다. "
                        + "다중 상품 담기의 옵션 확인은 get_product_details_batch와 채팅 질문으로 진행합니다.")
                .outputSchema(CommerceSchemas.OUTPUT).meta(views.metadata("display_product_detail", "catalog:read"))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(true).build()).build(), Set.of("catalog:read"),
                (identity, request) -> service.detail(identity, request.arguments()));
    }

    private McpToolDefinition display(String name, CommerceViews views, CoreCommerceService service) {
        return new McpToolDefinition(McpSchema.Tool.builder(name, CommerceSchemas.SEARCH)
                .title("코펜하겐 상품 검색 카드")
                .description(CommerceConfiguration.SEARCH_GUIDANCE
                        + " search_products가 상품을 반환한 경우에만 같은 조건으로 한 번 호출하세요. "
                        + "검색 실패 또는 빈 결과이면 이 도구를 호출하지 말고 텍스트로 안내하세요.")
                .outputSchema(CommerceSchemas.OUTPUT)
                .meta(views.metadata(name, "catalog:read"))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(true).build()).build(), Set.of("catalog:read"),
                (identity, request) -> service.search(identity, request.arguments()));
    }

    record CommerceResource(String uri, String html) {
    }

    record CommerceViews(Map<String, CommerceResource> resources) {
        Map<String, Object> metadata(String tool, String scope) {
            return metadata(tool, Set.of(scope));
        }

        Map<String, Object> metadata(String tool, Set<String> scopes) {
            String view = switch (tool) {
                case "get_product_detail", "display_product_detail" -> "detail";
                case "display_cart", "get_cart", "add_cart_item", "update_cart_item", "delete_cart_item", "prepare_cart"
                        -> "cart";
                case "prepare_purchase_handoff", "get_purchase_status", "resend_purchase_notification" -> "purchase";
                default -> "search";
            };
            String uri = resources.get(view).uri();
            return Map.of("ui", Map.of("resourceUri", uri, "visibility", List.of("model", "app")),
                    "securitySchemes", List.of(Map.of("type", "oauth2", "scopes", scopes.stream().sorted().toList())),
                    "openai/outputTemplate", uri, "openai/widgetAccessible", true);
        }
    }
}
