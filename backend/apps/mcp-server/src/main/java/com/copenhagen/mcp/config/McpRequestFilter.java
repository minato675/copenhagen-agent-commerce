package com.copenhagen.mcp.config;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** SDK의 역직렬화 오류 응답·로그에 요청 원문이 포함되지 않도록 JSON-RPC 경계를 검사한다. */
final class McpRequestFilter extends OncePerRequestFilter {

    private final McpJsonMapper mapper;
    private final int limit;

    McpRequestFilter(McpJsonMapper mapper, int limit) {
        this.mapper = mapper;
        this.limit = limit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            reject(response, 413, -32600, "MCP request is too large");
            return;
        }
        Object parsed;
        try {
            parsed = mapper.readValue(body, Object.class);
        } catch (IOException | IllegalArgumentException exception) {
            reject(response, 400, -32700, "Invalid JSON");
            return;
        }
        if (!(parsed instanceof Map<?, ?> message) || !validMessage(message)) {
            reject(response, 400, -32600, "Invalid JSON-RPC message");
            return;
        }
        try {
            McpSchema.deserializeJsonRpcMessage(mapper, new String(body, StandardCharsets.UTF_8));
            if ("initialize".equals(message.get("method"))) {
                mapper.convertValue(message.get("params"), McpSchema.InitializeRequest.class);
            } else if ("tools/call".equals(message.get("method"))) {
                mapper.convertValue(message.get("params"), McpSchema.CallToolRequest.class);
            }
        } catch (IOException | IllegalArgumentException exception) {
            reject(response, 400, -32602, "Invalid MCP parameters");
            return;
        }
        chain.doFilter(new BodyRequest(request, body), response);
    }

    private boolean validMessage(Map<?, ?> message) {
        if (!"2.0".equals(message.get("jsonrpc"))) {
            return false;
        }
        Object id = message.get("id");
        if (id != null && !(id instanceof String) && !(id instanceof Number)) {
            return false;
        }
        if (!message.containsKey("method")) {
            return message.containsKey("id") && (message.containsKey("result") ^ message.containsKey("error"));
        }
        if (!(message.get("method") instanceof String method) || method.isBlank()) {
            return false;
        }
        Object parameters = message.get("params");
        if (parameters != null && !(parameters instanceof Map<?, ?>)) {
            return false;
        }
        if ("tools/call".equals(method)) {
            return parameters instanceof Map<?, ?> arguments
                    && arguments.get("name") instanceof String name && !name.isBlank()
                    && (arguments.get("arguments") == null || arguments.get("arguments") instanceof Map<?, ?>);
        }
        if ("initialize".equals(method)) {
            return parameters instanceof Map<?, ?> initialization
                    && initialization.get("protocolVersion") instanceof String
                    && initialization.get("capabilities") instanceof Map<?, ?>
                    && initialization.get("clientInfo") instanceof Map<?, ?> client
                    && client.get("name") instanceof String && client.get("version") instanceof String;
        }
        return true;
    }

    private void reject(HttpServletResponse response, int status, int code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":" + code
                + ",\"message\":\"" + message + "\"}}");
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("MCP request body uses blocking reads");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
