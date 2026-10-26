package com.copenhagen.pay.payment.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.copenhagen.pay.common.auth.ConfiguredBearerTokenVerifier;
import com.copenhagen.pay.common.auth.PayAuthProperties;
import com.copenhagen.pay.common.auth.PayAuthenticationFilter;
import com.copenhagen.pay.common.auth.PayRouteAuthorizer;
import com.copenhagen.pay.common.error.ApiErrorWriter;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.common.idempotency.IdempotencyKeyResolver;
import com.copenhagen.pay.common.idempotency.JdbcIdempotencyRepository;
import com.copenhagen.pay.common.idempotency.PayIdempotencyFilter;
import com.copenhagen.pay.common.idempotency.PayIdempotencyService;
import com.copenhagen.pay.common.idempotency.RequestHashGenerator;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.credential.mandate.ArtifactEncryptionService;
import com.copenhagen.pay.payment.credential.mandate.MandateProperties;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * Core→Pay 구매 인계 알림을 실제 인증·멱등성 필터 체인으로 검증한다.
 *
 * <p>기존 알림 테스트는 {@link PurchaseNotificationService}를 직접 호출해 HTTP 경계를 건너뛰었고,
 * Core 쪽 계약 테스트는 Pay를 mock으로 대신했다. 그 사이로 이슈 429(비UUID 멱등키로 알림이
 * 400에 막히던 회귀)가 빠져나갔다.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PurchaseNotificationApiPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
    private static final String PATH = "/internal/v1/purchase-notifications";
    private static final String CORE_SERVICE_TOKEN = "test-core-service-token";
    private static final String ENCRYPTION_KEY =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private EmbeddedPostgres postgres;
    private Flyway flyway;
    private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    @BeforeAll
    void startPostgres() throws IOException {
        postgres = EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses", "127.0.0.1")
                .start();
    }

    @AfterAll
    void stopPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() {
        String schema = "notification_api_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:" + postgres.getPort()
                        + "/postgres?currentSchema=" + schema,
                "postgres",
                "postgres");
        flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PurchaseNotificationController(
                        new PurchaseNotificationService(jdbc, clock())))
                .addFilters(authenticationFilter(), idempotencyFilter())
                .build();
    }

    @AfterEach
    void tearDown() {
        PayLogContext.clear();
        if (flyway != null) {
            flyway.clean();
        }
    }

    @Test
    void uuidKeyCreatesHandoffNotificationAndRepeatIsReplayedWithoutDuplicate() throws Exception {
        UUID purchaseId = UUID.randomUUID();
        String key = notificationIdempotencyKey(purchaseId);

        assertEquals(200, handoff(key, purchaseId).getResponse().getStatus());

        assertEquals(1, count("pay_notification_outbox"));
        assertEquals("PURCHASE_HANDOFF", jdbc.queryForObject(
                "SELECT event_type FROM pay_notification_outbox", String.class));
        assertEquals(purchaseId.toString(), jdbc.queryForObject(
                "SELECT payload ->> 'purchaseRequestId' FROM pay_notification_outbox", String.class));

        // 같은 키·같은 본문 재시도는 최초 응답을 재생하고 알림을 늘리지 않는다.
        assertEquals(200, handoff(key, purchaseId).getResponse().getStatus());

        assertEquals(1, count("pay_notification_outbox"));
        assertEquals(1, count("pay_idempotency_records"));
    }

    @Test
    void prefixedKeyIsRejectedAndCreatesNoNotification() throws Exception {
        UUID purchaseId = UUID.randomUUID();

        // 이슈 429 회귀: Core가 보내던 "purchase-handoff-<UUID>" 형식.
        MvcResult result = handoff("purchase-handoff-" + purchaseId, purchaseId);

        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_INVALID"));
        assertEquals(0, count("pay_notification_outbox"));
    }

    @Test
    void missingKeyIsRejectedAndCreatesNoNotification() throws Exception {
        UUID purchaseId = UUID.randomUUID();

        MvcResult result = mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + CORE_SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(purchaseId)))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REQUIRED"));
        assertEquals(0, count("pay_notification_outbox"));
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
        assertEquals(0, count("pay_notification_outbox"));
        assertEquals(0, count("pay_idempotency_records"));
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

    private PayAuthenticationFilter authenticationFilter() {
        PayAuthProperties properties = new PayAuthProperties(
                Map.of(),
                Map.of(CORE_SERVICE_TOKEN, "core-service"));
        return new PayAuthenticationFilter(
                new ConfiguredBearerTokenVerifier(properties),
                new ApiErrorWriter(new ObjectMapper()),
                PayRouteAuthorizer.allowAllForTests());
    }

    private PayIdempotencyFilter idempotencyFilter() {
        PayIdempotencyService idempotencyService = new PayIdempotencyService(
                new JdbcIdempotencyRepository(jdbc),
                new IdempotencyGuard(),
                new ArtifactEncryptionService(
                        new MandateProperties("pay-service", "test-key", "", ENCRYPTION_KEY)),
                clock());
        return new PayIdempotencyFilter(
                new IdempotencyKeyResolver(),
                new ApiErrorWriter(new ObjectMapper()),
                new RequestHashGenerator(new ObjectMapper()),
                idempotencyService);
    }

    private Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
