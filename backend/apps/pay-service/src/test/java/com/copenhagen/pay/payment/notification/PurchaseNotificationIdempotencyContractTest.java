package com.copenhagen.pay.payment.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.copenhagen.pay.common.auth.ConfiguredBearerTokenVerifier;
import com.copenhagen.pay.common.auth.PayAuthProperties;
import com.copenhagen.pay.common.auth.PayAuthenticationFilter;
import com.copenhagen.pay.common.auth.PayRouteAuthorizer;
import com.copenhagen.pay.common.error.ApiErrorWriter;
import com.copenhagen.pay.common.idempotency.IdempotencyKeyResolver;
import com.copenhagen.pay.common.idempotency.PayIdempotencyFilter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * Core→Pay 구매 인계 알림의 HTTP 경계 계약. 인증·멱등성 필터를 실제로 태운다.
 *
 * <p>DB가 필요한 재생·중복 방지 검증은 {@code PurchaseNotificationApiPostgresIntegrationTest}에 있다.
 * 이 테스트는 DB 없이 돌기 때문에 CI의 {@code :apps:pay-service:test}에서 함께 실행되며,
 * 이슈 429(비UUID 멱등키로 알림이 400에 막히던 회귀)의 상시 가드 역할을 한다.
 */
class PurchaseNotificationIdempotencyContractTest {

    private static final String PATH = "/internal/v1/purchase-notifications";
    private static final String CORE_SERVICE_TOKEN = "test-core-service-token";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private PurchaseNotificationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(PurchaseNotificationService.class);
        PayAuthProperties properties = new PayAuthProperties(
                Map.of(),
                Map.of(CORE_SERVICE_TOKEN, "core-service"));
        PayAuthenticationFilter authenticationFilter = new PayAuthenticationFilter(
                new ConfiguredBearerTokenVerifier(properties),
                new ApiErrorWriter(new ObjectMapper()),
                PayRouteAuthorizer.allowAllForTests());
        PayIdempotencyFilter idempotencyFilter = new PayIdempotencyFilter(
                new IdempotencyKeyResolver(),
                new ApiErrorWriter(new ObjectMapper()));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PurchaseNotificationController(service))
                .addFilters(authenticationFilter, idempotencyFilter)
                .build();
    }

    @Test
    void coreDerivedUuidKeyReachesTheController() throws Exception {
        UUID purchaseId = UUID.randomUUID();
        String key = notificationIdempotencyKey(purchaseId);
        assertEquals(key, UUID.fromString(key).toString());

        MvcResult result = handoff(key, purchaseId);

        assertEquals(200, result.getResponse().getStatus());
        verify(service).create(USER_ID, purchaseId);
    }

    @Test
    void prefixedKeyIsRejectedBeforeTheController() throws Exception {
        UUID purchaseId = UUID.randomUUID();

        // 이슈 429 회귀: Core가 보내던 "purchase-handoff-<UUID>" 형식.
        MvcResult result = handoff("purchase-handoff-" + purchaseId, purchaseId);

        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_INVALID"));
        verify(service, never()).create(any(), any());
    }

    @Test
    void missingKeyIsRejectedBeforeTheController() throws Exception {
        UUID purchaseId = UUID.randomUUID();

        MvcResult result = mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + CORE_SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(purchaseId)))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REQUIRED"));
        verify(service, never()).create(any(), any());
    }

    @Test
    void missingServiceTokenIsRejectedBeforeIdempotency() throws Exception {
        UUID purchaseId = UUID.randomUUID();

        MvcResult result = mockMvc.perform(post(PATH)
                        .header("Idempotency-Key", notificationIdempotencyKey(purchaseId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(purchaseId)))
                .andReturn();

        assertEquals(401, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("AUTHENTICATION_REQUIRED"));
        verify(service, never()).create(any(), any());
    }

    /** Core {@code PaymentApprovalRequests.purchaseNotificationIdempotencyKey}와 같은 파생식. */
    private String notificationIdempotencyKey(UUID purchaseId) {
        return UUID.nameUUIDFromBytes(
                ("copenhagen-pay-purchase-handoff:" + purchaseId)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private MvcResult handoff(String idempotencyKey, UUID purchaseId) throws Exception {
        return mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + CORE_SERVICE_TOKEN)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(purchaseId)))
                .andReturn();
    }

    private String body(UUID purchaseId) {
        return "{\"userId\":\"" + USER_ID + "\",\"purchaseRequestId\":\"" + purchaseId + "\"}";
    }
}
