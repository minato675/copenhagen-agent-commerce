package com.copenhagen.merchantmock.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 파이썬 벤치와 자바 구현이 같은 답을 내는지 대조한다(ADR 0013 "결과", DESIGN.md 6.7절 "4차").
 *
 * <p>벤치가 규칙을 고른 근거는 파이썬 실측이다. 자바가 파이썬과 다른 답을 내면 그 실측은 자바 구현의 근거가 아니다. 그래서
 * 377 검색어(튜닝셋·검증셋 3~9, 4차 튜닝셋·검증셋 포함)마다 토큰 목록과 상위 10건 externalId 가 같은지 본다. 풀은 커밋된
 * 전량 100,262건을 external_id 순서로 싣는다 — 실서비스(JdbcProductRepository)와 같은 순서이고 4차 규칙이 바로 그 동점을 다룬다.
 * 동의어·수식어·속성·펫 목록은 {@code application.yml} 원문에서 읽는다.
 *
 * <p>3차는 {@code docs/s19-parity.json}(상위 4건, 풀 파일 순서)으로 대조했다. 4·5차에서 순위가 바뀌어 그 자료는 더 쓰지 않는다.
 *
 * <p>대조 자료 {@code docs/search-r7b-parity.json} 은 개인 문서 폴더(gitignore)에 있어 CI 에는 없다. 없으면 건너뛴다.
 * 만드는 법: {@code python docs/search-r7b-dump.py}.
 */
@Tag("parity")
class SearchParityTest {

    private static final Path PARITY = Path.of("docs", "search-r7b-parity.json");
    private static final int TOP = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("377 검색어에서 파이썬 벤치(R7b)와 같은 토큰·같은 상위 10건을 낸다")
    void javaMatchesPythonBench() throws IOException {
        Assumptions.assumeTrue(Files.exists(PARITY), "대조 자료가 없다(개인 문서). 건너뛴다");
        JsonNode dump = MAPPER.readTree(Files.readString(PARITY));
        assertEquals("R7b", dump.get("mode").asString(), "대조 자료는 5차 R7b 로 만든 것이어야 한다");

        MerchantProperties.Catalog catalog = catalogFromYml();
        CatalogPool pool = new CatalogPoolReader(new DefaultResourceLoader(), MAPPER)
                .read("classpath:catalog/pool.json.gz", 0);
        List<Product> products = new ArrayList<>(pool.products().size());
        for (CatalogPool.PoolProduct p : pool.products()) {
            List<Product.Variant> variants = p.variants().stream()
                    .map(v -> new Product.Variant(v.sku(), v.optionName(), v.optionValue(), v.priceDelta(), v.stock(),
                            Product.ProductStatus.valueOf(v.status())))
                    .toList();
            products.add(new Product(p.externalId() + "-uuid", "merchant-a", p.externalId(), p.title(), null,
                    p.category(), p.brand(), p.imageUrl(), p.basePrice(), p.currency(),
                    Product.ProductStatus.valueOf(p.status()), variants));
        }
        products.sort(Comparator.comparing(Product::externalId));
        CatalogStore store = new CatalogStore(() -> products);
        store.load();
        CatalogService service = new CatalogService(store, MerchantPropertiesFixture.withCatalog(catalog));
        List<SearchDoc> docs = store.searchDocs("merchant-a");

        List<String> tokenMismatches = new ArrayList<>();
        List<String> topMismatches = new ArrayList<>();
        int total = 0;
        for (JsonNode row : dump.get("rows")) {
            total++;
            String query = row.get("query").asString();
            List<String> expectedTokens = new ArrayList<>();
            row.get("tokens").forEach(node -> expectedTokens.add(node.asString()));
            List<String> expectedTop = new ArrayList<>();
            row.get("top").forEach(node -> expectedTop.add(node.asString()));

            List<String> actualTokens = service.terms(query, docs).stream().map(CatalogService.Term::token).toList();
            if (!actualTokens.equals(expectedTokens)) {
                tokenMismatches.add(query + "  파이썬 " + expectedTokens + "  자바 " + actualTokens);
            }
            List<String> actualTop = service.search("merchant-a", new CatalogMessages.SearchRequest(query, null))
                    .items().stream().limit(TOP).map(CatalogMessages.Item::productId).toList();
            if (!actualTop.equals(expectedTop)) {
                topMismatches.add(query + "  파이썬 " + expectedTop + "  자바 " + actualTop);
            }
        }
        String report = "질의 " + total + "개. 토큰 불일치 " + tokenMismatches.size() + ", 상위 10건 불일치 "
                + topMismatches.size() + "\n토큰:\n  " + String.join("\n  ", tokenMismatches.stream().limit(30).toList())
                + "\n상위 10건:\n  " + String.join("\n  ", topMismatches.stream().limit(30).toList());
        assertEquals(0, tokenMismatches.size() + topMismatches.size(), report);
    }

    /**
     * application.yml 원문의 merchant.catalog 에서 동의어·검색 조정값을 읽는다. 풀 경로는 비운다(로더 없이 직접 싣는다).
     * RelatedParityTest 도 쓴다.
     */
    @SuppressWarnings("unchecked")
    static MerchantProperties.Catalog catalogFromYml() throws IOException {
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> merchant = (Map<String, Object>) root.get("merchant");
            Map<String, Object> catalog = (Map<String, Object>) merchant.get("catalog");
            Map<String, Object> search = (Map<String, Object>) catalog.get("search");
            List<List<String>> synonyms = (List<List<String>>) catalog.get("synonyms");
            MerchantProperties.Catalog.Search tuning = new MerchantProperties.Catalog.Search(
                    (List<String>) search.get("fluff-words"),
                    ((Number) search.get("fluff-weight")).doubleValue(),
                    ((Number) search.get("tail-boost")).doubleValue(),
                    (List<String>) search.get("attribute-words"),
                    (Boolean) search.get("nori"),
                    (List<String>) search.get("pet-query-words"),
                    (List<String>) search.get("pet-query-tokens"),
                    (List<String>) search.get("pet-product-markers"),
                    (List<String>) search.get("head-blocked-words"),
                    (List<String>) search.get("accessory-words"),
                    (List<String>) search.get("accessory-title-words"),
                    (List<String>) search.get("accessory-category-words"),
                    (List<String>) search.get("bag-heads"),
                    (String) search.get("bag-word"));
            return new MerchantProperties.Catalog("", 0, synonyms, tuning);
        }
    }
}
