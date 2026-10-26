package com.copenhagen.merchantmock.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 파이썬 벤치와 자바 연관 상품이 같은 답을 내는지 대조한다(DESIGN.md 6.8.1절 "검증", ADR 0014).
 *
 * <p>ADR 0014 의 수치는 파이썬 실측이다. 자바가 다른 답을 내면 그 수치는 자바 구현의 근거가 아니다. 그래서 판 1·판 2 씨앗 1,000 마다
 * 세 모드(rrf3·rrf2·search, 중복 제거 포함)의 상위 4, 그리고 씨앗 둘 장바구니 500 의 번갈아 꺼내기가 같은지 본다. 풀은 커밋된 전량,
 * 이웃은 커밋된 파일, 검색 조정값은 {@code application.yml} 원문이다. 몰·재고 필터는 벤치에 없어 끈다.
 *
 * <p><b>스토어는 운영 DB 와 같은 순서·같은 구성으로 만든다.</b> 벤치의 검색 결과는 실행 중인 컨테이너에서 받았고, 운영은 상품을
 * {@code ORDER BY merchant_id, external_id} 로 읽어 S19 동점을 그 순서로 가른다. 처음에 풀 파일 순서로 만들었다가 3,500 중 2,489 가
 * 어긋났다(2026-09-26). 몰 merchant-a 에는 풀 전량에 V3 시드 5건(product-101~105)이 더 있어 그것도 넣는다. DB 의 en_US.utf8 순서는
 * 자바 문자열 순서와 같다(그날 전량 대조). 벤치는 검색 결과에서 풀에 없는 상품을 빼므로 여기서도 뺀다.
 *
 * <p>대조 자료 {@code docs/related-parity.json} 은 개인 문서 폴더(gitignore)에 있어 CI 에는 없다. 없으면 건너뛴다.
 * 만드는 법: {@code python docs/related-parity-dump.py}.
 */
@Tag("parity")
class RelatedParityTest {

    private static final Path PARITY = Path.of("docs", "related-parity.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MALL = "merchant-a";

    @Test
    @DisplayName("씨앗 1,000 × 세 모드와 장바구니 500 에서 파이썬 벤치와 같은 결과를 낸다")
    void javaMatchesPythonBench() throws IOException, RelatedNeighbors.Invalid {
        Assumptions.assumeTrue(Files.exists(PARITY), "대조 자료가 없다(개인 문서). 건너뛴다");
        JsonNode dump = MAPPER.readTree(Files.readString(PARITY));

        CatalogPool pool = new CatalogPoolReader(new DefaultResourceLoader(), MAPPER)
                .read("classpath:catalog/pool.json.gz", 0);
        assertEquals(dump.get("poolSha256").asString(), pool.sha256(), "대조 자료가 지금 풀로 만든 것이어야 한다");
        byte[] file = new DefaultResourceLoader().getResource("classpath:catalog/related-e5nb.bin.gz")
                .getContentAsByteArray();
        assertEquals(dump.get("neighborFileSha256").asString(), RelatedNeighbors.sha256(file),
                "대조 자료가 지금 이웃 파일로 만든 것이어야 한다");

        List<Product> products = new ArrayList<>(pool.products().size());
        for (CatalogPool.PoolProduct p : pool.products()) {
            List<Product.Variant> variants = p.variants().stream()
                    .map(v -> new Product.Variant(v.sku(), v.optionName(), v.optionValue(), v.priceDelta(), v.stock(),
                            Product.ProductStatus.valueOf(v.status())))
                    .toList();
            products.add(new Product(p.externalId() + "-uuid", MALL, p.externalId(), p.title(), null,
                    p.category(), p.brand(), p.imageUrl(), p.basePrice(), p.currency(),
                    Product.ProductStatus.valueOf(p.status()), variants));
        }
        products.addAll(CatalogFixture.seed());
        products.sort(java.util.Comparator.comparing(Product::externalId));
        CatalogStore store = new CatalogStore(() -> products);
        store.load();
        CatalogService catalog = new CatalogService(store,
                MerchantPropertiesFixture.withCatalog(SearchParityTest.catalogFromYml()));
        java.util.Set<String> inPool = new java.util.HashSet<>();
        pool.products().forEach(p -> inPool.add(p.externalId()));
        Function<String, List<String>> search = query -> catalog
                .search(MALL, new CatalogMessages.SearchRequest(query, null)).items().stream()
                .map(CatalogMessages.Item::productId).filter(inPool::contains).toList();
        Function<String, Optional<String>> none = id -> Optional.empty();

        List<String> ids = pool.products().stream().map(CatalogPool.PoolProduct::externalId).toList();
        List<String> titles = pool.products().stream().map(CatalogPool.PoolProduct::title).toList();
        List<String> categories = pool.products().stream().map(CatalogPool.PoolProduct::category).toList();
        long started = System.nanoTime();
        RelatedIndex index = RelatedIndex.build(titles, categories);
        long indexMillis = (System.nanoTime() - started) / 1_000_000;
        RelatedNeighbors neighbors = RelatedNeighbors.read(file, pool.sha256(), ids.size());
        Map<String, RelatedEngine> engines = Map.of(
                "rrf3", new RelatedEngine(ids, titles, index, neighbors, RelatedProperties.Mode.RRF3),
                "rrf2", new RelatedEngine(ids, titles, index, neighbors, RelatedProperties.Mode.RRF2),
                "search", new RelatedEngine(ids, titles, index, neighbors, RelatedProperties.Mode.SEARCH));
        Map<String, Integer> byId = new java.util.HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            byId.putIfAbsent(ids.get(i), i);
        }

        List<String> mismatches = new ArrayList<>();
        int rows = 0;
        for (JsonNode row : dump.get("rows")) {
            rows++;
            String seed = row.get("seed").asString();
            String title = titles.get(byId.get(seed));
            for (String mode : List.of("rrf3", "rrf2", "search")) {
                List<String> expected = new ArrayList<>();
                row.get(mode).forEach(node -> expected.add(node.asString()));
                RelatedEngine engine = engines.get(mode);
                List<String> actual = engine.merge(List.of(engine.ranked(seed, title, 4, search, none)),
                        List.of(seed), List.of(title), 4, id -> true, none);
                if (!actual.equals(expected)) {
                    mismatches.add(mode + " " + seed + "  파이썬 " + expected + "  자바 " + actual);
                }
            }
        }
        int baskets = 0;
        RelatedEngine rrf3 = engines.get("rrf3");
        for (JsonNode basket : dump.get("baskets")) {
            baskets++;
            List<String> seeds = new ArrayList<>();
            basket.get("seeds").forEach(node -> seeds.add(node.asString()));
            List<String> seedTitles = seeds.stream().map(id -> titles.get(byId.get(id))).toList();
            List<List<String>> lists = new ArrayList<>();
            for (int s = 0; s < seeds.size(); s++) {
                lists.add(rrf3.ranked(seeds.get(s), seedTitles.get(s), 20, search, none));
            }
            List<String> expected = new ArrayList<>();
            basket.get("rrf3").forEach(node -> expected.add(node.asString()));
            List<String> actual = rrf3.merge(lists, seeds, seedTitles, 4, id -> true, none);
            if (!actual.equals(expected)) {
                mismatches.add("basket " + seeds + "  파이썬 " + expected + "  자바 " + actual);
            }
        }
        String report = "씨앗 " + rows + " × 3 모드, 장바구니 " + baskets + ". 불일치 " + mismatches.size()
                + ". TF-IDF 색인 " + indexMillis + "ms, 낱말 " + index.vocabularySize() + ", 게시 " + index.postings()
                + "\n  " + String.join("\n  ", mismatches.stream().limit(30).toList());
        System.out.println(report);
        assertEquals(0, mismatches.size(), report);
    }
}
