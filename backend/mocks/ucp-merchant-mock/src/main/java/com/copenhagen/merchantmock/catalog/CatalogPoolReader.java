package com.copenhagen.merchantmock.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 풀 파일을 읽는다(DESIGN.md 5.7절). {@code .gz} 면 풀고, 상품 배열은 스트리밍으로 하나씩 읽는다.
 *
 * <p>트리로 통째로 읽지 않는 이유는 크기다. 9만 건 풀은 원문 30MB 이고 JsonNode 트리로 올리면 그 몇 배가
 * 기동 중 잠깐 필요하다. 상품 하나씩 읽어 레코드로 바꾸면 그 봉우리가 없다.
 *
 * <p>sha256 은 파일 바이트(압축된 그대로) 위에서 계산한다. 적재 건너뛰기의 기준이라 빠르고 단순해야 한다.
 */
@Component
public class CatalogPoolReader {

    private static final byte[] GZIP_MAGIC = {(byte) 0x1f, (byte) 0x8b};

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    public CatalogPoolReader(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    /**
     * @param location classpath: 또는 file: 경로
     * @param limit    앞 몇 건만 읽을지. 0 이면 전량
     */
    public CatalogPool read(String location, int limit) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("merchant.catalog.pool-file 이 가리키는 파일이 없다: " + location);
        }
        byte[] bytes;
        try (InputStream input = resource.getInputStream()) {
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("카탈로그 풀을 읽을 수 없다: " + location, exception);
        }
        String sha256 = sha256(bytes);
        try (InputStream raw = open(bytes); JsonParser parser = objectMapper.createParser(raw)) {
            return parse(parser, sha256, limit, location);
        } catch (IOException | JacksonException exception) {
            // IOException 은 압축이 깨진 것, JacksonException 은 JSON 형식이 깨진 것이다.
            throw new IllegalStateException("카탈로그 풀을 읽을 수 없다: " + location, exception);
        }
    }

    private static InputStream open(byte[] bytes) throws IOException {
        InputStream input = new ByteArrayInputStream(bytes);
        if (bytes.length >= 2 && bytes[0] == GZIP_MAGIC[0] && bytes[1] == GZIP_MAGIC[1]) {
            return new GZIPInputStream(input);
        }
        return input;
    }

    private CatalogPool parse(JsonParser parser, String sha256, int limit, String location) {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw new IllegalStateException("카탈로그 풀의 최상위가 객체가 아니다: " + location);
        }
        int totalCount = -1;
        List<CatalogPool.PoolProduct> products = null;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            if ("count".equals(field)) {
                totalCount = parser.getIntValue();
            } else if ("products".equals(field)) {
                products = readProducts(parser, limit, location);
            } else {
                parser.skipChildren();
            }
        }
        if (products == null || products.isEmpty()) {
            throw new IllegalStateException("카탈로그 풀에 products 배열이 없다: " + location);
        }
        return new CatalogPool(sha256, totalCount < 0 ? products.size() : totalCount, List.copyOf(products));
    }

    private List<CatalogPool.PoolProduct> readProducts(JsonParser parser, int limit, String location) {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            throw new IllegalStateException("카탈로그 풀의 products 가 배열이 아니다: " + location);
        }
        List<CatalogPool.PoolProduct> products = new ArrayList<>(limit > 0 ? limit : 4096);
        while (parser.nextToken() == JsonToken.START_OBJECT) {
            if (limit > 0 && products.size() >= limit) {
                parser.skipChildren();
                continue;
            }
            // ObjectMapper.readTree(parser) 는 값 뒤에 토큰이 남으면 거절한다(FAIL_ON_TRAILING_TOKENS). 배열 안의
            // 객체 하나만 트리로 읽는 것은 파서 쪽 메서드다.
            products.add(toProduct((JsonNode) parser.readValueAsTree(), location));
        }
        return products;
    }

    private static CatalogPool.PoolProduct toProduct(JsonNode node, String location) {
        String externalId = node.path("externalId").asString("");
        if (externalId.isBlank()) {
            throw new IllegalStateException("카탈로그 풀에 externalId 가 없는 상품이 있다: " + location);
        }
        JsonNode variants = node.path("variants");
        if (!variants.isArray() || variants.isEmpty()) {
            // 옵션이 없으면 pickVariant 가 SKU 를 못 찾아 체크아웃이 성립하지 않는다(5.7절).
            throw new IllegalStateException("옵션 없는 상품이 풀에 있다: " + externalId + " (" + location + ")");
        }
        List<CatalogPool.PoolVariant> parsed = new ArrayList<>(variants.size());
        for (JsonNode variant : variants) {
            parsed.add(new CatalogPool.PoolVariant(
                    variant.path("sku").asString(""),
                    variant.path("optionName").asString("option"),
                    variant.path("optionValue").asString("기본"),
                    variant.path("priceDelta").asLong(0L),
                    variant.path("stock").asInt(0),
                    variant.path("status").asString("ACTIVE")));
        }
        return new CatalogPool.PoolProduct(
                externalId,
                node.path("title").asString(""),
                node.path("category").asString(null),
                node.path("brand").asString(null),
                node.path("imageUrl").asString(null),
                node.path("basePrice").asLong(),
                node.path("currency").asString("KRW"),
                node.path("status").asString("ACTIVE"),
                List.copyOf(parsed));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 을 지원하지 않는 JVM 이다", exception);
        }
    }
}
