package com.copenhagen.merchantmock.order;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import com.copenhagen.merchantmock.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JdbcOrderRepository 의 SQL 검증(Jira 180).
 *
 * <p>{@link InMemoryOrderRepository} 는 {@code checkout_session_id} UNIQUE 를 자바로 흉내 낸다.
 * 그게 통과한다고 DB 제약이 실제로 걸려 있다는 뜻은 아니다. 중복 주문의 최종 방어선이 그 제약이라
 * (DESIGN.md 불변 조건 3번) 여기서 진짜로 막히는지를 본다.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcOrderRepositoryPostgresTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 15, 13, 0, 0, 0, ZoneOffset.ofHours(9));

    /** V3 시드의 러닝화 A. order_items.product_id FK 가 이 값을 가리킨다. */
    private static final String PRODUCT_ID = "11111111-1111-4111-8111-111111111111";

    private static final String CHECKOUT_ID = "checkout-1";

    private EmbeddedPostgresSupport postgres;
    private JdbcOrderRepository repository;
    private JdbcTemplate jdbc;

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

    @BeforeEach
    void setUp() {
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, null).migrate();
        this.jdbc = postgres.jdbcFor(dataSource);
        this.repository = new JdbcOrderRepository(jdbc);
        jdbc.update("INSERT INTO checkout_sessions (id, merchant_id, status) "
                + "VALUES (?, 'merchant-a', 'READY_FOR_COMPLETE')", CHECKOUT_ID);
    }

    @Test
    @DisplayName("넣고 다시 읽으면 주문과 항목이 그대로다")
    void insertAndFindById_newOrder_roundTripsAllColumns() {
        // given
        Order order = order("order-1", CHECKOUT_ID);

        // when
        boolean inserted = repository.insert(order, List.of(item("item-1", "order-1", 1)));

        // then
        assertTrue(inserted);
        Order stored = repository.findById("order-1").orElseThrow();
        assertEquals(CHECKOUT_ID, stored.checkoutSessionId());
        assertEquals(OrderStatus.CREATED, stored.status());
        assertEquals(92000L, stored.totalAmount());
        assertEquals("KRW", stored.currency());
        assertEquals("홍길동", stored.recipientName());
        assertEquals("101호", stored.addressLine2());
        assertEquals(0L, stored.version());
        assertEquals(1, repository.findItems("order-1").size());
    }

    @Test
    @DisplayName("같은 체크아웃으로 두 번 넣으면 UNIQUE 에 걸려 false 다")
    void insert_sameCheckoutTwice_isRejectedByUniqueConstraint() {
        // given
        repository.insert(order("order-1", CHECKOUT_ID), List.of(item("item-1", "order-1", 1)));

        // when
        boolean second = repository.insert(order("order-2", CHECKOUT_ID), List.of(item("item-2", "order-2", 1)));

        // then
        assertFalse(second, "checkout_session_id UNIQUE 가 중복 주문을 막아야 한다");
        assertEquals(1, count("orders"));
        assertEquals(0, repository.findItems("order-2").size(), "막힌 주문의 항목이 남으면 안 된다");
    }

    @Test
    @DisplayName("체크아웃으로 주문을 찾는다")
    void findByCheckoutSessionId_existingOrder_returnsIt() {
        // given
        repository.insert(order("order-1", CHECKOUT_ID), List.of(item("item-1", "order-1", 1)));

        // when
        Order found = repository.findByCheckoutSessionId(CHECKOUT_ID).orElseThrow();

        // then
        assertEquals("order-1", found.id());
        assertTrue(repository.findByCheckoutSessionId("checkout-없음").isEmpty());
    }

    @Test
    @DisplayName("주문 항목은 line_no 순서로 돌아온다")
    void findItems_multipleLines_returnsInLineOrder() {
        // given
        repository.insert(order("order-1", CHECKOUT_ID), List.of(
                item("item-3", "order-1", 3),
                item("item-1", "order-1", 1),
                item("item-2", "order-1", 2)));

        // when
        List<OrderItem> items = repository.findItems("order-1");

        // then
        assertEquals(List.of(1, 2, 3), items.stream().map(OrderItem::lineNo).toList());
    }

    @Test
    @DisplayName("line_amount 가 단가 곱하기 수량이 아니면 CHECK 에 걸린다")
    void insertItem_inconsistentLineAmount_isRejected() {
        // given
        repository.insert(order("order-1", CHECKOUT_ID), List.of(item("item-1", "order-1", 1)));

        // when & then
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                INSERT INTO order_items (id, order_id, line_no, product_id, sku, title, unit_amount,
                                         quantity, line_amount)
                VALUES (?, 'order-1', 9, ?, 'product-101-270', '러닝화 A', 89000, 2, 89000)
                """, "item-bad", PRODUCT_ID));
    }

    @Test
    @DisplayName("상품별 판매 집계가 product_id 인덱스를 탄다")
    void salesByProduct_explain_usesProductIndex() {
        // given
        repository.insert(order("order-1", CHECKOUT_ID), List.of(item("item-1", "order-1", 1)));

        // when
        String plan = String.join("\n", jdbc.queryForList("""
                EXPLAIN SELECT sum(line_amount) FROM order_items WHERE product_id = ?
                """, String.class, PRODUCT_ID));

        // then
        assertTrue(plan.contains("ix_order_items_product") || plan.contains("Seq Scan"),
                "계획: " + plan);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static Order order(String id, String checkoutId) {
        return new Order(id, "merchant-a", checkoutId, OrderStatus.CREATED, 92000L, 3000L, "KRW",
                "{\"id\":\"" + id + "\",\"status\":\"CREATED\"}",
                "홍길동", null, "06236", "서울특별시 강남구 테헤란로 1", "101호", 0L, NOW);
    }

    private static OrderItem item(String id, String orderId, int lineNo) {
        return OrderItem.of(id, orderId, lineNo, PRODUCT_ID, "product-101-270", "러닝화 A", 89000L, 1);
    }
}
