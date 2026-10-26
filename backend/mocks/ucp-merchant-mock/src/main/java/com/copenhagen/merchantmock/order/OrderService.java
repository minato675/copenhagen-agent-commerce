package com.copenhagen.merchantmock.order;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.catalog.CatalogStore;
import com.copenhagen.merchantmock.catalog.Product;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Checkout;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CheckoutRequest.Destination;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.LineItem;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Total;
import com.copenhagen.merchantmock.checkout.CheckoutSession;
import com.copenhagen.merchantmock.order.OrderMessages.Created;
import com.copenhagen.merchantmock.order.OrderMessages.OrderView;
import com.copenhagen.merchantmock.support.ApiException;
import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.Audit;
import com.copenhagen.merchantmock.support.LogContext;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 주문 생성과 조회(Jira 180).
 *
 * <p>주문은 결제가 {@link PaymentStatus#SUCCESS} 일 때만 생긴다. {@code FAILED} 나 {@code UNKNOWN} 이면
 * 만들지 않는다(DESIGN.md 불변 조건 1번). 이 클래스를 부르는 것은 176 의 Complete 흐름이고, 그쪽이 붙기
 * 전까지는 테스트가 결제 결과를 가정해 직접 부른다.
 *
 * <p>주문 항목의 원본은 체크아웃 스냅샷이다. 상품 정보를 다시 계산하지 않고 그 시점 값을 복사한다.
 * 상품 가격이 나중에 바뀌어도 과거 주문은 흔들리지 않는다(DESIGN.md 5.4절).
 */
@Service
public class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);

    private static final TypeReference<Map<String, String>> SKU_MAP = new TypeReference<>() {
    };

    private final OrderRepository repository;
    private final CatalogStore catalog;
    private final ObjectMapper objectMapper;
    private final MerchantProperties properties;
    private final Clock clock;
    private final OrderEventListener events;

    public OrderService(OrderRepository repository, CatalogStore catalog, ObjectMapper objectMapper,
            MerchantProperties properties, Clock clock, OrderEventListener events) {
        this.repository = repository;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.events = events;
    }

    /**
     * 결제 성공 뒤 주문을 만든다.
     *
     * <p>같은 체크아웃으로 두 번 불러도 주문은 하나다. INSERT 가 {@code checkout_session_id} UNIQUE 에
     * 걸리면 기존 주문을 찾아 돌려준다(DESIGN.md 불변 조건 3번).
     *
     * <p>주문 INSERT 와 웹훅 대장 INSERT({@link OrderEventListener#orderCreated})는 한 트랜잭션이다
     * (DESIGN.md 8.6절). 주문은 있는데 웹훅 행이 없는 상태를 만들지 않는다. 리포지토리의 {@code @Transactional}
     * 은 여기에 합류한다. 기존 주문을 돌려주는 두 경로에서는 사건을 다시 내지 않는다.
     *
     * @throws IllegalStateException 결제가 성공이 아닌데 불렀을 때. 부르는 쪽의 버그다
     */
    @Transactional
    public Created create(CheckoutSession session, PaymentStatus paymentStatus) {
        LogContext.put(LogContext.CHECKOUT_ID, session.id());
        if (paymentStatus != PaymentStatus.SUCCESS) {
            throw new IllegalStateException(
                    "결제가 성공이 아닌데 주문을 만들려 했다: " + session.id() + " (" + paymentStatus + ")");
        }

        Optional<Order> existing = repository.findByCheckoutSessionId(session.id());
        if (existing.isPresent()) {
            LOG.info("주문 생성 재시도. 기존 주문 반환. checkoutId={} orderId={}", session.id(), existing.get().id());
            return created(session.merchantId(), existing.get().id());
        }

        Checkout snapshot = objectMapper.readValue(session.canonicalCheckout(), Checkout.class);
        String orderId = UUID.randomUUID().toString();
        List<OrderItem> items = toItems(orderId, session, snapshot);
        Order order = toOrder(orderId, session, snapshot);

        if (!repository.insert(order, items)) {
            Order winner = repository.findByCheckoutSessionId(session.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "주문 INSERT 가 막혔는데 기존 주문이 없다: " + session.id()));
            LOG.info("주문 생성 경합. 기존 주문 반환. checkoutId={} orderId={}", session.id(), winner.id());
            return created(session.merchantId(), winner.id());
        }
        LOG.info("주문 생성. orderId={} checkoutId={} items={}", orderId, session.id(), items.size());
        LogContext.put(LogContext.ORDER_ID, orderId);
        Audit.event("order.created").after("CREATED").reason("payment_" + paymentStatus.name().toLowerCase(Locale.ROOT))
                .with("items", items.size()).log();
        events.orderCreated(order);
        return created(session.merchantId(), orderId);
    }

    /** Core 가 웹훅 유실을 의심할 때 부르는 자리다. 여기가 진실이다(docs/API_SPEC.md). */
    public OrderView get(String merchantId, String orderId) {
        properties.requireMall(merchantId);
        return repository.findById(orderId)
                // 다른 몰의 주문은 없는 것으로 답한다(393). 존재를 드러내지 않는다.
                .filter(order -> merchantId.equals(order.merchantId()))
                .map(this::toView)
                .orElseThrow(() -> ApiException.orderNotFound(orderId));
    }

    private OrderView toView(Order order) {
        String status = order.status().apiValue();
        return new OrderView(
                order.id(),
                order.checkoutSessionId(),
                status,
                new OrderView.Fulfillment(status),
                formatTimestamp(order.updatedAt()));
    }

    /**
     * 응답 시각은 Asia/Seoul offset 에 초 단위다(DESIGN.md 17절).
     *
     * <p>DB 에서 읽은 {@code timestamptz} 는 UTC offset 에 마이크로초까지 달려 나온다. 그대로 내보내면
     * 같은 응답 안의 체크아웃 {@code expires_at} 과 모양이 달라진다. 2026-09-15 로컬 확인에서 잡혔다.
     */
    private String formatTimestamp(OffsetDateTime timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.atZoneSameInstant(clock.getZone())
                .toOffsetDateTime()
                .truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** permalink 는 그 몰의 주문 조회 경로다(393, DESIGN.md 3.3절). */
    private Created created(String merchantId, String orderId) {
        return new Created(orderId, properties.restEndpoint(merchantId) + "/orders/" + orderId);
    }

    private Order toOrder(String orderId, CheckoutSession session, Checkout snapshot) {
        Destination destination = session.fulfillmentAddressJson() == null
                ? null : objectMapper.readValue(session.fulfillmentAddressJson(), Destination.class);
        OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        String status = OrderStatus.CREATED.apiValue();
        OrderView view = new OrderView(orderId, session.id(), status,
                new OrderView.Fulfillment(status),
                now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return new Order(
                orderId,
                session.merchantId(),
                session.id(),
                OrderStatus.CREATED,
                totalOf(session, snapshot),
                shippingFeeOf(snapshot),
                session.currency(),
                objectMapper.writeValueAsString(view),
                destination == null ? null : destination.recipient(),
                null,
                destination == null ? null : destination.postalCode(),
                destination == null ? null : destination.addressLine1(),
                destination == null ? null : destination.addressLine2(),
                0L,
                now);
    }

    /** 스냅샷의 합계가 진실이다. {@code total_amount} 컬럼은 캐시다(DESIGN.md 5.4절). */
    private static long totalOf(CheckoutSession session, Checkout snapshot) {
        long cached = session.totalAmount() == null ? 0L : session.totalAmount();
        if (snapshot.totals() == null) {
            return cached;
        }
        return snapshot.totals().stream()
                .filter(total -> "total".equals(total.type()))
                .mapToLong(Total::amount)
                .findFirst()
                .orElse(cached);
    }

    /**
     * 받은 배송비. 스냅샷의 {@code fulfillment} 줄이 원본이고 없으면 0 이다(DESIGN.md 5.4절, ADR 0009).
     *
     * <p>도서산간 추가금은 그 줄에 이미 합산돼 있다. 추가금만 따로 실린 줄은 없다(7.1절).
     */
    private static long shippingFeeOf(Checkout snapshot) {
        if (snapshot.totals() == null) {
            return 0L;
        }
        return snapshot.totals().stream()
                .filter(total -> "fulfillment".equals(total.type()))
                .mapToLong(Total::amount)
                .findFirst()
                .orElse(0L);
    }

    private List<OrderItem> toItems(String orderId, CheckoutSession session, Checkout snapshot) {
        Map<String, String> skus = session.lineSkusJson() == null
                ? Map.of() : objectMapper.readValue(session.lineSkusJson(), SKU_MAP);
        List<LineItem> lines = snapshot.lineItems() == null ? List.of() : snapshot.lineItems();

        List<OrderItem> items = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            LineItem line = lines.get(i);
            Product product = catalog.byExternalId(session.merchantId(), line.item().id())
                    .orElseThrow(() -> new IllegalStateException(
                            "주문할 상품이 카탈로그에 없다: " + line.item().id()));
            items.add(OrderItem.of(
                    UUID.randomUUID().toString(),
                    orderId,
                    i + 1,
                    product.id(),
                    resolveSku(skus, line, product, session.id()),
                    line.item().title(),
                    line.item().price(),
                    line.quantity()));
        }
        return List.copyOf(items);
    }

    /**
     * 선택된 SKU 를 찾는다. {@code line_skus_json} 이 원본이다(DESIGN.md 5.4절).
     *
     * <p>옵션이 하나뿐인 상품은 Core 가 {@code option_value_ids} 를 생략할 수 있어 그 경우만 상품에서
     * 되찾는다. 그래도 없으면 주문을 만들지 않는다. 결제 뒤에 무엇을 팔았는지 모르는 주문을 남기는 것보다
     * 터뜨리는 편이 낫다. {@code ready_for_complete} 인 체크아웃에서는 일어날 수 없는 경로다.
     */
    private static String resolveSku(Map<String, String> skus, LineItem line, Product product,
            String checkoutId) {
        String stored = skus.get(line.id());
        if (stored != null) {
            return stored;
        }
        if (product.variants().size() == 1) {
            return product.variants().get(0).sku();
        }
        throw new IllegalStateException(
                "선택된 옵션을 찾을 수 없다: " + checkoutId + " " + line.id() + " (" + line.item().id() + ")");
    }
}
