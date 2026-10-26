package com.copenhagen.merchantmock.catalog;

import java.util.List;

import javax.sql.DataSource;

import com.copenhagen.merchantmock.support.EmbeddedPostgresSupport;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JdbcCatalogSeedWriter 의 SQL 검증(Jira 354).
 *
 * <p>여기서만 잡히는 것이 둘이다. {@code ON CONFLICT (external_id)} 는 그 컬럼에 UNIQUE 가
 * 실제로 걸려 있어야 동작하고, {@code ?::jsonb} 는 문자열이 진짜 jsonb 로 들어가야 통과한다.
 * 인메모리로는 둘 다 통과하는 것처럼 보인다(DESIGN.md 5.7절, Jira 294 의 기준).
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcCatalogSeedWriterPostgresTest {

    private static final String VARIANTS = """
            [{"sku":"seed-1-a","optionName":"option","optionValue":"기본",\
            "priceDelta":0,"stock":100,"status":"ACTIVE"}]""";

    private EmbeddedPostgresSupport postgres;
    private JdbcCatalogSeedWriter writer;
    private JdbcTemplate jdbc;

    private static CatalogSeed.Entry entry(String externalId, String title, long basePrice, String variantsJson) {
        return new CatalogSeed.Entry(
                "aaaaaaaa-0000-4000-8000-00000000000" + externalId.charAt(externalId.length() - 1),
                "merchant-a",
                externalId,
                title,
                "테스트 > 시드",
                "테스트브랜드",
                "https://cdn.example.test/" + externalId + ".jpg",
                basePrice,
                "KRW",
                "ACTIVE",
                variantsJson);
    }

    @BeforeAll
    void startPostgres() throws Exception {
        this.postgres = EmbeddedPostgresSupport.start();
    }

    @AfterAll
    void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() {
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, null).migrate();
        this.jdbc = postgres.jdbcFor(dataSource);
        this.writer = new JdbcCatalogSeedWriter(jdbc);
    }

    @Test
    @DisplayName("시드를 넣으면 products 에 그대로 들어간다")
    void upsertAll_newEntries_insertsRows() {
        // given
        List<CatalogSeed.Entry> entries = List.of(entry("seed-1", "시드 하나", 12000L, VARIANTS));

        // when
        int written = writer.upsertAll(entries);

        // then
        assertEquals(1, written);
        assertEquals("시드 하나", jdbc.queryForObject(
                "SELECT title FROM products WHERE external_id = 'seed-1'", String.class));
    }

    @Test
    @DisplayName("variants_json 이 문자열이 아니라 jsonb 로 들어간다")
    void upsertAll_variantsJson_storedAsJsonb() {
        // given
        writer.upsertAll(List.of(entry("seed-1", "시드 하나", 12000L, VARIANTS)));

        // when
        // jsonb 가 아니면 이 연산자 자체가 SQL 오류를 낸다.
        String sku = jdbc.queryForObject(
                "SELECT variants_json -> 0 ->> 'sku' FROM products WHERE external_id = 'seed-1'",
                String.class);

        // then
        assertEquals("seed-1-a", sku);
    }

    @Test
    @DisplayName("같은 시드를 다시 넣어도 행이 늘지 않고 값만 갱신된다")
    void upsertAll_sameExternalId_updatesInPlace() {
        // given
        writer.upsertAll(List.of(entry("seed-1", "옛 이름", 12000L, VARIANTS)));
        long before = countSeeded();

        // when
        writer.upsertAll(List.of(entry("seed-1", "새 이름", 15000L, VARIANTS)));

        // then
        assertEquals(before, countSeeded());
        assertEquals("새 이름", jdbc.queryForObject(
                "SELECT title FROM products WHERE external_id = 'seed-1'", String.class));
        assertEquals(15000L, jdbc.queryForObject(
                "SELECT base_price FROM products WHERE external_id = 'seed-1'", Long.class));
    }

    @Test
    @DisplayName("갱신하면 updated_at 이 움직인다")
    void upsertAll_sameExternalId_touchesUpdatedAt() {
        // given
        writer.upsertAll(List.of(entry("seed-1", "옛 이름", 12000L, VARIANTS)));
        jdbc.update("UPDATE products SET updated_at = now() - interval '1 hour' WHERE external_id = 'seed-1'");

        // when
        writer.upsertAll(List.of(entry("seed-1", "새 이름", 12000L, VARIANTS)));

        // then
        Boolean moved = jdbc.queryForObject(
                "SELECT updated_at > now() - interval '1 minute' FROM products WHERE external_id = 'seed-1'",
                Boolean.class);
        assertTrue(Boolean.TRUE.equals(moved));
    }

    @Test
    @DisplayName("V3 fixture 를 지우지 않는다")
    void upsertAll_newEntries_keepsMigrationSeed() {
        // given
        Long before = jdbc.queryForObject(
                "SELECT count(*) FROM products WHERE external_id LIKE 'product-1%'", Long.class);

        // when
        writer.upsertAll(List.of(entry("seed-1", "시드 하나", 12000L, VARIANTS)));

        // then
        assertEquals(before, jdbc.queryForObject(
                "SELECT count(*) FROM products WHERE external_id LIKE 'product-1%'", Long.class));
    }

    @Test
    @DisplayName("커밋한 풀의 앞 2,000건이 실제 DB 를 거쳐 몰 셋 카탈로그에 올라오고 두 번째 적재는 건너뛴다")
    void poolFile_loadedThroughDatabase_appearsInCatalogStore() {
        // given
        ObjectMapper mapper = new ObjectMapper();
        CatalogSeedLoader loader = new CatalogSeedLoader(
                MerchantPropertiesFixture.withCatalogPool("classpath:catalog/pool.json.gz", 2000),
                new CatalogPoolReader(new DefaultResourceLoader(), mapper),
                new MallVariation(mapper),
                writer,
                new JdbcCatalogPoolStateRepository(jdbc));
        CatalogStore store = new CatalogStore(new JdbcProductRepository(jdbc, mapper));

        // when
        int written = loader.loadIfConfigured();
        int again = loader.loadIfConfigured();
        store.load();

        // then
        assertEquals(2000 * 3, written, "몰 셋 × 2,000건");
        assertEquals(0, again, "같은 풀·몰·limit 이면 건너뛴다(V12 catalog_pool_state)");
        // V3 fixture 5건이 그대로 남아 있어야 한다.
        assertEquals(2000 + 5, store.all("merchant-a").size());
        assertEquals(2000, store.all("merchant-b").size());

        Product product = store.all("merchant-a").stream()
                .filter(candidate -> candidate.externalId().startsWith("kurly:"))
                .findFirst()
                .orElseThrow();
        // jsonb 왕복에서 옵션이 살아남아야 SKU 로 체크아웃을 걸 수 있다.
        assertFalse(product.variants().isEmpty(), product.externalId());
        assertTrue(store.byExternalId("merchant-a", product.externalId()).isPresent());
        String sku = product.variants().get(0).sku();
        assertTrue(store.bySku("merchant-a", sku).isPresent(), sku);
        assertEquals("KRW", product.currency());
    }

    @Test
    @DisplayName("빈 목록은 SQL 을 보내지 않는다")
    void upsertAll_emptyList_writesNothing() {
        // given
        long before = countSeeded();

        // when
        int written = writer.upsertAll(List.of());

        // then
        assertEquals(0, written);
        assertEquals(before, countSeeded());
    }

    private long countSeeded() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM products", Long.class);
        return count == null ? 0L : count;
    }
}
