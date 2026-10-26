package com.copenhagen.pgmock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pgmock.PgMockTokenizationController.ErrorResponse;
import com.copenhagen.pgmock.PgMockTokenizationController.TokenizationRequest;
import com.copenhagen.pgmock.PgMockTokenizationController.TokenizationResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

class PgMockTokenizationControllerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-16T00:00:00Z"),
            ZoneOffset.UTC);
    private static final String KEY_1 = "550e8400-e29b-41d4-a716-446655440301";
    private static final String KEY_2 = "550e8400-e29b-41d4-a716-446655440302";
    private static final String KEY_3 = "550e8400-e29b-41d4-a716-446655440303";
    private static final String KEY_4 = "550e8400-e29b-41d4-a716-446655440304";
    private static final String KEY_5 = "550e8400-e29b-41d4-a716-446655440305";
    private static final String KEY_6 = "550e8400-e29b-41d4-a716-446655440306";
    private static final String KEY_7 = "550e8400-e29b-41d4-a716-446655440307";
    private static final String KEY_8 = "550e8400-e29b-41d4-a716-446655440308";
    private static final String KEY_9 = "550e8400-e29b-41d4-a716-446655440309";

    @Test
    void tokenizationEndpointUsesPublicApiPath() {
        RequestMapping mapping = PgMockTokenizationController.class
                .getAnnotation(RequestMapping.class);

        assertNotNull(mapping);
        assertArrayEquals(
                new String[]{"/api/pg/v1/payment-methods/tokenize"},
                mapping.value());
    }

    @Test
    void tokenize_withFirstCardPassword_returnsProviderToken() {
        PgMockTokenizationController controller = controller();

        var response = controller.tokenize(KEY_1, request("1234"));

        assertEquals(200, response.getStatusCode().value());
        TokenizationResponse body = (TokenizationResponse) response.getBody();
        assertNotNull(body);
        assertEquals("mock-pg", body.provider());
        assertTrue(body.providerTokenRef().startsWith("tok_mock_"));
        assertEquals("VISA", body.brand());
        assertEquals("1111", body.last4());
        assertEquals(12, body.expiryMonth());
        assertEquals(2030, body.expiryYear());
    }

    @Test
    void tokenize_withoutCardPassword_isAcceptedForLaterCardRegistration() {
        PgMockTokenizationController controller = controller();

        var response = controller.tokenize(KEY_2, request(null));

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

    @Test
    void tokenize_withInvalidCardData_returnsSanitizedError() {
        PgMockTokenizationController controller = controller();
        TokenizationRequest invalid = new TokenizationRequest(
                "4111111111111112",
                12,
                2030,
                "123",
                "1234");

        var response = controller.tokenize(KEY_3, invalid);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(
                new ErrorResponse("INVALID_CARD_DATA", "카드 정보가 올바르지 않습니다."),
                response.getBody());
    }

    @Test
    void tokenize_withInvalidCardPassword_returnsBadRequest() {
        PgMockTokenizationController controller = controller();

        var response = controller.tokenize(KEY_4, request("123"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void tokenize_withExpiredCard_returnsBadRequest() {
        PgMockTokenizationController controller = controller();
        TokenizationRequest expired = new TokenizationRequest(
                "4111111111111111",
                8,
                2026,
                "123",
                null);

        var response = controller.tokenize(KEY_5, expired);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void tokenize_sameKeyAndRequest_reusesFirstResult() {
        PgMockTokenizationController controller = controller();
        TokenizationRequest request = request("1234");

        TokenizationResponse first = (TokenizationResponse) controller
                .tokenize(KEY_6, request)
                .getBody();
        TokenizationResponse replay = (TokenizationResponse) controller
                .tokenize(KEY_6, request)
                .getBody();

        assertEquals(first, replay);
    }

    @Test
    void tokenize_sameKeyAndDifferentRequest_returnsConflict() {
        PgMockTokenizationController controller = controller();
        controller.tokenize(KEY_7, request("1234"));

        var response = controller.tokenize(
                KEY_7,
                new TokenizationRequest("4242424242424242", 12, 2030, "123", null));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(
                new ErrorResponse(
                        "IDEMPOTENCY_CONFLICT",
                        "같은 Idempotency-Key로 다른 요청을 보낼 수 없습니다."),
                response.getBody());
    }

    @Test
    void tokenize_withoutIdempotencyKey_returnsBadRequest() {
        var response = controller().tokenize(null, request("1234"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(
                new ErrorResponse(
                        "IDEMPOTENCY_KEY_REQUIRED",
                        "유효한 Idempotency-Key가 필요합니다."),
                response.getBody());
    }

    @Test
    void tokenize_withPrefixedIdempotencyKey_returnsInvalidKey() {
        var response = controller().tokenize(
                "tokenize-550e8400-e29b-41d4-a716-446655440310",
                request("1234"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(
                new ErrorResponse(
                        "IDEMPOTENCY_KEY_INVALID",
                        "Idempotency-Key는 UUID 형식이어야 합니다."),
                response.getBody());
    }

    @Test
    void tokenize_concurrentSameRequest_createsOneTokenizationResult() {
        PgMockTokenizationController controller = controller();

        List<TokenizationResponse> responses = IntStream.range(0, 16)
                .parallel()
                .mapToObj(index -> controller.tokenize(KEY_8, request("1234")))
                .map(response -> (TokenizationResponse) response.getBody())
                .toList();

        Set<TokenizationResponse> uniqueResponses = Set.copyOf(responses);
        assertEquals(1, uniqueResponses.size());
    }

    @Test
    void toString_redactsCardSecretsAndProviderToken() {
        TokenizationRequest request = request("1234");
        TokenizationResponse response = (TokenizationResponse) controller()
                .tokenize(KEY_9, request)
                .getBody();

        assertFalse(request.toString().contains(request.cardNumber()));
        assertFalse(request.toString().contains(request.cvc()));
        assertFalse(request.toString().contains(request.cardPassword()));
        assertFalse(response.toString().contains(response.providerTokenRef()));
    }

    private PgMockTokenizationController controller() {
        return new PgMockTokenizationController(new PgMockTokenizationService(CLOCK));
    }

    private TokenizationRequest request(String cardPassword) {
        return new TokenizationRequest(
                "4111111111111111",
                12,
                2030,
                "123",
                cardPassword);
    }
}
