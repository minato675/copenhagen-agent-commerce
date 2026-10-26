package com.copenhagen.merchantmock.catalog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 풀 파일 읽기(DESIGN.md 5.7절). 평문·gzip·limit·형식 오류를 본다. */
class CatalogPoolReaderTest {

    private final CatalogPoolReader reader = new CatalogPoolReader(new DefaultResourceLoader(), new ObjectMapper());

    @Test
    @DisplayName("평문 풀을 읽어 상품과 옵션으로 매핑한다")
    void read_plainJson_mapsProducts() {
        // when
        CatalogPool pool = reader.read("classpath:catalog-pool/valid.json", 0);

        // then
        assertEquals(3, pool.totalCount());
        assertEquals(3, pool.products().size());
        CatalogPool.PoolProduct first = pool.products().get(0);
        assertEquals("kurly:1000000001", first.externalId());
        assertEquals("시드 상품 하나", first.title());
        assertEquals("유제품 > 우유", first.category());
        assertEquals("테스트브랜드", first.brand());
        assertEquals(12000, first.basePrice());
        assertEquals(2, first.variants().size());
        assertEquals(3000, first.variants().get(1).priceDelta());
        assertEquals(64, pool.sha256().length());
    }

    @Test
    @DisplayName("gzip 풀도 같은 내용으로 읽고 sha256 은 파일 바이트 기준이라 평문과 다르다")
    void read_gzip_sameProductsDifferentSha(@TempDir Path directory) throws IOException {
        // given
        Path archive = directory.resolve("pool.json.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive))) {
            out.write(new ClassPathResource("catalog-pool/valid.json").getContentAsByteArray());
        }

        // when
        CatalogPool plain = reader.read("classpath:catalog-pool/valid.json", 0);
        CatalogPool zipped = reader.read("file:" + archive.toAbsolutePath(), 0);

        // then
        assertEquals(plain.products(), zipped.products());
        assertNotEquals(plain.sha256(), zipped.sha256());
    }

    @Test
    @DisplayName("limit 이 있으면 앞 N 건만 읽고 파일 전체 수는 그대로 알려 준다")
    void read_withLimit_readsPrefixOnly() {
        // when
        CatalogPool pool = reader.read("classpath:catalog-pool/valid.json", 2);

        // then: 앞 1,592건이 시연 필수라 "앞에서부터" 가 중요하다
        assertEquals(2, pool.products().size());
        assertEquals("kurly:1000000001", pool.products().get(0).externalId());
        assertEquals("daiso:2000000002", pool.products().get(1).externalId());
        assertEquals(3, pool.totalCount());
    }

    @Test
    @DisplayName("경로가 설정됐는데 파일이 없으면 기동을 세운다")
    void read_missingFile_throws() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> reader.read("classpath:catalog-pool/없는파일.json", 0));
        assertTrue(error.getMessage().contains("없다"));
    }

    @Test
    @DisplayName("products 가 비어 있으면 거절한다")
    void read_emptyProducts_throws() {
        assertThrows(IllegalStateException.class, () -> reader.read("classpath:catalog-pool/empty.json", 0));
    }

    @Test
    @DisplayName("옵션 없는 상품이 들어 있으면 거절한다")
    void read_productWithoutVariants_throws() {
        // 옵션이 없으면 pickVariant 가 SKU 를 못 찾아 체크아웃이 성립하지 않는다
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> reader.read("classpath:catalog-pool/no-variants.json", 0));
        assertTrue(error.getMessage().contains("kurly:1000000009"));
    }
}
