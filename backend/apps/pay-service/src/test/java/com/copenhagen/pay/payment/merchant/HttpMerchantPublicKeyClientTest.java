package com.copenhagen.pay.payment.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copenhagen.pay.common.observability.PayLogContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpMerchantPublicKeyClientTest {

    private static final String PROFILE = "{\"ucp\":{\"version\":\"2026-08-25\","
            + "\"services\":{},\"capabilities\":{},\"payment_handlers\":{}},"
            + "\"keys\":[{\"kty\":\"EC\",\"kid\":\"merchant-key-1\"}]}";
    private HttpServer server;
    private String responseBody;
    private int responseStatus;
    private String requestIdHeader;

    @BeforeEach
    void setUp() throws IOException {
        responseBody = PROFILE;
        responseStatus = 200;
        requestIdHeader = null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/ucp", exchange -> {
            requestIdHeader = exchange.getRequestHeaders().getFirst("X-Request-Id");
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        PayLogContext.clear();
    }

    @Test
    void readsKeysFromMerchantProfile() {
        HttpMerchantPublicKeyClient client = client();

        List<java.util.Map<String, Object>> keys = client.findPublicKeys("merchant-001");

        assertEquals("merchant-key-1", keys.getFirst().get("kid"));
    }

    @Test
    void propagatesRequestIdToMerchantProfileAdapter() {
        PayLogContext.put(PayLogContext.REQUEST_ID, "request-merchant-123");

        client().findPublicKeys("merchant-001");

        assertEquals("request-merchant-123", requestIdHeader);
    }

    @Test
    void mapsNonSuccessfulProfileResponseToLookupFailure() {
        responseStatus = 503;

        MerchantKeyLookupException exception = assertThrows(
                MerchantKeyLookupException.class,
                () -> client().findPublicKeys("merchant-001"));

        assertEquals("Merchant 공개키 조회가 HTTP 503로 실패했습니다.", exception.getMessage());
    }

    @Test
    void mapsMalformedProfileResponseToLookupFailure() {
        responseBody = "not-json";

        assertThrows(
                MerchantKeyLookupException.class,
                () -> client().findPublicKeys("merchant-001"));
    }

    private HttpMerchantPublicKeyClient client() {
        return new HttpMerchantPublicKeyClient(
                new MerchantServiceProperties(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "/.well-known/ucp",
                        1000,
                        1000,
                        "ES256"),
                new ObjectMapper());
    }
}
