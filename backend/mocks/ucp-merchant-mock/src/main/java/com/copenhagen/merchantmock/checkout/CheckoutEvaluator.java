package com.copenhagen.merchantmock.checkout;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.copenhagen.merchantmock.catalog.CatalogStore;
import com.copenhagen.merchantmock.catalog.Product;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Checkout;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CheckoutRequest;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.FulfillmentResult;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Item;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.LineItem;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Message;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Total;
import com.copenhagen.merchantmock.scenario.Scenario;
import com.copenhagen.merchantmock.scenario.ScenarioState;
import com.copenhagen.merchantmock.support.ApiException;
import com.copenhagen.merchantmock.support.MerchantProperties;
import org.springframework.stereotype.Component;

/**
 * 요청을 받아 계산서를 매긴다. 가격·재고·옵션·배송·총액을 확정하고 상태를 판정한다(DESIGN.md 7.1절).
 *
 * <p>여기서 정한 값이 거래 조건의 원본이다. Core 는 Agent 가 준 가격을 믿지 않고 이 값을 쓴다.
 *
 * <p>상품이 없으면 계산서 자체를 못 만드니 404 다. 나머지 부족은 계산서를 만들고 messages 로 알린다.
 * 서명은 여기서 하지 않는다. {@link CheckoutService} 가 ready_for_complete 판정 직후 도장을 찍는다(175).
 */
@Component
public class CheckoutEvaluator {

    /** 시나리오 PRICE_CHANGED 가 재계산 때 단가에 더하는 금액. 가격 변경 경로를 만드는 용도라 값은 임의다. */
    static final long PRICE_CHANGED_DELTA = 1000L;

    private static final String SHIPPING = "shipping";

    private final CatalogStore catalog;
    private final ScenarioState scenario;
    private final MerchantProperties properties;
    private final Clock clock;

    public CheckoutEvaluator(CatalogStore catalog, ScenarioState scenario,
            MerchantProperties properties, Clock clock) {
        this.catalog = catalog;
        this.scenario = scenario;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 판정 결과. 응답 스냅샷과, 컬럼에 캐시할 값들.
     *
     * @param lineSkus line item id 에서 선택된 SKU 로. {@code canonical_checkout} 에 SKU 자리가 없어
     *                 {@code line_skus_json} 으로 따로 남긴다(DESIGN.md 5.4절). 주문 상세가 이걸 읽는다.
     */
    record Evaluation(Checkout checkout, CheckoutStatus status, OffsetDateTime expiresAt,
            Map<String, String> lineSkus) {
    }

    /**
     * @param previous 수정(PUT)이면 직전 스냅샷. 단가가 달라졌는지 비교해 CHECKOUT_PRICE_CHANGED 를 얹는다.
     */
    Evaluation evaluate(String merchantId, String checkoutId, CheckoutRequest request, Optional<Checkout> previous) {
        List<Message> messages = new ArrayList<>();
        Map<String, String> lineSkus = new LinkedHashMap<>();
        List<LineItem> lines = resolveLines(merchantId, request, messages, lineSkus);
        long subtotal = lines.stream().mapToLong(l -> l.item().price() * l.quantity()).sum();

        FulfillmentResult fulfillment = resolveFulfillment(request, messages);
        boolean feeKnown = fulfillment != null && fulfillment.amount() != null;
        long fee = feeKnown ? fulfillment.amount() : 0L;

        List<Total> totals = new ArrayList<>();
        totals.add(new Total("subtotal", subtotal));
        if (feeKnown) {
            totals.add(new Total("fulfillment", fee));
        }
        totals.add(new Total("total", subtotal + fee));

        previous.ifPresent(p -> detectPriceChange(p, lines, messages));
        requirePurchaseRequestId(request, messages);

        CheckoutStatus status = decide(messages);
        OffsetDateTime expiresAt = status == CheckoutStatus.READY_FOR_COMPLETE
                ? OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS).plus(properties.checkout().ttl())
                : null;

        Checkout checkout = new Checkout(
                checkoutId,
                status.apiValue(),
                properties.currency(),
                lines,
                fulfillment,
                totals,
                messages.isEmpty() ? null : List.copyOf(messages),
                expiresAt == null ? null : expiresAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        // Map.copyOf 는 순서를 지키지 않는다. 서명 대상은 아니지만 같은 입력에 같은 바이트가 나오는 편이
        // 읽기도 비교하기도 쉽다. 줄 번호 순서를 그대로 둔다.
        return new Evaluation(checkout, status, expiresAt,
                Collections.unmodifiableMap(new LinkedHashMap<>(lineSkus)));
    }

    private List<LineItem> resolveLines(String merchantId, CheckoutRequest request, List<Message> messages,
            Map<String, String> lineSkus) {
        List<CheckoutRequest.LineItemRequest> requested =
                request == null || request.lineItems() == null ? List.of() : request.lineItems();
        if (requested.isEmpty()) {
            messages.add(Message.error("CHECKOUT_INCOMPLETE", "주문 항목이 없다", "line_items"));
            return List.of();
        }

        List<LineItem> lines = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            CheckoutRequest.LineItemRequest line = requested.get(i);
            String target = "line_items[" + i + "]";

            Product product = catalog.byExternalId(merchantId, line.productId())
                    .filter(p -> p.status() == Product.ProductStatus.ACTIVE)
                    .orElseThrow(() -> ApiException.productNotFound(line.productId()));

            int quantity = line.quantity() == null ? 0 : line.quantity();
            if (quantity <= 0) {
                messages.add(Message.error("CHECKOUT_INCOMPLETE", "수량은 1 이상이어야 한다", target + ".quantity"));
            }

            Optional<Product.Variant> variant = pickVariant(product, line, target, messages);
            long unitPrice = product.basePrice() + variant.map(Product.Variant::priceDelta).orElse(0L);
            if (scenario.is(Scenario.PRICE_CHANGED)) {
                unitPrice += PRICE_CHANGED_DELTA;
            }

            boolean outOfStock = scenario.is(Scenario.OUT_OF_STOCK)
                    || variant.map(v -> !v.purchasable() || v.stock() < quantity).orElse(false);
            if (outOfStock) {
                messages.add(Message.error("CHECKOUT_STOCK_INSUFFICIENT", "재고가 부족하다", target));
            }

            String lineId = "line-" + (i + 1);
            variant.ifPresent(v -> lineSkus.put(lineId, v.sku()));
            lines.add(new LineItem(lineId,
                    new Item(product.externalId(), product.title(), unitPrice),
                    Math.max(quantity, 0)));
        }
        return List.copyOf(lines);
    }

    /**
     * option_value_ids 는 SKU 다(카탈로그 상세조회의 optionValueId). 상품에 옵션이 하나뿐이면 생략을 허용한다.
     */
    private Optional<Product.Variant> pickVariant(Product product, CheckoutRequest.LineItemRequest line,
            String target, List<Message> messages) {
        List<String> ids = line.optionValueIds() == null ? List.of() : line.optionValueIds();
        if (ids.isEmpty()) {
            if (product.variants().size() == 1) {
                return Optional.of(product.variants().get(0));
            }
            messages.add(Message.error("CHECKOUT_INCOMPLETE", "옵션을 선택해야 한다", target + ".option_value_ids"));
            return Optional.empty();
        }
        Optional<Product.Variant> variant = product.variants().stream()
                .filter(v -> v.sku().equals(ids.get(0)))
                .findFirst();
        if (variant.isEmpty()) {
            messages.add(Message.error("CHECKOUT_INCOMPLETE", "상품에 없는 옵션이다: " + ids.get(0),
                    target + ".option_value_ids"));
        }
        return variant;
    }

    private FulfillmentResult resolveFulfillment(CheckoutRequest request, List<Message> messages) {
        CheckoutRequest.Destination destination = request == null || request.fulfillment() == null
                ? null : request.fulfillment().destination();
        if (destination == null || isBlank(destination.recipient()) || isBlank(destination.addressLine1())) {
            messages.add(Message.error("ADDRESS_REQUIRED", "배송지가 필요하다", "fulfillment.destination"));
            return null;
        }
        if (scenario.is(Scenario.ADDRESS_NOT_SERVICEABLE)) {
            messages.add(Message.error("ADDRESS_NOT_SERVICEABLE", "배송할 수 없는 지역이다", "fulfillment.destination"));
            return new FulfillmentResult(SHIPPING, "unavailable", null);
        }
        return new FulfillmentResult(SHIPPING, "available", shippingFee(destination, messages));
    }

    /**
     * 기본 배송비에 도서산간 추가금을 더한다(DESIGN.md 7.1절, ADR 0009).
     *
     * <p>추가금을 {@code totals} 의 별도 줄로 내지 않는다. Core 는 종류 이름으로 금액을 꺼내 가므로
     * 모르는 종류를 실으면 배송비가 적게 적히고 소계 + 배송비 ≠ 합계가 된다. 얼마가 왜 붙었는지는
     * {@code messages} 가 말한다. 막지 않으므로 {@code warning} 이다.
     */
    private long shippingFee(CheckoutRequest.Destination destination, List<Message> messages) {
        MerchantProperties.Fulfillment fulfillment = properties.fulfillment();
        return fulfillment.remoteAreaOf(destination.postalCode(),
                        destination.addressLine1(), destination.addressLine2())
                .map(area -> {
                    messages.add(Message.warning("CHECKOUT_REMOTE_AREA_SURCHARGE",
                            area.name() + " 이라 배송비 " + area.surcharge() + " 이 붙었다",
                            "fulfillment.destination"));
                    return Math.addExact(fulfillment.flatFee(), area.surcharge());
                })
                .orElseGet(fulfillment::flatFee);
    }

    /**
     * Merchant Authorization 의 필수 claim 이다(DESIGN.md 6.4절). 없으면 서명할 수 없고 서명이 없으면 Complete 가
     * {@code checkoutHash} 를 만들 수 없다. 완료로 갈 수 없는 체크아웃을 {@code ready_for_complete} 라 부르지 않는다.
     */
    private static void requirePurchaseRequestId(CheckoutRequest request, List<Message> messages) {
        if (request == null || isBlank(request.purchaseRequestId())) {
            messages.add(Message.error("CHECKOUT_INCOMPLETE", "purchase_request_id 가 필요하다",
                    "purchase_request_id"));
        }
    }

    private static void detectPriceChange(Checkout previous, List<LineItem> lines, List<Message> messages) {
        if (previous.lineItems() == null) {
            return;
        }
        Map<String, Long> before = new HashMap<>();
        previous.lineItems().forEach(l -> before.put(l.item().id(), l.item().price()));
        for (int i = 0; i < lines.size(); i++) {
            LineItem line = lines.get(i);
            Long old = before.get(line.item().id());
            if (old != null && old != line.item().price()) {
                messages.add(Message.warning("CHECKOUT_PRICE_CHANGED",
                        "단가가 " + old + "에서 " + line.item().price() + "로 바뀌었다",
                        "line_items[" + i + "].item.price"));
            }
        }
    }

    private CheckoutStatus decide(List<Message> messages) {
        if (scenario.is(Scenario.REQUIRES_ESCALATION)) {
            messages.add(Message.error("MERCHANT_HANDOFF_REQUIRED", "API 로 처리할 수 없다. 머천트에 직접 문의", null));
            return CheckoutStatus.REQUIRES_ESCALATION;
        }
        return messages.stream().anyMatch(Message::blocking)
                ? CheckoutStatus.INCOMPLETE
                : CheckoutStatus.READY_FOR_COMPLETE;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
