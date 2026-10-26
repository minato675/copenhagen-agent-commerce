package com.copenhagen.merchantmock.catalog;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import com.copenhagen.merchantmock.support.ApiException;
import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelatedServiceTest {

    private static final String POOL = "classpath:related/pool.json";
    private static final String MALL = MerchantPropertiesFixture.MERCHANT_A;

    @TempDir
    Path temp;

    private static List<Product> productsOf(CatalogPool pool) {
        List<Product> products = new ArrayList<>();
        for (CatalogPool.PoolProduct p : pool.products()) {
            List<Product.Variant> variants = p.variants().stream()
                    .map(v -> new Product.Variant(v.sku(), v.optionName(), v.optionValue(), v.priceDelta(), v.stock(),
                            Product.ProductStatus.valueOf(v.status())))
                    .toList();
            products.add(new Product(p.externalId() + "-uuid", MALL, p.externalId(), p.title(), null, p.category(),
                    p.brand(), p.imageUrl(), p.basePrice(), p.currency(), Product.ProductStatus.valueOf(p.status()),
                    variants));
        }
        return products;
    }

    private record Setup(RelatedService service, CatalogStore store) {
    }

    private Setup setup(String poolFile, String mode, String neighborsFile, CatalogService override) {
        CatalogPoolReader reader = new CatalogPoolReader(new DefaultResourceLoader(), new ObjectMapper());
        List<Product> products = productsOf(reader.read(POOL, 0));
        CatalogStore store = new CatalogStore(() -> products);
        store.load();
        MerchantProperties merchants = MerchantPropertiesFixture.withCatalogPool(poolFile, 0);
        CatalogService catalog = override != null ? override : new CatalogService(store, merchants);
        RelatedProperties properties = new RelatedProperties(true, mode, neighborsFile, null, null, null);
        return new Setup(new RelatedService(store, catalog, merchants, properties, reader, new DefaultResourceLoader()),
                store);
    }

    /** 테스트 풀과 짝이 맞는 이웃 파일. 순번은 풀 순서다(0 우유 1L … 7 닭가슴살 둘). */
    private String neighborFile(int[][] rows) throws IOException {
        byte[] pool = new DefaultResourceLoader().getResource(POOL).getContentAsByteArray();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.write(RelatedNeighbors.MAGIC);
            out.write(RelatedNeighbors.sha256(pool).getBytes(StandardCharsets.US_ASCII));
            out.writeInt(rows.length);
            out.writeInt(2);
            for (int[] row : rows) {
                out.writeInt(row[0]);
                out.writeInt(row[1]);
            }
        }
        Path file = temp.resolve("related.bin.gz");
        Files.write(file, bytes.toByteArray());
        return "file:" + file.toAbsolutePath();
    }

    private static List<String> ids(CatalogMessages.ItemsResponse response) {
        return response.items().stream().map(CatalogMessages.Item::productId).toList();
    }

    private static CatalogMessages.RelatedRequest request(Integer limit, String... ids) {
        return new CatalogMessages.RelatedRequest(List.of(ids), limit);
    }

    @Test
    @DisplayName("준비가 끝나기 전에는 503 RELATED_WARMING_UP 이다")
    void related_beforePrepare_returnsWarmingUp() {
        // given
        RelatedService service = setup(POOL, "rrf3", "file:none", null).service();

        // when
        ApiException error = assertThrows(ApiException.class,
                () -> service.related(MALL, request(null, "kurly:milk-1l")));

        // then
        assertEquals("RELATED_WARMING_UP", error.errorCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
    }

    @Test
    @DisplayName("이웃 파일이 짝이 맞으면 rrf3, 없으면 rrf2, 풀이 없으면 search 로 돈다")
    void prepare_dependingOnInputs_degradesStepByStep() throws IOException {
        // given
        int[][] rows = {{2, 3}, {0, 2}, {3, 0}, {2, 0}, {0, 2}, {0, 1}, {7, -1}, {6, -1}};
        Setup full = setup(POOL, "rrf3", neighborFile(rows), null);
        Setup noFile = setup(POOL, "rrf3", "file:" + temp.resolve("missing.bin.gz"), null);
        Setup noPool = setup("", "rrf3", "file:none", null);
        Setup searchOnly = setup(POOL, "search", "file:none", null);

        // when
        full.service().prepare();
        noFile.service().prepare();
        noPool.service().prepare();
        searchOnly.service().prepare();

        // then
        assertEquals(RelatedProperties.Mode.RRF3, full.service().mode());
        assertEquals(RelatedProperties.Mode.RRF2, noFile.service().mode());
        assertEquals(RelatedProperties.Mode.SEARCH, noPool.service().mode());
        assertEquals(RelatedProperties.Mode.SEARCH, searchOnly.service().mode());
        assertFalse(ids(noPool.service().related(MALL, request(null, "kurly:milk-1l"))).isEmpty());
    }

    @Test
    @DisplayName("씨앗 자신·같은 상품의 다른 용량·품절 상품은 추천하지 않는다")
    void related_milk_skipsSeedSizeVariantAndSoldOut() throws IOException {
        // given
        Setup setup = setup(POOL, "rrf3", neighborFile(new int[][] {
            {1, 4}, {0, 2}, {3, 0}, {2, 0}, {0, 2}, {0, 1}, {7, -1}, {6, -1}}), null);
        setup.service().prepare();

        // when
        List<String> out = ids(setup.service().related(MALL, request(4, "kurly:milk-1l")));

        // then
        assertFalse(out.contains("kurly:milk-1l"));
        assertFalse(out.contains("kurly:milk-18l"));
        assertFalse(out.contains("kurly:milk-soldout"));
        assertTrue(out.contains("kurly:milk-maeil"));
        assertTrue(out.contains("kurly:milk-namyang"));
    }

    @Test
    @DisplayName("씨앗 둘이면 씨앗마다 혼자일 때의 1순위를 번갈아 꺼내 두 관심사를 다 챙긴다")
    void related_twoSeeds_alternatesTopOfEach() {
        // given
        Setup setup = setup(POOL, "rrf2", "file:none", null);
        setup.service().prepare();
        String milkTop = ids(setup.service().related(MALL, request(1, "kurly:milk-1l"))).get(0);
        String chickenTop = ids(setup.service().related(MALL, request(1, "kurly:chicken"))).get(0);

        // when
        List<String> out = ids(setup.service().related(MALL, request(2, "kurly:milk-1l", "kurly:chicken")));

        // then
        assertEquals(List.of(milkTop, chickenTop), out);
        assertEquals("kurly:chicken-2", chickenTop);
    }

    @Test
    @DisplayName("없는 id 는 조용히 빠지고, 비었거나 전부 없으면 200 빈 배열이다")
    void related_unknownOrEmpty_returnsEmpty() {
        // given
        Setup setup = setup(POOL, "rrf2", "file:none", null);
        setup.service().prepare();

        // when & then
        assertEquals(List.of(), ids(setup.service().related(MALL, request(null))));
        assertEquals(List.of(), ids(setup.service().related(MALL, request(null, "kurly:nope"))));
        assertEquals(List.of(), ids(setup.service().related(MALL, null)));
        assertFalse(ids(setup.service().related(MALL, request(null, "kurly:nope", "kurly:chicken"))).isEmpty());
    }

    @Test
    @DisplayName("id 가 21개 이상이거나 limit 이 1~20 밖이면 400, 모르는 몰은 404 다")
    void related_invalidInput_rejects() {
        // given
        Setup setup = setup(POOL, "rrf2", "file:none", null);
        setup.service().prepare();
        List<String> many = new ArrayList<>(Collections.nCopies(21, "kurly:milk-1l"));

        // when & then
        assertEquals("INVALID_REQUEST", assertThrows(ApiException.class, () -> setup.service().related(MALL,
                new CatalogMessages.RelatedRequest(many, null))).errorCode());
        assertEquals("INVALID_REQUEST", assertThrows(ApiException.class,
                () -> setup.service().related(MALL, request(0, "kurly:milk-1l"))).errorCode());
        assertEquals("INVALID_REQUEST", assertThrows(ApiException.class,
                () -> setup.service().related(MALL, request(21, "kurly:milk-1l"))).errorCode());
        assertEquals("MERCHANT_NOT_FOUND", assertThrows(ApiException.class,
                () -> setup.service().related("merchant-z", request(null, "kurly:milk-1l"))).errorCode());
    }

    @Test
    @DisplayName("요청 처리 중 예상 못 한 오류는 그 요청만 503 RELATED_UNAVAILABLE 이다")
    void related_searchFails_returnsUnavailable() {
        // given — 검색이 터지는 카탈로그
        CatalogPoolReader reader = new CatalogPoolReader(new DefaultResourceLoader(), new ObjectMapper());
        List<Product> products = productsOf(reader.read(POOL, 0));
        CatalogStore store = new CatalogStore(() -> products);
        store.load();
        CatalogService broken = new CatalogService(store, MerchantPropertiesFixture.defaults()) {
            @Override
            public CatalogMessages.ItemsResponse search(String merchantId, CatalogMessages.SearchRequest request) {
                throw new IllegalStateException("검색 고장");
            }
        };
        Setup setup = setup(POOL, "rrf2", "file:none", broken);
        setup.service().prepare();

        // when
        ApiException error = assertThrows(ApiException.class,
                () -> setup.service().related(MALL, request(null, "kurly:milk-1l")));

        // then
        assertEquals("RELATED_UNAVAILABLE", error.errorCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
    }
}
