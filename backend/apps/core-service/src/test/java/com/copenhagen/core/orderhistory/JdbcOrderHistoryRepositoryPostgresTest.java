package com.copenhagen.core.orderhistory;

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

import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderItemRow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * v2가 상품 식별자와 이미지를 내주려면 항목 쿼리가 {@code merchant_checkouts}까지 거슬러 올라가야 한다.
 * 조인이 늘면 줄이 복제되거나 순서가 흔들릴 수 있어 실 PostgreSQL로 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class JdbcOrderHistoryRepositoryPostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private OrderHistoryRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;
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
        cartId = insertCart(userId);
    }

    @Test
    @DisplayName("항목 조회가 merchant id·상품 식별자·이미지를 함께 내주고 줄을 복제하지 않는다")
    void findItemsBySnapshotIds_carriesMerchantIdentifiersAndImage() {
        long orderId = insertOrder(userId, NOW.minus(1, ChronoUnit.DAYS));
        long snapshotId = snapshotIdOf(orderId);
        insertItem(orderId, "P-1", "P-1-BLK", "맨투맨", "https://cdn.example.test/p-1.jpg");
        insertItem(orderId, "P-2", "P-2-WHT", "바람막이", null);

        List<OrderItemRow> rows = repository.findItemsBySnapshotIds(List.of(snapshotId));

        assertEquals(2, rows.size());
        assertEquals(List.of("P-1", "P-2"), rows.stream().map(OrderItemRow::merchantProductId).toList());
        OrderItemRow first = rows.getFirst();
        assertEquals(1L, first.merchantRefId());
        assertEquals("P-1-BLK", first.merchantVariantId());
        assertEquals("https://cdn.example.test/p-1.jpg", first.imageUrl());
        // 이미지가 없는 구형 제공자·V22 이전 주문은 null 그대로 둔다.
        assertNull(rows.get(1).imageUrl());
    }

    @Test
    @DisplayName("PAID 행이 둘이어도 항목 줄이 늘어나지 않는다")
    void findItemsBySnapshotIds_doesNotDuplicateOnMultiplePaidTransactions() {
        long orderId = insertOrder(userId, NOW.minus(1, ChronoUnit.DAYS));
        insertPaidTransaction(orderId);
        insertPaidTransaction(orderId);
        insertItem(orderId, "P-1", "P-1-BLK", "맨투맨", "https://cdn.example.test/p-1.jpg");

        assertEquals(1, repository.findItemsBySnapshotIds(List.of(snapshotIdOf(orderId))).size());
    }

    private long snapshotIdOf(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT checkout_snapshot_id FROM orders WHERE id = ?", Long.class, orderId);
    }

    /** 이메일은 PII 컬럼(NOT NULL)이라 값만 채운다 — 이 테스트는 항목 쿼리만 검증한다. */
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

    private long insertOrder(long userId, Instant createdAt) {
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
                VALUES (?, ?, ?, ?, 'CONFIRMED', ?) RETURNING id
                """, Long.class, UUID.randomUUID(), snapshotId, userId,
                UUID.randomUUID().toString(), java.sql.Timestamp.from(createdAt));
    }

    private void insertPaidTransaction(long orderId) {
        jdbcTemplate.update("""
                INSERT INTO payment_transactions (checkout_snapshot_id, order_id, authorization_session_id,
                                                  pay_transaction_id, status, payment_amount, currency)
                SELECT o.checkout_snapshot_id, o.id, ?, ?, 'PAID', 1000, 'KRW'
                FROM orders o WHERE o.id = ?
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), orderId);
    }

    private void insertItem(long orderId, String productId, String variantId, String name, String imageUrl) {
        jdbcTemplate.update("""
                INSERT INTO checkout_snapshot_items (checkout_snapshot_id, merchant_product_id, merchant_variant_id,
                                                     product_name, product_url, image_url, selected_options,
                                                     quantity, unit_price_amount, line_total_amount, currency)
                SELECT o.checkout_snapshot_id, ?, ?, ?, 'http://merchant-a:8080/p', ?, '[]'::jsonb,
                       1, 1000, 1000, 'KRW'
                FROM orders o WHERE o.id = ?
                """, productId, variantId, name, imageUrl, orderId);
    }
}
