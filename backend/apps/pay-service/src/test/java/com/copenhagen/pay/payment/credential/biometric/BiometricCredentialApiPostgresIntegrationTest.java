package com.copenhagen.pay.payment.credential.biometric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.copenhagen.pay.common.auth.ConfiguredBearerTokenVerifier;
import com.copenhagen.pay.common.auth.PayAuthProperties;
import com.copenhagen.pay.common.auth.PayAuthenticationFilter;
import com.copenhagen.pay.common.auth.PayRouteAuthorizer;
import com.copenhagen.pay.common.error.ApiErrorWriter;
import com.copenhagen.pay.common.error.GlobalExceptionHandler;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.common.idempotency.IdempotencyKeyResolver;
import com.copenhagen.pay.common.idempotency.JdbcIdempotencyRepository;
import com.copenhagen.pay.common.idempotency.PayIdempotencyFilter;
import com.copenhagen.pay.common.idempotency.PayIdempotencyService;
import com.copenhagen.pay.common.idempotency.RequestHashGenerator;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.authorization.JdbcAuthorizationSessionRepository;
import com.copenhagen.pay.payment.credential.mandate.ArtifactEncryptionService;
import com.copenhagen.pay.payment.credential.mandate.MandateProperties;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
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
 * 생체 credential 조회·해제를 실제 인증·멱등성 필터 체인으로 검증한다.
 *
 * <p>해제는 승인 세션의 {@code required_action}까지 건드리므로 실 DB 없이는 의미가 없다.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BiometricCredentialApiPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
    private static final String PATH = "/api/pay/v1/users/me/biometric-credentials";
    private static final String USER_TOKEN = "test-user-token";
    private static final String DEVICE_ID = "device-001";
    private static final String ENCRYPTION_KEY =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000003";
    private static final String OTHER_USER_ID = "00000000-0000-0000-0000-000000000009";
    private static final String MERCHANT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String PAYMENT_METHOD_ID = "00000000-0000-0000-0000-000000000002";
    private static final String OTHER_PAYMENT_METHOD_ID = "00000000-0000-0000-0000-000000000008";

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
        String schema = "biometric_api_" + UUID.randomUUID().toString().replace("-", "");
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
                .standaloneSetup(new BiometricCredentialController(
                        new BiometricCredentialService(
                                new JdbcBiometricCredentialRepository(jdbc),
                                new JdbcAuthorizationSessionRepository(jdbc),
                                clock())))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(authenticationFilter(), idempotencyFilter())
                .build();
        insertReferenceData();
    }

    @AfterEach
    void tearDown() {
        PayLogContext.clear();
        if (flyway != null) {
            flyway.clean();
        }
    }

    @Test
    void statusReportsNotRegisteredBeforeEnrollment() throws Exception {
        MvcResult result = status();

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("\"registered\":false"));
    }

    @Test
    void statusReportsActiveAfterEnrollment() throws Exception {
        register();

        MvcResult result = status();

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"registered\":true"));
        assertTrue(body.contains("\"status\":\"ACTIVE\""));
    }

    @Test
    void revokeMarksCredentialRevokedAndStatusFlipsBack() throws Exception {
        register();

        assertEquals(204, revoke(UUID.randomUUID().toString()).getResponse().getStatus());

        assertTrue(status().getResponse().getContentAsString().contains("\"registered\":false"));
        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM biometric_credentials WHERE user_id = ?", String.class, USER_ID));
    }

    @Test
    void revokeWithoutRegisteredCredentialIsStillNoContent() throws Exception {
        assertEquals(204, revoke(UUID.randomUUID().toString()).getResponse().getStatus());
    }

    @Test
    void revokedDeviceCanRegisterAgain() throws Exception {
        register();
        revoke(UUID.randomUUID().toString());

        assertEquals(201, register().getResponse().getStatus());
        assertTrue(status().getResponse().getContentAsString().contains("\"registered\":true"));
    }

    @Test
    void revokeMovesPendingSessionToPinAndLeavesOtherUsersAlone() throws Exception {
        register();
        insertPendingSession("session-mine", USER_ID);
        insertPendingSession("session-other", OTHER_USER_ID);

        assertEquals(204, revoke(UUID.randomUUID().toString()).getResponse().getStatus());

        assertEquals("PIN", requiredAction("session-mine"));
        assertEquals("BIOMETRIC", requiredAction("session-other"));
    }

    @Test
    void revokeWithoutIdempotencyKeyIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(delete(PATH)
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .header("X-Device-Id", DEVICE_ID))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void missingDeviceHeaderIsRejectedOnStatusAndRevoke() throws Exception {
        MvcResult statusResult = mockMvc.perform(get(PATH)
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andReturn();
        MvcResult revokeResult = mockMvc.perform(delete(PATH)
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();

        assertEquals(400, statusResult.getResponse().getStatus());
        assertTrue(statusResult.getResponse().getContentAsString()
                .contains("BIOMETRIC_CREDENTIAL_INVALID"));
        assertEquals(400, revokeResult.getResponse().getStatus());
        assertTrue(revokeResult.getResponse().getContentAsString()
                .contains("BIOMETRIC_CREDENTIAL_INVALID"));
    }

    private MvcResult status() throws Exception {
        return mockMvc.perform(get(PATH)
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .header("X-Device-Id", DEVICE_ID))
                .andReturn();
    }

    private MvcResult revoke(String idempotencyKey) throws Exception {
        return mockMvc.perform(delete(PATH)
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .header("X-Device-Id", DEVICE_ID)
                        .header("Idempotency-Key", idempotencyKey))
                .andReturn();
    }

    private MvcResult register() throws Exception {
        String publicKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("public-key-bytes".getBytes());
        return mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + DEVICE_ID + "\",\"publicKey\":\"" + publicKey
                                + "\",\"credentialType\":\"PLATFORM_BIOMETRIC\"}"))
                .andReturn();
    }

    private String requiredAction(String sessionId) {
        return jdbc.queryForObject(
                "SELECT required_action FROM authorization_sessions WHERE id = ?",
                String.class,
                sessionId);
    }

    private PayAuthenticationFilter authenticationFilter() {
        PayAuthProperties properties = new PayAuthProperties(
                Map.of(USER_TOKEN, USER_ID),
                Map.of());
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

    private void insertReferenceData() {
        jdbc.update("""
                INSERT INTO merchant_accounts (id, merchant_id, display_name, auth_type, status)
                VALUES (?, 'merchant-test', 'Test Merchant', 'TOKEN', 'ACTIVE')
                """, MERCHANT_ACCOUNT_ID);
        jdbc.update("""
                INSERT INTO payment_methods
                    (id, user_id, provider, provider_token_ref, status, is_default)
                VALUES (?, ?, 'TEST', 'token-ref', 'ACTIVE', TRUE)
                """, PAYMENT_METHOD_ID, USER_ID);
        // 결제수단 FK가 (id, user_id) 복합키라 다른 사용자 세션에는 그 사용자의 결제수단이 필요하다.
        jdbc.update("""
                INSERT INTO payment_methods
                    (id, user_id, provider, provider_token_ref, status, is_default)
                VALUES (?, ?, 'TEST', 'token-ref-other', 'ACTIVE', TRUE)
                """, OTHER_PAYMENT_METHOD_ID, OTHER_USER_ID);
    }

    private void insertPendingSession(String sessionId, String userId) {
        jdbc.update("""
                INSERT INTO authorization_sessions
                    (id, purchase_request_id, user_id,
                     total_amount_minor, currency, selected_payment_method_id, status,
                     idempotency_key, request_hash, required_action, expires_at,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, 99000, 'KRW', ?,
                        'PENDING', ?, ?, 'BIOMETRIC', ?, ?, ?, 0)
                """,
                sessionId,
                UUID.randomUUID().toString(),
                userId,
                USER_ID.equals(userId) ? PAYMENT_METHOD_ID : OTHER_PAYMENT_METHOD_ID,
                "idem-" + sessionId,
                "a".repeat(64),
                Timestamp.from(NOW.plusSeconds(300)),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        jdbc.update(
                """
                INSERT INTO authorization_session_items
                    (id, authorization_session_id, merchant_account_id, external_checkout_id,
                     checkout_digest, display_snapshot, amount_minor, currency, status,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, 'checkout-test', ?, '{}'::jsonb, 99000, 'KRW', 'PENDING',
                        ?, ?, 0)
                """,
                UUID.randomUUID().toString(),
                sessionId,
                MERCHANT_ACCOUNT_ID,
                "0".repeat(64),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
    }
}
