package com.copenhagen.mcp.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 내부 API 응답에서 공개 계약 필드만 복사한다. null 금액을 0으로 바꾸지 않는다. */
final class CommerceProjection {
    private CommerceProjection() {
    }

    static Map<String, Object> search(Map<String, Object> source) {
        Map<String, Object> data = pick(source, "nextCursor", "checkedAt", "failedMerchants");
        timestamp(data.get("checkedAt"));
        if (data.get("nextCursor") != null) {
            text(data.get("nextCursor"), 4096);
        }
        data.put("failedMerchants", list(source.get("failedMerchants"), 100).stream()
                .map(CommerceProjection::id).toList());
        List<Map<String, Object>> products = new ArrayList<>();
        for (Object item : list(source.get("products"), 30)) {
            Map<String, Object> product = pick(map(item), "comparisonGroupId", "name", "brand", "imageUrl");
            text(product.get("name"), 500);
            product.put("category", namedId(map(item).get("category")));
            List<Map<String, Object>> offers = new ArrayList<>();
            for (Object entry : list(map(item).get("offers"), 100)) {
                Map<String, Object> offer = pick(map(entry), "merchantId", "merchantName", "merchantProductId",
                        "priceAmount", "currency", "availability");
                id(offer.get("merchantId"));
                id(offer.get("merchantProductId"));
                text(offer.get("merchantName"), 200);
                amount(offer.get("priceAmount"));
                currency(offer.get("currency"));
                availability(offer.get("availability"));
                offers.add(offer);
            }
            if (offers.isEmpty()) {
                throw invalid();
            }
            product.put("offers", offers);
            products.add(product);
        }
        data.put("products", products);
        Map<String, Object> filters = map(source.get("filters"));
        data.put("filters", Map.of("categories", list(filters.get("categories"), 500).stream()
                .map(CommerceProjection::namedId).toList(), "merchants", list(filters.get("merchants"), 100).stream()
                .map(CommerceProjection::namedId).toList()));
        return data;
    }

    static Map<String, Object> detail(Map<String, Object> source) {
        Map<String, Object> data = pick(source, "merchantId", "merchantProductId", "name", "imageUrl", "checkedAt");
        id(data.get("merchantId"));
        id(data.get("merchantProductId"));
        text(data.get("name"), 500);
        timestamp(data.get("checkedAt"));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Object item : list(source.get("optionGroups"), 30)) {
            Map<String, Object> group = namedId(item);
            group.put("values", list(map(item).get("values"), 200).stream()
                    .map(CommerceProjection::namedId).toList());
            groups.add(group);
        }
        data.put("optionGroups", groups);
        List<Map<String, Object>> variants = new ArrayList<>();
        for (Object item : list(source.get("variants"), 500)) {
            Map<String, Object> variant = pick(map(item), "merchantVariantId", "priceAmount", "currency",
                    "availability", "stockQuantity", "purchasable", "minQuantity", "maxQuantity");
            id(variant.get("merchantVariantId"));
            amount(variant.get("priceAmount"));
            currency(variant.get("currency"));
            availability(variant.get("availability"));
            if (!(variant.get("purchasable") instanceof Boolean)) {
                throw invalid();
            }
            if (number(variant.get("minQuantity")) < 1
                    || number(variant.get("maxQuantity")) < number(variant.get("minQuantity"))) {
                throw invalid();
            }
            amount(variant.get("stockQuantity"));
            variant.put("optionValueIds", list(map(item).get("optionValueIds"), 30).stream()
                    .map(CommerceProjection::id).toList());
            variants.add(variant);
        }
        data.put("variants", variants);
        Map<String, Object> shipping = pick(map(source.get("shipping")), "feeAmount", "status");
        amount(shipping.get("feeAmount"));
        data.put("shipping", shipping);
        return data;
    }

    static Map<String, Object> cart(Map<String, Object> source) {
        Map<String, Object> data = pick(source, "cartId", "version");
        id(data.get("cartId"));
        number(data.get("version"));
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        // 과거 앱에서 한도를 넘겨 담은 장바구니도 조회·삭제할 수 있어야 한다.
        for (Object entry : list(source.get("items"), 1000)) {
            Map<String, Object> item = pick(map(entry), "cartItemId", "merchantId", "merchantProductId",
                    "merchantVariantId", "name", "imageUrl", "quantity", "unitPriceAmount",
                    "lineTotalAmount", "currency");
            if (!ids.add(id(item.get("cartItemId")))) {
                throw invalid();
            }
            id(item.get("merchantId"));
            id(item.get("merchantProductId"));
            id(item.get("merchantVariantId"));
            text(item.get("name"), 500);
            if (number(item.get("quantity")) < 1) {
                throw invalid();
            }
            amount(item.get("unitPriceAmount"));
            amount(item.get("lineTotalAmount"));
            currency(item.get("currency"));
            item.put("selectedOptions", list(map(entry).get("selectedOptions"), 30).stream().map(value -> {
                Map<String, Object> option = pick(map(value), "name", "value");
                text(option.get("name"), 200);
                text(option.get("value"), 200);
                return option;
            }).toList());
            items.add(item);
        }
        data.put("items", items);
        List<Map<String, Object>> merchants = new ArrayList<>();
        for (Object value : list(source.get("merchantSummaries"), 100)) {
            Map<String, Object> merchant = summary(map(value));
            merchant.put("merchantId", id(map(value).get("merchantId")));
            merchant.put("merchantName", text(map(value).get("merchantName"), 200));
            merchants.add(merchant);
        }
        data.put("merchantSummaries", merchants);
        data.put("summary", summary(map(source.get("summary"))));
        data.put("failedMerchants", list(source.getOrDefault("failedMerchants", List.of()), 100).stream()
                .map(CommerceProjection::id).toList());
        return data;
    }

    private static Map<String, Object> summary(Map<String, Object> source) {
        Map<String, Object> value = pick(source, "currency", "itemSubtotalAmount", "discountAmount",
                "shippingAmount", "totalAmount", "status");
        currency(value.get("currency"));
        for (String key : List.of("itemSubtotalAmount", "discountAmount", "shippingAmount", "totalAmount")) {
            amount(value.get(key));
        }
        if (!(value.get("status") instanceof String status)
                || !Set.of("ESTIMATED", "CONFIRMED", "INCOMPLETE", "UNAVAILABLE").contains(status)) {
            throw invalid();
        }
        if (value.get("discountAmount") == null
                || value.get("discountAmount") instanceof Number discount && discount.longValue() == 0) {
            value.remove("discountAmount");
        }
        return value;
    }

    static Map<String, Object> purchaseHistory(Map<String, Object> source) {
        if (!source.containsKey("nextCursor")) {
            throw invalid();
        }
        Map<String, Object> result = pick(source, "periodStart", "nextCursor");
        timestamp(result.get("periodStart"));
        if (result.get("nextCursor") != null) {
            text(result.get("nextCursor"), 4096);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object value : list(source.get("items"), 50)) {
            Map<String, Object> row = map(value);
            if (!row.containsKey("merchantProductId") || !row.containsKey("merchantVariantId")) {
                throw invalid();
            }
            Map<String, Object> item = pick(row, "merchantId", "merchantName", "merchantProductId",
                    "merchantVariantId", "productName", "optionLabel", "purchaseCount", "totalQuantity",
                    "lastUnitPriceAmount", "currency", "lastPurchasedAt");
            id(item.get("merchantId"));
            text(item.get("merchantName"), 200);
            text(item.get("productName"), 500);
            text(item.get("optionLabel"), 2000);
            for (String key : List.of("merchantProductId", "merchantVariantId")) {
                if (item.get(key) != null) {
                    id(item.get(key));
                }
            }
            if (number(item.get("purchaseCount")) < 1 || number(item.get("totalQuantity")) < 1) {
                throw invalid();
            }
            number(item.get("lastUnitPriceAmount"));
            currency(item.get("currency"));
            timestamp(item.get("lastPurchasedAt"));
            items.add(item);
        }
        result.put("items", items);
        return result;
    }

    static Map<String, Object> purchaseStatus(Map<String, Object> source) {
        Map<String, Object> result = pick(source, "purchaseRequestId", "cartId", "cartVersion", "status",
                "expiresAt", "failureCode", "nextAction");
        id(result.get("purchaseRequestId"));
        id(result.get("cartId"));
        number(result.get("cartVersion"));
        timestamp(result.get("expiresAt"));
        Set<String> statuses = Set.of("PENDING_ADDRESS", "CREATING_CHECKOUTS", "AWAITING_APPROVAL",
                "COMPLETING", "COMPLETED", "PARTIALLY_COMPLETED", "CANCELLED", "REJECTED", "FAILED", "EXPIRED");
        if (!statuses.contains(result.get("status"))
                || !Set.of("SELECT_SHIPPING_ADDRESS", "OPEN_APPROVAL", "WAIT", "SHOW_RESULT")
                        .contains(result.get("nextAction"))) {
            throw invalid();
        }
        if (result.get("failureCode") != null) {
            text(result.get("failureCode"), 100);
        }
        result.put("merchantCheckouts", list(source.get("merchantCheckouts"), 100).stream().map(value -> {
            Map<String, Object> row = pick(map(value), "merchantId", "approvalId", "status", "totalAmount", "currency");
            id(row.get("merchantId"));
            if (row.get("approvalId") != null) {
                id(row.get("approvalId"));
            }
            if (!Set.of("CREATING", "AWAITING_APPROVAL", "COMPLETING", "COMPLETED", "FAILED", "EXPIRED",
                    "CANCELLED", "REJECTED").contains(row.get("status"))) {
                throw invalid();
            }
            number(row.get("totalAmount"));
            currency(row.get("currency"));
            return row;
        }).toList());
        result.put("notifications", list(source.get("notifications"), 101).stream().map(value -> {
            Map<String, Object> row = pick(map(value), "eventId", "status", "errorCode");
            if (row.get("eventId") != null) {
                id(row.get("eventId"));
            }
            if (!Set.of("PENDING", "RUNNING", "SENT", "FAILED", "EXHAUSTED", "UNAVAILABLE")
                    .contains(row.get("status"))) {
                throw invalid();
            }
            if (row.get("errorCode") != null) {
                text(row.get("errorCode"), 100);
            }
            return row;
        }).toList());
        return result;
    }

    static Map<String, Object> pick(Map<String, Object> source, String... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : fields) {
            result.put(field, source.get(field));
        }
        return result;
    }

    private static Map<String, Object> namedId(Object value) {
        return new LinkedHashMap<>(Map.of("id", id(map(value).get("id")), "name", text(map(value).get("name"), 200)));
    }

    static String id(Object value) {
        if (!(value instanceof String text) || !text.matches("[A-Za-z0-9_:-]{1,96}")) {
            throw invalid();
        }
        return text;
    }

    private static String text(Object value, int maximum) {
        if (!(value instanceof String text) || text.length() > maximum) {
            throw invalid();
        }
        return text;
    }

    private static long number(Object value) {
        if (!(value instanceof Number number) || number.longValue() < 0
                || number.doubleValue() != number.longValue() || number.longValue() > 9000000000000L) {
            throw invalid();
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
            throw invalid();
        }
    }

    private static void availability(Object value) {
        if (!(value instanceof String text) || !Set.of("IN_STOCK", "OUT_OF_STOCK", "UNKNOWN", "DISCONTINUED")
                .contains(text)) {
            throw invalid();
        }
    }

    private static void timestamp(Object value) {
        try {
            Instant.parse(text(value, 50));
        } catch (java.time.format.DateTimeParseException exception) {
            throw invalid();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw invalid();
        }
        return (Map<String, Object>) value;
    }

    private static List<?> list(Object value, int maximum) {
        if (!(value instanceof List<?> list) || list.size() > maximum) {
            throw invalid();
        }
        return list;
    }

    private static com.copenhagen.mcp.core.CoreApiException invalid() {
        return CoreCommerceService.failure("CORE_INVALID_RESPONSE", false);
    }
}
