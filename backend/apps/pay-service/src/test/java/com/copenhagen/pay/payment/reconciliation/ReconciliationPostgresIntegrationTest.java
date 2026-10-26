package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.payment.authorization.AuthorizationSessionLifecycleService;
import com.copenhagen.pay.payment.authorization.JdbcAuthorizationSessionRepository;
import com.copenhagen.pay.payment.credential.mandate.ArtifactEncryptionService;
import com.copenhagen.pay.payment.credential.mandate.MandateProperties;
import com.copenhagen.pay.payment.payment.JdbcPaymentExecutionRepository;
import com.copenhagen.pay.payment.payment.JdbcPaymentRepository;
import com.copenhagen.pay.payment.payment.PaymentLifecycleService;
import com.copenhagen.pay.payment.payment.PaymentReceiptService;
import com.copenhagen.pay.payment.pg.PaymentGateway;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult.Outcome;
import com.copenhagen.pay.payment.pg.PaymentGateway.StatusResult;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationPostgresIntegrationTest {

    private static final String ATTEMPT = "00000000-0000-0000-0000-000000000010";
    private EmbeddedPostgres postgres;
    private Flyway flyway;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private PaymentGateway gateway;
    private AnnotationConfigApplicationContext context;
    private ReconciliationService service;
    private ReconciliationJobService jobs;
    private ReconciliationSettlementService settlement;

    @BeforeAll
    void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    @AfterAll
    void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() {
        String schema = "reconciliation_" + UUID.randomUUID().toString().replace("-", "");
        dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:" + postgres.getPort() + "/postgres?currentSchema=" + schema,
                "postgres", "postgres");
        flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.migrate();
        new ResourceDatabasePopulator(new ClassPathResource("reconciliation/unknown-payment.sql"))
                .execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        clock = new MutableClock();
        gateway = mock(PaymentGateway.class);
        when(gateway.lookup(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new StatusResult(Outcome.SUCCESS, "pg-test", "APPROVED");
        });
        context = createContext(false);
        service = context.getBean(ReconciliationService.class);
        jobs = context.getBean(ReconciliationJobService.class);
        settlement = context.getBean(ReconciliationSettlementService.class);
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

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void process_result_updatesAllLedgersWithoutNewCharge(Outcome outcome) {
        when(gateway.lookup(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new StatusResult(outcome, outcome == Outcome.UNKNOWN ? null : "pg-test", "unsafe upstream text");
        });
        assertTrue(service.processNext());
        assertEquals(outcome.name(), status("payments"));
        assertEquals(outcome.name(), status("payment_attempts"));
        assertEquals(outcome.name(), status("pg_transactions"));
        assertEquals(outcome == Outcome.UNKNOWN ? "FAILED" : "SUCCEEDED", status("payment_reconciliation_jobs"));
        // 결제 결과는 세션이 아니라 판매처별 item 이 받는다.
        assertEquals("APPROVED", status("authorization_sessions"));
        assertEquals(switch (outcome) {
            case SUCCESS -> "COMPLETED";
            case FAILED -> "INVALIDATED";
            case UNKNOWN -> "PENDING";
        }, status("authorization_session_items"));
        assertEquals(outcome == Outcome.SUCCESS ? 1 : 0, count("payment_receipts"));
        assertEquals(1, count("payments"));
        assertEquals(1, count("payment_attempts"));
        assertEquals(1, count("pg_transactions"));
        assertNotNull(jdbc.queryForObject("SELECT last_checked_at FROM pg_transactions", java.sql.Timestamp.class));
        assertFalse(service.processNext());
        verify(gateway, times(1)).lookup(any());
        verify(gateway, never()).charge(any());
    }

    @Test
    void process_fiveUnknownLookups_exhaustsWithoutSixthCall() {
        when(gateway.lookup(any())).thenReturn(StatusResult.unknown("unavailable"));
        for (int i = 1; i <= 5; i++) {
            assertTrue(service.processNext());
            assertEquals(i, jdbc.queryForObject(
                    "SELECT attempt_count FROM payment_reconciliation_jobs", Integer.class));
            assertEquals(i == 5 ? "EXHAUSTED" : "FAILED", status("payment_reconciliation_jobs"));
            clock.advance(61);
        }
        assertFalse(service.processNext());
        assertEquals("UNKNOWN", status("payments"));
        verify(gateway, times(5)).lookup(any());
        verify(gateway, never()).charge(any());
    }

    @Test
    void reclaim_expiredLease_rejectsPreviousWorkerResult() {
        ReconciliationJob previous = jobs.claimNext().orElseThrow();
        clock.advance(61);
        ReconciliationJob recovered = jobs.claimNext().orElseThrow();
        assertEquals(2, recovered.attemptCount());
        settlement.settle(previous, new StatusResult(Outcome.SUCCESS, "pg-test", "APPROVED"));
        assertEquals("UNKNOWN", status("payments"));
        assertFalse(jobs.markSucceeded(previous));
        settlement.settle(recovered, new StatusResult(Outcome.FAILED, "pg-test", "DECLINED"));
        assertEquals("FAILED", status("payments"));
        assertEquals("SUCCEEDED", status("payment_reconciliation_jobs"));
        assertEquals(0, count("payment_receipts"));
    }

    @Test
    void reclaim_fifthWorkerCrash_exhaustsWithoutMoreQueries() {
        jdbc.update("UPDATE payment_reconciliation_jobs SET attempt_count = 4");
        ReconciliationJob last = jobs.claimNext().orElseThrow();
        assertEquals(5, last.attemptCount());
        clock.advance(61);
        assertTrue(service.processNext());
        assertEquals("EXHAUSTED", status("payment_reconciliation_jobs"));
        assertEquals(5, jdbc.queryForObject("SELECT attempt_count FROM payment_reconciliation_jobs", Integer.class));
        assertFalse(service.processNext());
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED", "READY", "PROCESSING"})
    void process_nonUnknownPayment_skipsPgAndCancelsJob(String paymentStatus) {
        jdbc.update("UPDATE payments SET status = ?", paymentStatus);
        assertTrue(service.processNext());
        assertEquals(paymentStatus, status("payments"));
        assertEquals("CANCELLED", status("payment_reconciliation_jobs"));
        assertEquals(0, jdbc.queryForObject("SELECT attempt_count FROM payment_reconciliation_jobs", Integer.class));
        verifyNoInteractions(gateway);
    }

    @Test
    void create_duplicateJobAndAttempt_databasePreventsDuplicates() {
        JdbcPaymentExecutionRepository repository = context.getBean(JdbcPaymentExecutionRepository.class);
        repository.insertReconciliationJob(ATTEMPT, "PG_TIMEOUT", clock.instant());
        repository.insertReconciliationJob(ATTEMPT, "PG_TIMEOUT", clock.instant());
        assertEquals(1, count("payment_reconciliation_jobs"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO payment_attempts (id, payment_id, attempt_no, operation, request_hash, status, started_at)
                SELECT ?, payment_id, 2, operation, request_hash, status, started_at FROM payment_attempts
                """, UUID.randomUUID().toString()));
        assertEquals(1, count("payment_attempts"));
    }

    @Test
    void claim_preservesOriginatingRequestIdForWorkerTracing() {
        JdbcPaymentExecutionRepository repository = context.getBean(JdbcPaymentExecutionRepository.class);
        repository.insertReconciliationJob(
                ATTEMPT,
                "TRACE_CONTEXT",
                clock.instant().minusSeconds(1),
                "request-reconciliation-123");

        ReconciliationJob claimed = jobs.claimNext().orElseThrow();

        assertEquals("request-reconciliation-123", claimed.requestId());
    }

    @Test
    void settle_replayedSuccess_issuesOnlyOneReceipt() {
        ReconciliationJob job = jobs.claimNext().orElseThrow();
        StatusResult success = new StatusResult(Outcome.SUCCESS, "pg-test", "APPROVED");
        settlement.settle(job, success);
        settlement.settle(job, success);
        assertEquals(1, count("payment_receipts"));
        assertEquals("COMPLETED", status("authorization_session_items"));
    }

    @Test
    void settle_receiptInsertFails_rollsBackLedgersSessionAndJob() {
        jdbc.execute("ALTER TABLE payment_receipts ADD CONSTRAINT test_reject_receipt CHECK (FALSE)");
        ReconciliationJob job = jobs.claimNext().orElseThrow();
        assertThrows(DataIntegrityViolationException.class,
                () -> settlement.settle(job, new StatusResult(Outcome.SUCCESS, "pg-test", "APPROVED")));
        assertEquals("UNKNOWN", status("payments"));
        assertEquals("UNKNOWN", status("payment_attempts"));
        assertEquals("UNKNOWN", status("pg_transactions"));
        assertEquals("APPROVED", status("authorization_sessions"));
        assertEquals("RUNNING", status("payment_reconciliation_jobs"));
        assertEquals(0, count("payment_receipts"));
    }

    @Test
    void process_twoWorkersAndDifferentReasons_onlyOneQueriesPg() throws Exception {
        context.getBean(JdbcPaymentExecutionRepository.class)
                .insertReconciliationJob(ATTEMPT, "PG_RESPONSE_LOST", clock.instant());
        CountDownLatch querying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(gateway.lookup(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            querying.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return new StatusResult(Outcome.SUCCESS, "pg-test", "APPROVED");
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(service::processNext);
            try {
                assertTrue(querying.await(10, TimeUnit.SECONDS));
                Future<Boolean> second = executor.submit(service::processNext);
                assertFalse(second.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertTrue(first.get(5, TimeUnit.SECONDS));
        }
        assertTrue(service.processNext());
        verify(gateway, times(1)).lookup(any());
        verify(gateway, never()).charge(any());
        assertEquals(1, count("payment_receipts"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM payment_reconciliation_jobs WHERE status = 'CANCELLED'", Integer.class));
    }

    @Test
    void worker_scheduledExecution_processesPersistedJob() throws Exception {
        context.close();
        CountDownLatch queried = new CountDownLatch(1);
        when(gateway.lookup(any())).thenAnswer(invocation -> {
            queried.countDown();
            return new StatusResult(Outcome.SUCCESS, "pg-test", "APPROVED");
        });
        context = createContext(true);
        assertTrue(queried.await(10, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"SUCCESS".equals(status("payments")) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("SUCCESS", status("payments"));
        assertEquals("SUCCEEDED", status("payment_reconciliation_jobs"));
    }

    private AnnotationConfigApplicationContext createContext(boolean worker) {
        AnnotationConfigApplicationContext created = new AnnotationConfigApplicationContext();
        created.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "pay.reconciliation.poll-delay-millis", "20")));
        created.register(TransactionConfiguration.class);
        created.registerBean(DataSource.class, () -> dataSource);
        created.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        created.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        created.registerBean(Clock.class, () -> clock);
        created.registerBean(PaymentGateway.class, () -> gateway);
        created.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        created.registerBean(ArtifactEncryptionService.class, () -> new ArtifactEncryptionService(
                new MandateProperties("pay-test", "synthetic-key", "", "")));
        created.register(JdbcReconciliationJobRepository.class, JdbcPaymentExecutionRepository.class,
                JdbcPaymentRepository.class, JdbcAuthorizationSessionRepository.class,
                AuthorizationSessionLifecycleService.class, PaymentLifecycleService.class, PaymentReceiptService.class,
                ReconciliationJobService.class, ReconciliationSettlementService.class, ReconciliationService.class);
        if (worker) {
            created.register(SchedulingConfiguration.class);
            created.register(PayWorker.class);
        }
        created.refresh();
        return created;
    }

    private String status(String table) {
        return jdbc.queryForObject("SELECT status FROM " + table, String.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class SchedulingConfiguration {
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-14T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }
}
