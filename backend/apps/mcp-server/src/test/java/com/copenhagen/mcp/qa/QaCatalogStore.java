package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.catalog.ProductDetailRequest;
import com.copenhagen.mcp.catalog.ProductSearchRequest;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Core의 집계 동작을 대체하는 test 전용 fixture. 운영 어댑터에 검색/가격 로직을 옮기지 않는다. */
final class QaCatalogStore {

    private static final List<Map<String, Object>> MERCHANTS = List.of(
            Map.of("merchantId", "qa-a", "name", "노르드 마켓"),
            Map.of("merchantId", "qa-b", "name", "하운 스토어"),
            Map.of("merchantId", "qa-c", "name", "벨루아 셀렉트"));
    private final List<Map<String, Object>> products;
    private final Set<String> failed;

    QaCatalogStore(List<Map<String, Object>> products, Set<String> failed) {
        this.products = List.copyOf(products);
        this.failed = Set.copyOf(failed);
    }

    Set<String> failedMerchants() {
        return failed;
    }

    static String merchantName(String merchantId, String fallback) {
        return MERCHANTS.stream().filter(merchant -> merchantId.equals(merchant.get("merchantId")))
                .map(merchant -> (String) merchant.get("name")).findFirst().orElse(fallback);
    }

    static QaCatalogStore load(Path file) throws IOException {
        if (Files.size(file) > 30_000_000) {
            throw new IllegalArgumentException("Local catalog exceeds QA size limit");
        }
        Map<String, Object> payload = McpJsonDefaults.getMapper()
                .readValue(Files.readAllBytes(file), new TypeRef<>() { });
        if (!"QA".equals(payload.get("mode")) || !Integer.valueOf(1).equals(payload.get("format"))) {
            throw new IllegalArgumentException("Expected QA catalog format 1");
        }
        return new QaCatalogStore(maps(payload.get("products")), Set.of());
    }

    Map<String, Object> search(ProductSearchRequest query) {
        List<Map<String, Object>> matches = products.stream().filter(product -> matchesText(product, query)).toList();
        List<String> categories = matches.stream().map(product -> (String) product.get("category"))
                .distinct().sorted().limit(200).toList();
        List<String> failures = failed.stream().filter(id -> query.merchantId().isEmpty()
                || id.equals(query.merchantId())).sorted().toList();
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> product : matches) {
            if (!query.category().isEmpty() && !query.category().equals(product.get("category"))) {
                continue;
            }
            List<Map<String, Object>> offers = maps(product.get("offers")).stream().filter(offer -> {
                Number price = (Number) offer.get("price");
                return !failed.contains(offer.get("merchantId"))
                        && (query.merchantId().isEmpty() || query.merchantId().equals(offer.get("merchantId")))
                        && (query.minPrice() == null || (price != null && price.longValue() >= query.minPrice()))
                        && (query.maxPrice() == null || (price != null && price.longValue() <= query.maxPrice()));
            }).map(QaCatalogStore::namedOffer).toList();
            if (!offers.isEmpty()) {
                Map<String, Object> card = new LinkedHashMap<>();
                for (String field : List.of("productId", "name", "brand", "category", "imageUrl")) {
                    card.put(field, product.get(field));
                }
                card.put("offers", offers);
                filtered.add(card);
            }
        }
        if (!query.sort().equals("RELEVANCE")) {
            Comparator<Map<String, Object>> comparator = Comparator.comparingLong(QaCatalogStore::minimumPrice);
            filtered.sort(query.sort().equals("PRICE_ASC") ? comparator : comparator.reversed());
        }
        int total = filtered.size();
        int from = Math.min((query.page() - 1) * query.pageSize(), total);
        List<Map<String, Object>> page = filtered.subList(from, Math.min(from + query.pageSize(), total));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "catalog.search");
        result.put("mode", "QA");
        result.put("status", !failures.isEmpty() ? "PARTIAL" : page.isEmpty() ? "EMPTY" : "OK");
        result.put("query", query.arguments());
        result.put("products", List.copyOf(page));
        result.put("categories", categories);
        result.put("merchants", MERCHANTS);
        result.put("failedMerchants", failures);
        result.put("total", total);
        result.put("hasNext", (long) query.page() * query.pageSize() < total);
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    private static boolean matchesText(Map<String, Object> product, ProductSearchRequest query) {
        String haystack = (product.get("name") + " " + product.get("brand") + " " + product.get("category"))
                .toLowerCase(Locale.ROOT);
        for (String term : query.query().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!haystack.contains(term)) {
                return false;
            }
        }
        return query.brand().isEmpty() || query.brand().equals(product.get("brand"));
    }

    Map<String, Object> detail(ProductDetailRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "catalog.detail");
        result.put("mode", "QA");
        result.put("request", request.arguments());
        result.put("checkedAt", Instant.now().toString());
        Map<String, Object> source = products.stream().filter(p -> request.productId().equals(p.get("productId")))
                .findFirst().orElse(null);
        Map<String, Object> sourceOffer = source == null ? null : maps(source.get("offers")).stream()
                .filter(o -> request.merchantId().equals(o.get("merchantId"))).findFirst().orElse(null);
        if (sourceOffer == null) {
            result.put("status", "NOT_FOUND");
            result.put("product", null);
            return result;
        }
        if (failed.contains(request.merchantId())) {
            throw new IllegalStateException("QA merchant unavailable");
        }
        Map<String, Object> product = new LinkedHashMap<>();
        for (String field : List.of("productId", "name", "brand", "category", "imageUrl")) {
            product.put(field, source.get(field));
        }
        product.put("description", source.getOrDefault("description", ""));
        product.put("specs", source.getOrDefault("specs", List.of()));
        boolean merchantUnavailable = List.of("OUT_OF_STOCK", "DISCONTINUED")
                .contains(sourceOffer.get("availability"));
        List<Map<String, Object>> variants = new ArrayList<>();
        for (Map<String, Object> variant : maps(source.getOrDefault("variants", List.of()))) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("optionId", variant.get("optionId"));
            option.put("label", variant.get("label"));
            option.put("availability", merchantUnavailable ? sourceOffer.get("availability")
                    : variant.get("availability"));
            option.put("purchaseAllowed", variant.get("purchaseAllowed"));
            option.put("price", ((Map<?, ?>) variant.get("prices")).get(request.merchantId()));
            variants.add(option);
        }
        Map<String, Object> selected = variants.stream().filter(v -> request.optionId().equals(v.get("optionId")))
                .findFirst().orElse(null);
        String selection = selected != null ? "VALID" : !request.optionId().isEmpty() ? "INVALID"
                : Boolean.TRUE.equals(source.get("optionsRequired")) ? "REQUIRED" : "UNKNOWN";
        Map<String, Object> offer = namedOffer(sourceOffer);
        if (selected != null) {
            offer.put("price", selected.get("price"));
            offer.put("availability", selected.get("availability"));
        } else if (selection.equals("REQUIRED") || selection.equals("INVALID")) {
            offer.put("price", null);
        }
        product.put("offer", offer);
        product.put("variants", variants);
        product.put("optionsComplete", source.getOrDefault("optionsComplete", true));
        product.put("optionsRequired", source.get("optionsRequired"));
        product.put("selectionStatus", selection);
        product.put("selectedOptionId", selected == null ? null : selected.get("optionId"));
        product.put("stockQuantity", source.get("stockQuantity"));
        Map<String, Object> shipping = new LinkedHashMap<>();
        shipping.put("status", "UNKNOWN");
        shipping.put("fee", null);
        shipping.put("description", "배송 조건 미확인");
        product.put("shipping", shipping);
        result.put("status", "FOUND");
        result.put("product", product);
        return result;
    }

    private static long minimumPrice(Map<String, Object> product) {
        return maps(product.get("offers")).stream().filter(offer -> offer.get("price") != null)
                .mapToLong(offer -> ((Number) offer.get("price")).longValue()).min().orElse(Long.MAX_VALUE);
    }

    private static Map<String, Object> namedOffer(Map<String, Object> source) {
        Map<String, Object> offer = new LinkedHashMap<>(source);
        offer.put("merchantName", merchantName((String) source.get("merchantId"), (String) source.get("merchantName")));
        return offer;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
