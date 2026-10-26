package com.copenhagen.core.internalapi;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * mcp-server {@code CommerceProjection}(이슈 400)의 검증 규칙을 <b>그대로 옮겨 적은</b> 헬퍼다.
 *
 * <p>모듈 경계를 지키려고 mcp-server 클래스를 직접 참조하지 않는다. 대신 규칙을 복사해 두고,
 * Core 응답이 그 규칙을 통과하는지 여기서 확인한다. MCP 쪽 규칙이 바뀌면 이 파일도 같이 고쳐야 한다.
 *
 * <p>출처: {@code backend/apps/mcp-server/src/main/java/com/copenhagen/mcp/integration/CommerceProjection.java}
 */
public final class McpContractAssertions {

    private static final Set<String> AVAILABILITY =
            Set.of("IN_STOCK", "OUT_OF_STOCK", "UNKNOWN", "DISCONTINUED");
    private static final Set<String> SUMMARY_STATUS =
            Set.of("ESTIMATED", "CONFIRMED", "INCOMPLETE", "UNAVAILABLE");

    private McpContractAssertions() {
    }

    /** {@code CoreCommerceService.search()} + {@code CommerceProjection.search()}. */
    public static void assertSearch(Map<String, Object> source) {
        if (!(source.get("products") instanceof List<?>) || !(source.get("filters") instanceof Map<?, ?>)
                || !(source.get("failedMerchants") instanceof List<?>)
                || !source.containsKey("nextCursor")) {
            fail("검색 응답에 products·filters·failedMerchants·nextCursor가 모두 있어야 한다: " + source);
        }
        timestamp(source.get("checkedAt"));
        if (source.get("nextCursor") != null) {
            text(source.get("nextCursor"), 4096);
        }
        list(source.get("failedMerchants"), 100).forEach(McpContractAssertions::id);
        for (Object item : list(source.get("products"), 30)) {
            Map<String, Object> product = map(item);
            text(product.get("name"), 500);
            id(product.get("comparisonGroupId"));
            namedId(product.get("category"));
            List<?> offers = list(product.get("offers"), 100);
            if (offers.isEmpty()) {
                fail("비교 묶음에는 offer가 최소 1건 있어야 한다: " + product);
            }
            for (Object entry : offers) {
                Map<String, Object> offer = map(entry);
                id(offer.get("merchantId"));
                id(offer.get("merchantProductId"));
                text(offer.get("merchantName"), 200);
                amount(offer.get("priceAmount"));
                currency(offer.get("currency"));
                availability(offer.get("availability"));
            }
        }
        Map<String, Object> filters = map(source.get("filters"));
        list(filters.get("categories"), 500).forEach(McpContractAssertions::namedId);
        list(filters.get("merchants"), 100).forEach(McpContractAssertions::namedId);
    }

    /** {@code CommerceProjection.detail()}. */
    public static void assertDetail(Map<String, Object> source) {
        id(source.get("merchantId"));
        id(source.get("merchantProductId"));
        text(source.get("name"), 500);
        timestamp(source.get("checkedAt"));
        for (Object item : list(source.get("optionGroups"), 30)) {
            namedId(item);
            list(map(item).get("values"), 200).forEach(McpContractAssertions::namedId);
        }
        for (Object item : list(source.get("variants"), 500)) {
            Map<String, Object> variant = map(item);
            id(variant.get("merchantVariantId"));
            amount(variant.get("priceAmount"));
            currency(variant.get("currency"));
            availability(variant.get("availability"));
            if (!(variant.get("purchasable") instanceof Boolean)) {
                fail("purchasable은 boolean이어야 한다: " + variant);
            }
            if (number(variant.get("minQuantity")) < 1
                    || number(variant.get("maxQuantity")) < number(variant.get("minQuantity"))) {
                fail("minQuantity·maxQuantity 범위가 올바르지 않다: " + variant);
            }
            amount(variant.get("stockQuantity"));
            list(variant.get("optionValueIds"), 30).forEach(McpContractAssertions::id);
        }
        Map<String, Object> shipping = map(source.get("shipping"));
        amount(shipping.get("feeAmount"));
        if (!(shipping.get("status") instanceof String)) {
            fail("shipping.status가 없다: " + shipping);
        }
    }

    /** {@code CommerceProjection.cart()}. */
    public static void assertCart(Map<String, Object> source) {
        id(source.get("cartId"));
        number(source.get("version"));
        Set<String> cartItemIds = new HashSet<>();
        for (Object entry : list(source.get("items"), 100)) {
            Map<String, Object> item = map(entry);
            if (!cartItemIds.add(id(item.get("cartItemId")))) {
                fail("cartItemId가 중복됐다: " + item);
            }
            id(item.get("merchantId"));
            id(item.get("merchantProductId"));
            id(item.get("merchantVariantId"));
            text(item.get("name"), 500);
            if (number(item.get("quantity")) < 1) {
                fail("quantity는 1 이상이어야 한다: " + item);
            }
            amount(item.get("unitPriceAmount"));
            amount(item.get("lineTotalAmount"));
            currency(item.get("currency"));
            for (Object value : list(item.get("selectedOptions"), 30)) {
                Map<String, Object> option = map(value);
                text(option.get("name"), 200);
                text(option.get("value"), 200);
            }
        }
        for (Object value : list(source.get("merchantSummaries"), 100)) {
            summary(map(value));
            id(map(value).get("merchantId"));
            text(map(value).get("merchantName"), 200);
        }
        summary(map(source.get("summary")));
        list(source.getOrDefault("failedMerchants", List.of()), 100).forEach(McpContractAssertions::id);
    }

    private static void summary(Map<String, Object> source) {
        currency(source.get("currency"));
        for (String key : List.of("itemSubtotalAmount", "discountAmount", "shippingAmount", "totalAmount")) {
            amount(source.get(key));
        }
        if (!(source.get("status") instanceof String status) || !SUMMARY_STATUS.contains(status)) {
            fail("summary.status 값이 허용 집합 밖이다: " + source);
        }
    }

    private static Map<String, Object> namedId(Object value) {
        Map<String, Object> map = map(value);
        id(map.get("id"));
        text(map.get("name"), 200);
        return map;
    }

    private static String id(Object value) {
        if (!(value instanceof String text) || !text.matches("[A-Za-z0-9_:-]{1,96}")) {
            fail("id 형식이 아니다: " + value);
            return null;
        }
        return text;
    }

    private static void text(Object value, int maximum) {
        if (!(value instanceof String text) || text.length() > maximum) {
            fail("문자열이 아니거나 너무 길다: " + value);
        }
    }

    private static long number(Object value) {
        if (!(value instanceof Number number) || number.longValue() < 0
                || number.doubleValue() != number.longValue() || number.longValue() > 9000000000000L) {
            fail("0 이상의 정수가 아니다: " + value);
            return -1;
        }
        return number.longValue();
    }

    private static void amount(Object value) {
        if (value != null) {
            number(value);
        }
    }

    private static void currency(Object value) {
        if (!"KRW".equals(value)) {
            fail("currency는 KRW만 허용된다: " + value);
        }
    }

    private static void availability(Object value) {
        if (!(value instanceof String text) || !AVAILABILITY.contains(text)) {
            fail("availability 값이 허용 집합 밖이다: " + value);
        }
    }

    private static void timestamp(Object value) {
        text(value, 50);
        try {
            Instant.parse((String) value);
        } catch (RuntimeException exception) {
            fail("ISO-8601 시각이 아니다: " + value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            fail("객체가 아니다: " + value);
        }
        return (Map<String, Object>) value;
    }

    private static List<?> list(Object value, int maximum) {
        if (!(value instanceof List<?> list) || list.size() > maximum) {
            fail("배열이 아니거나 너무 길다: " + value);
            return List.of();
        }
        return list;
    }
}
