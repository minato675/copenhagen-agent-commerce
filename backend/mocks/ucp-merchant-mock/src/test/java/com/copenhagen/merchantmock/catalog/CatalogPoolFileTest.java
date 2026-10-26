package com.copenhagen.merchantmock.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 커밋한 카탈로그 풀({@code catalog/pool.json.gz})을 검사한다(DESIGN.md 5.7절 2026-09-21 개정, ADR 0011).
 *
 * <p>{@code tools/build_catalog_pool.py} 의 산출물이 DB 제약과 이 모듈의 전제를 지키는지 본다. 데이터를 갈아끼울 때
 * 이 테스트가 먼저 잡아야 한다. 몰 셋 전개(id·가격·재고)는 {@link MallVariationTest} 와 {@link CatalogSeedLoaderTest}
 * 가 보고, 여기서는 풀 자체를 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogPoolFileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POOL = "classpath:catalog/pool.json.gz";

    /** 시연 필수 앞부분. 옛 시드 셋(strict 1,408 + 식재료 184)의 externalId 를 고정한 것이다. */
    private static final int BASE_COUNT = 1592;

    /** mcp-server CatalogUiConfiguration 이 위젯 CSP 에 허용한 다섯 도메인. 벗어나면 카드에 그림이 안 뜬다. */
    private static final List<String> ALLOWED_IMAGE_HOSTS = List.of(
            "product-image.kurly.com", "3p-image.kurly.com", "img-cf.kurly.com", "img.danuri.io",
            "cdn.daisomall.co.kr");

    /** 옵션이 있어야 자연스러운 카테고리 낱말(ADR 0010 의 합성 대상). 여기서는 90% 이상이 옵션 둘 이상이어야 한다. */
    private static final List<String> OPTION_CATEGORIES = List.of(
            "스포츠화", "운동화", "러닝화", "신발", "슈즈", "부츠", "샌들", "의류", "스포츠웨어", "휴대폰", "태블릿", "이어폰", "여행가방");

    private static final List<String> PASTA_INGREDIENTS =
            List.of("파스타면", "토마토", "양파", "마늘", "올리브유", "파마산", "베이컨");

    private CatalogPool pool;

    @BeforeAll
    void readPool() {
        pool = new CatalogPoolReader(new DefaultResourceLoader(), MAPPER).read(POOL, 0);
    }

    @Test
    @DisplayName("풀이 옛 시드 셋보다 훨씬 크고 count 가 실제 건수와 같다")
    void pool_isLargeAndCountMatches() {
        assertEquals(pool.products().size(), pool.totalCount());
        assertTrue(pool.products().size() >= BASE_COUNT * 10, "풀 " + pool.products().size() + "건");
    }

    @Test
    @DisplayName("앞 1,592건은 옛 시드 셋과 같은 상품이다 — 시연 재료는 limit 을 어떻게 줘도 살아 있다")
    void pool_prefix_isTheDemoSet() throws IOException {
        // given
        Set<String> expected = new HashSet<>(Arrays.asList(
                new ClassPathResource("catalog-pool/base-external-ids.txt")
                        .getContentAsString(StandardCharsets.UTF_8).trim().split("\\R")));
        assertEquals(BASE_COUNT, expected.size());

        // when
        Set<String> prefix = new HashSet<>();
        for (CatalogPool.PoolProduct product : pool.products().subList(0, BASE_COUNT)) {
            prefix.add(product.externalId());
        }

        // then
        assertEquals(expected, prefix);
    }

    @Test
    @DisplayName("externalId 와 SKU 가 풀 안에서 유일하다")
    void pool_hasUniqueIdsAndSkus() {
        Set<String> ids = new HashSet<>();
        Set<String> skus = new HashSet<>();
        for (CatalogPool.PoolProduct product : pool.products()) {
            assertTrue(ids.add(product.externalId()), "externalId 중복: " + product.externalId());
            for (CatalogPool.PoolVariant variant : product.variants()) {
                assertTrue(skus.add(variant.sku()), "SKU 중복: " + variant.sku());
            }
        }
    }

    @Test
    @DisplayName("컬럼 길이·금액·이미지 도메인이 스키마와 위젯 CSP 안에 있다")
    void pool_fitsSchemaAndImagePolicy() {
        for (CatalogPool.PoolProduct product : pool.products()) {
            String label = product.externalId();
            assertTrue(product.externalId().length() <= 100, label);
            assertFalse(product.title().isBlank(), label + " 제목 없음");
            assertTrue(product.title().length() <= 255, label + " 제목 길이");
            assertTrue(product.imageUrl() != null && product.imageUrl().length() <= 500, label + " 이미지 URL");
            assertTrue(product.category() == null || product.category().length() <= 255, label + " 카테고리 길이");
            assertTrue(product.brand() == null || product.brand().length() <= 100, label + " 브랜드 길이");
            assertTrue(product.basePrice() > 0, label + " 가격");
            assertEquals("KRW", product.currency(), label);
            assertFalse(product.variants().isEmpty(), label + " 옵션 없음");
            for (CatalogPool.PoolVariant variant : product.variants()) {
                assertTrue(product.basePrice() + variant.priceDelta() > 0, variant.sku() + " 옵션가");
            }
            String image = product.imageUrl();
            assertTrue(ALLOWED_IMAGE_HOSTS.stream().anyMatch(host -> image.contains("//" + host + "/")),
                    label + " 이미지 도메인이 허용 목록 밖이다: " + image);
        }
    }

    @Test
    @DisplayName("옵션이 있어야 할 카테고리(신발·의류·휴대폰·태블릿·이어폰·여행가방)의 90% 이상이 옵션 둘 이상이다")
    void pool_optionCategories_mostlyHaveSelectableOptions() {
        int inScope = 0;
        int selectable = 0;
        for (CatalogPool.PoolProduct product : pool.products()) {
            String category = product.category() == null ? "" : product.category();
            if (OPTION_CATEGORIES.stream().noneMatch(category::contains)) {
                continue;
            }
            inScope++;
            if (product.variants().size() >= 2) {
                selectable++;
            }
        }
        assertTrue(inScope >= 100, "대상 상품 " + inScope + "건");
        assertTrue(selectable * 100L >= inScope * 90L, "옵션 둘 이상 " + selectable + " / " + inScope);
    }

    @Test
    @DisplayName("풀에 카테고리가 없는 상품이 없고 말단 카테고리가 옛 시드(523)보다 많다")
    void pool_categories_areFilledAndDiverse() {
        Set<String> leaves = new HashSet<>();
        for (CatalogPool.PoolProduct product : pool.products()) {
            assertTrue(product.category() != null && !product.category().isBlank(),
                    product.externalId() + " 카테고리 없음");
            leaves.add(product.category());
        }
        assertTrue(leaves.size() > 523, "말단 카테고리 " + leaves.size());
    }

    @Test
    @DisplayName("토마토 파스타 재료가 전부 검색되고 운동화로 러닝화가 나온다")
    void pool_demoQueries_stillWork() {
        CatalogService service = searchOverFirst(BASE_COUNT);
        for (String ingredient : PASTA_INGREDIENTS) {
            assertFalse(titlesOf(service, ingredient).isEmpty(), ingredient + " 0건");
        }
        List<String> shoes = titlesOf(service, "운동화");
        assertTrue(shoes.stream().anyMatch(title -> title.contains("러닝화")), "운동화 → 러닝화: " + shoes);
    }

    @Test
    @DisplayName("전량 풀에서 검색 한 번이 예산 안에 든다 — 수치는 PORTFOLIO 에 적는다")
    void pool_fullSearch_isFastEnough() {
        // given: 몰 하나 = 풀 전량. 사전 계산(SearchDoc)은 스냅샷을 만들 때 한 번이다
        long heapBefore = usedHeap();
        CatalogService service = searchOverFirst(pool.products().size());
        System.out.printf("[힙] 풀 %d건을 몰 하나로 올린 뒤 증가 약 %dMB (풀 객체 자체 제외)%n",
                pool.products().size(), (usedHeap() - heapBefore) / (1024 * 1024));
        List<String> queries = List.of("운동화", "가벼운 러닝화", "유기농 토마토", "무선 이어폰", "10만원 이하 러닝화",
                "맛있는 우유", "국산 돼지고기", "nike", "볼펜", "캠핑 의자");
        for (String query : queries) {
            service.search("merchant-a", new CatalogMessages.SearchRequest(query, null));
        }

        // when
        List<Long> millis = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            for (String query : queries) {
                long started = System.nanoTime();
                service.search("merchant-a", new CatalogMessages.SearchRequest(query, null));
                millis.add((System.nanoTime() - started) / 1_000_000);
            }
        }
        millis.sort(null);
        long p50 = millis.get(millis.size() / 2);
        long p95 = millis.get((int) Math.ceil(millis.size() * 0.95) - 1);
        System.out.printf("[검색 시간] 풀 %d건, 질의 %d회, p50 %dms, p95 %dms, 최대 %dms%n",
                pool.products().size(), millis.size(), p50, p95, millis.get(millis.size() - 1));

        // then: 목표는 p95 50ms(13절). 단언은 CI 러너 편차를 감안해 넉넉히 둔다 — 여기서 잡을 것은 퇴행이다
        assertTrue(p95 < 500, "검색 p95 " + p95 + "ms");
    }

    @Test
    @DisplayName("몰 셋이 같은 풀을 올려도 검색 사전 계산은 한 벌이다 — 힙 증가를 적어 둔다")
    void pool_threeMalls_shareSearchDocs() {
        // given
        long heapBefore = usedHeap();
        List<Product> products = new ArrayList<>(pool.products().size() * 3);
        for (String mall : List.of("merchant-a", "merchant-b", "merchant-c")) {
            for (CatalogPool.PoolProduct entry : pool.products()) {
                products.add(new Product(
                        mall + entry.externalId(), mall, entry.externalId(), entry.title(), null, entry.category(),
                        entry.brand(), entry.imageUrl(), entry.basePrice(), entry.currency(),
                        Product.ProductStatus.ACTIVE,
                        List.of(new Product.Variant(entry.externalId() + "-any", "option", "기본", 0, 100,
                                Product.ProductStatus.ACTIVE))));
            }
        }
        CatalogStore store = new CatalogStore(() -> products);

        // when
        store.load();
        long grown = (usedHeap() - heapBefore) / (1024 * 1024);
        System.out.printf("[힙] 풀 %d건 × 몰 셋 = %d행 올린 뒤 증가 약 %dMB (풀 객체 자체 제외)%n",
                pool.products().size(), products.size(), grown);

        // then
        assertEquals(pool.products().size() * 3, store.size());
        assertTrue(store.searchDocs("merchant-a").get(0) == store.searchDocs("merchant-b").get(0),
                "같은 상품의 SearchDoc 은 몰이 달라도 같은 객체다");
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** 풀 앞 n 건을 몰 하나의 판매 중 상품으로 올린 검색기. 옵션은 검색의 관심사가 아니라 하나로 대신한다. */
    private CatalogService searchOverFirst(int n) {
        List<Product> products = new ArrayList<>(n);
        for (CatalogPool.PoolProduct entry : pool.products().subList(0, n)) {
            products.add(new Product(
                    entry.externalId() + "-id", "merchant-a", entry.externalId(), entry.title(), null,
                    entry.category(), entry.brand(), entry.imageUrl(), entry.basePrice(), entry.currency(),
                    Product.ProductStatus.ACTIVE,
                    List.of(new Product.Variant(entry.externalId() + "-any", "option", "기본", 0, 100,
                            Product.ProductStatus.ACTIVE))));
        }
        CatalogStore store = new CatalogStore(() -> products);
        store.load();
        return new CatalogService(store, MerchantPropertiesFixture.defaults());
    }

    private static List<String> titlesOf(CatalogService service, String query) {
        List<String> titles = new ArrayList<>();
        service.search("merchant-a", new CatalogMessages.SearchRequest(query, null)).items()
                .forEach(item -> titles.add(item.title()));
        return titles;
    }
}
