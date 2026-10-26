package com.copenhagen.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 실제 Core endpoint와 구분되는 테스트 전용 HTTP fixture. */
public final class FakeCore implements AutoCloseable {

    public static final String PRIVATE_MARKER = "PRIVATE_FIXTURE_MARKER";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String lastBody;
    private volatile String lastAuthorization;

    public FakeCore() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/fixture/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int requestCount() {
        return requests.get();
    }

    public String lastBody() {
        return lastBody;
    }

    public String lastAuthorization() {
        return lastAuthorization;
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
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        String scenario = exchange.getRequestURI().getPath().substring("/fixture/".length());
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            switch (scenario) {
                case "success" -> respond(exchange, 200, "{\"value\":\"confirmed\"}");
                case "service-error" -> respond(exchange, 503, PRIVATE_MARKER);
                case "unauthorized" -> respond(exchange, 401, PRIVATE_MARKER);
                case "wrong-schema" -> respond(exchange, 200, "{\"private\":\"" + PRIVATE_MARKER + "\"}");
                case "invalid-json" -> respond(exchange, 200, PRIVATE_MARKER);
                case "empty" -> respond(exchange, 200, "");
                case "null" -> respond(exchange, 200, "null");
                case "wrong-type" -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    respond(exchange, 200, PRIVATE_MARKER);
                }
                case "oversized" -> respond(exchange, 200, "{\"value\":\"" + "x".repeat(8192) + "\"}");
                case "redirect" -> {
                    exchange.getResponseHeaders().set("Location", baseUrl() + "/fixture/success");
                    respond(exchange, 302, "{}");
                }
                case "timeout" -> release.await();
                case "slow-body" -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    release.await();
                }
                default -> respond(exchange, 404, "{}");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }
}
