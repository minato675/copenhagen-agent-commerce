package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * products 테이블을 읽는 구현. JdbcTemplate 만 쓰고 JPA 를 쓰지 않는다(DESIGN.md 4절).
 *
 * <p>variants_json 은 jsonb 라 문자열로 꺼내 Jackson 으로 푼다.
 */
@Repository
public class JdbcProductRepository implements ProductRepository {

    private static final String SELECT_ALL = """
            SELECT id, merchant_id, external_id, title, category, brand, image_url,
                   base_price, currency, status, variants_json::text
              FROM products
             ORDER BY merchant_id, external_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcProductRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Product> findAll() {
        // 몰 셋이 같은 상품을 팔아 제목·카테고리·이미지·SKU 문자열이 세 번씩 온다. 같은 문자열을 한 객체로
        // 모아 27만 행을 한 벌 값으로 올린다(DESIGN.md 13절). 맵은 이 호출 안에서만 산다.
        Map<String, String> shared = new HashMap<>();
        return jdbcTemplate.query(SELECT_ALL, rowMapper(shared));
    }

    private RowMapper<Product> rowMapper(Map<String, String> shared) {
        return (rs, rowNum) -> new Product(
                rs.getString("id"),
                share(shared, rs.getString("merchant_id")),
                share(shared, rs.getString("external_id")),
                share(shared, rs.getString("title")),
                null,
                share(shared, rs.getString("category")),
                share(shared, rs.getString("brand")),
                share(shared, rs.getString("image_url")),
                rs.getLong("base_price"),
                share(shared, rs.getString("currency")),
                Product.ProductStatus.valueOf(rs.getString("status")),
                parseVariants(rs.getString("variants_json"), shared));
    }

    private static String share(Map<String, String> shared, String value) {
        if (value == null) {
            return null;
        }
        String existing = shared.putIfAbsent(value, value);
        return existing == null ? value : existing;
    }

    private List<Product.Variant> parseVariants(String json, Map<String, String> shared) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            List<Product.Variant> variants = new ArrayList<>();
            for (JsonNode node : array) {
                variants.add(new Product.Variant(
                        share(shared, node.path("sku").asText()),
                        share(shared, node.path("optionName").asText()),
                        share(shared, node.path("optionValue").asText()),
                        node.path("priceDelta").asLong(0L),
                        node.path("stock").asInt(0),
                        Product.ProductStatus.valueOf(node.path("status").asText("ACTIVE"))));
            }
            return List.copyOf(variants);
        } catch (JacksonException | IllegalArgumentException exception) {
            // JacksonException 은 JSON 형식 오류, IllegalArgumentException 은 status 값이 enum 에 없는 경우다.
            throw new IllegalStateException("products.variants_json 을 읽을 수 없다: " + json, exception);
        }
    }
}
