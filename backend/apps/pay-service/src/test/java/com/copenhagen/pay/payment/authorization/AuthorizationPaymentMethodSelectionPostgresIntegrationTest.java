package com.copenhagen.pay.payment.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.payment.merchant.JdbcMerchantAccountRepository;
import com.copenhagen.pay.payment.merchant.MerchantPublicKeyClient;
import com.copenhagen.pay.payment.merchant.MerchantServiceProperties;
import com.copenhagen.pay.payment.paymentmethod.JdbcPaymentMethodRepository;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationPaymentMethodSelectionPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String MERCHANT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String MERCHANT_ID = "merchant-test";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000003";
    private static final String KEY_ID = "merchant-test-es256-1";

    private EmbeddedPostgres postgres;
    private Flyway flyway;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private ECKey signingKey;

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
    void setUp() throws JOSEException {
        String schema = "payment_method_selection_"
                + UUID.randomUUID().toString().replace("-", "");
        dataSource = new DriverManagerDataSource(
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
        jdbc.update(
                """
                INSERT INTO merchant_accounts (id, merchant_id, display_name, auth_type, status)
                VALUES (?, ?, ?, 'TOKEN', 'ACTIVE')
                """,
                MERCHANT_ACCOUNT_ID,
                MERCHANT_ID,
                "Test Merchant");
        signingKey = new ECKeyGenerator(Curve.P_256)
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.ES256)
                .generate();
        context = createContext();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        if (flyway != null) {
            flyway.clean();
        }
    }

    @Test
    void selectsCardAndPreservesTheApprovalSnapshot() {
        AuthorizationSessionService service = context.getBean(AuthorizationSessionService.class);
        AuthorizationSession before = service.create(
                        command("00000000-0000-0000-0000-000000000005"),
                        "550e8400-e29b-41d4-a716-446655440502",
                        repeat('a', 64))
                .session();
        String paymentMethodId = "00000000-0000-0000-0000-000000000101";
        insertPaymentMethod(paymentMethodId, false);

        AuthorizationSession after = service.selectPaymentMethod(
                USER_ID,
                before.id(),
                paymentMethodId);

        assertEquals(AuthorizationSessionStatus.PENDING, after.status());
        assertEquals(paymentMethodId, after.selectedPaymentMethodId());
        assertEquals(before.purchaseRequestId(), after.purchaseRequestId());
        assertEquals(
                before.items().getFirst().externalCheckoutId(),
                after.items().getFirst().externalCheckoutId());
        assertEquals(
                before.items().getFirst().checkoutDigest(),
                after.items().getFirst().checkoutDigest());
        assertEquals(
                service.displaySnapshot(before.items().getFirst()),
                service.displaySnapshot(after.items().getFirst()));
        assertEquals(before.totalAmountMinor(), after.totalAmountMinor());
        assertEquals(before.currency(), after.currency());
        assertEquals(before.expiresAt(), after.expiresAt());

        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT selected_payment_method_id, status, total_amount_minor, currency,

                       expires_at
                FROM authorization_sessions
                WHERE id = ?
                """,
                before.id());
        assertEquals(paymentMethodId, row.get("selected_payment_method_id").toString().trim());
        assertEquals("PENDING", row.get("status"));
        assertEquals(99000L, ((Number) row.get("total_amount_minor")).longValue());
        assertEquals("KRW", row.get("currency").toString().trim());

        assertEquals(
                before.expiresAt(),
                ((java.sql.Timestamp) row.get("expires_at")).toInstant());
    }

    @Test
    void concurrentSelectionsLeaveOneCompletePendingState() throws Exception {
        AuthorizationSessionService service = context.getBean(AuthorizationSessionService.class);
        AuthorizationSession before = service.create(
                        command("00000000-0000-0000-0000-000000000006"),
                        "approval-selection-002",
                        repeat('b', 64))
                .session();
        String firstPaymentMethodId = "00000000-0000-0000-0000-000000000102";
        String secondPaymentMethodId = "00000000-0000-0000-0000-000000000103";
        insertPaymentMethod(firstPaymentMethodId, false);
        service.selectPaymentMethod(USER_ID, before.id(), firstPaymentMethodId);
        insertPaymentMethod(secondPaymentMethodId, false);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<AuthorizationSession> first = executor.submit(() -> {
                start.await();
                return service.selectPaymentMethod(USER_ID, before.id(), firstPaymentMethodId);
            });
            Future<AuthorizationSession> second = executor.submit(() -> {
                start.await();
                return service.selectPaymentMethod(USER_ID, before.id(), secondPaymentMethodId);
            });
            start.countDown();

            AuthorizationSession firstResult = first.get();
            AuthorizationSession secondResult = second.get();
            AuthorizationSession finalSession = service.findDetail(USER_ID, before.id());

            assertEquals(AuthorizationSessionStatus.PENDING, firstResult.status());
            assertEquals(AuthorizationSessionStatus.PENDING, secondResult.status());
            assertEquals(AuthorizationSessionStatus.PENDING, finalSession.status());
            assertTrue(Set.of(firstPaymentMethodId, secondPaymentMethodId)
                    .contains(finalSession.selectedPaymentMethodId()));
            assertEquals(
                    before.items().getFirst().checkoutDigest(),
                    finalSession.items().getFirst().checkoutDigest());
            assertEquals(before.expiresAt(), finalSession.expiresAt());
        } finally {
            executor.shutdownNow();
        }
    }

    private AnnotationConfigApplicationContext createContext() {
        AnnotationConfigApplicationContext created = new AnnotationConfigApplicationContext();
        created.register(TransactionConfiguration.class);
        created.registerBean(DataSource.class, () -> dataSource);
        created.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        created.registerBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        created.registerBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));
        created.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        created.registerBean(IdempotencyGuard.class);
        created.registerBean(ApprovalProperties.class, () -> new ApprovalProperties(300, 60, true));
        created.registerBean(MerchantServiceProperties.class, () -> new MerchantServiceProperties(
                "http://merchant.test",
                "/.well-known/ucp",
                1000,
                1000,
                "ES256"));
        created.registerBean(MerchantPublicKeyClient.class, () -> merchantId ->
                List.of(signingKey.toPublicJWK().toJSONObject()));
        created.registerBean(MerchantCheckoutVerifier.class);
        created.register(
                JdbcAuthorizationSessionRepository.class,
                JdbcMerchantAccountRepository.class,
                JdbcPaymentMethodRepository.class,
                JdbcVerifiedCheckoutEvidenceRepository.class,
                AuthorizationSessionService.class);
        created.refresh();
        return created;
    }

    private void insertPaymentMethod(String paymentMethodId, boolean defaultMethod) {
        jdbc.update(
                """
                INSERT INTO payment_methods
                    (id, user_id, provider, provider_token_ref, brand, last4,
                     expiry_month, expiry_year, status, is_default, deleted_at)
                VALUES (?, ?, 'PG_MOCK', ?, 'VISA', '1234', 12, 2030, 'ACTIVE', ?, NULL)
                """,
                paymentMethodId,
                USER_ID,
                "provider-token-" + paymentMethodId,
                defaultMethod);
    }

    private CreateAuthorizationSessionCommand command(String purchaseRequestId) {
        String checkoutJwt = signedCheckout(purchaseRequestId);
        return new CreateAuthorizationSessionCommand(
                purchaseRequestId,
                USER_ID,
                99000,
                "KRW",
                List.of(new CreateAuthorizationSessionCommand.Item(
                        MERCHANT_ID,
                        "checkout-test",
                        externalHash(checkoutJwt),
                        checkoutJwt,
                        new AuthorizationDisplaySnapshot(
                                "Test Merchant",
                                List.of(new AuthorizationDisplaySnapshot.SummaryItem(
                                        "테스트 상품",
                                        null,
                                        1,
                                        99000))),
                        99000,
                        "KRW")));
    }

    private String signedCheckout(String purchaseRequestId) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("merchantId", MERCHANT_ID)
                .claim("checkoutId", "checkout-test")
                .claim("purchaseRequestId", purchaseRequestId)
                .claim("amountMinor", 99000)
                .claim("currency", "KRW")
                .expirationTime(java.util.Date.from(NOW.plusSeconds(300)))
                .build();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY_ID).build(),
                claims);
        try {
            signedJwt.sign(new ECDSASigner(signingKey.toECPrivateKey()));
            return signedJwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("test checkout signing failed", exception);
        }
    }

    private String externalHash(String checkoutJwt) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(checkoutJwt.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private String repeat(char value, int count) {
        return String.valueOf(value).repeat(count);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }
}
