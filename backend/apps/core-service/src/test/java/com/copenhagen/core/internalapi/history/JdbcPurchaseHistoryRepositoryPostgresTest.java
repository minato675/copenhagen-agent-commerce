package com.copenhagen.core.internalapi.history;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.copenhagen.core.internalapi.history.PurchaseHistoryRepository.PurchaseHistoryRow;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 설계서 §4.3 — 실 PostgreSQL로 집계 규칙을 확인한다. 결제 완료 여부·주문 상태·기간·사용자 격리와
 * 페이지 경계까지 쿼리가 직접 책임진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class JdbcPurchaseHistoryRepositoryPostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant PERIOD_START = NOW.minus(180, ChronoUnit.DAYS);

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private PurchaseHistoryRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;
    private long otherUserId;
    private long cartId;

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("""
                TRUNCATE users, carts, cart_items, addresses, purchase_requests, merchant_checkouts,
                    checkout_snapshots, checkout_snapshot_items, checkout_fulfillment_option_snapshots,
                    orders, payment_transactions
                RESTART IDENTITY CASCADE
                """);
        jdbcTemplate.update("""
                INSERT INTO merchants (id, merchant_name, ucp_profile_url, base_url, business_profile,
                                       active_capabilities, status)
                VALUES (1, 'A몰', 'http://merchant-a:8080/.well-known/ucp', 'http://merchant-a:8080',
                        '{}'::jsonb, '[]'::jsonb, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """);
        userId = insertUser("구매자");
        otherUserId = insertUser("남의 계정");
        cartId = insertCart(userId);
    }

    @Test
    @DisplayName("같은 상품을 두 주문에서 사면 주문 수와 수량을 합치고 최근 줄의 옵션·단가를 남긴다")
    void findPage_aggregatesRepeatedPurchases() {
        long older = insertPaidOrder(userId, NOW.minus(40, ChronoUnit.DAYS), "CONFIRMED");
        insertItem(older, "P-1", "P-1-BLK", "맨투맨", "[{\"label\":\"블랙\"}]", 1, 30000L);
        long newer = insertPaidOrder(userId, NOW.minus(2, ChronoUnit.DAYS), "DELIVERED");
        insertItem(newer, "P-1", "P-1-WHT", "맨투맨", "[{\"label\":\"화이트\"}]", 2, 39000L);

        List<PurchaseHistoryRow> rows = repository.findPage(userId, PERIOD_START, null, 21);

        assertEquals(1, rows.size());
        PurchaseHistoryRow row = rows.getFirst();
        assertEquals(2, row.purchaseCount());
        assertEquals(3, row.totalQuantity());
        assertEquals("P-1-WHT", row.merchantVariantId());
        assertEquals(39000L, row.unitPriceAmount());
        // merchants 는 V7 시드라 다른 통합 테스트가 남긴 이름일 수 있다 — 조인 결과가 그 행인지만 본다.
        assertEquals(
                jdbcTemplate.queryForObject("SELECT merchant_name FROM merchants WHERE id = 1", String.class),
                row.merchantName());
    }

    @Test
    @DisplayName("한 주문 안에서 같은 상품을 여러 줄로 사면 주문 수는 1이고 수량만 합친다")
    void findPage_countsOneOrderOnce() {
        long orderId = insertPaidOrder(userId, NOW.minus(1, ChronoUnit.DAYS), "CONFIRMED");
        insertItem(orderId, "P-1", "P-1-BLK", "맨투맨", "[]", 1, 30000L);
        insertItem(orderId, "P-1", "P-1-WHT", "맨투맨", "[]", 2, 30000L);

        PurchaseHistoryRow row = repository.findPage(userId, PERIOD_START, null, 21).getFirst();

        assertEquals(1, row.purchaseCount());
        assertEquals(3, row.totalQuantity());
    }

    @Test
    @DisplayName("결제 행이 둘이어도 수량이 부풀지 않는다")
    void findPage_doesNotDuplicateOnMultiplePaidTransactions() {
        long orderId = insertPaidOrder(userId, NOW.minus(1, ChronoUnit.DAYS), "CONFIRMED");
        insertItem(orderId, "P-1", "P-1-BLK", "맨투맨", "[]", 2, 30000L);
        insertPaidTransaction(orderId);

        PurchaseHistoryRow row = repository.findPage(userId, PERIOD_START, null, 21).getFirst();

        assertEquals(1, row.purchaseCount());
        assertEquals(2, row.totalQuantity());
    }

    @Test
    @DisplayName("미결제·취소·실패·기간 밖·다른 사용자 주문은 빠진다")
    void findPage_excludesOutOfScopeOrders() {
        long unpaid = insertOrder(userId, NOW.minus(1, ChronoUnit.DAYS), "CONFIRMED");
        insertItem(unpaid, "P-UNPAID", "V", "미결제", "[]", 1, 1000L);
        long cancelled = insertPaidOrder(userId, NOW.minus(1, ChronoUnit.DAYS), "CANCELLED");
        insertItem(cancelled, "P-CANCELLED", "V", "취소", "[]", 1, 1000L);
        long failed = insertPaidOrder(userId, NOW.minus(1, ChronoUnit.DAYS), "FAILED");
        insertItem(failed, "P-FAILED", "V", "실패", "[]", 1, 1000L);
        long expired = insertPaidOrder(userId, NOW.minus(200, ChronoUnit.DAYS), "DELIVERED");
        insertItem(expired, "P-OLD", "V", "오래된", "[]", 1, 1000L);
        long other = insertPaidOrder(otherUserId, NOW.minus(1, ChronoUnit.DAYS), "DELIVERED");
        insertItem(other, "P-OTHER", "V", "남의 것", "[]", 1, 1000L);

        assertEquals(List.of(), repository.findPage(userId, PERIOD_START, null, 21));
    }

    @Test
    @DisplayName("커서 페이지가 중복·누락 없이 이어진다")
    void findPage_pagesWithoutGapOrDuplicate() {
        for (int index = 0; index < 5; index++) {
            long orderId = insertPaidOrder(userId, NOW.minus(index + 1, ChronoUnit.DAYS), "DELIVERED");
            insertItem(orderId, "P-" + index, "P-" + index + "-V", "상품" + index, "[]", 1, 1000L);
        }

        List<PurchaseHistoryRow> first = repository.findPage(userId, PERIOD_START, null, 3);
        PurchaseHistoryRow last = first.get(1);
        List<PurchaseHistoryRow> second = repository.findPage(userId, PERIOD_START,
                PurchaseHistoryCursor.of(last.lastPurchasedAt(), last.merchantRefId(), last.merchantProductId()), 21);

        assertEquals(List.of("P-0", "P-1", "P-2"), first.stream().map(PurchaseHistoryRow::merchantProductId).toList());
        assertEquals(List.of("P-2", "P-3", "P-4"), second.stream().map(PurchaseHistoryRow::merchantProductId).toList());
    }

    /** 이메일은 PII 컬럼(NOT NULL)이라 값만 채운다 — 이 테스트는 집계 쿼리만 검증한다. */
    private long insertUser(String name) {
        String emailHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (public_id, name, email_encrypted, email_hash)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, UUID.randomUUID(), name,
                name.getBytes(java.nio.charset.StandardCharsets.UTF_8), emailHash);
    }

    private long insertCart(long userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO carts (public_id, user_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
                Long.class, UUID.randomUUID(), userId);
    }

    private long insertPaidOrder(long userId, Instant createdAt, String status) {
        long orderId = insertOrder(userId, createdAt, status);
        insertPaidTransaction(orderId);
        return orderId;
    }

    private long insertOrder(long userId, Instant createdAt, String status) {
        long purchaseRequestId = jdbcTemplate.queryForObject("""
                INSERT INTO purchase_requests (public_id, user_id, cart_id, request_channel, status, policy_version)
                VALUES (?, ?, ?, 'MCP', 'COMPLETED', 'v1') RETURNING id
                """, Long.class, UUID.randomUUID(), userId, cartId);
        long merchantCheckoutId = jdbcTemplate.queryForObject("""
                INSERT INTO merchant_checkouts (purchase_request_id, merchant_id, merchant_checkout_id,
                                                checkout_digest, status)
                VALUES (?, 1, ?, repeat('0', 64), 'COMPLETED') RETURNING id
                """, Long.class, purchaseRequestId, UUID.randomUUID().toString());
        long snapshotId = jdbcTemplate.queryForObject("""
                INSERT INTO checkout_snapshots (public_id, merchant_checkout_id, checkout_digest,
                                                merchant_checkout_payload, currency)
                VALUES (?, ?, repeat('0', 64), '{}'::jsonb, 'KRW') RETURNING id
                """, Long.class, UUID.randomUUID(), merchantCheckoutId);
        return jdbcTemplate.queryForObject("""
                INSERT INTO orders (public_id, checkout_snapshot_id, user_id, merchant_order_id, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, UUID.randomUUID(), snapshotId, userId,
                UUID.randomUUID().toString(), status, java.sql.Timestamp.from(createdAt));
    }

    private void insertPaidTransaction(long orderId) {
        jdbcTemplate.update("""
                INSERT INTO payment_transactions (checkout_snapshot_id, order_id, authorization_session_id,
                                                  pay_transaction_id, status, payment_amount, currency)
                SELECT o.checkout_snapshot_id, o.id, ?, ?, 'PAID', 1000, 'KRW'
                FROM orders o WHERE o.id = ?
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), orderId);
    }

    private void insertItem(long orderId, String productId, String variantId, String name,
            String selectedOptions, int quantity, long unitPrice) {
        jdbcTemplate.update("""
                INSERT INTO checkout_snapshot_items (checkout_snapshot_id, merchant_product_id, merchant_variant_id,
                                                     product_name, product_url, selected_options, quantity,
                                                     unit_price_amount, line_total_amount, currency)
                SELECT o.checkout_snapshot_id, ?, ?, ?, 'http://merchant-a:8080/p', ?::jsonb, ?, ?, ?, 'KRW'
                FROM orders o WHERE o.id = ?
                """, productId, variantId, name, selectedOptions, quantity, unitPrice,
                unitPrice * quantity, orderId);
    }
}
