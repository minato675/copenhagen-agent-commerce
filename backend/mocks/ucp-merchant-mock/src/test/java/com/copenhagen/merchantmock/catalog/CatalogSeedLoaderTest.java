package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 카탈로그 풀 로더 단위 테스트(DESIGN.md 5.7절 2026-09-21 개정). DB 없이 쓰기 경계와 상태 저장소를 가짜로 바꿔 돈다.
 */
class CatalogSeedLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<CatalogSeed.Entry> written = new ArrayList<>();
    private final InMemoryState state = new InMemoryState();

    /** 상태 한 행을 메모리에 든다. Jdbc 구현은 postgresTest 가 따로 본다. */
    private static final class InMemoryState implements CatalogPoolStateRepository {
        private CatalogPoolState current;
        private int saves;

        @Override
        public Optional<CatalogPoolState> find() {
            return Optional.ofNullable(current);
        }

        @Override
        public void save(CatalogPoolState next) {
            current = next;
            saves++;
        }
    }

    private CatalogSeedLoader loaderOf(String poolFile, int limit) {
        return new CatalogSeedLoader(
                MerchantPropertiesFixture.withCatalogPool(poolFile, limit),
                new CatalogPoolReader(new DefaultResourceLoader(), MAPPER),
                new MallVariation(MAPPER),
                entries -> {
                    written.addAll(entries);
                    return entries.size();
                },
                state);
    }

    @Test
    @DisplayName("풀 경로가 비어 있으면 아무것도 쓰지 않는다")
    void loadIfConfigured_blankPoolFile_writesNothing() {
        // given
        CatalogSeedLoader loader = loaderOf("", 0);

        // when
        int count = loader.loadIfConfigured();

        // then
        assertEquals(0, count);
        assertTrue(written.isEmpty());
        assertTrue(state.find().isEmpty());
    }

    @Test
    @DisplayName("풀 상품마다 몰 셋의 행을 만든다 — externalId 는 같고 id 는 다르며 가격은 ±10% 안이다")
    void loadIfConfigured_validPool_writesOneRowPerMall() {
        // given: 픽스처 상품 셋 × 몰 셋
        CatalogSeedLoader loader = loaderOf("classpath:catalog-pool/valid.json", 0);

        // when
        int count = loader.loadIfConfigured();

        // then
        assertEquals(9, count);
        assertEquals(9, written.size());
        Set<String> ids = new HashSet<>();
        for (CatalogSeed.Entry entry : written) {
            assertTrue(ids.add(entry.id()), "id 가 겹친다: " + entry.id());
        }
        List<CatalogSeed.Entry> shampoo = written.stream()
                .filter(entry -> entry.externalId().equals("kurly:1000000001")).toList();
        assertEquals(List.of("merchant-a", "merchant-b", "merchant-c"),
                shampoo.stream().map(CatalogSeed.Entry::merchantId).toList());
        for (CatalogSeed.Entry entry : shampoo) {
            assertTrue(entry.basePrice() >= 12000 * 0.9 - 100 && entry.basePrice() <= 12000 * 1.1 + 100,
                    entry.merchantId() + " " + entry.basePrice());
            assertEquals("시드 상품 하나", entry.title());
            assertEquals("유제품 > 우유", entry.category());
            assertEquals("테스트브랜드", entry.brand());
            assertEquals(2, MAPPER.readTree(entry.variantsJson()).size());
        }
    }

    @Test
    @DisplayName("옵션 차액에도 같은 배율이 붙어 기본가 + 차액이 반올림 단위에 떨어진다")
    void loadIfConfigured_validPool_scalesVariantDeltaWithBasePrice() {
        // given
        loaderOf("classpath:catalog-pool/valid.json", 0).loadIfConfigured();

        // when
        CatalogSeed.Entry entry = written.stream()
                .filter(e -> e.externalId().equals("kurly:1000000001") && e.merchantId().equals("merchant-a"))
                .findFirst().orElseThrow();
        long delta = MAPPER.readTree(entry.variantsJson()).get(1).path("priceDelta").asLong();

        // then: 원본 차액 3,000 에 ±10% 가 붙고 합은 100원 단위다(1만원 이상)
        assertTrue(delta >= 2600 && delta <= 3400, "차액 " + delta);
        assertEquals(0, (entry.basePrice() + delta) % 100);
    }

    @Test
    @DisplayName("같은 풀·같은 몰·같은 limit 이면 두 번째 기동은 적재를 건너뛴다")
    void loadIfConfigured_unchangedPool_skipsSecondLoad() {
        // given
        CatalogSeedLoader loader = loaderOf("classpath:catalog-pool/valid.json", 0);
        int first = loader.loadIfConfigured();

        // when
        int second = loader.loadIfConfigured();

        // then
        assertEquals(9, first);
        assertEquals(0, second);
        assertEquals(9, written.size(), "두 번째는 쓰지 않는다");
        assertEquals(1, state.saves);
        assertEquals(3, state.find().orElseThrow().productCount());
        assertEquals("merchant-a,merchant-b,merchant-c", state.find().orElseThrow().malls());
    }

    @Test
    @DisplayName("limit 이 바뀌면 다시 적재한다")
    void loadIfConfigured_changedLimit_reloads() {
        // given
        loaderOf("classpath:catalog-pool/valid.json", 2).loadIfConfigured();
        assertEquals(6, written.size());

        // when
        int count = loaderOf("classpath:catalog-pool/valid.json", 0).loadIfConfigured();

        // then
        assertEquals(9, count);
        assertEquals(0, state.find().orElseThrow().poolLimit());
    }

    @Test
    @DisplayName("경로가 설정됐는데 파일이 없으면 기동을 세운다")
    void loadIfConfigured_missingFile_throws() {
        CatalogSeedLoader loader = loaderOf("classpath:catalog-pool/없는파일.json", 0);
        assertThrows(IllegalStateException.class, loader::loadIfConfigured);
        assertTrue(written.isEmpty());
    }
}
