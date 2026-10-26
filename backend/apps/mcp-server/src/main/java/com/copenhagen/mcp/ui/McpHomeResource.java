package com.copenhagen.mcp.ui;

import com.copenhagen.mcp.auth.McpIdentity;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/** 내용 해시를 리소스 주소로 사용해 새 UI와 호스트의 기존 캐시를 구분한다. */
public final class McpHomeResource {

    private static final String MIME_TYPE = "text/html;profile=mcp-app";
    private final String html;
    private final String version;
    private final String uri;

    public McpHomeResource() throws IOException {
        html = new ClassPathResource("mcp-ui/home.html").getContentAsString(StandardCharsets.UTF_8);
        try {
            version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(html.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
        uri = "ui://copenhagen/home-" + version + ".html";
    }

    public String uri() {
        return uri;
    }

    public String version() {
        return version;
    }

    public SyncResourceSpecification specification() {
        Map<String, Object> metadata = Map.of(
                "ui", Map.of("prefersBorder", true,
                        "csp", Map.of("connectDomains", List.of(), "resourceDomains", List.of())),
                "openai/widgetDescription", "코펜하겐 연결 확인 화면입니다. 화면이 상태를 표시하므로 "
                        + "UI 아래에 같은 설명이나 화면을 열었다는 문장을 반복하지 마세요.");
        McpSchema.Resource resource = McpSchema.Resource.builder(uri, "copenhagen-home")
                .title("코펜하겐").mimeType(MIME_TYPE).meta(metadata).build();
        return new SyncResourceSpecification(resource, (exchange, request) -> {
            McpIdentity.from(exchange);
            return new McpSchema.ReadResourceResult(List.of(
                    new McpSchema.TextResourceContents(uri, MIME_TYPE, html, metadata)));
        });
    }
}
