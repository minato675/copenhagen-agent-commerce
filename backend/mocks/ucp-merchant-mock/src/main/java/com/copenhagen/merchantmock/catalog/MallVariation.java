package com.copenhagen.merchantmock.catalog;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 풀의 상품 하나를 몰 하나의 행으로 바꾼다(DESIGN.md 5.7절 2026-09-21 개정, ADR 0011).
 *
 * <p>몰마다 다른 것은 셋이다. 내부 id(uuid5), 가격(±10%), 품절(5%). 전부 결정적 해시라 난수가 없다 —
 * 같은 풀이면 어느 기동에서도 같은 값이고, 리허설에서 본 최저가 몰이 시연 당일에도 같다.
 *
 * <p>규칙의 파이썬 원본은 {@code tools/mall_rules.py} 다. 두 구현이 같은 답을 내는지는
 * {@code tools/mall_variation_golden.py} 가 뽑은 황금값을 {@code MallVariationTest} 가 비교해 지킨다.
 * 규칙을 바꾸려면 두 곳을 같이 바꾸고 황금값을 다시 뽑는다.
 */
@Component
public final class MallVariation {

    /** RFC 4122 의 NAMESPACE_URL. 파이썬 {@code uuid.NAMESPACE_URL} 과 같다. */
    static final UUID NAMESPACE_URL = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    static final double PRICE_MIN_FACTOR = 0.90;
    static final double PRICE_SPAN = 0.20;
    static final double SOLD_OUT_RATIO = 0.05;
    static final int STOCK_OUT = 0;

    private static final String ID_PREFIX = "copenhagen:merchant-mock:product:";
    private static final double TWO_POW_64 = 18446744073709551616.0;

    private final ObjectMapper objectMapper;

    public MallVariation(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 몰 하나의 products 행. 문자열 필드는 풀 객체의 참조를 그대로 쓴다 — 몰 셋이 한 벌을 나눠 쓰는 것이
     * 27만 행 메모리의 전제다(13절).
     */
    public CatalogSeed.Entry derive(String mall, CatalogPool.PoolProduct product) {
        String externalId = product.externalId();
        double factor = priceFactor(externalId, mall);
        long unit = roundingUnit(product.basePrice());
        long scaledBase = Math.max(roundTo(product.basePrice() * factor, unit), unit);
        boolean soldOut = soldOut(externalId, mall);

        List<Map<String, Object>> variants = new ArrayList<>(product.variants().size());
        for (CatalogPool.PoolVariant variant : product.variants()) {
            // 기준가와 옵션가를 각각 배율로 곱해 반올림한 뒤 차액을 구한다. 그래야 base + delta 가 단위에 떨어진다.
            long scaledPrice = roundTo((product.basePrice() + variant.priceDelta()) * factor, unit);
            long delta = scaledPrice - scaledBase;
            if (scaledBase + delta <= 0) {
                delta = unit - scaledBase;
            }
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("sku", variant.sku());
            json.put("optionName", variant.optionName());
            json.put("optionValue", variant.optionValue());
            json.put("priceDelta", delta);
            json.put("stock", soldOut ? STOCK_OUT : variant.stock());
            json.put("status", variant.status());
            variants.add(json);
        }
        return new CatalogSeed.Entry(
                deterministicId(mall, externalId),
                mall,
                externalId,
                product.title(),
                product.category(),
                product.brand(),
                product.imageUrl(),
                scaledBase,
                product.currency(),
                product.status(),
                objectMapper.writeValueAsString(variants));
    }

    /** 0 ≤ u < 1 인 결정적 실수. sha256 의 앞 8바이트를 부호 없는 정수로 읽어 2^64 로 나눈다. */
    static double u(String... parts) {
        byte[] digest = sha256(String.join("|", parts));
        byte[] head = new byte[8];
        System.arraycopy(digest, 0, head, 0, 8);
        // BigInteger → double 은 가장 가까운 값으로 반올림하고 2^64 로 나누는 것은 지수만 바뀌어 정확하다.
        // 파이썬 int / 2**64 의 정확히 반올림된 결과와 같다.
        return new BigInteger(1, head).doubleValue() / TWO_POW_64;
    }

    static double priceFactor(String externalId, String mall) {
        return PRICE_MIN_FACTOR + PRICE_SPAN * u(externalId, mall, "price");
    }

    static boolean soldOut(String externalId, String mall) {
        return u(externalId, mall, "stock") < SOLD_OUT_RATIO;
    }

    /** 1만원 미만은 10원, 이상은 100원 단위. 원본 기준가로 정한다. */
    static long roundingUnit(long price) {
        return price < 10000 ? 10 : 100;
    }

    /** 파이썬 {@code int(amount / unit + 0.5) * unit}. int() 는 0 쪽으로 자르고 (long) 캐스트도 그렇다. */
    static long roundTo(double amount, long unit) {
        return (long) (amount / unit + 0.5) * unit;
    }

    /** 파이썬 {@code uuid5(NAMESPACE_URL, "copenhagen:merchant-mock:product:{mall}:{externalId}")}. */
    static String deterministicId(String mall, String externalId) {
        return uuid5(NAMESPACE_URL, ID_PREFIX + mall + ":" + externalId).toString();
    }

    /** RFC 4122 5절의 이름 기반 UUID(SHA-1). Java 표준 라이브러리에는 v3(MD5)만 있다. */
    static UUID uuid5(UUID namespace, String name) {
        MessageDigest sha1 = digest("SHA-1");
        sha1.update(toBytes(namespace));
        byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) {
            most = (most << 8) | (hash[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            least = (least << 8) | (hash[i] & 0xff);
        }
        return new UUID(most, least);
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (most & 0xff);
            most >>>= 8;
            bytes[8 + i] = (byte) (least & 0xff);
            least >>>= 8;
        }
        return bytes;
    }

    private static byte[] sha256(String text) {
        return digest("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " 을 지원하지 않는 JVM 이다", exception);
        }
    }
}
