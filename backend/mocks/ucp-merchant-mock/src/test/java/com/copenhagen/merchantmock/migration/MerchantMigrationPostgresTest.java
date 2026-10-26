package com.copenhagen.merchantmock.migration;

import java.util.List;

import javax.sql.DataSource;
import java.time.OffsetDateTime;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.support.EmbeddedPostgresSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 마이그레이션 검증(Jira 294). 운영과 같은 SQL 파일을 진짜 PostgreSQL 에 적용한다.
 *
 * <p>이 모듈의 다른 테스트는 Spring 컨텍스트를 안 띄워 Flyway 가 한 번도 돌지 않는다. 빌드 초록이
 * SQL 정상을 뜻하지 않는 상태였고 그 구멍을 메우는 것이 이 클래스다.
 *
 * <p>두 경우를 모두 본다. 빈 DB 에 전체를 적용하는 경우와, 직전 공유 버전이 깔린 DB 에 새 마이그레이션을
 * 얹는 경우다. 후자가 배포 스모크 3단계(develop 데이터 위 이미지 교체)가 잡던 상황이다(DESIGN.md 25.5절).
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantMigrationPostgresTest {

    private static final List<String> TABLES =
            List.of("products", "checkout_sessions", "orders", "order_items", "webhook_deliveries");

    private EmbeddedPostgresSupport postgres;

    @BeforeAll
    void startPostgres() throws Exception {
        this.postgres = EmbeddedPostgresSupport.start();
    }

    @AfterAll
    void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    @DisplayName("빈 DB 에 V1~V12 를 전부 적용한다")
    void migrate_emptyDatabase_appliesAllVersions() {
        // given
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);

        // when
        int applied = postgres.flywayFor(dataSource, schema, null).migrate().migrationsExecuted;

        // then
        JdbcTemplate jdbc = postgres.jdbcFor(dataSource);
        assertEquals(12, applied);
        assertEquals("12", currentVersion(jdbc, schema));
        TABLES.forEach(table -> assertTrue(tableExists(jdbc, schema, table), table + " 가 없다"));
    }

    @Test
    @DisplayName("V3 까지 적용된 DB 위에 V4~V12 가 올라간다")
    void migrate_databaseAtV3_appliesRemainingVersions() {
        // given
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, "3").migrate();
        JdbcTemplate jdbc = postgres.jdbcFor(dataSource);
        assertEquals("3", currentVersion(jdbc, schema));
        assertEquals(5, seededProductCount(jdbc));

        // when
        int applied = postgres.flywayFor(dataSource, schema, null).migrate().migrationsExecuted;

        // then
        assertEquals(9, applied);
        assertEquals("12", currentVersion(jdbc, schema));
        assertTrue(tableExists(jdbc, schema, "order_items"));
        assertEquals(5, seededProductCount(jdbc), "기존 시드 데이터가 살아 있어야 한다");
    }

    @Test
    @DisplayName("V4 가 쓰지 않는 checkout_sessions 컬럼을 걷어낸다")
    void migrate_afterV4_dropsUnusedCheckoutColumns() {
        // given
        Migrated migrated = migrated();

        // when & then
        List.of("buyer_ref", "completion_key", "completion_request_hash", "checkout_digest",
                        "signing_kid", "processor_transaction_id", "payment_error_code")
                .forEach(column -> assertFalse(columnExists(migrated, column),
                        column + " 은 V4 에서 지웠어야 한다"));
        assertTrue(columnExists(migrated, "canonical_checkout"));
        assertTrue(columnExists(migrated, "completion_response_json"));
    }

    @Test
    @DisplayName("만료 스윕 워커가 쓰는 인덱스가 있다")
    void migrate_always_createsExpirySweepIndex() {
        // given
        Migrated migrated = migrated();

        // when
        List<String> indexes = migrated.jdbc().queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = 'checkout_sessions'",
                String.class, migrated.schema());

        // then
        assertTrue(indexes.contains("ix_checkout_expiry"), indexes.toString());
        assertTrue(indexes.contains("ix_checkout_payment"), indexes.toString());
    }

    @Test
    @DisplayName("상태 CHECK 제약이 UCP 여섯 값만 받는다")
    void checkoutStatusCheck_unknownValue_isRejected() {
        // given
        JdbcTemplate jdbc = migrated().jdbc();

        // when & then
        jdbc.update("INSERT INTO checkout_sessions (id, merchant_id, status) VALUES (?, 'merchant-a', ?)",
                "ok-1", "READY_FOR_COMPLETE");
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "INSERT INTO checkout_sessions (id, merchant_id, status) VALUES (?, 'merchant-a', ?)",
                "bad-1", "EXPIRED"));
    }

    @Test
    @DisplayName("주문 상세는 주문과 상품을 모두 참조한다")
    void orderItems_missingParent_isRejected() {
        // given
        JdbcTemplate jdbc = migrated().jdbc();

        // when & then
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                INSERT INTO order_items (id, order_id, line_no, product_id, sku, title, unit_amount,
                                         quantity, line_amount)
                VALUES (?, ?, 1, ?, 'sku-1', '러닝화 A', 89000, 1, 89000)
                """, "item-1", "11111111-1111-4111-8111-000000000000",
                "11111111-1111-4111-8111-111111111111"));
    }

    @Test
    @DisplayName("V5 까지 적용된 DB 위에 V6~V9 가 올라가고 기존 체크아웃 행이 살아 있다")
    void migrate_databaseAtV5_addsLineSkusColumn() {
        // given
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, "5").migrate();
        JdbcTemplate jdbc = postgres.jdbcFor(dataSource);
        jdbc.update("INSERT INTO checkout_sessions (id, status) VALUES (?, ?)",
                "before-v6", "READY_FOR_COMPLETE");

        // when
        int applied = postgres.flywayFor(dataSource, schema, null).migrate().migrationsExecuted;

        // then
        assertEquals(7, applied);
        assertEquals("12", currentVersion(jdbc, schema));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM checkout_sessions WHERE id = 'before-v6'", Integer.class));
        assertNull(jdbc.queryForObject(
                "SELECT line_skus_json::text FROM checkout_sessions WHERE id = 'before-v6'", String.class),
                "V6 이전 행은 비어 있어야 한다");
    }

    @Test
    @DisplayName("line_skus_json 은 jsonb 이고 객체를 그대로 담는다")
    void lineSkusJson_jsonObject_roundTrips() {
        // given
        JdbcTemplate jdbc = migrated().jdbc();

        // when
        jdbc.update("""
                INSERT INTO checkout_sessions (id, merchant_id, status, line_skus_json)
                VALUES (?, 'merchant-a', 'READY_FOR_COMPLETE', ?::jsonb)
                """, "skus-1", "{\"line-1\":\"product-101-270\",\"line-2\":\"product-104-single\"}");

        // then
        assertEquals("product-101-270", jdbc.queryForObject("""
                SELECT line_skus_json ->> 'line-1' FROM checkout_sessions WHERE id = 'skus-1'
                """, String.class));
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                INSERT INTO checkout_sessions (id, merchant_id, status, line_skus_json)
                VALUES (?, 'merchant-a', 'READY_FOR_COMPLETE', ?::jsonb)
                """, "skus-2", "{깨진 JSON"));
    }

    @Test
    @DisplayName("V6 까지 적용된 DB 의 REQUESTED 결제 행이 V7 에서 PROCESSING 으로 옮겨진다")
    void migrate_databaseAtV6_movesRequestedPaymentsToProcessing() {
        // given
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, "6").migrate();
        JdbcTemplate jdbc = postgres.jdbcFor(dataSource);
        jdbc.update("INSERT INTO checkout_sessions (id, status, payment_status) VALUES (?, ?, ?)",
                "requested", "COMPLETE_IN_PROGRESS", "REQUESTED");
        jdbc.update("INSERT INTO checkout_sessions (id, status, payment_status) VALUES (?, ?, ?)",
                "success", "COMPLETED", "SUCCESS");
        jdbc.update("INSERT INTO checkout_sessions (id, status) VALUES (?, ?)", "none", "READY_FOR_COMPLETE");

        // when
        int applied = postgres.flywayFor(dataSource, schema, null).migrate().migrationsExecuted;

        // then
        assertEquals(6, applied);
        assertEquals("12", currentVersion(jdbc, schema));
        assertEquals("PROCESSING", paymentStatus(jdbc, "requested"), "옛 값은 뜻이 같은 PROCESSING 으로 옮긴다");
        assertEquals("SUCCESS", paymentStatus(jdbc, "success"), "다른 값은 건드리지 않는다");
        assertNull(paymentStatus(jdbc, "none"));
    }

    @Test
    @DisplayName("결제 상태 CHECK 가 공유 enum 다섯 값만 받고 REQUESTED 는 거절한다")
    void paymentStatusCheck_sharedEnumValues_acceptedAndRequestedRejected() {
        // given
        JdbcTemplate jdbc = migrated().jdbc();

        // when & then
        for (PaymentStatus status : PaymentStatus.values()) {
            jdbc.update("INSERT INTO checkout_sessions (id, merchant_id, status, payment_status) "
                    + "VALUES (?, 'merchant-a', ?, ?)", "ok-" + status, "COMPLETE_IN_PROGRESS", status.name());
        }
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "INSERT INTO checkout_sessions (id, merchant_id, status, payment_status) "
                        + "VALUES (?, 'merchant-a', ?, ?)",
                "bad-1", "COMPLETE_IN_PROGRESS", "REQUESTED"));
    }

    private static String paymentStatus(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT payment_status FROM checkout_sessions WHERE id = ?", String.class, id);
    }

    /**
     * 최신까지 적용된 새 스키마 하나.
     *
     * <p>스키마를 같이 들고 다니는 이유는 information_schema 와 pg_indexes 가 DB 전체를 보기 때문이다.
     * 스키마로 거르지 않으면 다른 테스트가 만든 스키마의 테이블까지 세어 단언이 흔들린다.
     */
    private record Migrated(JdbcTemplate jdbc, String schema) {
    }

    private Migrated migrated() {
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        Flyway flyway = postgres.flywayFor(dataSource, schema, null);
        flyway.migrate();
        return new Migrated(postgres.jdbcFor(dataSource), schema);
    }

    private static String currentVersion(JdbcTemplate jdbc, String schema) {
        return jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
    }

    private static int seededProductCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
    }

    private static boolean tableExists(JdbcTemplate jdbc, String schema, String table) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = ? AND table_name = ?
                """, Integer.class, schema, table) == 1;
    }

    private static boolean columnExists(Migrated migrated, String column) {
        return migrated.jdbc().queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = ? AND table_name = 'checkout_sessions' AND column_name = ?
                """, Integer.class, migrated.schema(), column) == 1;
    }
    @Test
    @DisplayName("V7 까지 적용된 DB 위에 V8·V9 가 올라가고 기존 결제 행이 살아 있다")
    void migrate_databaseAtV7_addsRecheckColumnsAndKeepsRows() {
        // given
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, "7").migrate();
        JdbcTemplate jdbc = postgres.jdbcFor(dataSource);
        jdbc.update("""
                INSERT INTO checkout_sessions (id, status, payment_status, payment_requested_at)
                VALUES (?, ?, ?, now())
                """, "unknown-1", "COMPLETE_IN_PROGRESS", "UNKNOWN");

        // when
        int applied = postgres.flywayFor(dataSource, schema, null).migrate().migrationsExecuted;

        // then
        Migrated migrated = new Migrated(jdbc, schema);
        assertEquals(5, applied);
        assertEquals("12", currentVersion(jdbc, schema));
        assertTrue(columnExists(migrated, "payment_credential_id"));
        assertTrue(columnExists(migrated, "payment_next_attempt_at"));
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT count(*) FROM checkout_sessions WHERE id = 'unknown-1'", Integer.class));
        assertNotNull(jdbc.queryForObject(
                "SELECT payment_requested_at FROM checkout_sessions WHERE id = 'unknown-1'",
                OffsetDateTime.class), "청구 시각은 V8 이 건드리지 않는다");
    }

    @Test
    @DisplayName("V8 이 재조회 인덱스를 payment_next_attempt_at 기준으로 바꾼다")
    void migrate_v8_repointsPaymentIndex() {
        // given & when
        Migrated migrated = migrated();

        // then
        String definition = migrated.jdbc().queryForObject("""
                SELECT indexdef FROM pg_indexes
                 WHERE schemaname = ? AND indexname = 'ix_checkout_payment'
                """, String.class, migrated.schema());
        assertTrue(definition.contains("payment_next_attempt_at"), definition);
        assertFalse(definition.contains("payment_requested_at"), definition);
    }

    @Test
    @DisplayName("V8 까지 적용된 DB 위에 V9 가 올라가면 기존 행은 merchant-a 가 되고 UNIQUE 는 몰 안에서만 건다")
    void migrate_databaseAtV8_addsMerchantScopeAndBackfills() {
        // given
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, "8").migrate();
        JdbcTemplate jdbc = postgres.jdbcFor(dataSource);
        jdbc.update("INSERT INTO checkout_sessions (id, status) VALUES (?, ?)", "before-v9", "READY_FOR_COMPLETE");

        // when
        int applied = postgres.flywayFor(dataSource, schema, "9").migrate().migrationsExecuted;

        // then
        assertEquals(1, applied);
        assertEquals("9", currentVersion(jdbc, schema));
        assertEquals("merchant-a", jdbc.queryForObject(
                "SELECT merchant_id FROM checkout_sessions WHERE id = 'before-v9'", String.class),
                "393 이전 행은 전부 merchant-a 다");
        assertEquals("merchant-a", jdbc.queryForObject(
                "SELECT merchant_id FROM products WHERE external_id = 'product-101'", String.class),
                "V3 fixture 도 merchant-a 다");

        // 몰이 다르면 같은 external_id 가 들어가고, 같은 몰이면 UNIQUE 에 걸린다(DESIGN.md 5.4절)
        String insert = """
                INSERT INTO products (id, merchant_id, external_id, title, base_price, currency, status, variants_json)
                VALUES (?, ?, 'product-101', '러닝화 A', 89000, 'KRW', 'ACTIVE', '[]'::jsonb)
                """;
        jdbc.update(insert, "99999999-0000-4000-8000-000000000001", "merchant-b");
        assertThrows(RuntimeException.class,
                () -> jdbc.update(insert, "99999999-0000-4000-8000-000000000002", "merchant-a"));
        // 몰을 빼먹은 INSERT 는 DB 가 막는다. DEFAULT 는 backfill 뒤 지웠다
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "INSERT INTO checkout_sessions (id, status) VALUES (?, ?)", "no-mall", "READY_FOR_COMPLETE"));
    }

    @Test
    @DisplayName("V9 까지 적용된 DB 위에 V10 이 올라가면 기존 주문의 배송비는 0 이고 음수는 막힌다")
    void migrate_databaseAtV9_addsShippingFeeColumn() {
        // given
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, "9").migrate();
        JdbcTemplate jdbc = postgres.jdbcFor(dataSource);
        jdbc.update("""
                INSERT INTO checkout_sessions (id, merchant_id, status) VALUES (?, ?, ?)
                """, "before-v10", "merchant-a", "COMPLETED");
        jdbc.update("""
                INSERT INTO orders (id, merchant_id, checkout_session_id, status, total_amount, currency,
                                    raw_order_json)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, "order-before-v10", "merchant-a", "before-v10", "CREATED", 92000L, "KRW", "{}");

        // when
        int applied = postgres.flywayFor(dataSource, schema, "10").migrate().migrationsExecuted;

        // then
        assertEquals(1, applied);
        assertEquals("10", currentVersion(jdbc, schema));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT shipping_fee_amount FROM orders WHERE id = 'order-before-v10'", Long.class),
                "V10 이전 주문은 배송비를 따로 적지 않았다. 소급해 계산하지 않는다");
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "UPDATE orders SET shipping_fee_amount = -1 WHERE id = 'order-before-v10'"),
                "받은 돈이 음수일 수 없다");
    }

    @Test
    @DisplayName("V10 까지 적용된 DB 위에 V11 이 올라가면 기존 상품의 분류는 비어 있다")
    void migrate_databaseAtV10_addsCategoryAndBrandColumns() {
        // given
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, "10").migrate();
        JdbcTemplate jdbc = postgres.jdbcFor(dataSource);

        // when
        int applied = postgres.flywayFor(dataSource, schema, null).migrate().migrationsExecuted;

        // then
        assertEquals(2, applied);
        assertEquals("12", currentVersion(jdbc, schema));
        assertEquals(5, seededProductCount(jdbc), "V3 fixture 가 살아 있어야 한다");
        // 분류는 시드가 채운다. 마이그레이션이 소급해 추측하지 않는다(470)
        assertEquals(5, (int) jdbc.queryForObject(
                "SELECT count(*) FROM products WHERE category IS NULL AND brand IS NULL", Integer.class));
        jdbc.update("UPDATE products SET category = ?, brand = ? WHERE external_id = 'product-101'",
                "스포츠/레저 > 스포츠화", "나이키");
        assertEquals("스포츠/레저 > 스포츠화", jdbc.queryForObject(
                "SELECT category FROM products WHERE external_id = 'product-101'", String.class));
    }
}
