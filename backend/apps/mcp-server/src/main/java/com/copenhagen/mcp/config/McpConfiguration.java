package com.copenhagen.mcp.config;

import com.copenhagen.mcp.auth.McpAuthResponses;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClient;
import com.copenhagen.mcp.core.CoreProperties;
import com.copenhagen.mcp.tool.McpToolDefinition;
import com.copenhagen.mcp.tool.McpToolExecutor;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({McpProperties.class, CoreProperties.class})
public class McpConfiguration {

    @Bean
    McpJsonMapper mcpJsonMapper() {
        return McpJsonDefaults.getMapper();
    }

    @Bean
    JsonSchemaValidator mcpSchemaValidator() {
        return McpJsonDefaults.getSchemaValidator();
    }

    @Bean
    FilterRegistrationBean<McpRequestFilter> mcpRequestFilter(McpJsonMapper mapper, McpProperties properties) {
        FilterRegistrationBean<McpRequestFilter> registration = new FilterRegistrationBean<>(
                new McpRequestFilter(mapper, properties.maxRequestBytes()));
        registration.addUrlPatterns("/mcp");
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport(McpJsonMapper mapper, McpProperties properties) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper)
                .mcpEndpoint("/mcp")
                .contextExtractor(request -> McpTransportContext.create(Map.of(
                        McpIdentity.CONTEXT_KEY, request.getAttribute(McpIdentity.CONTEXT_KEY))))
                .maxRequestSize(properties.maxRequestBytes())
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedOrigins(properties.allowedOrigins())
                        .allowedHosts(properties.allowedHosts())
                        .build())
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, "/mcp");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer mcpServer(
            HttpServletStreamableServerTransportProvider transport,
            McpJsonMapper mapper,
            JsonSchemaValidator validator,
            McpProperties properties,
            McpAuthResponses authResponses,
            List<McpToolDefinition> definitions,
            com.copenhagen.mcp.diagnostics.DiagnosticSessions diagnostics,
            List<SyncResourceSpecification> resources) {
        McpToolExecutor executor = new McpToolExecutor(mapper, validator, authResponses);
        return McpServer.sync(transport)
                .serverInfo("copenhagen-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false)
                        .resources(false, false).build())
                .requestTimeout(properties.requestTimeout())
                .jsonMapper(mapper)
                .jsonSchemaValidator(validator)
                // 공통 실행기가 입력을 검증하고 검증 상세값의 외부 노출을 방지한다.
                .validateToolInputs(false)
                .tools(definitions.stream().map(diagnostics::instrument).map(executor::specification).toList())
                .resources(resources)
                .build();
    }

    @Bean(destroyMethod = "close")
    HttpClient coreHttpClient(CoreProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    CoreClient coreClient(HttpClient coreHttpClient, CoreProperties properties,
            McpJsonMapper mapper, JsonSchemaValidator validator) {
        return new CoreClient(coreHttpClient, properties, mapper, validator);
    }
}
