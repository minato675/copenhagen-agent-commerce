package com.copenhagen.mcp.auth;

import com.copenhagen.mcp.tool.McpToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class McpProtectedResourceController {

    private final McpAuthProperties properties;
    private final McpAuthResponses responses;
    private final List<String> supportedScopes;

    public McpProtectedResourceController(
            McpAuthProperties properties, McpAuthResponses responses, List<McpToolDefinition> definitions) {
        this.properties = properties;
        this.responses = responses;
        this.supportedScopes = Stream.concat(properties.requiredScopes().stream(),
                definitions.stream().flatMap(definition -> definition.requiredScopes().stream()))
                .distinct().sorted().toList();
    }

    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    public ResponseEntity<Map<String, Object>> metadata() {
        if (!properties.configured()) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                    .body(responses.body(new McpAuthException(McpAuthError.AUTH_NOT_CONFIGURED)));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                "resource", properties.resource().toString(),
                "authorization_servers", List.of(properties.issuer().toString()),
                "scopes_supported", supportedScopes,
                "bearer_methods_supported", List.of("header")));
    }
}
