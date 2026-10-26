package com.copenhagen.mcp.merchant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** loopback의 명시적 fixture 경로만 제공한다. 실제 Core 인증·등록·Merchant 어댑터 구현이 아니다. */
public final class FakeMerchantCore implements AutoCloseable {

    public static final String PATH = "/fixture/merchant-directory";
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> OBJECT_TYPE = new TypeRef<>() { };

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch release = new CountDownLatch(1);
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger unexpectedRequests = new AtomicInteger();
    private final ConcurrentLinkedQueue<Map<String, Object>> received = new ConcurrentLinkedQueue<>();

    public FakeMerchantCore() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respond(String user, Map<String, Object> body) throws IOException {
        respond(user, 200, MAPPER.writeValueAsString(body));
    }

    public void respond(String user, int status, String body) {
        replies.put(user, new Reply(status, body));
    }

    public int requestCount() {
        return requests.get();
    }

    public int unexpectedRequestCount() {
        return unexpectedRequests.get();
    }

    public boolean received(String user, String connection) {
        return received.contains(Map.of("publicUserRef", user, "connectionRef", connection));
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
        executor.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            Map<String, Object> request = MAPPER.readValue(exchange.getRequestBody().readAllBytes(), OBJECT_TYPE);
            if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().toString().equals(PATH)
                    || exchange.getRequestHeaders().getFirst("Authorization") != null
                    || !request.keySet().equals(Set.of("publicUserRef", "connectionRef"))) {
                unexpectedRequests.incrementAndGet();
                send(exchange, 400, "{}");
                return;
            }
            received.add(Map.copyOf(request));
            Reply reply = replies.get((String) request.get("publicUserRef"));
            if (reply == null) {
                send(exchange, 404, "{}");
            } else if (reply.status() == 0) {
                release.await();
            } else {
                if (reply.status() == 302) {
                    exchange.getResponseHeaders().set("Location", baseUrl() + "/forbidden-merchant");
                }
                send(exchange, reply.status(), reply.body());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private record Reply(int status, String body) {
    }
}
